# OfflinePay — pitch one-pager

## The problem

India runs on UPI — but UPI breaks the moment the issuer bank is unreachable.
Power cuts, weak signal, festival-day load: each costs merchants real revenue.
UPI Lite was the response, but it still requires the **issuer bank** to be
online for the offline ledger to drain. Both ends offline ≠ supported.

## Our answer

A cryptographic voucher rail. Customers sign vouchers up front; merchants
verify them locally; settlement happens on a public chain when either side
reconnects.

> **Key insight:** if a merchant's phone can verify a voucher with a
> standard `ecrecover` against the customer's address, no bank in the world
> needs to be online.

## How it works (one tap)

1. Customer tops up ₹100 once a week. Backend mints USDC, locks it in
   `OfflineVault.sol` against the customer's address.
2. Customer's phone (or a top-up ESP32) signs N small vouchers
   (₹10–₹200 each, 24h expiry) and stores them on the phone or writes them
   to a MIFARE card.
3. Merchant phone reads a voucher via NFC HCE (phone-to-phone) or via an
   ESP32+RC522 reader bridged over Bluetooth (card-to-phone).
4. Merchant phone runs `ECDSA.recover` locally — accepts in <100 ms.
5. When merchant reconnects, queued vouchers are batch-settled on Polygon
   in one tx. ~$0.0002/voucher in gas at typical Polygon prices.

## The signature trick that makes this real

Every voucher is hashed and signed in **one** scheme — secp256k1 + EIP-191 —
which is verifiable both:

- on-chain by `OfflineVault.settleVoucher`, and
- off-chain by the merchant Android app via BouncyCastle.

The same bytes work in both worlds. The contract binds `chainId` and the
deployment address into the digest, so a voucher from one network can't
replay on another.

## Why it wins

- **Both sides truly offline.** Not just the merchant. UPI Lite can't claim this.
- **Works without a smartphone.** A ₹40 MIFARE card or ₹120 DESFire fob —
  inclusion for users who can't afford a smartphone or who broke theirs.
- **Cross-border by default.** USDC settles anywhere with a Polygon RPC.
- **Sub-cent gas.** Batched settlement on Polygon zkEVM/Base brings effective
  cost below 1 paisa per voucher.
- **No new bank.** OfflinePay can route to existing UPI on the on-ramp side
  (Onmeta, Transak) and to merchant bank accounts on the off-ramp side.

## What's working today

- Solidity contract — 11/11 tests passing.
- Node backend issuing real ECDSA-signed vouchers, settling them on a local
  Polygon node in batches of up to 50.
- ESP32 firmware (reader + top-up) for MIFARE Classic 1K, ~336 bytes/voucher.
- Live single-page judge dashboard with a one-button end-to-end demo.
- CLI simulator that runs the entire flow in 6 seconds.

## Roadmap

- Replace MIFARE Classic with DESFire EV2 (AES-128 + per-card keys).
- Wire Onmeta sandbox for real INR→USDC on-ramp.
- Move custody from backend to Android Keystore (non-custodial mode).
- Audit-grade rewrite of OfflineVault.sol; deploy to Polygon zkEVM mainnet.
- Merchant settlement automation — the merchant app pushes batches every
  N vouchers OR every M minutes, whichever first.

## Demo path

`cd offlinepay/simulator && node demo.js` — full happy path + replay attack
prevention in 6 seconds. Or open `dashboard/index.html` and click
**Run end-to-end demo**.

## Asks of the judges

We need feedback on (a) the hardware UX with key fobs vs cards (we believe
fobs win — easier to attach to a keychain, harder to lose), and (b) whether
custodial v1 or non-custodial v1 is the right launch posture in India given
the current crypto regulatory state.
