// Single-tap simulator. Useful while a merchant Android app is open and the
// dashboard is watching — fires one voucher through the redeem queue.
//
// Usage:
//   node simulator/tap.js [--amount 200000]

import chalk from "chalk";

const API = process.env.OFFLINEPAY_API || "http://localhost:4000";
const CUSTOMER = "0x70997970C51812dc3A010C7d01b50e0d17dc79C8";

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, cur, i, arr) => {
    if (cur.startsWith("--")) acc.push([cur.slice(2), arr[i+1]]); return acc;
  }, [])
);
const amount = Number(args.amount || 200000);

const issue = await fetch(API + "/api/vouchers/issue", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ customer: CUSTOMER, count: 1, amountUsdcEach: amount })
}).then(r => r.json());

if (!issue.ok) throw new Error("issue failed: " + JSON.stringify(issue));
const v = issue.vouchers[0];
console.log(chalk.cyan("issued ") + v.voucher.voucherId);

const redeem = await fetch(API + "/api/merchant/redeem", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ vouchers: [v] })
}).then(r => r.json());
console.log(chalk.cyan("redeemed:"), redeem);
