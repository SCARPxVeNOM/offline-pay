# OfflinePay — P2P NFC tap MVP design

**Date:** 2026-05-08
**Scope:** Single Android wallet app, phone-to-phone offline USDC payments via
NFC tap (QR fallback). Replaces the customer-app / merchant-app split with a
unified "wallet" app where every user can send and receive.
**Out of scope:** mesh replication, MerchantRegistry, ESP32 reader, backend
gas-sponsor relay, iOS, receiver-driven payment requests.

## 1. Goal

Two Android phones, both offline, can transfer USDC by tapping. The sender
enters an amount, holds the phone near the receiver's phone, and the voucher
moves over NFC. The receiver settles on Polygon when reconnected. No POS, no
ESP32, no merchant identity — both sides are equal P2P wallets.

## 2. What stays from the current repo

- `OfflineVault.sol` — unchanged. We use `settleVoucher` (bearer/fixed-merchant
  path) where `voucher.merchant` is set to the receiver's wallet address and
  the receiver phone is `msg.sender`.
- `voucher.js` digest preimage and JSON schema — unchanged.
- `VoucherSigner.kt`, `NonceTracker.kt`, `KeyVault.kt`, `KeyBackup.kt`,
  `BackupRestoreActivity.kt` — ported into the unified app verbatim.
- `VoucherVerifier.kt`, `VoucherStore.kt` (DB schema simplified — see §7) —
  ported.
- Backend `/api/topup` and `/api/keybackup/pepper` — unchanged.
- Dashboard — unchanged, read-only.

## 3. What gets cut from the MVP wire-up

Code stays in the repo (additive re-introduction later) but is not built into
the unified app:

- `MeshBroadcaster`, `HandshakeManager` — mesh replication, claim/ack,
  Sybil-resistant peer auth.
- `BluetoothBridge` — ESP32 SPP transport.
- `MerchantRegistry.sol` usage on the device side; `settleVoucherAsDevice`.
- ESP32 firmware (`firmware/reader/`).
- Backend `/api/merchant/settle` — still served, but the app stops calling it.

## 4. Architecture

```
[Sender phone]               [Receiver phone]                [Backend]              [Polygon]
─ KeyVault                   ─ KeyVault                      ─ /api/topup           ─ OfflineVault
─ NonceTracker               ─ VoucherStore                  ─ Mock UPI              (lockFunds,
─ VoucherSigner              ─ VoucherVerifier                                        settleVoucher,
─ HCE service                ─ Reader-mode loop                                       settleBatch)
─ Send screen                ─ Receive screen                                        ─ MockUSDC
─ QR show fallback           ─ QR scan fallback
```

Single APK. The same install can act as either side per transaction.

## 5. NFC tap protocol

Sender enters amount on Send screen → taps "Pay" → HCE service goes live with
the pending `(amount, expiry)` in a singleton state holder. Receiver opens
Receive screen → reader mode starts. Phones tap.

```
APDU                             Direction       Payload
SELECT AID  (00 A4 04 00 …)      R → S           offlinepay AID bytes
SW=9000                          S → R
INS 0xC1 "REQUEST_PAY"           R → S           20-byte receiver EVM address
DATA + SW=9000                   S → R           <2-byte BE len><voucher JSON>9000
```

The 0xC1 command APDU is `00 C1 00 00 14 <20 addr bytes>` (no Le). The HCE
service identifies it by `commandApdu[1] == 0xC1` and reads bytes 5..24 as
the address. The existing `0xC0` handler in `HceVoucherService.kt` is
removed — the unified app does not pre-load a queue of bearer vouchers.

On INS 0xC1, the HCE service:

1. Reads the 20-byte address from the APDU data field.
2. Reads the pending `(amount, expiry)` from the singleton state. If unset,
   returns `6A 82` ("file not found") so the receiver shows a clean error.
3. Calls `VoucherSigner.signNext(receiverAddrHex, amount, expiry)` — uses
   `NonceTracker.next()` (synchronous `commit()`) so a crash mid-tap can
   never reuse a nonce. The current `signNext` signature must be extended
   to accept `receiverAddr` and `amount` as parameters (today's customer
   app passes a fixed merchant from `Config`); same digest math, just
   parameterized.
