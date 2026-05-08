// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import "@openzeppelin/contracts/token/ERC20/IERC20.sol";
import "@openzeppelin/contracts/utils/cryptography/ECDSA.sol";
import "@openzeppelin/contracts/utils/cryptography/MessageHashUtils.sol";
import "@openzeppelin/contracts/access/Ownable.sol";
import "@openzeppelin/contracts/utils/Pausable.sol";
import "@openzeppelin/contracts/utils/ReentrancyGuard.sol";

interface IMerchantRegistry {
    function resolveDevice(address device) external view returns (bytes32 id, address primary);
}

/// @title OfflineVault — settlement layer for OfflinePay vouchers.
/// @notice Customers lock USDC, sign offline vouchers (bearer or fixed-merchant),
///         and merchants redeem them on-chain when they reconnect.
contract OfflineVault is Ownable, Pausable, ReentrancyGuard {
    using ECDSA for bytes32;
    using MessageHashUtils for bytes32;

    IERC20 public immutable usdc;

    /// @notice Optional merchant registry. When set, callers of
    ///         `settleVoucherAsDevice` are treated as device keys and the
    ///         payout goes to their merchant's primary wallet.
    IMerchantRegistry public merchantRegistry;

    // Hard caps to bound offline double-spend exposure.
    uint256 public maxSinglePayment   = 2_000_000;      // $2.00 (USDC has 6 decimals)
    uint256 public maxLockedBalance   = 5_000_000;      // $5.00 per customer
    uint256 public defaultVoucherTTL  = 24 hours;

    mapping(address => uint256) public lockedBalance;   // payer => locked USDC
    mapping(address => uint256) public lastNonce;       // payer => last consumed nonce
    mapping(bytes32 => bool)    public usedVouchers;    // voucherId => spent flag

    event FundsLocked(address indexed payer, uint256 amount, uint256 newBalance);
    event FundsUnlocked(address indexed payer, uint256 amount, uint256 newBalance);
    event VoucherSettled(
        bytes32 indexed voucherId,
        address indexed payer,
        address indexed merchant,
        uint256 amount,
        uint256 nonce
    );
    event LimitsUpdated(uint256 maxSingle, uint256 maxBalance, uint256 ttl);
    event MerchantRegistryUpdated(address indexed registry);

    struct Voucher {
        address payer;       // funds owner
        address merchant;    // address(0) = bearer (any merchant can claim)
        uint256 amount;      // USDC base units (6 decimals)
        uint256 expiry;      // unix seconds
        uint256 nonce;       // strictly increasing per payer
        bytes32 voucherId;   // unique id (uuid hashed or random bytes32)
    }

    constructor(address _usdc) Ownable(msg.sender) {
        require(_usdc != address(0), "usdc=0");
        usdc = IERC20(_usdc);
    }

    // ─── Customer flows ─────────────────────────────────────────────

    /// @notice Lock USDC into the vault so vouchers signed by msg.sender can settle.
    function lockFunds(uint256 amount) external whenNotPaused nonReentrant {
        require(amount > 0, "amount=0");
        require(lockedBalance[msg.sender] + amount <= maxLockedBalance, "exceeds cap");
        require(usdc.transferFrom(msg.sender, address(this), amount), "transfer failed");
        lockedBalance[msg.sender] += amount;
        emit FundsLocked(msg.sender, amount, lockedBalance[msg.sender]);
    }

    /// @notice Withdraw unspent locked USDC back to wallet.
    function unlock(uint256 amount) external nonReentrant {
        uint256 bal = lockedBalance[msg.sender];
        require(bal >= amount, "insufficient");
        lockedBalance[msg.sender] = bal - amount;
        require(usdc.transfer(msg.sender, amount), "transfer failed");
        emit FundsUnlocked(msg.sender, amount, lockedBalance[msg.sender]);
    }

    // ─── Merchant settlement ────────────────────────────────────────

    /// @notice Settle a single voucher signed by `voucher.payer`.
    /// @dev If merchant==0 the voucher is bearer and anyone (msg.sender) can redeem.
    ///      Otherwise only `voucher.merchant` can call this.
    function settleVoucher(Voucher calldata v, bytes calldata sig)
        external
        whenNotPaused
        nonReentrant
    {
        _settle(v, sig, msg.sender);
    }

    /// @notice Batch settle to amortize gas across many small offline txs.
    function settleBatch(Voucher[] calldata vs, bytes[] calldata sigs)
        external
        whenNotPaused
        nonReentrant
    {
        require(vs.length == sigs.length, "len mismatch");
        for (uint256 i = 0; i < vs.length; i++) {
            _settle(vs[i], sigs[i], msg.sender);
        }
    }

    /// @notice Settle a voucher claimed by an authorized device key.
    /// @dev    msg.sender is a device (phone/ESP32). The registry resolves it
    ///         to the merchant's primary wallet, which receives the funds.
    ///         The voucher's `merchant` field must equal the primary wallet
    ///         (or be address(0) for bearer).
    function settleVoucherAsDevice(Voucher calldata v, bytes calldata sig)
        external
        whenNotPaused
        nonReentrant
    {
        address primary = _resolvePrimary(msg.sender);
        _settle(v, sig, primary);
    }

    /// @notice Batch device-keyed settlement.
    function settleBatchAsDevice(Voucher[] calldata vs, bytes[] calldata sigs)
        external
        whenNotPaused
        nonReentrant
    {
        require(vs.length == sigs.length, "len mismatch");
        address primary = _resolvePrimary(msg.sender);
        for (uint256 i = 0; i < vs.length; i++) {
            _settle(vs[i], sigs[i], primary);
        }
    }

    function _resolvePrimary(address device) internal view returns (address primary) {
        require(address(merchantRegistry) != address(0), "registry unset");
        (, primary) = merchantRegistry.resolveDevice(device);
        require(primary != address(0), "device not authorized");
    }

    function _settle(Voucher calldata v, bytes calldata sig, address claimer) internal {
        require(!usedVouchers[v.voucherId], "already settled");
        require(block.timestamp <= v.expiry, "expired");
        require(v.amount > 0 && v.amount <= maxSinglePayment, "bad amount");
        require(v.nonce > lastNonce[v.payer], "stale nonce");
        require(lockedBalance[v.payer] >= v.amount, "insufficient locked");

        if (v.merchant != address(0)) {
            require(v.merchant == claimer, "wrong merchant");
        }

        bytes32 digest = voucherDigest(v);
        address recovered = digest.toEthSignedMessageHash().recover(sig);
        require(recovered == v.payer, "bad signature");

        usedVouchers[v.voucherId] = true;
        lastNonce[v.payer] = v.nonce;
        lockedBalance[v.payer] -= v.amount;

        require(usdc.transfer(claimer, v.amount), "payout failed");
        emit VoucherSettled(v.voucherId, v.payer, claimer, v.amount, v.nonce);
    }

    // ─── Voucher hashing (canonical) ────────────────────────────────

    /// @notice Off-chain signers MUST hash exactly these fields, in this order,
    ///         then prepend the EIP-191 prefix before signing. The chainId is
    ///         baked in to prevent cross-chain replay.
    function voucherDigest(Voucher calldata v) public view returns (bytes32) {
        return keccak256(abi.encode(
            v.payer,
            v.merchant,
            v.amount,
            v.expiry,
            v.nonce,
            v.voucherId,
            block.chainid,
            address(this)
        ));
    }

    // ─── Admin ──────────────────────────────────────────────────────

    function setMerchantRegistry(address registry) external onlyOwner {
        merchantRegistry = IMerchantRegistry(registry);
        emit MerchantRegistryUpdated(registry);
    }

    function setLimits(uint256 _maxSingle, uint256 _maxBalance, uint256 _ttl) external onlyOwner {
        maxSinglePayment  = _maxSingle;
        maxLockedBalance  = _maxBalance;
        defaultVoucherTTL = _ttl;
        emit LimitsUpdated(_maxSingle, _maxBalance, _ttl);
    }

    function pause() external onlyOwner { _pause(); }
    function unpause() external onlyOwner { _unpause(); }
}
