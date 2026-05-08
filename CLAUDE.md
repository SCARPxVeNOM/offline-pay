# CLAUDE.md — repo guide for AI assistants

You are picking up an in-flight project. Read this once before you read code.
README.md has the marketing summary; this file has the *opinions* you need to
work effectively without re-deriving them from grep.

## What this is

OfflinePay = USDC payments where both customer **and** merchant can be fully
offline at tap time. Customer signs vouchers ahead of time; merchant verifies
locally; settlement on Polygon happens when either side reconnects.

There are three surfaces: Solidity contracts, two Android apps (customer +
merchant), an ESP32 reader, and a Node/Express + SQLite backend that proxies
on-chain calls and issues custodial-path vouchers.

## Layout

```
contracts/        Hardhat workspace
  contracts/      OfflineVault.sol, MerchantRegistry.sol, MockUSDC.sol
  test/           Mocha+Chai tests — ALL must pass before commit
  scripts/        deploy.js writes deployments/<network>.json
  deployments/    Auto-generated. Backend reads the latest file via mtime.

backend/          Node 18+, ESM. `npm run dev` (watch) or `npm start`.
  src/server.js   All HTTP routes
  src/chain.js    ethers v6 vault/USDC bindings
  src/voucher.js  buildVoucher / signVoucher / digest — MUST stay byte-identical
                  to OfflineVault.voucherDigest and to the Android verifiers.
  src/db.js       better-sqlite3 schema (DDL inline). Bumping a column? Bump
                  the migration here, don't add a v2 file.
  data/           Runtime sqlite + .pepper file. gitignored.

android-merchant/ Kotlin, Gradle KTS. minSdk 26, targetSdk 34.
  app/src/main/java/com/offlinepay/merchant/
    MainActivity.kt        UI wiring + lifecycle
    BluetoothBridge.kt     ESP32 SPP frames -> Voucher Flow
    VoucherVerifier.kt     ECDSA recover + EIP-191; matches voucher.js exactly
    VoucherStore.kt        Room DB v2 (replicaCount, replicaPeers)
    MeshBroadcaster.kt     Nearby Connections P2P_CLUSTER + claim/ack
    HandshakeManager.kt    Sybil-resistant peer auth (challenge-response)
    MerchantKeyVault.kt    Per-device EVM key
    SettlementClient.kt    HTTP client to backend /api/merchant/settle

android-customer/ Kotlin. NFC HCE service.
  app/src/main/java/com/offlinepay/customer/
    KeyVault.kt            Local secp256k1 key (SharedPreferences — replace
                           with Android Keystore for production)
    VoucherSigner.kt       signNext() is the prod path; sign(nonce=…) is
                           test/legacy.
    NonceTracker.kt        commit()-backed counter. RECOVERY_SKIP=1000.
    KeyBackup.kt           PBKDF2(passphrase + server-pepper) + AES-GCM
    BackupRestoreActivity  UI for backup + restore
    HceVoucherService.kt   APDU handler — emits one voucher per tap
    NextVoucherProvider    In-memory FIFO of card payloads (also under HCE)

firmware/reader/  Arduino/ESP32 sketch
  offline_pay_reader.ino   RC522 + BT SPP + decision protocol
  wallet.h / wallet.cpp    secp256k1 keygen via mbedtls, Keccak-256 (NOT
                           NIST SHA3) for EVM address derivation, EIP-191
                           signEthMessage. Persisted in NVS Preferences.

dashboard/        Next.js judge dashboard (read-only stats over backend API)
simulator/        Local stress test scripts
```

## Architectural decisions you must respect

### 1. Voucher digest is sacred

The keccak preimage in `OfflineVault.voucherDigest`, `voucher.js
voucherDigest`, `VoucherVerifier.voucherDigest`, and `VoucherSigner.digest`
**must** stay byte-identical: `(payer, merchant, amount, expiry, nonce,
voucherId, chainId, vault)` ABI-encoded then keccak'd. If you add a field,
add it in **all five places** in the same order, and update the on-chain
struct + tests. The contract test file's `buildDigest()` is the canonical
reference — change it last and let tests guide the rollout.

