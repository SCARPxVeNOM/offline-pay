import express from "express";
import cors from "cors";
import morgan from "morgan";
import crypto from "node:crypto";
import rateLimit from "express-rate-limit";
import { ethers } from "ethers";
import { config } from "./config.js";
import { db } from "./db.js";
import { vault, usdc, signer, provider } from "./chain.js";
import {
  buildVoucher, signVoucher, voucherDigest, recoverVoucherSigner,
  voucherToCardJson, newVoucherId
} from "./voucher.js";
import { v4 as uuidv4 } from "uuid";

const app = express();
app.use(cors());
app.use(express.json({ limit: "1mb" }));
app.use(morgan("dev"));

// ─── RPC proxy ────────────────────────────────────────────────────────────
// Phones talk to the laptop over USB (`adb reverse tcp:4000 tcp:4000`)
// regardless of their own Wi-Fi/DNS state. This forwards JSON-RPC to the
// configured upstream so SettlementClient on the phone can use
// http://127.0.0.1:4000/rpc as its endpoint.
app.post("/rpc", express.text({ type: "*/*", limit: "1mb" }), async (req, res) => {
  try {
    const body = typeof req.body === "string" ? req.body : JSON.stringify(req.body || {});
    const upstream = await fetch(config.rpcUrl, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body,
    });
    res.status(upstream.status);
    res.set("content-type", upstream.headers.get("content-type") || "application/json");
    res.send(await upstream.text());
  } catch (e) {
    res.status(502).json({ error: e.message });
  }
});

// ─── Health ───────────────────────────────────────────────────────────────
app.get("/api/health", async (_req, res) => {
  let blockNumber = null;
  try { blockNumber = await provider.getBlockNumber(); } catch {}
  res.json({
    ok: true,
    chainId: config.chainId,
    vault: config.vault,
    usdc: config.usdc,
    backendSigner: signer.address,
    blockNumber
  });
});

// ─── Customer registration ────────────────────────────────────────────────
app.post("/api/customers/register", (req, res) => {
  const { address, upiId } = req.body || {};
  if (!ethers.isAddress(address)) return res.status(400).json({ error: "bad address" });
  db.prepare(
    "INSERT OR REPLACE INTO customers (address, upi_id, created_at) VALUES (?, ?, ?)"
  ).run(address.toLowerCase(), upiId || null, Date.now());
  res.json({ ok: true });
});

app.get("/api/customers/:address", async (req, res) => {
  const addr = req.params.address.toLowerCase();
  const row = db.prepare("SELECT * FROM customers WHERE address = ?").get(addr);
  let lockedBalance = "0", lastNonce = 0;
  try {
    if (vault) {
      lockedBalance = (await vault.lockedBalance(addr)).toString();
      lastNonce = Number(await vault.lastNonce(addr));
    }
  } catch (e) { /* chain not running */ }
  res.json({ ...row, lockedBalance, lastNonce });
});

