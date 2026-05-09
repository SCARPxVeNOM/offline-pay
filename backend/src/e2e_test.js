// End-to-end test for the Option B custodial-fallback protocol.
//
// Spins up two ephemeral wallets, drives the exact same flow as the Android
// app would: backend init → user-signed approve+lock → offline-style voucher
// → backend init for receiver → receiver-signed settleBearerBatch.
//
// If this passes, the protocol works. UI is just chrome.
import "dotenv/config";
import { ethers } from "ethers";
import { config } from "./config.js";

const RPC = config.rpcUrl;
const provider = new ethers.JsonRpcProvider(RPC);
const VAULT = config.vault;
const USDC  = config.usdc;
const CHAIN_ID = config.chainId;

const VAULT_ABI = [
  "function lockedBalance(address) view returns (uint256)",
  "function lockFunds(uint256)",
  "function settleBearerBatch((address,address,uint256,uint256,uint256,bytes32)[],bytes[],address)",
  "function voucherDigest((address,address,uint256,uint256,uint256,bytes32)) view returns (bytes32)",
];
const USDC_ABI = [
  "function balanceOf(address) view returns (uint256)",
  "function approve(address,uint256)",
];
const vaultRO = new ethers.Contract(VAULT, VAULT_ABI, provider);
const usdcRO  = new ethers.Contract(USDC,  USDC_ABI,  provider);

const BACKEND = `http://localhost:${config.port}`;

function log(msg, ...args) { console.log(`[e2e] ${msg}`, ...args); }
function fmt(amt) { return (Number(amt) / 1e6).toFixed(2); }

async function backendInit(address, amountUsdc) {
  const r = await fetch(`${BACKEND}/api/wallet/init`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ address, amountUsdc: amountUsdc.toString() }),
  });
  const j = await r.json();
  if (!j.ok) throw new Error(`init: ${j.error}`);
  return j;
}

async function step1_topupSender(sender, amountUsdc) {
  log(`(1) topup sender ${sender.address}`);
  const t0 = Date.now();
  const init = await backendInit(sender.address, amountUsdc);
  log(`    backend init done in ${Date.now() - t0}ms, txs:`, init.txs);

  // Mirror approveAndLock — both txs with sequential nonces.
  const baseNonce = await provider.getTransactionCount(sender.address, "pending");
  const usdc  = new ethers.Contract(USDC, USDC_ABI, sender);
  const vault = new ethers.Contract(VAULT, VAULT_ABI, sender);
  const t1 = Date.now();
  // Explicit gasLimit so ethers skips eth_estimateGas — otherwise the gas
  // estimator simulates lockFunds against the latest block, where approve
  // hasn't mined yet, and reverts with InsufficientAllowance.
  const approveTx = await usdc.approve(VAULT, amountUsdc, {
    nonce: baseNonce, gasLimit: 100_000n,
  });
  const lockTx    = await vault.lockFunds(amountUsdc, {
    nonce: baseNonce + 1, gasLimit: 200_000n,
  });
  log(`    submitted approve=${approveTx.hash.slice(0, 10)} lock=${lockTx.hash.slice(0, 10)}`);
  const [aRcpt, lRcpt] = await Promise.all([approveTx.wait(), lockTx.wait()]);
  log(`    both mined in ${Date.now() - t1}ms approveStatus=${aRcpt.status} lockStatus=${lRcpt.status}`);
  if (aRcpt.status !== 1) throw new Error(`approve reverted: ${approveTx.hash}`);
  if (lRcpt.status !== 1) throw new Error(`lock reverted: ${lockTx.hash}`);

  const locked = await vaultRO.lockedBalance(sender.address);
  log(`    lockedBalance[sender] = ${fmt(locked)} USDC`);
  if (locked < amountUsdc) throw new Error(`lock mismatch: got ${locked}`);
  return lockTx.hash;
}

