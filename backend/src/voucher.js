import { ethers } from "ethers";
import { v4 as uuidv4 } from "uuid";
import { config } from "./config.js";

/// Build the canonical OfflineVault voucher digest.
/// MUST match OfflineVault.voucherDigest exactly:
///   (payer, merchant, recipient, amount, expiry, nonce, voucherId, chainId, vault)
/// `recipient` is signed so relay broadcasters cannot redirect funds.
export function voucherDigest(v) {
  const encoded = ethers.AbiCoder.defaultAbiCoder().encode(
    ["address","address","address","uint256","uint256","uint256","bytes32","uint256","address"],
    [v.payer, v.merchant, v.recipient, v.amount, v.expiry, v.nonce, v.voucherId, config.chainId, config.vault]
  );
  return ethers.keccak256(encoded);
}

/// Sign a voucher with the given ethers Wallet (EIP-191 personal_sign over the digest).
export async function signVoucher(wallet, voucher) {
  const digest = voucherDigest(voucher);
  const sig = await wallet.signMessage(ethers.getBytes(digest));
  return sig;
}

/// Recover the signer address from a signed voucher — used to verify before
/// storing in DB or before forwarding to the merchant.
export function recoverVoucherSigner(voucher, signature) {
  const digest = voucherDigest(voucher);
  return ethers.verifyMessage(ethers.getBytes(digest), signature);
}

/// Generate a fresh voucherId as bytes32.
export function newVoucherId() {
  return ethers.id(uuidv4());
}

/// Build a voucher object (without signature). amountUsdc is in base units (6 decimals).
/// `recipient` is required for bearer vouchers (where merchant=0); the contract
/// rejects recipient=0 on the bearer settle path. For legacy fixed-merchant
/// flows pass recipient = merchant (or 0 — they're not used there).
export function buildVoucher({ payer, merchant, recipient, amountUsdc, ttlSeconds, nonce }) {
  return {
    payer,
    merchant: merchant || ethers.ZeroAddress,
    recipient: recipient || ethers.ZeroAddress,
    amount: BigInt(amountUsdc),
    expiry: BigInt(Math.floor(Date.now() / 1000) + (ttlSeconds || 24 * 3600)),
    nonce: BigInt(nonce),
    voucherId: newVoucherId()
  };
}

/// Compact JSON for the NFC wire / QR code. Matches Voucher.kt CardVoucherPayload.
export function voucherToCardJson(v, signature) {
  return JSON.stringify({
    v: 2,                      // bumped: schema includes recipient
    i: v.voucherId,
    p: v.payer,
    m: v.merchant,
    r: v.recipient,
    a: v.amount.toString(),
    e: Number(v.expiry),
    n: Number(v.nonce),
    s: signature
  });
}

export function voucherFromCardJson(json) {
  const o = JSON.parse(json);
  return {
    voucher: {
      payer: o.p,
      merchant: o.m,
      recipient: o.r,
      amount: BigInt(o.a),
      expiry: BigInt(o.e),
      nonce: BigInt(o.n),
      voucherId: o.i
    },
    signature: o.s
  };
}