// ─── UPI top-up (mock) → mint mUSDC and lock on the customer's behalf ─────
// In a real product this is an Onmeta/MoonPay style INR→USDC bridge.
// For the demo: backend mints mUSDC to itself, approves Vault, calls lockFunds,
// then issues a batch of bearer vouchers signed BY THE BACKEND for that customer.
// The customer's actual key is irrelevant in this custodial-demo path; for the
// non-custodial path the customer phone signs locally instead.
app.post("/api/topup", async (req, res) => {
  try {
    const { customer, amountInrPaise } = req.body || {};
    if (!ethers.isAddress(customer))
      return res.status(400).json({ error: "bad customer address" });
    if (!Number.isFinite(amountInrPaise) || amountInrPaise <= 0)
      return res.status(400).json({ error: "bad amount" });

    // Demo conversion: 1 INR = 1 cent USDC (₹100 ≈ $1.00).
    // Real one would call live FX + bridge.
    const amountUsdc = BigInt(Math.floor(amountInrPaise / 100)) * 10_000n; // paise/100 = INR; INR*0.01*1e6
    const upiRef = `UPI-${Date.now()}-${Math.floor(Math.random()*9999)}`;
    const id = uuidv4();

    db.prepare(
      "INSERT INTO topups VALUES (?, ?, ?, ?, ?, ?, ?)"
    ).run(id, customer.toLowerCase(), upiRef, amountInrPaise, amountUsdc.toString(), "pending", Date.now());

    if (vault && usdc) {
      // Mint mUSDC to backend, lock on Vault. The backend wallet is acting as
      // a custodial proxy "payer" in the demo path.
      try {
        await (await usdc.mint(signer.address, amountUsdc)).wait();
        await (await usdc.approve(config.vault, amountUsdc)).wait();
        await (await vault.lockFunds(amountUsdc)).wait();
        db.prepare("UPDATE topups SET status = 'locked' WHERE id = ?").run(id);
      } catch (e) {
        db.prepare("UPDATE topups SET status = 'failed' WHERE id = ?").run(id);
        return res.status(500).json({ error: "lock failed", detail: e.message });
      }
    }

    res.json({ ok: true, topupId: id, upiRef, amountUsdc: amountUsdc.toString() });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ─── Issue vouchers (custodial demo path) ────────────────────────────────
// POST /api/vouchers/issue { customer, count, amountUsdcEach }
//   returns N signed vouchers to be written onto the MIFARE card.
app.post("/api/vouchers/issue", async (req, res) => {
  try {
    const { customer, count = 5, amountUsdcEach } = req.body || {};
    if (!ethers.isAddress(customer))
      return res.status(400).json({ error: "bad customer" });
    const amt = BigInt(amountUsdcEach || 200_000); // default $0.20 per voucher
    if (count < 1 || count > 50)
      return res.status(400).json({ error: "count 1-50" });

    // Custodial demo: backend's wallet is the payer; the funds were locked under
    // the backend address by /api/topup. So we sign FROM the backend wallet.
    let nonce = 0;
    try {
      if (vault) nonce = Number(await vault.lastNonce(signer.address));
    } catch {}

    const issued = [];
    for (let i = 0; i < count; i++) {
      nonce += 1;
      const voucher = buildVoucher({
        payer: signer.address,         // custodial path
        merchant: ethers.ZeroAddress,  // bearer
        amountUsdc: amt,
        ttlSeconds: 24 * 3600,
        nonce
      });
      const signature = await signVoucher(signer, voucher);

      db.prepare(
        "INSERT INTO vouchers (voucher_id, payer, merchant, amount, expiry, nonce, signature, status, issued_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'issued', ?)"
      ).run(voucher.voucherId, voucher.payer.toLowerCase(),
            voucher.merchant.toLowerCase(), voucher.amount.toString(),
            Number(voucher.expiry), Number(voucher.nonce),
            signature, Date.now());

      issued.push({
        voucher: {
          payer: voucher.payer,
          merchant: voucher.merchant,
          amount: voucher.amount.toString(),
          expiry: Number(voucher.expiry),
          nonce: Number(voucher.nonce),
          voucherId: voucher.voucherId
        },
        signature,
        cardPayload: voucherToCardJson(voucher, signature)
      });
    }
    res.json({ ok: true, vouchers: issued });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ─── Wallet (Option A custodial) — atomic topup that mints, locks, pre-signs ────
// One-shot endpoint the unified wallet app calls. Returns N pre-signed bearer
// vouchers totaling the requested amount. Phone stores them locally and emits
// one or more on each NFC tap. Backend remains custodian until vouchers settle.
//
// Body: { address, amountUsdc, denomUsdc?, count? }
//   denomUsdc * count == amountUsdc; defaults give 5 equal pieces.
//
// Each voucher: payer = backend.address, merchant = 0x0 (bearer), nonce
// strictly increasing per-backend (mutex-serialized below). Replay safety
// is voucherId-only — see OfflineVault.settleBearer for why nonce is not
// enforced on the bearer settle path.
let chainOpQueue = Promise.resolve();
function withChainLock(fn) {
  const next = chainOpQueue.then(fn, fn);
  // Don't propagate failures into the queue chain.
  chainOpQueue = next.catch(() => null);
  return next;
}

app.post("/api/wallet/topup", async (req, res) => {
  if (!vault || !usdc) return res.status(503).json({ error: "chain not configured" });
  try {
    const { address, amountUsdc, denomUsdc, count } = req.body || {};
    if (!ethers.isAddress(address)) return res.status(400).json({ error: "bad address" });
    const total = BigInt(amountUsdc || 1_000_000); // default $1
    if (total <= 0n || total > 100_000_000n) return res.status(400).json({ error: "amount out of range" });

    const n = Number.isInteger(count) && count > 0 && count <= 50 ? count : 5;
    const denom = denomUsdc ? BigInt(denomUsdc) : (total / BigInt(n));
    if (denom * BigInt(n) !== total) return res.status(400).json({ error: "denom*count must equal amount" });

    const result = await withChainLock(async () => {
      // Mint mUSDC to backend, lock in vault. Approve only the marginal amount.
      await (await usdc.mint(signer.address, total)).wait();
      await (await usdc.approve(config.vault, total)).wait();
      await (await vault.lockFunds(total)).wait();

      // Allocate contiguous nonces above whatever the chain has settled so far.
      // This is a strictly-monotonic upper bound; bearer settle ignores nonce
      // anyway, so we just need uniqueness across in-flight vouchers.
      let nonce = Number(await vault.lastNonce(signer.address));
      const issued = [];
      for (let i = 0; i < n; i++) {
        nonce += 1;
        const voucher = buildVoucher({
          payer: signer.address,
          merchant: ethers.ZeroAddress,
          amountUsdc: denom,
          ttlSeconds: 24 * 3600,
          nonce
        });
        const signature = await signVoucher(signer, voucher);

        db.prepare(
          "INSERT INTO vouchers (voucher_id, payer, merchant, amount, expiry, nonce, signature, status, issued_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'issued', ?)"
        ).run(voucher.voucherId, voucher.payer.toLowerCase(),
              voucher.merchant.toLowerCase(), voucher.amount.toString(),
              Number(voucher.expiry), Number(voucher.nonce),
              signature, Date.now());

        issued.push({
          voucher: {
            payer: voucher.payer, merchant: voucher.merchant,
            amount: voucher.amount.toString(),
            expiry: Number(voucher.expiry), nonce: Number(voucher.nonce),
            voucherId: voucher.voucherId
          },
          signature,
          cardPayload: voucherToCardJson(voucher, signature)
        });
      }
      return issued;
    });

    res.json({ ok: true, owner: address.toLowerCase(),
               amountUsdc: total.toString(), vouchers: result });
  } catch (e) {
    console.error("/api/wallet/topup", e);
    res.status(500).json({ error: e.message });
  }
});

// ─── Option B: prep a wallet for self-custody ─────────────────────────────
// Sender (or first-time receiver) calls /api/wallet/init { address, amountUsdc }
// once when they have internet. Backend:
//   - gas-funds the wallet with 0.05 MATIC if balance < 0.02 MATIC
//   - mints `amountUsdc` of MockUSDC into the wallet (default $5)
// User wallet then signs its own approve + lockFunds locally to put USDC
// in the vault under its own payer slot. Tap-time signing happens with
// the user's key. Receiver settles on-chain itself (also from its own
// wallet) so the on-chain story is sender-wallet → receiver-wallet
// directly — fully visible in the explorer's tx history of either side.
app.post("/api/wallet/init", async (req, res) => {
  if (!usdc) return res.status(503).json({ error: "chain not configured" });
  try {
    const { address, amountUsdc } = req.body || {};
    if (!ethers.isAddress(address)) return res.status(400).json({ error: "bad address" });
    // 0 amount = gas-only top-up (used by the receiver before its first
    // on-chain settle). Otherwise mints requested mUSDC into the wallet.
    const amt = amountUsdc != null ? BigInt(amountUsdc) : 5_000_000n;
    if (amt < 0n || amt > 100_000_000n) return res.status(400).json({ error: "amount out of range" });

    const result = await withChainLock(async () => {
      const txs = {};
      // Amoy gas can spike; one settleBearerBatch costs ~0.06 MATIC, and a
      // topup runs three txs (~0.18). Keep a high floor + generous top-up so
      // a single mid-flow gas spike doesn't cause "insufficient funds".
      const gasFloor = ethers.parseEther("0.10");
      const gasTopup = ethers.parseEther("0.20");
      const balance  = await provider.getBalance(address);
      if (balance < gasFloor) {
        const need = gasTopup - balance;
        const t = await signer.sendTransaction({ to: address, value: need });
        txs.gas = (await t.wait()).hash;
      }
      if (amt > 0n) {
        const t2 = await usdc.mint(address, amt);
        txs.mint = (await t2.wait()).hash;
      }
      return txs;
    });

    res.json({ ok: true, address: address.toLowerCase(),
               amountUsdc: amt.toString(), txs: result });
  } catch (e) {
    console.error("/api/wallet/init", e);
    res.status(500).json({ error: e.message });
  }
});

// ─── Receiver pushes received vouchers; backend settles on-chain to recipient ───
// Body: { recipient, vouchers: [{ voucher, signature }] }
// Response: { ok, settled: N, tx: '0x…', rejected: [{voucherId, reason}] }
app.post("/api/wallet/redeem", async (req, res) => {
  if (!vault) return res.status(503).json({ error: "chain not configured" });
  try {
    const { recipient, vouchers } = req.body || {};
    if (!ethers.isAddress(recipient)) return res.status(400).json({ error: "bad recipient" });
    if (!Array.isArray(vouchers) || vouchers.length === 0)
      return res.status(400).json({ error: "vouchers[] required" });

    // Pre-validate signatures + dedupe before paying gas.
    const ok = [], rejected = [];
    for (const item of vouchers) {
      const v = item.voucher;
      try {
        if (v.merchant && v.merchant.toLowerCase() !== ethers.ZeroAddress.toLowerCase())
          throw new Error("not bearer");
        const recovered = recoverVoucherSigner(v, item.signature);
        if (recovered.toLowerCase() !== v.payer.toLowerCase()) throw new Error("bad sig");
        const existing = db.prepare("SELECT status FROM vouchers WHERE voucher_id=?").get(v.voucherId);
        if (existing && existing.status === "settled") throw new Error("already settled");
        ok.push(item);
      } catch (e) {
        rejected.push({ voucherId: v.voucherId, reason: e.message });
      }
    }
    if (!ok.length) return res.json({ ok: true, settled: 0, rejected });

    const result = await withChainLock(async () => {
      const vs = ok.map(it => [
        it.voucher.payer, it.voucher.merchant,
        BigInt(it.voucher.amount), BigInt(it.voucher.expiry),
        BigInt(it.voucher.nonce), it.voucher.voucherId
      ]);
      const sigs = ok.map(it => it.signature);
      const tx = await vault.settleBearerBatch(vs, sigs, recipient);
      const rcpt = await tx.wait();
      for (const it of ok) {
        db.prepare(
          "UPDATE vouchers SET status='settled', settled_tx=?, redeemed_at=? WHERE voucher_id=?"
        ).run(rcpt.hash, Date.now(), it.voucher.voucherId);
      }
      return rcpt.hash;
    });

    res.json({ ok: true, settled: ok.length, tx: result, rejected });
  } catch (e) {
    console.error("/api/wallet/redeem", e);
    res.status(500).json({ error: e.message });
  }
});

// ─── Merchant collects vouchers from offline taps and sends them here later ──
app.post("/api/merchant/redeem", (req, res) => {
  const { vouchers } = req.body || {};
  if (!Array.isArray(vouchers)) return res.status(400).json({ error: "vouchers[]" });
  const accepted = [], rejected = [];
  for (const item of vouchers) {
    const v = item.voucher;
    try {
      const recovered = recoverVoucherSigner(v, item.signature);
      if (recovered.toLowerCase() !== v.payer.toLowerCase()) throw new Error("bad sig");
      const existing = db.prepare("SELECT status FROM vouchers WHERE voucher_id=?").get(v.voucherId);
      if (existing && existing.status === "settled") throw new Error("already settled");
      if (existing && existing.status === "redeemed") throw new Error("already redeemed");
      const upd = db.prepare(
        "UPDATE vouchers SET status='redeemed', redeemed_at=? WHERE voucher_id=? AND status='issued'"
      ).run(Date.now(), v.voucherId);
      if (upd.changes === 0) throw new Error("voucher unknown");
      accepted.push(v.voucherId);
    } catch (e) {
      rejected.push({ voucherId: v.voucherId, reason: e.message });
    }
  }
  res.json({ accepted, rejected });
});

// ─── Settle redeemed vouchers on-chain ───────────────────────────────────
app.post("/api/merchant/settle", async (req, res) => {
  if (!vault) return res.status(503).json({ error: "chain not configured" });
  try {
    const { merchantAddress } = req.body || {};
    const claimer = merchantAddress || signer.address;
    const rows = db.prepare(
      "SELECT * FROM vouchers WHERE status='redeemed' LIMIT 50"
    ).all();
    if (!rows.length) return res.json({ settled: 0 });

    // Tuples are passed positionally because the ABI declares them with
    // unnamed components. Order MUST match Voucher struct in OfflineVault.sol:
    // (payer, merchant, amount, expiry, nonce, voucherId).
    const vs = rows.map(r => [
      r.payer,
      r.merchant,
      BigInt(r.amount),
      BigInt(r.expiry),
      BigInt(r.nonce),
      r.voucher_id
    ]);
    const sigs = rows.map(r => r.signature);

    // For demo simplicity backend itself claims (acts as merchant relay).
    const tx = await vault.settleBatch(vs, sigs);
    const rcpt = await tx.wait();
    for (const r of rows) {
      db.prepare(
        "UPDATE vouchers SET status='settled', settled_tx=? WHERE voucher_id=?"
      ).run(rcpt.hash, r.voucher_id);
    }
    res.json({ settled: rows.length, tx: rcpt.hash });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ─── Pepper for keybackup (anti-brute-force on blob leaks) ───────────────
// HMAC(serverSecret, userId) → per-user pepper. A blob dump alone is
// useless without this value, and computing it requires the server secret.
// In production this should be rate-limited per IP/userId and audited.
function pepperFor(userId) {
  return crypto.createHmac("sha256", config.keybackupPepperSecret)
               .update(String(userId)).digest("base64");
}

// 5 attempts per 15-min window per (IP, userId). Without this, an attacker
// who has dumped the keybackups table could enumerate userIds and harvest
// peppers to mount offline brute-force on each blob.
const pepperLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 5,
  standardHeaders: true,
  legacyHeaders: false,
  keyGenerator: (req) => `${req.ip}:${req.params.userId}`,
  handler: (_req, res) => res.status(429).json({ error: "too many attempts" }),
});

// ─── Faucet — mint MockUSDC directly to a user wallet (P2P demo path) ────
// Real USDC has no public mint; in production replace with an actual on-ramp.
const faucetLimiter = rateLimit({
  windowMs: 15 * 60 * 1000, max: 10,
  standardHeaders: true, legacyHeaders: false,
  handler: (_req, res) => res.status(429).json({ error: "rate limited" }),
});

app.post("/api/faucet", faucetLimiter, async (req, res) => {
  if (!usdc) return res.status(503).json({ error: "chain not configured" });
  try {
    const { address, amountUsdc } = req.body || {};
    if (!ethers.isAddress(address)) return res.status(400).json({ error: "bad address" });
    const amt = BigInt(amountUsdc || 5_000_000); // default $5
    if (amt <= 0n || amt > 100_000_000n) return res.status(400).json({ error: "amount out of range" });

    // First-time funding: top up gas if balance is below the demo threshold.
    // 0.05 MATIC covers ~50 settle/lock txs at typical Amoy gas prices.
    const gasFloor = ethers.parseEther("0.02");
    const gasTopup = ethers.parseEther("0.05");
    const balance  = await provider.getBalance(address);
    if (balance < gasFloor) {
      const gasTx = await signer.sendTransaction({ to: address, value: gasTopup });
      await gasTx.wait();
    }

    const tx = await usdc.mint(address, amt);
    const rcpt = await tx.wait();
    res.json({ ok: true, tx: rcpt.hash, amountUsdc: amt.toString() });
  } catch (e) { res.status(500).json({ error: e.message }); }
});

app.get("/api/keybackup/pepper/:userId", pepperLimiter, (req, res) => {
  if (!req.params.userId || req.params.userId.length > 256) {
    return res.status(400).json({ error: "bad userId" });
  }
  res.json({ userId: req.params.userId, pepperB64: pepperFor(req.params.userId) });
});

// ─── Encrypted key backup (customer recovery) ────────────────────────────
// The backend stores OPAQUE ciphertext keyed by userId. The plaintext private
// key never leaves the customer's device — only AES-GCM ciphertext under a
// PBKDF2 key derived from the user's passphrase. So a backend compromise
// leaks neither funds nor identity.
app.post("/api/keybackup", (req, res) => {
  const b = req.body || {};
  const need = ["userId", "address", "saltB64", "ivB64", "ciphertextB64", "iterations"];
  for (const k of need) if (b[k] == null) return res.status(400).json({ error: `missing ${k}` });
  if (!ethers.isAddress(b.address)) return res.status(400).json({ error: "bad address" });
  if (!Number.isInteger(b.iterations) || b.iterations < 100_000) {
    return res.status(400).json({ error: "iterations too low" });
  }
  // Cap blob size to prevent abuse.
  if (b.ciphertextB64.length > 4096 || b.saltB64.length > 64 || b.ivB64.length > 64) {
    return res.status(400).json({ error: "blob too large" });
  }
  db.prepare(
    "INSERT OR REPLACE INTO keybackups (user_id, address, salt_b64, iv_b64, ciphertext_b64, iterations, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
  ).run(b.userId, b.address.toLowerCase(), b.saltB64, b.ivB64, b.ciphertextB64, b.iterations, Date.now());
  res.json({ ok: true });
});

app.get("/api/keybackup/:userId", (req, res) => {
  const row = db.prepare(
    "SELECT user_id as userId, address, salt_b64 as saltB64, iv_b64 as ivB64, ciphertext_b64 as ciphertextB64, iterations FROM keybackups WHERE user_id = ?"
  ).get(req.params.userId);
  if (!row) return res.status(404).json({ error: "not found" });
  res.json(row);
});

// ─── Read-only stats for the judge dashboard ─────────────────────────────
app.get("/api/stats", (_req, res) => {
  const counts = db.prepare(
    "SELECT status, COUNT(*) as n, COALESCE(SUM(CAST(amount AS INTEGER)), 0) as vol FROM vouchers GROUP BY status"
  ).all();
  const recent = db.prepare(
    "SELECT voucher_id, payer, merchant, amount, status, issued_at, redeemed_at, settled_tx FROM vouchers ORDER BY issued_at DESC LIMIT 25"
  ).all();
  res.json({ counts, recent });
});

app.get("/api/vouchers", (_req, res) => {
  const rows = db.prepare(
    "SELECT * FROM vouchers ORDER BY issued_at DESC LIMIT 100"
  ).all();
  res.json(rows);
});

app.get("/api/topups", (_req, res) => {
  res.json(db.prepare("SELECT * FROM topups ORDER BY created_at DESC LIMIT 50").all());
});

app.listen(config.port, () => {
  console.log(`OfflinePay backend listening on http://localhost:${config.port}`);
  console.log(`  chainId : ${config.chainId}`);
  console.log(`  vault   : ${config.vault || "(not configured)"}`);
  console.log(`  signer  : ${signer.address}`);
});
