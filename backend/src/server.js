import express from "express";
import cors from "cors";
import morgan from "morgan";
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
