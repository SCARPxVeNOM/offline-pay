import { ethers } from "ethers";
import { config } from "./config.js";

export const provider = new ethers.JsonRpcProvider(config.rpcUrl);
const wallet   = new ethers.Wallet(config.backendKey, provider);
// NonceManager serializes pending nonces locally so back-to-back txs don't race.
export const signer   = new ethers.NonceManager(wallet);
// Convenience: expose the address (NonceManager doesn't have .address until awaited).
signer.address = wallet.address;

const VAULT_ABI = [
  "function lockFunds(uint256 amount)",
  "function unlock(uint256 amount)",
  "function lockedBalance(address) view returns (uint256)",
  "function lastNonce(address) view returns (uint256)",
  "function usedVouchers(bytes32) view returns (bool)",
  "function maxSinglePayment() view returns (uint256)",
  "function maxLockedBalance() view returns (uint256)",
  "function settleVoucher((address,address,uint256,uint256,uint256,bytes32),bytes)",
  "function settleBatch((address,address,uint256,uint256,uint256,bytes32)[],bytes[])",
  "function settleBearer((address,address,uint256,uint256,uint256,bytes32),bytes,address)",
  "function settleBearerBatch((address,address,uint256,uint256,uint256,bytes32)[],bytes[],address)",
  "event VoucherSettled(bytes32 indexed voucherId, address indexed payer, address indexed merchant, uint256 amount, uint256 nonce)",
  "event FundsLocked(address indexed payer, uint256 amount, uint256 newBalance)"
];

const USDC_ABI = [
  "function balanceOf(address) view returns (uint256)",
  "function decimals() view returns (uint8)",
  "function approve(address,uint256) returns (bool)",
  "function transfer(address,uint256) returns (bool)",
  "function mint(address,uint256)" // Mock USDC only
];

export const vault = config.vault
  ? new ethers.Contract(config.vault, VAULT_ABI, signer)
  : null;

export const usdc = config.usdc
  ? new ethers.Contract(config.usdc, USDC_ABI, signer)
  : null;
