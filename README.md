# OFFPAY

> **Tap now. Chain later.** Two Android phones can transfer USDC peer-to-peer
> with both phones fully offline, then the receiver settles to Polygon when
> internet returns. No POS, no QR, no SMS — just NFC tap.

## What's new (v2 — unified wallet app)

The current shippable build is `android-wallet/`. Both phones run the same
app and can act as either sender or receiver per transaction. End-to-end
flow:

1. **Topup** (online): user wallet locks USDC into `OfflineVault` on
   Polygon Amoy. Backend gas-sponsors the wallet so a fresh user has zero
   onboarding friction.
2. **Tap** (offline): sender enters amount → arms HCE → taps receiver's
   phone. Sender's HCE signs a bearer voucher at tap time using the user's
   own secp256k1 key + `NonceTracker`; the receiver's NFC reader verifies
   the signature locally.
3. **Settle** (online, opportunistic): receiver's phone calls
   `OfflineVault.settleBearerBatch(vouchers, sigs, recipient)` directly
   from its own wallet → vault transfers USDC to receiver. A
   `NetworkCallback` fires this automatically on connectivity restore.

Every settle tx is visible on Polygonscan. Both sender's `lockFunds` and
receiver's `settleBearerBatch` show up under their respective wallet
addresses.

### What this build proves

- ✅ Phone-to-phone NFC tap works end-to-end on real Android hardware
  (tested OnePlus 6T ↔ Samsung).
- ✅ Both users hold their own keys and sign their own on-chain
  transactions — no custodial reliance during tap or settle.
- ✅ Sender phone never opens an Ethereum RPC; only the receiver does
  (and a backend-relay fallback if it lacks gas).
- ✅ `OfflinePay` voucher digest is byte-identical across Solidity,
  voucher.js, `VoucherSigner.kt`, and `VoucherVerifier.kt`.

### Quick start (two phones)

```powershell
# 0) Deploy contracts to Polygon Amoy (one-time)
cd contracts && npm install
$env:DEPLOYER_PRIVATE_KEY = "0x…"
npx hardhat run --network amoy scripts/deploy.js
# Paste vault + usdc addresses into android-wallet/.../Config.kt

# 1) Backend on laptop
cd ..\backend && npm install && npm start

# 2) Two phones via USB, ADB
adb -s <oneplus> reverse tcp:4000 tcp:4000
adb -s <samsung> reverse tcp:4000 tcp:4000

# 3) Build + install the wallet APK
cd ..\android-wallet
.\gradlew.bat :app:assembleDebug
adb -s <oneplus> install -r app\build\outputs\apk\debug\app-debug.apk
adb -s <samsung> install -r app\build\outputs\apk\debug\app-debug.apk

# 4) E2E protocol test (no UI required)
cd ..\backend && node src/e2e_test.js
```

### v2 architecture (Option B — non-custodial)

```
[Sender phone]              [Receiver phone]            [Backend]              [Polygon Amoy]
─ KeyVault                  ─ KeyVault                  ─ /api/wallet/init     ─ OfflineVault
─ NonceTracker              ─ VoucherStore               (gas + USDC mint)      (lockFunds,
─ VoucherSigner             ─ VoucherVerifier           ─ /api/wallet/redeem    settleBearerBatch)
─ HCE service               ─ ReaderModeLoop             (gas-sponsor relay)   ─ MockUSDC (Amoy)
─ SettlementClient          ─ SettlementClient
  · approveAndLock            · settleBearerBatch
```

### Known limitations / pre-pilot work