4. Encodes the voucher as JSON (same `CardVoucherPayload` shape that already
   crosses the wire today), prepends a 2-byte big-endian length, appends
   `9000`, returns.
5. Marks the pending state as consumed so the next tap requires a fresh
   amount entry on the Send screen.

The receiver, on reading the response:

1. Parses the length-prefixed JSON.
2. Runs `VoucherVerifier.verify(v)`:
   - chainId / vault address match the device's configured network
   - amount ≤ `maxSinglePayment`
   - expiry not in the past
   - signature recovers to `payer`
   - voucherId not seen in local store
3. Saves to `VoucherStore` with status `accepted`.
4. Triggers a settle attempt (see §6).

Latency target: full SELECT + 0xC1 round-trip + sign + write under 300 ms.
Per-key signing in `VoucherSigner` is sub-50 ms on any modern phone; budget
is comfortable.

### Signing inside HCE — safety notes

- The signing happens in `processCommandApdu`, which Android invokes on a
  binder thread. Heavy work there can drop the field connection. Mitigations:
  - Pre-warm `KeyVault` and `VoucherSigner` lazily on Send screen load.
  - Pre-compute `expiry = now + defaultTTL` when the user types the amount,
    not in `processCommandApdu`.
- The HCE service must never block on disk or network. `NonceTracker.next()`
  uses synchronous `SharedPreferences.commit()`; fine — the write is a small
  int and is the *only* persistent step before returning.
- If signing throws, return `6A 82` and surface the error on the Send screen
  via a singleton "last HCE error" channel.

## 6. Settlement

Receiver phone calls the vault directly. No backend in the path.

```kotlin
// pseudocode
val txHash = vault.settleBatch(pendingVouchers.map { it.toContractStruct() },
                               pendingVouchers.map { it.signatureBytes })
                  .send(fromWallet = receiverWallet)
```

- Single voucher → `settleVoucher`. Two or more queued → `settleBatch`.
- Receiver wallet needs MATIC for gas. First-run flow: if MATIC balance is
  zero, show a "fund this wallet for gas" screen with the address copied to
  clipboard and an Amoy faucet hint.
- Auto-settle when: (a) receiver app is foregrounded and reachable
  (`ConnectivityManager.NetworkCallback` reports a default network with
  internet capability), (b) at least one voucher with status `accepted`
  is pending. Manual "Settle now" button is also available on the Receive
  screen.
- On success, mark vouchers `settled` with the tx hash. On failure, keep
  status `accepted` and retry on next foreground.
- Errors from the contract surface to the receiver UI by exact revert
  reason: `already settled`, `expired`, `bad signature`, `insufficient
  locked`, `stale nonce`. Each maps to a specific UI message.

### Why direct settle, not backend relay

- Zero contract changes; zero new backend endpoints.
- True P2P story: "your phone is the wallet, your phone settles".
- Demo gas covered by Amoy faucet.
- Production replacement path is additive: add `settleVoucherTo(v, sig,
  recipient)` to the contract, point a `RelayClient` at it, default-on for
  zero-MATIC wallets. Doesn't break any of the MVP code.

## 7. Local storage

`VoucherStore` (Room) — schema simplified from current v2:

```
voucher
  voucherId TEXT PK
  payer TEXT
  merchant TEXT          -- receiver's address (us)
  amount TEXT            -- BigInteger as decimal string
  expiry INTEGER
  nonce INTEGER
  signature TEXT
  status TEXT            -- accepted | settled | rejected
  rejectReason TEXT?
  receivedAt INTEGER
  settledAt INTEGER?
  txHash TEXT?
```

Removed columns from the merchant-app schema: `replicaCount`, `replicaPeers`.
Removed status value: `replica`. No migration needed — we ship a fresh schema
in the new app.

Each install has exactly one wallet (one `KeyVault`). The same wallet is
used for sending (signs vouchers, consumes `NonceTracker`) and receiving
(its address is the `merchant` field of incoming vouchers, and it is the
`msg.sender` for `settleVoucher`). When acting as sender, `VoucherStore`
stays empty — sender does not retain a copy of issued vouchers. If a
sender-side audit log is needed, that is a follow-up.

