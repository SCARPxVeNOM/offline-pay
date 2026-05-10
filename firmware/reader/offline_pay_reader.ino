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
#define ENDORSE_DOMAIN       "OFFPAY-ENDORSE-V1"

// On-chain settlement target. MUST match phones' Config.kt VAULT_ADDRESS.
// Re-deploys the vault → bump the bytes here AND reflash every reader.
static const uint8_t VAULT_BYTES[20] = {
  0x3e, 0x73, 0xaa, 0x75, 0x06, 0xc5, 0xa8, 0x33, 0xe0, 0x84,
  0x2c, 0x94, 0x84, 0x58, 0xaf, 0x9d, 0x63, 0xc1, 0x9d, 0xcd
};
static const uint64_t CHAIN_ID_VAL = 80002;  // Polygon Amoy

// keccak256("OFFPAY-ENDORSE-V1") cached in setup() so the endorsement
// digest computation in onWrite/loop doesn't re-hash on every tap.
static uint8_t s_endorseDomainHash[32];

// Operating mode. Default = IDLE so a freshly-booted reader doesn't
// hammer the RC522 with continuous detection cycles (which steals BT
// CPU + drops bytes during phone WRITE handshakes). Phone explicitly
// asks for READ mode when it's on the Receive screen, and the WRITE
// handler temporarily flips into WRITE mode and back.
enum Mode { MODE_IDLE = 0, MODE_READ = 1, MODE_WRITE = 2 };
static volatile Mode s_mode = MODE_IDLE;
static const char* modeName(Mode m) {
  switch (m) {
    case MODE_IDLE:  return "IDLE";
    case MODE_READ:  return "READ";
    case MODE_WRITE: return "WRITE";
  }
  return "?";
}

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
  // Arduino.h #defines HEX as 16; rename to avoid the macro collision.
  static const char* kHex = "0123456789abcdef";
  String s; s.reserve(n * 2);
  for (size_t i = 0; i < n; i++) {
    s += kHex[b[i] >> 4];
    s += kHex[b[i] & 0x0f];
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

  // Pre-compute keccak256("OFFPAY-ENDORSE-V1") once. Used as the first
  // 32-byte field in the endorsement digest preimage on every card read.
  keccak256_arduino((const uint8_t*)ENDORSE_DOMAIN, strlen(ENDORSE_DOMAIN), s_endorseDomainHash);

  // Boot in IDLE so we don't hammer the RC522 before any phone needs
  // us to. Phone explicitly transitions us to READ when the user
  // opens the Receive screen.
  s_mode = MODE_IDLE;
  Serial.println("[mode] booted in IDLE — phone will request READ when needed");
  showReady();
}

static void setMode(Mode m) {
  if (s_mode == m) return;
  Serial.print("[mode] "); Serial.print(modeName(s_mode));
  Serial.print(" -> ");    Serial.println(modeName(m));
  s_mode = m;
  // Whenever we leave a mode, drop any half-detected card so the next
  // mode starts with a clean slate.
  s_lastUid = "";
  if (m != MODE_READ) {
    rfid.PICC_HaltA();
    rfid.PCD_StopCrypto1();
  }
}

// Per-UID dedup: a MIFARE card sitting on the reader gets re-detected
// by `PICC_IsNewCardPresent` every loop iteration after a halt(). If we
// re-enter the read flow each time, we starve BT command processing,
// spam serial, AND drop incoming BT bytes (the ESP-IDF rx buffer is
// finite). Track the last processed UID; the same card sitting in the
// field is silently ignored until it physically leaves and a different
// card / no card is seen, which clears `s_lastUid`.
static String s_lastUid = "";

// Sleep that keeps draining BT while it waits. UI helpers (flashRed,
// flashGreen) used to call delay() directly which let the BT rx buffer
// fill up and drop bytes. Use this instead so a phone's WRITE/CHALLENGE
// traffic is never lost during a UI cue.
static void btSleep(unsigned long ms) {
  unsigned long start = millis();
  while (millis() - start < ms) {
    pumpBtCommands();
    delay(5);
  }
}

