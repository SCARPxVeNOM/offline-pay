// =============================================================================
//  OfflinePay — ESP32 Top-Up Writer Firmware
// =============================================================================
//  Hardware: ESP32 + RC522 (same wiring as the reader sketch).
//
//  Flow:
//    1. Connect to WiFi.
//    2. POST /api/vouchers/issue to the backend, asking for ONE voucher.
//    3. Wait for a card tap.
//    4. Write the issued voucher's `cardPayload` JSON across MIFARE data
//       blocks 4..30 (same 21-block layout as the reader expects).
//    5. Green LED + beep on success.
//
//  Configure the SSID, password, customer address, and backend URL below.
//  For the demo it is fine to point this at a laptop running:
//      cd backend && npm run dev
// =============================================================================

#include <SPI.h>
#include <MFRC522.h>
#include <WiFi.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>

// --- USER CONFIG ---------------------------------------------------------

const char *WIFI_SSID     = "YOUR_WIFI_SSID";
const char *WIFI_PASSWORD = "YOUR_WIFI_PASSWORD";

// HTTP endpoint of the OfflinePay backend.
const char *BACKEND_URL   = "http://192.168.1.100:4000/api/vouchers/issue";

// Whose card are we loading? In the custodial-demo path the backend signs
// FROM its own wallet, so the value here is mostly informational, but the
// backend may use it to bind future voucher issuance.
const char *CUSTOMER_ADDR = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";

// USDC base units per voucher (6 decimals). Default = $0.20.
const long  AMOUNT_USDC_EACH = 200000;

// --- PINS ---------------------------------------------------------------

#define SS_PIN     5
#define RST_PIN    22
#define LED_GREEN  26
#define LED_RED    27
#define BUZZER     25

MFRC522 rfid(SS_PIN, RST_PIN);
byte DEFAULT_KEY[6] = {0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF};

// Same layout the reader uses. 21 blocks * 16 = 336 bytes capacity.
const uint8_t VOUCHER_BLOCKS[] = {
   4,  5,  6,
   8,  9, 10,
  12, 13, 14,
  16, 17, 18,
  20, 21, 22,
  24, 25, 26,
  28, 29, 30
};
const uint8_t VOUCHER_BLOCK_COUNT = sizeof(VOUCHER_BLOCKS) / sizeof(VOUCHER_BLOCKS[0]);

// --- Setup --------------------------------------------------------------

void setup() {
  Serial.begin(115200);
  while (!Serial && millis() < 2000) {}
  SPI.begin();
  rfid.PCD_Init();

  pinMode(LED_GREEN, OUTPUT);
  pinMode(LED_RED,   OUTPUT);
  pinMode(BUZZER,    OUTPUT);

  Serial.print("[WIFI] connecting to ");
  Serial.println(WIFI_SSID);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  unsigned long t0 = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - t0 < 30000) {
    delay(500);
    Serial.print(".");
  }
  Serial.println();
  if (WiFi.status() == WL_CONNECTED) {
    Serial.print("[WIFI] connected, ip=");
    Serial.println(WiFi.localIP());
  } else {
    Serial.println("[WIFI] failed — top-up firmware needs internet");
  }

  Serial.println("[TOPUP] tap a card to load a fresh voucher.");
}

// --- Main loop ----------------------------------------------------------

void loop() {
  if (!rfid.PICC_IsNewCardPresent()) { delay(50); return; }
  if (!rfid.PICC_ReadCardSerial())   { delay(50); return; }

  Serial.println("[CARD] detected — fetching voucher from backend");

  String payload = fetchVoucherPayload();
  if (payload.length() == 0) {
    Serial.println("[BACKEND] empty — aborting");
    flashRed();
    halt();
    return;
  }

  if (payload.length() > VOUCHER_BLOCK_COUNT * 16) {
    Serial.print("[CARD] payload too big: ");
    Serial.print(payload.length());
    Serial.println(" bytes");
    flashRed();
    halt();
    return;
  }

  if (!writePayload(payload)) {
    Serial.println("[CARD] write failed");
    flashRed();
    halt();
    return;
  }

  Serial.println("[CARD] voucher written successfully");
  flashGreen();
  halt();
}

// --- Backend HTTP -------------------------------------------------------

String fetchVoucherPayload() {
  if (WiFi.status() != WL_CONNECTED) return "";

  HTTPClient http;
  http.begin(BACKEND_URL);
  http.addHeader("Content-Type", "application/json");

  StaticJsonDocument<256> req;
  req["customer"]       = CUSTOMER_ADDR;
  req["count"]          = 1;
  req["amountUsdcEach"] = AMOUNT_USDC_EACH;
  String body;
  serializeJson(req, body);

  int code = http.POST(body);
  if (code != 200) {
    Serial.print("[HTTP] status ");
    Serial.println(code);
    http.end();
    return "";
  }

  String response = http.getString();
  http.end();

  // Parse {"ok":true,"vouchers":[{"cardPayload":"..."}]}
  DynamicJsonDocument resp(8192);
  if (deserializeJson(resp, response) != DeserializationError::Ok) {
    Serial.println("[HTTP] bad json");
    return "";
  }
  const char *cardPayload = resp["vouchers"][0]["cardPayload"];
  if (!cardPayload) return "";
  return String(cardPayload);
}

// --- MIFARE write -------------------------------------------------------

bool authBlock(uint8_t blockNum) {
  MFRC522::MIFARE_Key key;
  memcpy(key.keyByte, DEFAULT_KEY, 6);
  return rfid.PCD_Authenticate(
    MFRC522::PICC_CMD_MF_AUTH_KEY_A, blockNum, &key, &(rfid.uid)
  ) == MFRC522::STATUS_OK;
}

bool writePayload(const String &payload) {
  size_t len = payload.length();
  for (uint8_t i = 0; i < VOUCHER_BLOCK_COUNT; i++) {
    uint8_t block = VOUCHER_BLOCKS[i];
    byte data[16] = {0};
    for (uint8_t j = 0; j < 16; j++) {
      size_t idx = (size_t)i * 16 + j;
      data[j] = (idx < len) ? (byte)payload[idx] : 0x00;
    }
    if (!authBlock(block)) {
      Serial.print("[CARD] auth failed @ block "); Serial.println(block);
      return false;
    }
    if (rfid.MIFARE_Write(block, data, 16) != MFRC522::STATUS_OK) {
      Serial.print("[CARD] write failed @ block "); Serial.println(block);
      return false;
    }
  }
  return true;
}

void halt() { rfid.PICC_HaltA(); rfid.PCD_StopCrypto1(); }

// --- UI ------------------------------------------------------------------

void flashGreen() {
  digitalWrite(LED_GREEN, HIGH);
  tone(BUZZER, 1200, 120); delay(140);
  tone(BUZZER, 1600, 160); delay(800);
  digitalWrite(LED_GREEN, LOW); noTone(BUZZER);
}
void flashRed() {
  digitalWrite(LED_RED, HIGH);
  tone(BUZZER, 350, 250); delay(300);
  tone(BUZZER, 350, 250); delay(700);
  digitalWrite(LED_RED, LOW); noTone(BUZZER);
}
