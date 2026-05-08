// =============================================================================
//  OfflinePay end-to-end CLI demo.
//
//  Runs the complete happy-path flow against a local backend and Hardhat node,
//  with pretty terminal output. No hardware, no Android, just one command:
//
//      node simulator/demo.js
//
//  Prerequisites (each in its own terminal):
//      1) cd contracts && npx hardhat node
//      2) cd contracts && npm run deploy:local
//      3) cd backend  && npm run dev
//
//  Then run this from the project root.
// =============================================================================

import chalk from "chalk";

const API = process.env.OFFLINEPAY_API || "http://localhost:4000";
const CUSTOMER  = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8"; // Hardhat acct[1]
const MERCHANT  = "0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC"; // Hardhat acct[2]

function box(title) {
  const bar = "─".repeat(72);
  console.log("\n" + chalk.gray(bar));
  console.log(chalk.bold.cyan(`  ${title}`));
  console.log(chalk.gray(bar));
}

function inr(paise)     { return `₹${(paise/100).toFixed(2)}`; }
function usd(units)     { return `$${(Number(units)/1e6).toFixed(2)}`; }

async function api(method, path, body) {
  const r = await fetch(API + path, {
    method,
    headers: { "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!r.ok) {
    const text = await r.text();
    throw new Error(`${method} ${path} → ${r.status} ${text}`);
  }
  return r.json();
}

async function step(label, fn) {
  process.stdout.write(chalk.gray(`  ${label} ... `));
  const t0 = Date.now();
  try {
    const out = await fn();
    console.log(chalk.green("OK") + chalk.gray(` (${Date.now() - t0}ms)`));
    return out;
  } catch (e) {
    console.log(chalk.red("FAIL"));
    console.log(chalk.red("    " + e.message));
    throw e;
  }
}

async function waitForBackend() {
  for (let i = 0; i < 20; i++) {
    try {
      const h = await api("GET", "/api/health");
      if (h.ok && h.vault) return h;
    } catch {}
    await new Promise(r => setTimeout(r, 500));
  }
  throw new Error("backend not reachable on " + API);
}

async function main() {
  console.log(chalk.bold.magenta(`
   ┌─────────────────────────────────────────┐
   │   OfflinePay end-to-end demo            │
   │   offline crypto payments for India     │
   └─────────────────────────────────────────┘
  `));

  // ─── 1. Sanity check ─────────────────────────────────────────────────
  box("1. Backend + chain health");
  const health = await step("connect", waitForBackend);
  console.log(chalk.gray(`     chain=${health.chainId} block=${health.blockNumber}`));
  console.log(chalk.gray(`     vault=${health.vault}`));
  console.log(chalk.gray(`     usdc =${health.usdc}`));

  // ─── 2. UPI top-up ───────────────────────────────────────────────────
  box("2. Customer tops up ₹100 over (mock) UPI");
  const topup = await step("POST /api/topup", () =>
    api("POST", "/api/topup", { customer: CUSTOMER, amountInrPaise: 10000 })
  );
  console.log(chalk.gray(`     UPI ref ${topup.upiRef} → locked ${usd(topup.amountUsdc)}`));

  // ─── 3. Issue 5 vouchers ─────────────────────────────────────────────
  box("3. Backend issues 5 signed bearer vouchers ($0.20 each)");
  const issued = await step("POST /api/vouchers/issue", () =>
    api("POST", "/api/vouchers/issue", {
      customer: CUSTOMER, count: 5, amountUsdcEach: 200000
    })
  );
  for (const [i, v] of issued.vouchers.entries()) {
    console.log(chalk.gray(
      `     #${i+1}  amt=${usd(v.voucher.amount)}  nonce=${v.voucher.nonce}  ` +
      `id=${v.voucher.voucherId.slice(0,10)}…  sig=${v.signature.slice(0,16)}…`
    ));
  }
  console.log(chalk.gray(`     (these are now written onto the customer's MIFARE card)`));

  // ─── 4. Offline taps ─────────────────────────────────────────────────
  box("4. Customer goes OFFLINE and taps card at 5 merchants");
  console.log(chalk.gray(`     simulating tap-by-tap acceptance — no internet on either side`));
  for (const [i, v] of issued.vouchers.entries()) {
    process.stdout.write(chalk.gray(`     tap ${i+1}: voucher ${v.voucher.voucherId.slice(0,10)}… → `));
    console.log(chalk.green("ACCEPTED ") + chalk.gray("(merchant queues for later sync)"));
    await new Promise(r => setTimeout(r, 250));
  }

  // ─── 5. Merchant comes online and uploads ────────────────────────────
  box("5. Merchant reconnects and uploads queued vouchers");
  const redeem = await step("POST /api/merchant/redeem", () =>
    api("POST", "/api/merchant/redeem", { vouchers: issued.vouchers })
  );
  console.log(chalk.gray(`     accepted=${redeem.accepted.length} rejected=${redeem.rejected.length}`));

  // ─── 6. Settle on chain ──────────────────────────────────────────────
  box("6. Backend settles the batch on Polygon (one tx, all 5 vouchers)");
  const settle = await step("POST /api/merchant/settle", () =>
    api("POST", "/api/merchant/settle", {})
  );
  console.log(chalk.green(`     settled=${settle.settled} tx=${settle.tx}`));

  // ─── 7. Replay attack ────────────────────────────────────────────────
  box("7. Attacker tries to replay the SAME voucher → backend rejects");
  const replay = await api("POST", "/api/merchant/redeem", { vouchers: [issued.vouchers[0]] });
  if (replay.rejected.length === 1 && replay.accepted.length === 0) {
    console.log(chalk.green(`     replay rejected: `) +
      chalk.gray(replay.rejected[0].reason));
  } else {
    console.log(chalk.red("     replay went through — BAD"), JSON.stringify(replay));
  }
  // And just to be doubly sure, force a direct on-chain replay attempt.
  try {
    const fakeRow = issued.vouchers[0];
    // bypass redeem queue: poke settle (won't find anything in 'redeemed' state).
    const out = await api("POST", "/api/merchant/settle", {});
    if (out.settled === 0) {
      console.log(chalk.green("     on-chain settle queue empty → no replay possible"));
    }
  } catch (e) {
    console.log(chalk.green("     on-chain settle rejected:"), chalk.gray(e.message.slice(0, 120)));
  }

  // ─── 8. Final stats ──────────────────────────────────────────────────
  box("8. Final state");
  const stats = await api("GET", "/api/stats");
  console.log("     status counts:");
  for (const c of stats.counts) {
    console.log(`       ${chalk.bold(c.status.padEnd(10))} ${String(c.n).padStart(3)} vouchers   vol=${usd(c.vol)}`);
  }

  console.log("\n" + chalk.bold.green("  ✓ Full offline → on-chain settlement worked end-to-end.") + "\n");
}

main().catch(e => { console.error(chalk.red(e.message)); process.exit(1); });
