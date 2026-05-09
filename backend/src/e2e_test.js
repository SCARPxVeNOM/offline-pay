// End-to-end test for the v3 recipient-bound bearer flow.
//
// Spins up THREE ephemeral wallets to prove the headline mesh property:
//   - sender + receiver are offline (we just don't broadcast from them)
//   - relay broadcasts settleBearerBatch
//   - funds land at the receiver's address (signed in the digest)
//
// If this passes, the protocol works.
import "dotenv/config";
import { ethers } from "ethers";
import { config } from "./config.js";

const RPC = config.rpcUrl;
const provider = new ethers.JsonRpcProvider(RPC);
const VAULT = config.vault;
const USDC  = config.usdc;
const CHAIN_ID = config.chainId;

const VOUCHER = "(address,address,address,uint256,uint256,uint256,bytes32)";
const VAULT_ABI = [
  "function lockedBalance(address) view returns (uint256)",
  "function lockFunds(uint256)",
  `function settleBearerBatch(${VOUCHER}[],bytes[])`,
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

  const baseNonce = await provider.getTransactionCount(sender.address, "pending");
  const usdc  = new ethers.Contract(USDC,  USDC_ABI,  sender);
  const vault = new ethers.Contract(VAULT, VAULT_ABI, sender);
  const t1 = Date.now();
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

  // Infura's read replicas lag the canonical chain by 1-2s after a write.
  // Retry briefly so the test doesn't false-positive on read staleness.
  let locked = 0n;
  for (let i = 0; i < 10; i++) {
    locked = await vaultRO.lockedBalance(sender.address);
    if (locked >= amountUsdc) break;
    await new Promise(r => setTimeout(r, 1000));
  }
  log(`    lockedBalance[sender] = ${fmt(locked)} USDC`);
  if (locked < amountUsdc) throw new Error(`lock mismatch: got ${locked}`);
  return lockTx.hash;
}

/// Signs a voucher with v3 digest:
///   (payer, merchant=0, recipient, amount, expiry, nonce, voucherId, chainId, vault)
async function step2_signVoucher(sender, recipient, amountBaseUnits, voucherNonce) {
  const payer = sender.address;
  const merchant = ethers.ZeroAddress;
  const expiry = BigInt(Math.floor(Date.now() / 1000) + 3600);
  const voucherId = ethers.id(`voucher-${Date.now()}-${Math.random()}`);
  const v = {
    payer, merchant, recipient,
    amount: BigInt(amountBaseUnits),
    expiry, nonce: BigInt(voucherNonce), voucherId,
  };
  const digestEnc = ethers.AbiCoder.defaultAbiCoder().encode(
    ["address","address","address","uint256","uint256","uint256","bytes32","uint256","address"],
    [v.payer, v.merchant, v.recipient, v.amount, v.expiry, v.nonce, v.voucherId, CHAIN_ID, VAULT]
  );
  const hash = ethers.keccak256(digestEnc);
  const signature = await sender.signMessage(ethers.getBytes(hash));
  log(`(2) signed voucher id=${v.voucherId.slice(0, 10)}… amount=${fmt(v.amount)} → recipient=${recipient.slice(0, 10)}…`);
  return { v, signature };
}

/// Relay broadcasts the settle. msg.sender is `relay`, but funds land at
/// the voucher's signed `recipient` field.
async function step3_relayBroadcasts(relay, voucher, signature) {
  log(`(3) relay broadcasts settleBearerBatch from ${relay.address}`);
  await backendInit(relay.address, 0n);  // fund relay with gas
  const vault = new ethers.Contract(VAULT, VAULT_ABI, relay);
  const tuple = [
    voucher.payer, voucher.merchant, voucher.recipient,
    voucher.amount, voucher.expiry, voucher.nonce, voucher.voucherId,
  ];
  const t0 = Date.now();
  const tx = await vault.settleBearerBatch([tuple], [signature]);
  log(`    submitted ${tx.hash.slice(0, 10)}…`);
  const rcpt = await tx.wait();
  log(`    mined in ${Date.now() - t0}ms blockNumber=${rcpt.blockNumber} status=${rcpt.status}`);
  if (rcpt.status !== 1) throw new Error(`settle reverted: ${tx.hash}`);
  return rcpt.hash;
}

async function main() {
  log("config:", { rpc: RPC, vault: VAULT, usdc: USDC, chainId: CHAIN_ID });

  const h = await fetch(`${BACKEND}/api/health`).then(r => r.json());
  if (!h.ok) throw new Error("backend not healthy");
  log("backend ok at block", h.blockNumber);

  // Three independent wallets — sender, receiver, relay.
  const sender   = ethers.Wallet.createRandom().connect(provider);
  const receiver = ethers.Wallet.createRandom().connect(provider);
  const relay    = ethers.Wallet.createRandom().connect(provider);
  log(`sender   = ${sender.address}`);
  log(`receiver = ${receiver.address}`);
  log(`relay    = ${relay.address} (broadcaster — never touches funds)`);

  const amount     = 1_000_000n;     // $1.00 locked
  const voucherAmt =   500_000n;     // $0.50 paid

  await step1_topupSender(sender, amount);

  // Sender signs a voucher specifying receiver as the recipient.
  const { v, signature } = await step2_signVoucher(sender, receiver.address, voucherAmt, 1);

  const recvBefore  = await usdcRO.balanceOf(receiver.address);
  const relayBefore = await usdcRO.balanceOf(relay.address);
  log(`receiver USDC before = ${fmt(recvBefore)}, relay USDC before = ${fmt(relayBefore)}`);

  const settleTx = await step3_relayBroadcasts(relay, v, signature);

  const recvAfter  = await usdcRO.balanceOf(receiver.address);
  const relayAfter = await usdcRO.balanceOf(relay.address);
  log(`receiver USDC after  = ${fmt(recvAfter)}, relay USDC after = ${fmt(relayAfter)}`);

  if (recvAfter - recvBefore !== voucherAmt)
    throw new Error(`receiver delta mismatch: ${recvAfter - recvBefore}`);
  if (relayAfter !== relayBefore)
    throw new Error(`relay should not receive funds: ${relayAfter}`);

  const lockedAfter = await vaultRO.lockedBalance(sender.address);
  log(`sender locked after  = ${fmt(lockedAfter)} USDC (was ${fmt(amount)})`);
  if (lockedAfter !== amount - voucherAmt)
    throw new Error(`sender locked mismatch: ${lockedAfter}`);

  log("====================================");
  log("✅ V3 RELAY E2E TEST PASSED");
  log(`   sender → vault → receiver, broadcast by RELAY (third wallet)`);
  log(`   relay never received funds — recipient binding works.`);
  log(`   settle tx: https://amoy.polygonscan.com/tx/${settleTx}`);
  log("====================================");
}

main().catch(e => {
  console.error("[e2e] FAILED:", e);
  process.exit(1);
});
