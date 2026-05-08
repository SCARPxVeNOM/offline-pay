# OfflinePay Customer (Android)

Wallet app that:

1. Generates a secp256k1 key in `KeyVault` (demo: SharedPreferences;
   production: Android Keystore + StrongBox).
2. Calls the OfflinePay backend's `/api/topup` to mock-mint USDC and lock it
   on `OfflineVault.sol`.
3. Calls `/api/vouchers/issue` (custodial demo path) **or** signs vouchers
   locally via `VoucherSigner` (non-custodial path).
4. Pushes each issued voucher's `cardPayload` JSON into `NextVoucherProvider`,
   a tiny in-process queue.
5. Exposes `HceVoucherService` (a `HostApduService`) under AID
   `F0011AC0DE10AC1D`. When the merchant phone reads the customer phone
   over NFC, it issues APDU `00 C0 …` and we respond with the next pending
   voucher payload.

Tap-to-pay between two Android phones: customer phone runs this app, merchant
phone runs an NFC reader app that selects the OfflinePay AID and parses the
returned bytes. The same `Voucher` JSON the ESP32 reader produces.

## Build

Open in Android Studio Hedgehog or newer. Targets Kotlin 2.0.20, AGP 8.6.0,
compileSdk 34, minSdk 26.

```bash
./gradlew :app:assembleDebug
```

## Files

- `KeyVault.kt`            — secp256k1 key persistence (replace with Keystore in prod).
- `VoucherSigner.kt`       — local signing path; produces the same digest the contract uses.
- `TopupClient.kt`         — REST client to the backend.
- `HceVoucherService.kt`   — `HostApduService` that emits the next voucher.
- `NextVoucherProvider`    — process-local queue feeding the HCE service.
- `MainActivity.kt`        — top-up + load + queue display UI.

## Configure

Edit `BACKEND` in `MainActivity.kt`. `10.0.2.2` is the emulator's loopback;
on a real device set it to `http://<laptop-ip>:4000`.

## Pairing with the merchant app

The current `android-merchant` app expects vouchers to arrive over Bluetooth
SPP from the ESP32 reader. To do phone-to-phone NFC handoff, add a small NFC
reader Activity to the merchant app — it should call `IsoDep.transceive` with
APDU `00C0000000`, parse the 2-byte length + UTF-8 payload, and feed the
resulting JSON through `VoucherVerifier.verify` exactly like a Bluetooth
frame. (Left as a TODO for v2; the Bluetooth path is the pitch demo.)
