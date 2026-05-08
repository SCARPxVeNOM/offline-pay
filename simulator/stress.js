// Stress / throughput demo. Issues N vouchers and settles in batches of 50.
// Useful for proving the system's gas cost per voucher in the pitch.
//
// Usage: node simulator/stress.js [count]

import chalk from "chalk";

const API   = process.env.OFFLINEPAY_API || "http://localhost:4000";
const COUNT = Number(process.argv[2] || 25);
const CUSTOMER = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";

console.log(chalk.bold.cyan(`stress test: ${COUNT} offline taps → batched settle\n`));

async function api(method, path, body) {
  return fetch(API + path, {
    method,
    headers: { "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined,
  }).then(r => r.json());
}

// First top up enough USDC.
const cap = Math.min(5_000_000, COUNT * 200_000); // contract maxLockedBalance = $5
await api("POST", "/api/topup", { customer: CUSTOMER, amountInrPaise: cap / 10000 * 100 });

const t0 = Date.now();
const issued = await api("POST", "/api/vouchers/issue", {
  customer: CUSTOMER, count: COUNT, amountUsdcEach: 200_000
});
console.log(chalk.gray(`issued ${issued.vouchers.length} in ${Date.now()-t0}ms`));

await api("POST", "/api/merchant/redeem", { vouchers: issued.vouchers });
const tSettle = Date.now();
const out = await api("POST", "/api/merchant/settle", {});
console.log(chalk.gray(`settle batch ${out.settled} in ${Date.now()-tSettle}ms tx=${out.tx}`));
console.log(chalk.green(`\n  total: ${Date.now()-t0}ms for ${COUNT} vouchers`));