- MockUSDC on Amoy. Real-money flow needs an on-ramp like
  [Onmeta](https://onmeta.in) — see
  [docs/superpowers/specs/](docs/superpowers/specs/).
- KeyVault stores private keys in `SharedPreferences` plaintext. Move to
  Android Keystore before any pilot money moves.
- Backend keeps a small custodial gas sponsor; for production this would
  graduate to ERC-2771 meta-tx relayer.

---

## v1 (legacy) — customer/merchant split

> Cryptographically-signed offline payments for India. **Both** the customer **and**
> the merchant can be fully offline. Settlement happens on Polygon when either side
> reconnects. Works with smartphones (NFC HCE), feature phones (MIFARE card), or even
> a key fob.

OfflinePay turns crypto into a real payment rail for places where the network
isn't always there — kirana stores, delivery riders, rural transit, festival
grounds, power cuts. The secret is that vouchers are signed by the customer
**ahead of time**, so a merchant phone can verify and accept payment with no
internet at all, then settle in batch when it reconnects.

```
   ┌──────────────┐                                ┌──────────────┐
   │   Customer   │  signs vouchers when online    │   Customer   │
   │  smartphone  │ ─────────────────────────────► │ MIFARE card  │
   │   or wallet  │                                │  (or key fob)│
   └──────┬───────┘                                └──────┬───────┘
          │                                               │ tap
          │ HCE / NFC                                     ▼
          ▼                                       ┌──────────────┐
   ┌──────────────┐ Bluetooth SPP                 │ ESP32+RC522  │
   │   Merchant   │ ◄──────────────────────────── │   reader     │
   │  smartphone  │                                └──────────────┘
   └──────┬───────┘
          │ verify ECDSA signature offline (BouncyCastle)
          │ store accepted voucher in Room DB
          │
          │  …merchant comes back online…
          ▼
   ┌─────────────────────────┐ batch tx ┌────────────────────┐
   │  OfflinePay backend     │─────────►│  Polygon Amoy /    │
   │  (or merchant directly) │          │  Mainnet           │
   └─────────────────────────┘          │  OfflineVault.sol  │
                                        └────────────────────┘
```

## Why this is different from UPI Lite

UPI Lite **still requires the issuer bank to be online** for the spend ledger
to validate. The merchant device is offline; the customer's bank isn't. That
breaks during full-zone power outages and where bank correspondents simply
aren't reachable.

OfflinePay's voucher is a **self-verifying** message:
`(payer, merchant, amount, expiry, nonce, voucherId, chainId, vault)` hashed
with keccak256 and signed with secp256k1 (EIP-191). **The merchant phone
recovers the signer locally** — no third party online. Settlement on Polygon
costs roughly 1 cent per batch of 50 vouchers.

| Property                       | UPI Lite                       | OfflinePay                                   |
|--------------------------------|--------------------------------|----------------------------------------------|
| Bank online for verification?  | Yes (issuer must be reachable) | **No**                                       |
| Customer offline?              | Yes                            | Yes                                          |
| Merchant offline?              | Partially (hold-and-replay)    | **Yes — voucher is cryptographically valid** |
| Cross-border?                  | No                             | Yes (USDC settles anywhere)                  |
| Works without smartphone?      | No                             | **Yes — MIFARE card or key fob**             |
| Settlement layer?              | NPCI                           | Public chain (Polygon) → bank-agnostic       |

## What's in this repo

```
offlinepay/
├── contracts/         Hardhat — OfflineVault.sol + tests + Amoy deploy script
├── backend/           Node/Express — voucher issuer, mock UPI top-up, settlement queue
├── firmware/
│   ├── reader/        ESP32 sketch — reads MIFARE card and forwards over Bluetooth
│   └── topup/         ESP32 sketch — fetches voucher from backend, writes to MIFARE
├── android-merchant/  Kotlin — Bluetooth SPP receive + offline verify + Room queue + sync
├── android-customer/  Kotlin — HCE phone-to-phone + top-up UI + payer key vault
├── simulator/         Node — end-to-end CLI demo, single-tap helper, stress test
├── dashboard/         Single-file HTML — live judge-friendly console
└── docs/              Pitch one-pager, demo runbook, threat model
```

## Quick start (no hardware needed — full demo on a laptop)

You will need **Node.js 20+** and **npm**. That's it.

```bash
# Terminal A — local chain
cd contracts
npm install
npx hardhat node

# Terminal B — deploy
cd contracts
npm run deploy:local

# Terminal C — backend
cd backend
npm install
cp .env.example .env
npm run dev

# Terminal D — open the live console
cd dashboard
python -m http.server 5173        # or any static server
# then open http://localhost:5173
```

Click **Run end-to-end demo** in the dashboard. Watch the voucher feed light
up, the on-chain settled count climb, and the tx hash appear. Or run it from
the CLI:

```bash
cd simulator && npm install
node demo.js
```

That single command will print a step-by-step walkthrough of the entire flow
(top-up, voucher issuance, 5 offline taps, batch settle, replay rejection,
final stats).

## Hardware demo (ESP32 + RC522)

If you've soldered the RC522 headers and wired it as in `firmware/README.md`:

1. Flash `firmware/topup/topup_writer.ino` (edit the WiFi creds + backend URL),
   tap a blank MIFARE Classic 1K card → it gets loaded with a fresh voucher.
2. Flash `firmware/reader/offline_pay_reader.ino` to the same or a second
   ESP32 → it broadcasts as Bluetooth device `OfflinePay_Reader`.
3. Pair `OfflinePay_Reader` from the merchant Android phone, run the merchant
   app, then tap the loaded card on the reader. The phone shows the verified
   voucher, the LED flashes green, the buzzer beeps, and the card's voucher
   blocks are overwritten with `USED____________`.

## Polygon Amoy testnet

```bash
# Get free Amoy MATIC: https://faucet.polygon.technology
cd contracts
cp .env.example .env       # set DEPLOYER_PRIVATE_KEY
npm run deploy:amoy
# update CHAIN_RPC_URL + CHAIN_ID + VAULT_ADDRESS + USDC_ADDRESS in backend/.env
```

The `MockUSDC.sol` contract gets deployed alongside Vault on Amoy because real
USDC isn't on Amoy — for mainnet replace it with the canonical address (the
deploy script does this for you when network = `polygon`).