## 8. UI screens

```
HomeScreen
  ├─ wallet address + copy button
  ├─ locked USDC balance / wallet USDC balance
  ├─ MATIC balance + faucet hint if 0
  ├─ [ Send ] [ Receive ] [ Top up ]
  └─ recent activity list (last 10 vouchers)

SendScreen
  ├─ amount input (USDC, 6-decimal aware)
  ├─ "Hold near receiver phone" hint
  ├─ HCE-active indicator (pulsing dot when in tap-ready state)
  ├─ post-tap success / error banner
  └─ "Show QR instead" fallback button

ReceiveScreen
  ├─ this device's address (large, copyable, also rendered as a QR
  │   for the fallback path — sender scans this same QR)
  ├─ "Tap sender's phone to your back" hint
  ├─ reader-mode scanning indicator
  ├─ "Scan voucher QR" fallback button
  ├─ pending vouchers list (count + total)
  └─ [ Settle now ] (auto-runs in background too)

TopUpScreen           (existing TopupClient flow)
BackupRestoreScreen   (existing, ported as-is)
HistoryScreen         (read-only filtered VoucherStore view)
```

Programmatic Kotlin views (matches repo convention — see CLAUDE.md
"Conventions").

## 9. QR fallback

Symmetric two-QR exchange when NFC isn't available or fails:

1. Receiver opens Receive screen — its address is already shown as a QR
   (CameraX + ZXing for rendering).
2. Sender taps "Show QR instead" → camera opens, scans receiver's address QR
   (ML Kit barcode scanner). Sender enters amount, hits "Generate voucher
   QR" — same `VoucherSigner.signNext()` call, but the JSON is rendered as a
   QR (ZXing) rather than emitted via HCE.
3. Receiver taps "Scan voucher QR" → camera opens, scans the voucher QR →
   same `VoucherVerifier.verify` path → save → settle.

Voucher JSON is ~400 bytes; comfortably fits in a single QR at error
correction level M with margin.

The voucher signing code, verifier code, and `NonceTracker` consumption are
*identical* across NFC and QR paths — only the transport differs.

## 10. Build / module structure

New gradle module: `android-wallet/` (replaces both `android-customer/` and
`android-merchant/` for the active build). Both old modules stay in the repo
but get dropped from `settings.gradle` of the main wallet build.

Package: `com.offlinepay.wallet`.
minSdk 26 (NFC HCE requirement), targetSdk 34. Same as today.

```
android-wallet/app/src/main/java/com/offlinepay/wallet/
  MainActivity.kt              navigation between screens
  HomeActivity.kt
  SendActivity.kt              amount entry + HCE arming
  ReceiveActivity.kt           reader mode + QR scan
  TopupActivity.kt             ports customer flow
  BackupRestoreActivity.kt     ports customer flow
  HistoryActivity.kt
  KeyVault.kt                  ported
  NonceTracker.kt              ported
  VoucherSigner.kt             ported
  VoucherVerifier.kt           ported, simplified (no merchant-registry path)
  VoucherStore.kt              ported, schema simplified per §7
  HceVoucherService.kt         extended to handle INS 0xC1
  ReaderModeLoop.kt            new — IsoDep send/receive
  PendingPayment.kt            singleton state for "next tap will sign this"
  SettlementClient.kt          rewritten — direct on-chain calls
  TopupClient.kt               ported
  KeyBackup.kt                 ported
  Qr.kt                        new — render + scan helpers
```

`apduservice.xml` AID: reuse the existing AID from
`android-customer/app/src/main/res/xml/apduservice.xml` so the receiver's
SELECT bytes don't change.

## 11. Configuration

Same constants as today, hoisted to a `Config.kt`:

```kotlin
object Config {
  const val CHAIN_ID = 31337L            // 80002 for Amoy
  const val VAULT_ADDRESS = "0x…"        // from contracts/deployments/<network>.json
  const val USDC_ADDRESS = "0x…"
  const val BACKEND_BASE = "http://10.0.2.2:4000"
  val MAX_SINGLE_USDC = BigInteger("2000000")  // matches contract default
  const val DEFAULT_TTL_SECONDS = 24 * 3600
}
```

