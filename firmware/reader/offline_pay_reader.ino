// =============================================================================
//  OfflinePay — ESP32 Reader Firmware
// =============================================================================
//  Hardware: ESP32 Dev Module + RC522 + 2 LEDs + buzzer
//
//  Flow on each card tap:
//    1. Detect MIFARE Classic 1K card.
//    2. Read voucher JSON across data blocks 4..30 (skipping sector trailers).
//    3. Forward the JSON over Bluetooth SPP (device name "OfflinePay_Reader").
//    4. Wait up to 10s for the merchant phone to reply with ACCEPT or REJECT.
//    5. ACCEPT  -> green LED + happy beep + overwrite voucher blocks with USED.
//       REJECT  -> red   LED + sad beep, do NOT mark used.
//       TIMEOUT -> red   LED + double beep.
//
//  Voucher payload layout on the card:
//    - Written across 21 data blocks (4-30 except sector trailers 7,11,15,19,23,27,31).
//    - Encoded as plain JSON, NUL-terminated. Block bytes after the NUL are ignored.
//
//  Pinout (matches build doc):
//    RC522 SDA(SS) -> GPIO 5     RC522 RST -> GPIO 22
//    RC522 SCK     -> GPIO 18    GND       -> GND
//    RC522 MOSI    -> GPIO 23    3.3V      -> 3.3V (NOT 5V)
//    RC522 MISO    -> GPIO 19
//    LED green     -> GPIO 26 (220 ohm to GND)
//    LED red       -> GPIO 27 (220 ohm to GND)
//    Buzzer +      -> GPIO 25
// =============================================================================

#include <SPI.h>
#include <MFRC522.h>
#include <BluetoothSerial.h>
#include <esp_system.h>
#include "wallet.h"

#define SS_PIN     5
#define RST_PIN    22
#define LED_GREEN  26
#define LED_RED    27
#define BUZZER     25

#define DECISION_TIMEOUT_MS  10000UL
#define CLAIM_DOMAIN         "OFFPAY-CLAIM-V1"

MFRC522 rfid(SS_PIN, RST_PIN);
BluetoothSerial SerialBT;

// Pending claim challenge: a fresh 16-byte nonce is generated on every
// REQUEST_CHALLENGE from the phone. The next CLAIM frame must sign over
// (CLAIM_DOMAIN || espBtMacBytes || nonce). Single-pending-nonce model
// is fine for one BT-SPP-at-a-time; if multiple phones request in
// parallel we'd need a per-session map.
static uint8_t  s_challenge[16];
static bool     s_challengeValid = false;

// ---- helpers shared with parser --------------------------------------------

static bool hexCharToNibble(char c, uint8_t* out) {
  if (c >= '0' && c <= '9') { *out = c - '0'; return true; }
  if (c >= 'a' && c <= 'f') { *out = c - 'a' + 10; return true; }
  if (c >= 'A' && c <= 'F') { *out = c - 'A' + 10; return true; }
  return false;
}

// Returns number of bytes parsed, 0 on error. Skips a "0x" prefix if present.
static size_t hexToBytes(const char* hex, size_t hexLen, uint8_t* out, size_t outCap) {
  size_t i = 0;
  if (hexLen >= 2 && hex[0] == '0' && (hex[1] == 'x' || hex[1] == 'X')) i = 2;
  size_t outLen = 0;
  while (i + 1 < hexLen && outLen < outCap) {
    uint8_t hi, lo;
    if (!hexCharToNibble(hex[i], &hi)) return 0;
    if (!hexCharToNibble(hex[i + 1], &lo)) return 0;
    out[outLen++] = (hi << 4) | lo;
    i += 2;
  }
  return outLen;
}

static String hexFromBytes(const uint8_t* b, size_t n) {
  static const char* HEX = "0123456789abcdef";
  String s; s.reserve(n * 2);
  for (size_t i = 0; i < n; i++) {
    s += HEX[b[i] >> 4];
    s += HEX[b[i] & 0x0f];
  }
  return s;
}

// Get this device's BT MAC (6 bytes) — used as the device-binding identifier
// in the CLAIM challenge so a sig captured for one reader can't be replayed
// against another.
static void getBtMacBytes(uint8_t mac[6]) {
  esp_read_mac(mac, ESP_MAC_BT);
}