## Voucher format (the canonical hash)

The `voucherDigest` function in `OfflineVault.sol` is the source of truth.
The backend (`backend/src/voucher.js`) and the merchant Android app
(`android-merchant/.../VoucherVerifier.kt`) reproduce the same bytes:

```
keccak256(abi.encode(
    payer    : address,
    merchant : address,        // 0x0 = bearer (any merchant can claim)
    amount   : uint256,        // USDC base units (6 decimals)
    expiry   : uint256,        // unix seconds
    nonce    : uint256,        // strictly > lastNonce[payer]
    voucherId: bytes32,        // unique id per voucher
    chainId  : uint256,        // bound to network
    vault    : address         // bound to deployment
))
```

It's then EIP-191 prefixed (`"\x19Ethereum Signed Message:\n32"`) before
signing with secp256k1. The same signature works for both offline merchant
verification (`ecrecover`) and on-chain settlement (`ECDSA.recover`).

## Threat model & limits

Offline payments cannot prevent **all** double-spend without an oracle. We
bound the worst-case loss with three knobs in the contract:

| Knob              | Default | Purpose                                              |
|-------------------|---------|------------------------------------------------------|
| `maxSinglePayment`| $2.00   | Caps damage from a single stolen card / fake tap.    |
| `maxLockedBalance`| $5.00   | Caps total exposure per customer per top-up cycle.   |
| `defaultVoucherTTL`| 24 h   | Stale vouchers expire — limits the attack window.    |

A double-spent voucher across two offline merchants is detected on settlement
(only the first to reach the chain wins). The losing merchant's app shows a
reconciliation toast — same UX as a chargeback. For low-amount caps this is
acceptable; for higher amounts the customer phone refreshes its nonce by
pinging the backend, which is the same model as UPI Lite.

The merchant phone also keeps a local SQLite cache of every voucherId it has
ever seen, so the same card cannot be tapped twice on the same merchant.

## What still needs work for production

- **MIFARE Classic is broken crypto.** Demo uses default key 0xFFFF…; for a
  real product use MIFARE DESFire EV2 with AES-128 and per-card derived keys.
- **Custodial vs non-custodial.** The demo's `/api/topup` uses a backend wallet
  as proxy payer. The real flow is: customer's phone holds the key in the
  hardware-backed Keystore (StrongBox if available) and signs vouchers itself.
  The non-custodial path is wired in the Android customer app's `KeyVault.kt`.
- **Onmeta integration.** Replace the mock UPI in `/api/topup` with a real
  on/off-ramp — Onmeta, Transak, Bridge.xyz all expose an INR→USDC endpoint.
- **Chain choice.** Polygon PoS is fine; for true sub-cent gas, deploy on
  Polygon zkEVM, Base, or even Solana via ECDSA precompiles.

## Repo navigation tips

- All contract logic + tests in `contracts/contracts/OfflineVault.sol` and
  `contracts/test/OfflineVault.test.js`. **`npm test` → 11/11 pass.**
- Backend voucher signing: `backend/src/voucher.js` (`voucherDigest`,
  `signVoucher`, `recoverVoucherSigner`).
- Demo entry points: `dashboard/index.html`, `simulator/demo.js`.
- The `glowing-painting-beaver.md` plan file in `~/.claude/plans/` describes
  every architectural decision and the status of each component.

---

Built for a hackathon. The architecture is real; some sharp edges are not yet
production-grade. PRs welcome 🤝