For the demo, both phones run with the same `Config.CHAIN_ID` and
`VAULT_ADDRESS`. Mismatched configs surface as "wrong chain" rejection in
`VoucherVerifier`.

## 12. Threat model deltas

- **Sender phone stolen with locked USDC.** Same risk as today's customer
  app — mitigated by the existing backup/restore. Open: lock-screen / app
  PIN, deferred.
- **Receiver phone stolen between accept and settle.** Voucher JSON in
  `VoucherStore` plus the receiver's wallet key would let the thief settle
  to themselves — but that's the same wallet the legitimate owner controls,
  so the funds end up at the right address. Real risk is loss of MATIC and
  loss of access to the wallet itself; same as any non-custodial wallet.
  Backup story is the answer here too.
- **Replay across receivers.** Voucher's `merchant` field binds it to a
  single receiver address; on-chain `_settle` enforces `v.merchant ==
  claimer`. A relayed voucher cannot be settled by a different address.
- **Double-spend across two receivers.** Sender would need to sign two
  vouchers with the same nonce; `NonceTracker.next()` with sync `commit()`
  prevents this on the device side, and on-chain `lastNonce[payer]`
  enforces it as the safety net.
- **Hostile receiver setting `amount` themselves.** Receiver doesn't
  control amount in this flow — sender's HCE reads only the address from
  INS 0xC1. Amount comes from the Send screen state. Receiver-driven
  amount is a future feature with its own APDU INS.

## 13. Testing

- **Contract tests:** unchanged, must stay green (`npx hardhat test`).
- **Round-trip tests:** add a JVM test that exercises
  `VoucherSigner.signNext` → `VoucherVerifier.verify` for the new
  receiver-as-merchant path. This is the only new code path that touches
  the digest, and the digest is sacred (CLAUDE.md §1).
- **Manual two-phone test plan:** see §14.

## 14. Demo / pilot acceptance

Two phones, both Android, both with the unified APK, both holding ~0.1
MATIC for gas. Demo network: Polygon Amoy.

1. Phone A tops up (mock UPI) → 100 USDC issued, 100 USDC locked in vault.
2. Phone A: Send → enter 1.50 USDC → "Pay" → HCE armed.
3. Phone B: Receive → reader mode armed.
4. Tap. Phone A shows "✓ paid 1.50 USDC". Phone B shows "✓ received 1.50
   USDC, settling…".
5. Phone B settles on Amoy (one tx). Both phones, when reconnected, see
   the new on-chain balances.
6. Repeat twice more without internet on either phone. Confirm Phone B
   queues 3 vouchers and settles them in a single `settleBatch` once
   reconnected.
7. NFC failure path: turn off NFC on one phone. "Show QR instead" + "Scan
   voucher QR" path completes the same payment.

## 15. Reactivation paths (deferred features)

All cut features are re-introducible without modifying anything in the
MVP. None of these need to be designed now; logged for clarity.

- **Mesh replication:** import `MeshBroadcaster` + `HandshakeManager`,
  call `broadcast(v)` after a successful receive, gate `claimAndWait`
  before settle. Re-add the dropped DB columns via a Room migration.
- **Merchant POS mode:** flavor or runtime toggle to enable
  `MerchantRegistry`-aware settlement, ESP32 Bluetooth bridge, and
  `settleVoucherAsDevice`.
- **ESP32 reader:** firmware unchanged; merchant flavor adds
  `BluetoothBridge` back.
- **Gas-sponsor relay:** add `settleVoucherTo(v, sig, recipient)` to the
  contract, deploy, point a `RelayClient` at it, default-on for wallets
  with zero MATIC.
- **Receiver-driven amount:** new APDU INS (e.g. `0xC2`) carrying
  `(receiverAddr, requestedAmount)`. Sender confirms with a tap on Send
  screen instead of pre-entering amount. Coexists with `0xC1`.
- **iOS sender via Core NFC:** voucher and contract are unchanged. iOS
  acts as sender (HCE not available on iOS for arbitrary AIDs) — needs a
  different transport between iOS sender and Android receiver.