void loop() {
  // Drain any pending BT commands first — pairing / WRITE / mode
  // transitions. Card detection happens only when we're explicitly
  // in READ mode; otherwise the loop is BT-only so a phone WRITE
  // handshake never competes with RC522 SPI for CPU.
  pumpBtCommands();

  if (s_mode != MODE_READ) {
    // IDLE or WRITE mode: card detection is handled elsewhere (or not
    // at all). Just keep the BT pump cycling.
    btSleep(20);
    return;
  }

  if (!rfid.PICC_IsNewCardPresent()) {
    // No card in field — reset dedup so a card returning later is
    // processed fresh.
    s_lastUid = "";
    btSleep(50);
    return;
  }
  if (!rfid.PICC_ReadCardSerial())   { btSleep(50); return; }

  String uid = uidToHex(rfid.uid.uidByte, rfid.uid.size);
  // Dedup: same physical card sitting on the reader → silently halt
  // and let the BT pump get CPU. Cleared when the card physically
  // leaves the field (PICC_IsNewCardPresent → false on next iter).
  if (uid == s_lastUid) {
    halt();
    btSleep(80);
    return;
  }
  s_lastUid = uid;
  Serial.println(String("[CARD] uid=") + uid);

  String voucherJson = readVoucher();
  // Reject obvious garbage early. A real voucher JSON contains the
  // voucherId field (`"i":"0x...`). Anything missing that is a stale
  // write or an empty card — bail out fast so we don't enter the
  // 10-second decision wait (which would eat the phone's
  // WRITE/CHALLENGE traffic).
  // NOTE: we don't check for `"v":` first-field because the encoder
  // omits it when it equals the default (= 2) — saves 6 bytes on the
  // wire so the JSON fits in MIFARE Classic 1K.
  if (voucherJson.length() < 50 || voucherJson.indexOf("\"i\":\"0x") < 0) {
    Serial.println(String("[CARD] no usable voucher (got '")
                  + voucherJson + "') — skipping read flow");
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

  // For B2 (true-bearer cards), emit a fresh endorsement so the relay
  // can settle via `settleBearerWithEndorsement`. This commits THIS
  // reader (and its bonded merchant primary) as the recipient — a
  // malicious mesh relay can't redirect funds without forging this
  // signature. We always emit when a bonded owner exists; phone side
  // ignores the endorsement when the voucher is recipient-bound.
  emitEndorsement(voucherJson);

  String decision = waitForDecision(DECISION_TIMEOUT_MS);
  Serial.println(String("[BT] decision=") + decision);

  if (decision == "ACCEPT") {
    // Mark the card USED *before* the visual cue. flashGreen takes
    // ~1 second; during that wait the MFRC522's session state is lost
    // (or the user lifts the card). Doing the write first ensures the
    // card is reliably overwritten while we still have a live session.
    bool marked = markVoucherUsed();
    if (marked) {
      Serial.println("[CARD] marked USED");
    } else {
      // Re-establish the session with the card and try once more.
      // Common after a long wait — PICC drops state.
      halt();
      btSleep(40);
      if (rfid.PICC_IsNewCardPresent() && rfid.PICC_ReadCardSerial()) {
        marked = markVoucherUsed();
        if (marked) Serial.println("[CARD] marked USED (retry)");
      }
    }
    if (!marked) {
      Serial.println("[CARD] WARN: could not mark USED — risk of replay!");
    }
    flashGreen();
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

    // Pump BT between blocks. Each MIFARE auth+read takes ~5-10ms;
    // 21 blocks = ~150-200ms of blocking SPI work. Without pumping
    // here the ESP32's 1024-byte BT RX buffer overflows when the
    // phone tries to send a WRITE (627 bytes) during the read.
    pumpBtCommands();

    if (!authBlock(block)) {
      Serial.println(String("[CARD] auth failed @ block ") + block);
      return "";
    }

    bufferSize = sizeof(buffer);
    if (rfid.MIFARE_Read(block, buffer, &bufferSize) != MFRC522::STATUS_OK) {
      Serial.println(String("[CARD] read failed @ block ") + block);
      return "";
    }

    // Fast-path: if the very first block starts with "USED" the card
    // is the known-empty sentinel — bail immediately so we don't waste
    // 200ms reading 21 blocks of "USED____________".
    if (i == 0 && buffer[0] == 'U' && buffer[1] == 'S' &&
        buffer[2] == 'E' && buffer[3] == 'D') {
      return "USED";
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

/// Block-read the BT socket waiting for an ACCEPT or REJECT decision.
///
/// While we wait, the phone may legitimately send unrelated commands
/// (REQUEST_CHALLENGE, CLAIM, WRITE, STATUS) — earlier we'd swallow
/// those as the "decision" and dump them. Now we route any non-decision
/// line through the same handler the main loop uses, so a paired phone
/// can complete its WRITE handshake even if a card happens to be on
/// the reader at the time.
String waitForDecision(unsigned long timeoutMs) {
  unsigned long start = millis();
  String line;
  while (millis() - start < timeoutMs) {
    while (SerialBT.available()) {
      char c = SerialBT.read();
      if (c == '\n' || c == '\r') {
        line.trim();
        if (line.length() > 0) {
          if (line == "ACCEPT" || line == "REJECT") return line;
          // Anything else is a real BT command. Hand it off and keep
          // waiting for the actual decision.
          handleBtLine(line);
          line = "";
        }
      } else {
        line += c;
        if (line.length() > 1500) line = ""; // junk guard
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

// LED + buzzer cues use btSleep() so the BT rx buffer never starves
// during the visual feedback. Without this, ~1 second of plain delay()
// is enough for the ESP-IDF rx ring to drop bytes from a phone WRITE
// command — exact symptom that broke card-write testing.
void flashGreen() {
  digitalWrite(LED_GREEN, HIGH);
  tone(BUZZER, 1200, 120);
  btSleep(140);
  tone(BUZZER, 1600, 160);
  btSleep(800);
  digitalWrite(LED_GREEN, LOW);
  noTone(BUZZER);
}

void flashRed(const char *why) {
  Serial.println(String("[UI] red: ") + why);
  digitalWrite(LED_RED, HIGH);
  tone(BUZZER, 350, 250);
  btSleep(300);
  tone(BUZZER, 350, 250);
  btSleep(700);
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
      if (btLineBuf.length() > 1500) {
        // Pathological input — drop and resync. Threshold is sized
        // so a legitimate WRITE line ("WRITE <addr> <pubkey> <sig>
        // <json>" ≈ 700 bytes) still fits with margin for future
        // schema growth.
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
  } else if (line.startsWith("WRITE ")) {
    onWrite(line);
  } else if (line == "STATUS") {
    onStatus();
  } else if (line == "WIPE_OWNER") {
    OfflinePayWallet::clearOwner();
    SerialBT.println("OK ok");
  } else if (line == "MODE READ") {
    setMode(MODE_READ);
    SerialBT.println("OK mode=READ");
  } else if (line == "MODE IDLE") {
    setMode(MODE_IDLE);
    SerialBT.println("OK mode=IDLE");
  } else if (line == "MODE WRITE") {
    // Optional explicit transition; WRITE handler also flips on its own.
    setMode(MODE_WRITE);
    SerialBT.println("OK mode=WRITE");
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

// --- WRITE flow ----------------------------------------------------------
//
// Format (one line, space-separated):
//   WRITE <addr> <pubkey_uncompressed_130hex> <sig_65hex_with_or_without_0x> <json>
//
// Auth: same EIP-191 model as CLAIM but with domain "OFFPAY-WRITE-V1" and
// the challenge bound to the JSON body (sig signs over keccak256(json)).
// Phone must REQUEST_CHALLENGE just before sending WRITE.
//
// Effect: ESP32 enters write mode for up to WRITE_WAIT_MS (default 30s)
// and writes <json> across the same MIFARE Classic data blocks the read
// path walks (4..30 minus sector trailers), NUL-terminated. On success
// it replies "OK <uid>"; on timeout / write fail it replies "ERR <r>".

#define WRITE_WAIT_MS 30000UL
#define WRITE_DOMAIN  "OFFPAY-WRITE-V1"

void onWrite(const String& line) {
  if (!s_challengeValid) {
    SerialBT.println("ERR no_challenge_issued");
    return;
  }
  if (!OfflinePayWallet::hasOwner()) {
    SerialBT.println("ERR no_owner_bonded");
    return;
  }

  // tokenize: WRITE <addr> <pubkey> <sig> <json...>
  int sp1 = line.indexOf(' ');
  int sp2 = line.indexOf(' ', sp1 + 1);
  int sp3 = line.indexOf(' ', sp2 + 1);
  int sp4 = line.indexOf(' ', sp3 + 1);
  if (sp1 < 0 || sp2 < 0 || sp3 < 0 || sp4 < 0) {
    SerialBT.println("ERR malformed_write"); return;
  }
  String addrTok   = line.substring(sp1 + 1, sp2);
  String pubkeyTok = line.substring(sp2 + 1, sp3);
  String sigTok    = line.substring(sp3 + 1, sp4);
  String jsonTok   = line.substring(sp4 + 1);

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

  // Owner gate: only the bonded owner can request a card write.
  uint8_t ownerStored[20];
  if (!OfflinePayWallet::getOwner(ownerStored)) {
    SerialBT.println("ERR no_owner_bonded"); return;
  }
  if (memcmp(addr20, ownerStored, 20) != 0) {
    SerialBT.println("ERR not_owner"); return;
  }

  // Build the auth payload: WRITE_DOMAIN || bt_mac(6) || challenge(16) ||
  // keccak256(json)(32). Same prefix shape as CLAIM but domain-separated.
  uint8_t mac[6]; getBtMacBytes(mac);
  uint8_t jsonHash[32];
  keccak256_arduino((const uint8_t*)jsonTok.c_str(), jsonTok.length(), jsonHash);

  size_t domLen = strlen(WRITE_DOMAIN);
  size_t payloadLen = domLen + 6 + 16 + 32;
  uint8_t payload[128];
  memcpy(payload, WRITE_DOMAIN, domLen);
  memcpy(payload + domLen, mac, 6);
  memcpy(payload + domLen + 6, s_challenge, 16);
  memcpy(payload + domLen + 6 + 16, jsonHash, 32);

  bool ok = OfflinePayWallet::verifyEthPersonalSig(
      payload, payloadLen, pub65, sig, sig + 32, addr20);
  if (!ok) {
    SerialBT.println("ERR bad_signature");
    return;
  }
  s_challengeValid = false;

  // We've authenticated the WRITE request. Take exclusive control of
  // the reader for up to WRITE_WAIT_MS — main loop's read flow stays
  // out of the way, BT pump still runs (no SPI competing for cycles).
  Mode prevMode = s_mode;
  setMode(MODE_WRITE);
  digitalWrite(LED_GREEN, HIGH);
  Serial.print("[write] waiting up to ");
  Serial.print(WRITE_WAIT_MS / 1000);
  Serial.println("s for card…");

  unsigned long start = millis();
  bool gotCard = false;
  while (millis() - start < WRITE_WAIT_MS) {
    if (rfid.PICC_IsNewCardPresent() && rfid.PICC_ReadCardSerial()) {
      gotCard = true; break;
    }
    pumpBtCommands();   // keep BT alive during the card-tap wait
    delay(40);
  }
  if (!gotCard) {
    digitalWrite(LED_GREEN, LOW);
    setMode(prevMode);
    SerialBT.println("ERR card_timeout");
    return;
  }

  String uid = uidToHex(rfid.uid.uidByte, rfid.uid.size);
  String wr = writeVoucher(jsonTok);
  halt();
  digitalWrite(LED_GREEN, LOW);
  setMode(prevMode);

  if (wr.length() == 0) {
    tone(BUZZER, 1200, 120); delay(140);
    tone(BUZZER, 1600, 160); delay(160);
    noTone(BUZZER);
    SerialBT.print("OK ");
    SerialBT.println(uid);
  } else {
    flashRed("WRITE_FAIL");
    SerialBT.print("ERR ");
    SerialBT.println(wr);
  }
}

// Returns "" on success, otherwise the reason string.
String writeVoucher(const String& jsonText) {
  // Pad JSON across the voucher data blocks (336 bytes total). Reads
  // stop at the first NUL byte, so any extra capacity is silently OK.
  if (jsonText.length() + 1 > VOUCHER_BLOCK_COUNT * 16) {
    return String("payload_too_large");
  }
  uint8_t buf[VOUCHER_BLOCK_COUNT * 16];
  memset(buf, 0, sizeof(buf));
  memcpy(buf, jsonText.c_str(), jsonText.length()); // NUL terminator already at jsonText.length()

  for (uint8_t i = 0; i < VOUCHER_BLOCK_COUNT; i++) {
    uint8_t block = VOUCHER_BLOCKS[i];
    if (!authBlock(block)) return String("auth_block_") + block;
    byte chunk[16];
    memcpy(chunk, buf + (i * 16), 16);
    if (rfid.MIFARE_Write(block, chunk, 16) != MFRC522::STATUS_OK) {
      return String("write_block_") + block;
    }
  }
  return String("");
}

// --- ENDORSE flow --------------------------------------------------------
//
// At every successful read, sign an endorsement that commits this reader
// (and its bonded merchant primary) as the payee for the voucher just
// scanned. Output line:
//   ENDORSE <ts> <merchantPrimaryHex> <espAddrHex> <sigHex>
//
// The relay node bundles VOUCHER + ENDORSE and submits via
// `settleBearerWithEndorsement(...)` on chain. If the voucher was
// recipient-bound (recipient != 0), the phone ignores ENDORSE and uses
// the legacy `settleBearer` path.

void emitEndorsement(const String& voucherJson) {
  if (!OfflinePayWallet::hasOwner()) return;

  uint8_t voucherId[32];
  if (!extractVoucherId(voucherJson.c_str(), voucherId)) {
    Serial.println("[endorse] no voucherId in JSON — skipping");
    return;
  }
  uint8_t owner20[20]; OfflinePayWallet::getOwner(owner20);
  uint8_t self20[20];  OfflinePayWallet::addressBytes(self20);

  // Build abi.encode(bytes32 domain, bytes32 voucherId, address device,
  //                  address primary, uint256 ts, uint256 chainId,
  //                  address vault)  — 7 × 32 = 224 bytes.
  uint8_t pre[7 * 32]; memset(pre, 0, sizeof(pre));
  memcpy(pre,            s_endorseDomainHash, 32);   // [0..32]
  memcpy(pre + 32,       voucherId,           32);   // [32..64]
  memcpy(pre + 64 + 12,  self20,              20);   // [76..96]
  memcpy(pre + 96 + 12,  owner20,             20);   // [108..128]

  uint64_t ts = (uint64_t)millis();                  // demo: monotonic uptime
  for (int i = 0; i < 8; i++)
    pre[128 + 24 + i] = (uint8_t)(ts >> (8 * (7 - i)));
  for (int i = 0; i < 8; i++)
    pre[160 + 24 + i] = (uint8_t)(CHAIN_ID_VAL >> (8 * (7 - i)));
  memcpy(pre + 192 + 12, VAULT_BYTES, 20);

  uint8_t digest[32];
  keccak256_arduino(pre, sizeof(pre), digest);

  // signEthMessage applies the EIP-191 prefix internally before ECDSA.
  String sig = OfflinePayWallet::signEthMessage(digest, 32);
  if (sig.length() == 0) {
    Serial.println("[endorse] signing failed");
    return;
  }

  SerialBT.print("ENDORSE ");
  SerialBT.print((unsigned long)ts);
  SerialBT.print(" 0x");
  SerialBT.print(hexFromBytes(owner20, 20));
  SerialBT.print(" 0x");
  SerialBT.print(hexFromBytes(self20, 20));
  SerialBT.print(" ");
  SerialBT.println(sig);
}

// Tiny extractor: scans for the first occurrence of `"i":"0x` in the JSON
// (matching CardVoucherPayload's @SerialName("i")) and reads the next 64
// hex chars into `out32`. Returns false if the field isn't present or
// the hex is malformed. Avoids pulling in a full JSON parser on the MCU.
bool extractVoucherId(const char* json, uint8_t out32[32]) {
  const char* needle = "\"i\":\"0x";
  const char* p = strstr(json, needle);
  if (!p) return false;
  p += strlen(needle);
  for (int i = 0; i < 32; i++) {
    uint8_t hi, lo;
    if (!hexCharToNibble(p[i*2],     &hi)) return false;
    if (!hexCharToNibble(p[i*2 + 1], &lo)) return false;
    out32[i] = (uint8_t)((hi << 4) | lo);
  }
  return true;
}

// keccak256 over arbitrary bytes. We have the same primitive in wallet.cpp
// but it's static there; re-expose a thin wrapper for the sketch via a
// local copy so we don't have to refactor wallet.cpp's namespace surface.
static void keccak256_arduino(const uint8_t* data, size_t len, uint8_t out32[32]) {
  static const uint64_t RC[24] = {
    0x0000000000000001ULL,0x0000000000008082ULL,0x800000000000808aULL,0x8000000080008000ULL,
    0x000000000000808bULL,0x0000000080000001ULL,0x8000000080008081ULL,0x8000000000008009ULL,
    0x000000000000008aULL,0x0000000000000088ULL,0x0000000080008009ULL,0x000000008000000aULL,
    0x000000008000808bULL,0x800000000000008bULL,0x8000000000008089ULL,0x8000000000008003ULL,
    0x8000000000008002ULL,0x8000000000000080ULL,0x000000000000800aULL,0x800000008000000aULL,
    0x8000000080008081ULL,0x8000000000008080ULL,0x0000000080000001ULL,0x8000000080008008ULL,
  };
  // Arduino.h #defines PI as a math constant; rename to KECCAK_PI to
  // avoid macro substitution.
  static const int RHO[24] = {1,3,6,10,15,21,28,36,45,55,2,14,27,41,56,8,25,43,62,18,39,61,20,44};
  static const int KECCAK_PI [24] = {10,7,11,17,18,3,5,16,8,21,24,4,15,23,19,13,12,2,20,14,22,9,6,1};
  uint64_t st[25] = {0};
  const size_t rate = 136;
  uint8_t buf[200] = {0};
  size_t pos = 0;

  auto absorb = [&](void) {
    for (int i = 0; i < (int)(rate/8); i++) {
      uint64_t lane = 0;
      for (int b = 0; b < 8; b++) lane |= ((uint64_t)buf[i*8+b]) << (8*b);
      st[i] ^= lane;
    }
    uint64_t bc[5], t;
    for (int r = 0; r < 24; r++) {
      for (int i = 0; i < 5; i++) bc[i] = st[i] ^ st[i+5] ^ st[i+10] ^ st[i+15] ^ st[i+20];
      for (int i = 0; i < 5; i++) {
        t = bc[(i+4)%5] ^ ((bc[(i+1)%5] << 1) | (bc[(i+1)%5] >> 63));
        for (int j = 0; j < 25; j += 5) st[j+i] ^= t;
      }
      t = st[1];
      for (int i = 0; i < 24; i++) {
        int j = KECCAK_PI[i];
        bc[0] = st[j];
        st[j] = (t << RHO[i]) | (t >> (64 - RHO[i]));
        t = bc[0];
      }
      for (int j = 0; j < 25; j += 5) {
        for (int i = 0; i < 5; i++) bc[i] = st[j+i];
        for (int i = 0; i < 5; i++) st[j+i] ^= (~bc[(i+1)%5]) & bc[(i+2)%5];
      }
      st[0] ^= RC[r];
    }
    memset(buf, 0, rate);
  };

  while (len > 0) {
    size_t n = (rate - pos < len) ? (rate - pos) : len;
    memcpy(buf + pos, data, n);
    pos += n; data += n; len -= n;
    if (pos == rate) { absorb(); pos = 0; }
  }
  buf[pos]      ^= 0x01;
  buf[rate - 1] ^= 0x80;
  absorb();
  for (int i = 0; i < 32; i++) out32[i] = (uint8_t)(st[i/8] >> (8 * (i % 8)));
}