### 2. Two settlement paths — don't merge them

- **Bearer / fixed-merchant** — `OfflineVault.settleVoucher(...)`, msg.sender
  receives the funds. Used by the backend's custodial proxy path.
- **Device-keyed** — `OfflineVault.settleVoucherAsDevice(...)`, msg.sender
  is a device key, vault resolves it via `MerchantRegistry.resolveDevice`,
  funds always go to the merchant's primary wallet. Used when phones/ESP32s
  hold their own keys.

Both go through `_settle()` for double-spend / nonce / signature checks.
Don't unify the entrypoints — the difference is *who pays out*, and
collapsing it would silently change the threat model.

### 3. Nonce monotonicity is per-payer, NOT per-merchant

`OfflineVault.lastNonce[payer]` is monotonic over the customer's wallet.
The customer phone owns the only authoritative counter (`NonceTracker`).
On recovery we apply `RECOVERY_SKIP = 1000` because vouchers signed at
nonces ≤ N may still be in flight on merchant SQLite or mesh peers — the
new phone resuming at N+1 would race them and one side reverts.

`NonceTracker.next()` uses **synchronous `commit()`**, not `apply()`.
Don't change that. apply() is async; a crash between return and disk
write reuses a nonce silently.

### 4. Mesh has three independent guarantees — don't conflate them

- **Replication** (`MeshBroadcaster.broadcast`): durability against device
  loss. Voucher gets gossiped; peers verify signature before storing as
  `replica` rows.
- **Sybil-resistance** (`HandshakeManager`): only verified peer addresses
  contribute to `replicaCount`. `from` field on a wire message is *never*
  trusted — only `verifiedAddress(endpointId)` after challenge-response.
- **Race avoidance** (`claimAndWait`): if two backups try to settle the
  same voucher, the loser observes a `claim` message and backs off
  `CLAIM_BACKOFF_MS`. Both backing off is the right answer — the contract's
  `usedVouchers` mapping is the safety net regardless.

If you're tempted to "simplify" by skipping the handshake when both peers
are in the registry: don't. The registry membership is *the thing the
handshake is proving* — without it, anyone can spoof a registered address.

### 5. Backend never sees plaintext keys

`KeyBackup` encrypts `{privKeyHex, lastNonce}` as a JSON inner payload with
PBKDF2(passphrase + ":" + base64(serverPepper), salt, 200_000). The server
HMACs `userId` with `KEYBACKUP_PEPPER_SECRET` to produce the per-user
pepper. Server-DB compromise without the pepper secret is not enough to
brute-force any blob. The pepper endpoint is rate-limited to 5 requests
per 15-min per (IP, userId).

Backwards compatibility: `restore()` falls back to "ciphertext is the raw
32-byte private key" if JSON decode fails. Keep that path until you're sure
no pre-fix backups remain in the wild.

### 6. ESP32 hashes Keccak-256, NOT NIST SHA3-256

mbedtls's `sha3` module emits NIST SHA3 (different padding). Ethereum uses
the original Keccak. `firmware/reader/wallet.cpp` ships a tiny standalone
Keccak implementation — **do not** replace it with `mbedtls_sha3_*`. The
function is named `keccak256` for a reason.

The signed `v` byte is hardcoded to 27 in `signEthMessage`. The on-chain
verifier (and `VoucherVerifier.kt`) recovers by trying {27, 28}. Production
should compute canonical recovery id via `mbedtls_ecdsa_sign_ext` once
that's exposed by the Arduino-ESP32 framework.

### 7. NVS on ESP32 is plaintext by default

The wallet private key sits in NVS under namespace `opwallet`, key `priv`.
Production must enable flash encryption (`idf.py menuconfig` → Security
features) so the eFuse-derived key encrypts the blob. Until then, any
attacker with the physical device can extract the key. This is fine for
the demo because `MerchantRegistry.revokeDevice` neutralizes a stolen
key — but document any change to that assumption.

## How to run the demo

