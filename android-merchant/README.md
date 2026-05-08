# OfflinePay Merchant (Android)

Kotlin app that:

1. Connects to the ESP32 reader over Bluetooth SPP (`OfflinePay_Reader`).
2. Receives `VOUCHER <uid> <json>` frames.
3. Verifies the secp256k1 signature **offline** with web3j (`Sign.signedMessageHashToKey`)
   against the same digest the smart contract uses.
4. Persists accepted vouchers in Room.
5. When the merchant taps **Settle**, POSTs the queue to the backend's
   `/api/merchant/redeem` + `/api/merchant/settle` endpoints, which submit
   `OfflineVault.settleBatch` on Polygon.

## Build

Open in **Android Studio Hedgehog** or newer. The Gradle config targets:

- Kotlin 2.0.20
- AGP 8.6.0
- compileSdk 34, minSdk 26 (Android 8.0)

```bash
./gradlew :app:assembleDebug
```

## Configure the demo

Edit `MainActivity.kt`:

| Constant            | Default                                       | When to change           |
|---------------------|-----------------------------------------------|--------------------------|
| `CHAIN_ID`          | 31337 (hardhat)                               | 80002 for Amoy           |
| `VAULT_ADDRESS`     | local hardhat default                         | actual deployment         |
| `MAX_SINGLE_USDC`   | 2_000_000 (matches contract)                  | tighter caps for kiranas |
| `BACKEND_BASE_URL`  | `http://10.0.2.2:4000` (emulator → host)      | `http://<laptop-ip>:4000`|

Pair `OfflinePay_Reader` from the device's Bluetooth settings before opening
the app.

## Files

- `Voucher.kt` — typed payload + the JSON wire format from the card.
- `VoucherVerifier.kt` — reproduces `OfflineVault.voucherDigest`, runs
  `signedMessageHashToKey`, compares to `payer`. **Same crypto path the
  contract uses.**
- `VoucherStore.kt` — Room queue with accepted / rejected / settled states.
- `BluetoothBridge.kt` — listens on the SPP socket, parses frames, sends
  `ACCEPT\n` / `REJECT\n` back.
- `SettlementClient.kt` — POSTs queued rows to the backend.
- `MainActivity.kt` — wires everything, in-code Compose-free UI for speed.
