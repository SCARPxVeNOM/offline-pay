# OfflinePay Firmware

Two Arduino sketches. They share pinout and the same MIFARE block layout so
that a card written by `topup_writer` can be read by `offline_pay_reader`.

## offline_pay_reader

Tap a loaded MIFARE card → reads the voucher JSON across blocks 4-30 (skipping
sector trailers) → forwards over Bluetooth SPP as `VOUCHER <uid> <json>` →
waits for `ACCEPT\n` or `REJECT\n` from the merchant phone → green/red LED +
buzzer + overwrites blocks with `USED____________` on accept.

Bluetooth device name: **`OfflinePay_Reader`**.

Pair this from the merchant Android phone in Bluetooth settings BEFORE running
the merchant app.

## topup_writer

Edit the SSID, password, backend URL, and customer address at the top, then
flash. Tap a fresh MIFARE Classic 1K card → fetches a freshly signed voucher
from `POST /api/vouchers/issue` → writes it across the same 21 data blocks.

Default key for blank cards: `FF FF FF FF FF FF`. If your card uses different
keys, change `DEFAULT_KEY[]` in both sketches.

## Pinout (matches the build doc exactly)

| RC522 | ESP32  | Note            |
|-------|--------|-----------------|
| SDA   | GPIO 5 | Chip select     |
| SCK   | GPIO 18| SPI clock       |
| MOSI  | GPIO 23|                 |
| MISO  | GPIO 19|                 |
| RST   | GPIO 22|                 |
| 3.3V  | 3.3V   | NOT 5V          |
| GND   | GND    |                 |

| Output            | Pin     |
|-------------------|---------|
| LED green +       | GPIO 26 |
| LED red +         | GPIO 27 |
| Buzzer +          | GPIO 25 |

Use 220 ohm series resistors for the LEDs.

## Required libraries (Arduino IDE → Library Manager)

- **MFRC522** by GithubCommunity
- **ArduinoJson** by Benoit Blanchon
- **BluetoothSerial** (built into the ESP32 core)
- **WiFi**, **HTTPClient** (built in)
