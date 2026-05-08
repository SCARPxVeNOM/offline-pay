# OfflinePay threat model

## Trust assumptions

| Actor              | Trusted to…                                           |
|--------------------|-------------------------------------------------------|
| Customer phone / card | Hold the payer's secp256k1 key. Sign honest vouchers. |
| Merchant phone     | Verify and queue accepted vouchers. Settle eventually. |
| Backend / issuer   | Bridge UPI ↔ USDC. (Custodial demo path only — also signs vouchers in v1.) |
| Polygon / chain    | Honest finality. Standard Layer-1 trust.              |
| ESP32 reader       | Just I/O. Never holds keys. Compromising it gives the attacker only what's already on the card. |

## Attacks and mitigations

### A1. Double-spend across two merchants
Customer taps the same card at merchant A and merchant B while both are
offline. Both accept. **Mitigation:** the contract uses `usedVouchers` as a
mask + `lastNonce` as a strict ordering. Whoever settles first wins; the
loser's app shows a reconciliation toast on next sync. Capped to
`maxSinglePayment` = $2 by default.

### A2. Replay of an already-settled voucher
Attacker captures a voucher and tries to settle it again. **Mitigation:**
`usedVouchers[voucherId] = true` in `_settle`, second call reverts.

### A3. Cross-chain replay
Attacker takes a voucher signed for chain A and replays on chain B
(if vault is deployed identically). **Mitigation:** `chainId` and the
deployed `vault` address are baked into `voucherDigest`. Different network
or different deployment → different digest → different signer → reject.

### A4. Voucher tampering on the card
Attacker reads card, modifies amount field, writes back. **Mitigation:** the
signature covers `amount`. `recover(digest, sig) != payer` → reject.

### A5. Merchant fakes accept and pockets card
ESP32 reader marks the card USED on accept. Even if the attacker stops the
merchant phone from confirming, the firmware's `markVoucherUsed` runs only on
ACCEPT. A REJECT or TIMEOUT leaves the card intact, so the customer can try
elsewhere. (Edge case: a malicious merchant could send ACCEPT, mark the card
USED, but never settle on chain. The customer loses that voucher's value.
Bounded by `maxSinglePayment` and reputational cost — same as a UPI scam.)

### A6. Backend custodial wallet compromise
In the demo path the backend signs vouchers on the customer's behalf. If
compromised, the attacker drains the locked balance (capped to
`maxLockedBalance` = $5 per customer). **Mitigation:** non-custodial mode is
wired up — `KeyVault.kt` keeps the customer's secp256k1 key in the Android
hardware-backed Keystore. The backend then only orchestrates UPI ↔ on-chain
locks; signing happens on the user's phone.

### A7. MIFARE Classic clone
Anyone with a Proxmark can clone a MIFARE Classic 1K in ~30 seconds. The
attacker now has a duplicate of all vouchers on the card. **Mitigation:**
default key 0xFFFF… is fine for a hackathon demo; production must use
DESFire EV2 + per-card AES-128 derived keys (handled by the top-up station).
Even with a clone the per-voucher cap and the "first-to-settle wins" rule
limit losses to one offline tap.

### A8. Side-channel on signing key
Customer's phone leaks the key via malware. **Mitigation:** Android
hardware-backed Keystore + StrongBox where available. Limit per-customer
locked balance so the upper bound on loss is small.

## Limits in code (the dials judges should poke)

```solidity
uint256 public maxSinglePayment   = 2_000_000;      // $2.00
uint256 public maxLockedBalance   = 5_000_000;      // $5.00
uint256 public defaultVoucherTTL  = 24 hours;
```

These are owner-mutable but emit `LimitsUpdated` events. The defaults are
intentionally conservative for a v1 launch in India. UPI Lite's per-tx cap is
₹500 (~$6); ours is $2 to match a more cautious mental model around offline
double-spend exposure.

## What this does NOT defend against

- **A bug in the customer phone's app** that signs malicious vouchers. (Audit
  + reproducible builds + hardware key is the long-term answer.)
- **NPCI-level collusion.** We're a sidecar, not a replacement, for now.
- **Total network outage > 24h.** Vouchers expire; users would need offline
  re-issuance, which is a future feature (signature chain w/ deferred root
  commitment).
