# OfflineVault Contracts

Settlement layer for OfflinePay. Customers lock USDC, sign offline vouchers, merchants redeem when they reconnect.

## Quickstart

```bash
npm install
npx hardhat compile
npx hardhat test
```

## Local devnet

```bash
npx hardhat node                          # in one terminal
npm run deploy:local                      # in another
# deployments/localhost.json now contains addresses for backend/Android to use
```

## Deploy to Polygon Amoy testnet

1. Copy `.env.example` to `.env`, set `DEPLOYER_PRIVATE_KEY` (a fresh wallet).
2. Get test MATIC from <https://faucet.polygon.technology>.
3. Run `npm run deploy:amoy`.

## Voucher hash (canonical)

Anyone signing a voucher (backend, Android customer app) must produce this exact digest, then EIP-191-prefix it before signing:

```
keccak256(abi.encode(
  payer    : address,
  merchant : address,        // 0x0 = bearer
  amount   : uint256,        // USDC base units (6 decimals)
  expiry   : uint256,        // unix seconds
  nonce    : uint256,        // strictly > lastNonce[payer]
  voucherId: bytes32,        // unique id
  chainId  : uint256,        // bound to network
  vault    : address         // bound to deployment
))
```

The contract exposes `voucherDigest(Voucher)` so off-chain signers can compare bit-for-bit.

## Limits (mutable by owner)

| Knob              | Default       | Why                                    |
|-------------------|---------------|----------------------------------------|
| maxSinglePayment  | $2.00 (2e6)   | Caps blast radius of one stolen card.  |
| maxLockedBalance  | $5.00 (5e6)   | Caps total double-spend exposure/user. |
| defaultVoucherTTL | 24h           | Limits how long an offline voucher can sit. |
