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
#include "wallet.h"

#define SS_PIN     5
#define RST_PIN    22
#define LED_GREEN  26
#define LED_RED    27
#define BUZZER     25

#define DECISION_TIMEOUT_MS  10000UL

MFRC522 rfid(SS_PIN, RST_PIN);
BluetoothSerial SerialBT;

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