// Default factory key for unconfigured MIFARE Classic cards.
byte DEFAULT_KEY[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};

// Data blocks we use for the voucher payload (sector 1..7, 21 blocks * 16 = 336 B).
// Sector trailers (every 4th block: 7, 11, 15, 19, 23, 27) are NOT included.
const uint8_t VOUCHER_BLOCKS[] = {
   4,  5,  6,         // sector 1
   8,  9, 10,         // sector 2
  12, 13, 14,         // sector 3
  16, 17, 18,         // sector 4
  20, 21, 22,         // sector 5
  24, 25, 26,         // sector 6
  28, 29, 30          // sector 7
};
const uint8_t VOUCHER_BLOCK_COUNT = sizeof(VOUCHER_BLOCKS) / sizeof(VOUCHER_BLOCKS[0]);

// ---------------------------------------------------------------------------

void setup() {
  Serial.begin(115200);
  while (!Serial && millis() < 2000) {}
  SPI.begin();
  rfid.PCD_Init();

  pinMode(LED_GREEN, OUTPUT);
  pinMode(LED_RED,   OUTPUT);
  pinMode(BUZZER,    OUTPUT);

  if (!SerialBT.begin("OfflinePay_Reader")) {
    Serial.println("[BT] init failed");
  } else {
    Serial.println("[BT] OfflinePay_Reader online");
  }

  // Provision (or load) this device's secp256k1 wallet. Address must be
  // registered on MerchantRegistry via authorizeDevice() before settlement.
  if (!OfflinePayWallet::begin()) {
    Serial.println("[wallet] init FAILED — settlement disabled");
  }

  Serial.println("[RC522] reader ready, waiting for card...");
  showReady();
}

void loop() {
  // Drain any pending BT commands first — pairing / status / future
  // WRITE/WIPE flow. Card detection happens after, so a phone asking
  // for a challenge doesn't have to wait for a card tap.
  pumpBtCommands();

  if (!rfid.PICC_IsNewCardPresent()) { delay(50); return; }
  if (!rfid.PICC_ReadCardSerial())   { delay(50); return; }

  String uid = uidToHex(rfid.uid.uidByte, rfid.uid.size);
  Serial.println(String("[CARD] uid=") + uid);

  String voucherJson = readVoucher();
  if (voucherJson.length() == 0) {
    Serial.println("[CARD] no voucher payload");
    flashRed("EMPTY");
    halt();
    return;
  }

  Serial.println(String("[CARD] voucher=") + voucherJson);

  // Forward to merchant phone, prefixed with the UID and this reader's
  // device address (so the merchant phone can sanity-check that the reader
  // is one of its authorized devices).
  SerialBT.print("VOUCHER ");
  SerialBT.print(OfflinePayWallet::address());
  SerialBT.print(" ");
  SerialBT.print(uid);
  SerialBT.print(" ");
  SerialBT.println(voucherJson);

  String decision = waitForDecision(DECISION_TIMEOUT_MS);
  Serial.println(String("[BT] decision=") + decision);

  if (decision == "ACCEPT") {
    flashGreen();
    if (markVoucherUsed()) {
      Serial.println("[CARD] marked USED");
    } else {
      Serial.println("[CARD] WARN: could not mark USED — risk of replay!");
    }
  } else if (decision == "REJECT") {
    flashRed("REJECT");
  } else {
    flashRed("TIMEOUT");
  }
  halt();
}

// --- MIFARE I/O -----------------------------------------------------------

bool authBlock(uint8_t blockNum) {
  MFRC522::MIFARE_Key key;
  memcpy(key.keyByte, DEFAULT_KEY, 6);
  return rfid.PCD_Authenticate(
    MFRC522::PICC_CMD_MF_AUTH_KEY_A, blockNum, &key, &(rfid.uid)
  ) == MFRC522::STATUS_OK;
}

String readVoucher() {
  String out;
  out.reserve(VOUCHER_BLOCK_COUNT * 16);
  uint8_t buffer[18];
  uint8_t bufferSize;

  for (uint8_t i = 0; i < VOUCHER_BLOCK_COUNT; i++) {
    uint8_t block = VOUCHER_BLOCKS[i];

    if (!authBlock(block)) {
      Serial.println(String("[CARD] auth failed @ block ") + block);
      return "";
    }

    bufferSize = sizeof(buffer);
    if (rfid.MIFARE_Read(block, buffer, &bufferSize) != MFRC522::STATUS_OK) {
      Serial.println(String("[CARD] read failed @ block ") + block);
      return "";
    }

    for (uint8_t j = 0; j < 16; j++) {
      char c = (char)buffer[j];
      if (c == 0) return out;          // NUL terminator -> done
      out += c;
    }
  }
  return out;
}

