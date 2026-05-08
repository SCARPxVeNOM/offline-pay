import { ethers } from "ethers";
import { v4 as uuidv4 } from "uuid";
import { config } from "./config.js";

/// Build the canonical OfflineVault voucher digest.
/// MUST match OfflineVault.voucherDigest exactly.
export function voucherDigest(v) {
  const encoded = ethers.AbiCoder.defaultAbiCoder().encode(
    ["address","address","uint256","uint256","uint256","bytes32","uint256","address"],
    [v.payer, v.merchant, v.amount, v.expiry, v.nonce, v.voucherId, config.chainId, config.vault]
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
export function buildVoucher({ payer, merchant, amountUsdc, ttlSeconds, nonce }) {
  return {
    payer,
    merchant: merchant || ethers.ZeroAddress,
    amount: BigInt(amountUsdc),
    expiry: BigInt(Math.floor(Date.now() / 1000) + (ttlSeconds || 24 * 3600)),
    nonce: BigInt(nonce),
    voucherId: newVoucherId()
  };
}

/// Compact format for the ESP32 / MIFARE card. We can't fit full JSON with addresses
/// on a 16-byte block, so the ESP32 stores a tiny base64-ish blob and the merchant
/// app expands it (signature is the only big field — 65 bytes hex = 132 chars).
///
/// Layout written across 2 consecutive MIFARE blocks (32 bytes total) for short
/// vouchers, but for safety we use 4 blocks (64 bytes) — enough for everything
/// except the signature, which goes into the next 5 blocks. Total: 9 data blocks
/// per voucher. With 5 vouchers/card and 16-byte blocks, this just barely fits.
///
/// Easier path: store the *voucherId* and *amount* on the card, plus a server-issued
/// short opaque token, and have the merchant phone fetch the full signed voucher
/// from a local cache once it sees the token. But for fully-offline we need it ALL
/// on the card.
///
/// Compromise used here: the card stores a single short JSON like
///   {"v":"1","i":"<uuid>","a":<amt>,"e":<exp>,"n":<n>,"p":"<payer>","s":"<sig>"}
/// concatenated across 16 contiguous data blocks (256 bytes), more than enough.
/// The reader firmware reassembles by reading until it hits a \0 byte.
export function voucherToCardJson(v, signature) {
  return JSON.stringify({
    v: 1,
    i: v.voucherId,
    p: v.payer,
    m: v.merchant,
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
      amount: BigInt(o.a),
      expiry: BigInt(o.e),
      nonce: BigInt(o.n),
      voucherId: o.i
    },
    signature: o.s
  };
}