```powershell
# 1. Contracts — local Hardhat node + deploy
cd contracts
npm install
npx hardhat node                       # leaves a node running on :8545
# in another terminal:
npm run deploy:local                   # writes deployments/localhost.json

# 2. Backend
cd ..\backend
npm install
npm run dev                            # http://localhost:4000

# 3. Customer / merchant apps — Android Studio, run on emulator or paired device
# Emulator → host loopback is 10.0.2.2 (already wired in MainActivity).

# 4. Smoke test
curl http://localhost:4000/api/health
```

`start-demo.ps1` (Windows) and `start-demo.sh` (Unix) wire the first three
steps together. Useful when you've blown away `data/` and want a clean run.

## Tests

```powershell
cd contracts && npx hardhat test       # 19 tests, all must pass
```

There are no JVM-side unit tests yet for the Android modules — verifier
correctness is exercised by the contract tests' digest reference. If you
add Kotlin tests, target `VoucherSigner` ↔ `VoucherVerifier` round-trip
first.

## Conventions in this repo

- **Code style**: terse. Comments explain *why* (especially the security
  trade-offs). Don't add docstrings that just restate the function name.
- **No backwards-compat shims** unless you've checked there's data in the
  wild that needs them. The `KeyBackup` legacy raw-key fallback is the one
  intentional exception.
- **Programmatic Android UI**: the Activities build views in code rather
  than XML. Stick with that style for the demo apps; it keeps each screen
  in one file.
- **Voucher amounts**: USDC base units (6 decimals) everywhere on the wire
  and in storage. Display formatting (`/1e6`) only at the UI edge.
- **Logging**: Android uses `android.util.Log` with class-name TAG. Server
  uses `morgan` for HTTP and `console.log` for app-level. No structured
  logger yet — don't add one without team agreement.
- **Single source of truth for nonces**: only `NonceTracker.next()`. If
  you find another `++` on a nonce field, it's a bug.

## Pre-pilot follow-ups (open work)

These are flagged as known-incomplete; pick one if you have spare time.

1. **Deterministic claim tie-break**. Current `claimAndWait` is conservative
   — both peers back off on a true tie. Track claimant address per voucher
   and let the lowest address proceed.
2. **Pepper endpoint user-token auth**. Rate limiting buys time. A short-
   lived OTP-bound token per user would close the enumeration entirely.
3. **MerchantRegistry RPC poller**. The Sybil gate's allowlist on
   `MainActivity` is currently empty (fail-closed). Production needs a
   periodic on-chain `MerchantRegistered` / `DeviceAuthorized` /
   `DeviceRevoked` event poll to populate it.
4. **Android Keystore migration**. Both `KeyVault` and `MerchantKeyVault`
   persist keys in plaintext SharedPreferences for demo simplicity. Wrap
   with Keystore (StrongBox where available) before any pilot money moves.
5. **ESP32 canonical `v` byte**. See §6 above.
6. **Backup/restore for the merchant phone**. Today only the customer side
   has KeyBackup. A merchant losing their phone before settle still loses
   pending vouchers if no mesh peer was nearby.
7. **Voucher TTL on mesh replicas**. `replica` rows accumulate forever in
   `VoucherStore`. Add a sweep that drops expired or settled-elsewhere
   replicas after `defaultVoucherTTL`.

## Things that look like bugs but aren't

- `MeshBroadcaster.isRegisteredDevice` field is unused inside the class.
  The handshake holds its own copy. Kept on the broadcaster for callers
  that may want it later — don't remove until that's wired.
- `MerchantRegistry.revokeDevice` clears `_deviceMerchant[device]` so a
  revoked device gets `(0, 0)` from `resolveDevice`. The `_devices` mapping
  is also flipped to false — both checks intentionally redundant.
- `OfflineVault._settle` checks `v.merchant == claimer`. With device-keyed
  settlement, `claimer` is the *primary* wallet (resolved from the device
  in `_resolvePrimary`), so a fixed-merchant voucher binds to the primary,
  not the device. This is intentional.
- `start-demo.ps1` may show a console warning about `VAULT_ADDRESS` on
  first run — that's the backend reading the deployment file before it
  exists. Re-running after `deploy:local` clears it.

## When in doubt

Read the contract tests (`contracts/test/`). They're the executable spec.
If a behavior isn't tested there, it's not load-bearing.