async function step2_signVoucher(sender, amountBaseUnits, voucherNonce) {
  // Match the Kotlin VoucherSigner exactly:
  //   keccak(abi.encode(payer, merchant=0, amount, expiry, nonce, voucherId, chainId, vault))
  //   then EIP-191 personal_sign over that digest.
  const payer = sender.address;
  const merchant = ethers.ZeroAddress;
  const expiry = BigInt(Math.floor(Date.now() / 1000) + 3600);
  const voucherId = ethers.id(`voucher-${Date.now()}-${Math.random()}`);
  const v = {
    payer, merchant,
    amount: BigInt(amountBaseUnits),
    expiry, nonce: BigInt(voucherNonce), voucherId,
  };
  const digest = ethers.AbiCoder.defaultAbiCoder().encode(
    ["address","address","uint256","uint256","uint256","bytes32","uint256","address"],
    [v.payer, v.merchant, v.amount, v.expiry, v.nonce, v.voucherId, CHAIN_ID, VAULT]
  );
  const hash = ethers.keccak256(digest);
  const signature = await sender.signMessage(ethers.getBytes(hash));
  log(`(2) signed voucher id=${v.voucherId.slice(0, 10)}… amount=${fmt(v.amount)}`);
  return { v, signature };
}

async function step3_receiverSettles(receiver, voucher, signature, recipient) {
  log(`(3) receiver init for gas`);
  await backendInit(receiver.address, 0n);

  log(`    receiver broadcasts settleBearerBatch from ${receiver.address}`);
  const vault = new ethers.Contract(VAULT, VAULT_ABI, receiver);
  const tuple = [voucher.payer, voucher.merchant, voucher.amount,
                 voucher.expiry, voucher.nonce, voucher.voucherId];
  const t0 = Date.now();
  const tx = await vault.settleBearerBatch([tuple], [signature], recipient);
  log(`    submitted ${tx.hash.slice(0, 10)}…`);
  const rcpt = await tx.wait();
  log(`    mined in ${Date.now() - t0}ms blockNumber=${rcpt.blockNumber}`);
  return rcpt.hash;
}

async function main() {
  log("config:", { rpc: RPC, vault: VAULT, usdc: USDC, chainId: CHAIN_ID });

  // Health check
  const h = await fetch(`${BACKEND}/api/health`).then(r => r.json());
  if (!h.ok) throw new Error("backend not healthy");
  log("backend ok at block", h.blockNumber);

  // Two fresh ephemeral wallets.
  const sender   = ethers.Wallet.createRandom().connect(provider);
  const receiver = ethers.Wallet.createRandom().connect(provider);
  log(`sender   = ${sender.address}`);
  log(`receiver = ${receiver.address}`);

  const amount = 1_000_000n;     // $1.00 locked
  const voucherAmt = 500_000n;   // $0.50 paid

  await step1_topupSender(sender, amount);

  // Sender's nonce starts at 1 (lastNonce[payer] init = 0).
  const { v, signature } = await step2_signVoucher(sender, voucherAmt, 1);

  const balBefore = await usdcRO.balanceOf(receiver.address);
  log(`receiver USDC balance before = ${fmt(balBefore)}`);

  const settleTx = await step3_receiverSettles(receiver, v, signature, receiver.address);

  const balAfter = await usdcRO.balanceOf(receiver.address);
  log(`receiver USDC balance after  = ${fmt(balAfter)}`);
  if (balAfter - balBefore !== voucherAmt)
    throw new Error(`receiver balance delta mismatch: ${balAfter - balBefore}`);

  const lockedAfter = await vaultRO.lockedBalance(sender.address);
  log(`sender locked after = ${fmt(lockedAfter)} USDC`);
  if (lockedAfter !== amount - voucherAmt)
    throw new Error(`sender locked mismatch: ${lockedAfter}`);

  log("====================================");
  log("✅ END-TO-END PROTOCOL TEST PASSED");
  log(`   topup → voucher → settle on Polygon Amoy`);
  log(`   settle tx: https://amoy.polygonscan.com/tx/${settleTx}`);
  log("====================================");
}

main().catch(e => {
  console.error("[e2e] FAILED:", e);
  process.exit(1);
});
