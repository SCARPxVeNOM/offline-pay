# Demo runbook

A 90-second judge demo that uses no hardware. If hardware is wired up, see
the optional steps at the end.

## 0. One-time setup (~3 min)

```bash
cd offlinepay/contracts && npm install
cd ../backend           && npm install && cp .env.example .env
cd ../simulator         && npm install
```

## 1. Three terminals

| Terminal | Command                                      | Purpose            |
|----------|----------------------------------------------|--------------------|
| A        | `cd contracts && npx hardhat node`           | local Polygon-like chain |
| B        | `cd contracts && npm run deploy:local`       | deploys vault + mock USDC |
| C        | `cd backend && npm run dev`                  | issuer + UPI mock + settle |

## 2. Demo path (pick one)

### A) Web dashboard (preferred for judges)

Open `dashboard/index.html` in the browser. The top-right pill should turn
green and read `chain 31337`.

Click **Run end-to-end demo**. Talk through:

1. ₹100 UPI top-up — locked $1.00 of USDC on-chain.
2. 5 vouchers issued, each $0.20, expiry 24 h.
3. Each tap shown live as a row turning amber (`redeemed`).
4. **Settle** — single tx, all 5 rows go green (`settled`), tx hash visible.
5. The replay attempt at the end is rejected — show the log line.

### B) Pure CLI

```bash
cd offlinepay/simulator
node demo.js
```

Eight numbered steps with timing. Same story, faster.

### C) Stress / throughput

```bash
node simulator/stress.js 25
```

Issues 25 vouchers, batches them into one settle tx, prints elapsed time.
Use to back the "1 paisa per voucher" gas claim in the pitch.

## 3. Optional — hardware demo

Pre-reqs: ESP32 wired per `firmware/README.md`, RC522 header pins soldered.

1. Edit `firmware/topup/topup_writer.ino`:
   - `WIFI_SSID`, `WIFI_PASSWORD`
   - `BACKEND_URL` → `http://<your-laptop-ip>:4000/api/vouchers/issue`
2. Flash. Tap a blank MIFARE Classic 1K → green LED + beep = card loaded.
3. Re-flash with `firmware/reader/offline_pay_reader.ino`.
4. From the merchant Android phone: pair `OfflinePay_Reader` in BT settings.
5. Open `android-merchant` in Android Studio, run on the merchant phone.
6. Tap the loaded card on the reader. Phone shows verified voucher, LED green,
   buzzer happy. Tap again on the same card — LED red, "ALREADY USED".

## 4. Things to point at while talking

- **`OfflineVault.sol:voucherDigest`** — the canonical hash. "Same bytes get
  signed, then verified both off-chain by the phone and on-chain by the
  contract."
- **`backend/src/voucher.js:signVoucher`** — three lines of code that produce
  the signature.
- **`firmware/reader/offline_pay_reader.ino:readVoucher`** — the loop that
  reads 21 MIFARE blocks and reassembles the JSON.
- The `dashboard` voucher feed turning green — that's the moment USDC moves
  on-chain.

## 5. When things go wrong

| Symptom                                       | Fix                                                     |
|-----------------------------------------------|---------------------------------------------------------|
| dashboard shows "backend offline"             | check terminal C is running and on port 4000            |
| `nonce has already been used`                 | restart hardhat node + rerun `deploy:local` + restart backend |
| `cannot use object value with unnamed components` | ensure the latest `backend/src/server.js` is running (passes tuples positionally) |
| ESP32 won't pair                              | hold BOOT button while flashing; reset after upload     |