bool markVoucherUsed() {
  // Overwrite each voucher data block with the literal sentinel "USED____________"
  // so the firmware's next read sees the sentinel instead of a valid voucher.
  byte usedPattern[16] = { 'U','S','E','D','_','_','_','_','_','_','_','_','_','_','_','_' };
  bool ok = true;
  for (uint8_t i = 0; i < VOUCHER_BLOCK_COUNT; i++) {
    uint8_t block = VOUCHER_BLOCKS[i];
    if (!authBlock(block)) { ok = false; continue; }
    if (rfid.MIFARE_Write(block, usedPattern, 16) != MFRC522::STATUS_OK) {
      ok = false;
    }
  }
  return ok;
}

void halt() {
  rfid.PICC_HaltA();
  rfid.PCD_StopCrypto1();
}

// --- Bluetooth ------------------------------------------------------------

String waitForDecision(unsigned long timeoutMs) {
  unsigned long start = millis();
  String line;
  while (millis() - start < timeoutMs) {
    while (SerialBT.available()) {
      char c = SerialBT.read();
      if (c == '\n' || c == '\r') {
        line.trim();
        if (line.length() > 0) return line;
      } else {
        line += c;
        if (line.length() > 32) line = ""; // junk guard
      }
    }
    delay(20);
  }
  return "TIMEOUT";
}

// --- UI helpers -----------------------------------------------------------

String uidToHex(byte *uid, byte n) {
  String s;
  for (byte i = 0; i < n; i++) {
    if (uid[i] < 0x10) s += "0";
    s += String(uid[i], HEX);
  }
  return s;
}

void flashGreen() {
  digitalWrite(LED_GREEN, HIGH);
  tone(BUZZER, 1200, 120);
  delay(140);
  tone(BUZZER, 1600, 160);
  delay(800);
  digitalWrite(LED_GREEN, LOW);
  noTone(BUZZER);
}

void flashRed(const char *why) {
  Serial.println(String("[UI] red: ") + why);
  digitalWrite(LED_RED, HIGH);
  tone(BUZZER, 350, 250);
  delay(300);
  tone(BUZZER, 350, 250);
  delay(700);
  digitalWrite(LED_RED, LOW);
  noTone(BUZZER);
}

void showReady() {
  for (int i = 0; i < 2; i++) {
    digitalWrite(LED_GREEN, HIGH); delay(120);
    digitalWrite(LED_GREEN, LOW);  delay(120);
  }
}

// --- BT command pump ------------------------------------------------------
//
// Reads any complete \n-terminated line currently buffered on the SPP
// socket and dispatches it. Non-blocking: returns immediately if no full
// line is available, so the main loop's card-detection cadence stays
// crisp.

static String btLineBuf;

void pumpBtCommands() {
  while (SerialBT.available()) {
    char c = SerialBT.read();
    if (c == '\n' || c == '\r') {
      if (btLineBuf.length() > 0) {
        handleBtLine(btLineBuf);
        btLineBuf = "";
      }
    } else {
      btLineBuf += c;
      if (btLineBuf.length() > 600) {
        // Pathological input — drop and resync.
        Serial.println("[BT] line buffer overflow, dropping");
        btLineBuf = "";
      }
    }
  }
}

void handleBtLine(const String& line) {
  // Don't block the main loop with parsing diagnostics.
  Serial.print("[BT] cmd: ");
  Serial.println(line.length() > 80 ? (line.substring(0, 80) + "…") : line);

  if (line == "REQUEST_CHALLENGE") {
    onRequestChallenge();
  } else if (line.startsWith("CLAIM ")) {
    onClaim(line);
  } else if (line == "STATUS") {
    onStatus();
  } else if (line == "WIPE_OWNER") {
    OfflinePayWallet::clearOwner();
    SerialBT.println("OK ok");
  }
  // ACCEPT/REJECT for the voucher-decision flow are still consumed by the
  // existing waitForDecision() inside the card-tap path — they arrive
  // synchronously inside that read window and never hit this pump.
}

void onRequestChallenge() {
  // 16 bytes of hardware entropy. esp_random() is the on-board hardware
  // RNG seeded by Wi-Fi/BT noise; for higher assurance we'd add mbedtls
  // CTR-DRBG on top, but for a per-claim nonce this is sufficient.
  for (int i = 0; i < 16; i++) {
    s_challenge[i] = (uint8_t)(esp_random() & 0xff);
  }
  s_challengeValid = true;
  String hex = hexFromBytes(s_challenge, 16);
  SerialBT.print("CHALLENGE ");
  SerialBT.println(hex);
}

void onStatus() {
  // Reports the firmware's own EVM address + the current owner address
  // (or the zero address when no owner is bonded). The phone uses this
  // to display "ESP32 0x… → owner 0x…" in the control center.
  SerialBT.print("STATUS ");
  SerialBT.print(OfflinePayWallet::address());
  SerialBT.print(" ");
  SerialBT.println(OfflinePayWallet::ownerAddressHex());
}

void onClaim(const String& line) {
  // Format: CLAIM <addr> <pubkey_hex> <sig_hex>
  //   addr   : 0x + 40 hex
  //   pubkey : 130 hex (uncompressed: 04 || X || Y)
  //   sig    : 0x + 130 hex (r || s || v)

  if (!s_challengeValid) {
    SerialBT.println("ERR no_challenge_issued");
    return;
  }

  // crude tokenize on spaces; line.indexOf is O(n) but the line is short.
  int sp1 = line.indexOf(' ');
  int sp2 = line.indexOf(' ', sp1 + 1);
  int sp3 = line.indexOf(' ', sp2 + 1);
  if (sp1 < 0 || sp2 < 0 || sp3 < 0) {
    SerialBT.println("ERR malformed_claim");
    return;
  }
  String addrTok   = line.substring(sp1 + 1, sp2);
  String pubkeyTok = line.substring(sp2 + 1, sp3);
  String sigTok    = line.substring(sp3 + 1);

  // --- decode the parts ---
  uint8_t addr20[20];
  if (hexToBytes(addrTok.c_str(), addrTok.length(), addr20, 20) != 20) {
    SerialBT.println("ERR bad_addr_hex"); return;
  }
  uint8_t pub65[65];
  if (hexToBytes(pubkeyTok.c_str(), pubkeyTok.length(), pub65, 65) != 65 ||
      pub65[0] != 0x04) {
    SerialBT.println("ERR bad_pubkey_hex"); return;
  }
  uint8_t sig[65];
  if (hexToBytes(sigTok.c_str(), sigTok.length(), sig, 65) != 65) {
    SerialBT.println("ERR bad_sig_hex"); return;
  }

  // --- build the payload the phone signed ---
  // payload = "OFFPAY-CLAIM-V1" || espBtMacBytes(6) || challenge(16)
  uint8_t mac[6]; getBtMacBytes(mac);
  size_t domLen = strlen(CLAIM_DOMAIN);
  size_t payloadLen = domLen + 6 + 16;
  uint8_t payload[64];
  memcpy(payload, CLAIM_DOMAIN, domLen);
  memcpy(payload + domLen, mac, 6);
  memcpy(payload + domLen + 6, s_challenge, 16);

  // --- verify ECDSA + address consistency ---
  bool ok = OfflinePayWallet::verifyEthPersonalSig(
      payload, payloadLen, pub65, sig, sig + 32, addr20);
  if (!ok) {
    SerialBT.println("ERR bad_signature");
    return;
  }

  // --- consume the challenge so the same sig can't be replayed ---
  s_challengeValid = false;

  // --- persist the new owner ---
  if (!OfflinePayWallet::setOwner(addr20)) {
    SerialBT.println("ERR owner_persist_failed");
    return;
  }

  Serial.print("[claim] new owner = ");
  Serial.println(hexFromBytes(addr20, 20));

  // Reply with our own EVM address — the phone stores this in its bond
  // record so future VOUCHER frames carrying our address can be
  // sanity-checked (anti-spoof).
  SerialBT.print("OK ");
  SerialBT.println(OfflinePayWallet::address());
}
