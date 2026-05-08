// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

/// @title MerchantRegistry — ENS-style merchant identity for OfflinePay.
/// @notice Maps a human-readable merchantId (e.g. "chai-stall-mg-road") to a
///         primary settlement wallet, plus a list of authorized device keys
///         (phone, ESP32) that may sign on behalf of that merchant.
///
///         Loss flow: if a phone or ESP32 is lost, the merchant simply
///         revokes its device key here. No funds are at risk because
///         settlement always pays the primary wallet — device keys never
///         hold custody.
contract MerchantRegistry {
    struct Merchant {
        address primaryWallet; // settlement target
        bool    exists;
    }

    // merchantId hash => merchant record
    mapping(bytes32 => Merchant) private _merchants;

    // merchantId hash => deviceAddress => authorized
    mapping(bytes32 => mapping(address => bool)) private _devices;

    // reverse lookup: deviceAddress => merchantId hash (so settlement can
    // resolve a device key to its merchant in O(1))
    mapping(address => bytes32) private _deviceMerchant;

    event MerchantRegistered(bytes32 indexed id, string name, address indexed primary);
    event PrimaryUpdated(bytes32 indexed id, address indexed oldPrimary, address indexed newPrimary);
    event DeviceAuthorized(bytes32 indexed id, address indexed device);
    event DeviceRevoked(bytes32 indexed id, address indexed device);

    modifier onlyPrimary(bytes32 id) {
        require(_merchants[id].exists, "unknown merchant");
        require(msg.sender == _merchants[id].primaryWallet, "not primary");
        _;
    }

    /// @notice Hash a human-readable name into the merchantId used by the
    ///         contract. Off-chain code (Android, ESP32, backend) must use
    ///         the same hash so they all agree on the key.
    function idOf(string calldata name) public pure returns (bytes32) {
        return keccak256(bytes(name));
    }

    /// @notice Register a new merchant. The caller becomes the primary wallet.
    function register(string calldata name) external returns (bytes32 id) {
        id = idOf(name);
        require(!_merchants[id].exists, "already registered");
        _merchants[id] = Merchant({ primaryWallet: msg.sender, exists: true });
        emit MerchantRegistered(id, name, msg.sender);
    }

    /// @notice Rotate the primary wallet (e.g. after a phone-loss recovery).
    function setPrimary(bytes32 id, address newPrimary) external onlyPrimary(id) {
        require(newPrimary != address(0), "primary=0");
        address old = _merchants[id].primaryWallet;
        _merchants[id].primaryWallet = newPrimary;
        emit PrimaryUpdated(id, old, newPrimary);
    }

    /// @notice Authorize a device (phone or ESP32) to sign vouchers / settle
    ///         on behalf of this merchant.
    function authorizeDevice(bytes32 id, address device) external onlyPrimary(id) {
        require(device != address(0), "device=0");
        require(_deviceMerchant[device] == bytes32(0), "device already linked");
        _devices[id][device] = true;
        _deviceMerchant[device] = id;
        emit DeviceAuthorized(id, device);
    }

    /// @notice Revoke a device — call this immediately if a device is lost
    ///         or stolen. Pending vouchers held on other mesh nodes still
    ///         settle to the primary wallet, so this is a safe operation.
    function revokeDevice(bytes32 id, address device) external onlyPrimary(id) {
        require(_devices[id][device], "device not authorized");
        _devices[id][device] = false;
        delete _deviceMerchant[device];
        emit DeviceRevoked(id, device);
    }

    // ─── Views ──────────────────────────────────────────────────────────────

    function primaryOf(bytes32 id) external view returns (address) {
        require(_merchants[id].exists, "unknown merchant");
        return _merchants[id].primaryWallet;
    }

    function isAuthorized(bytes32 id, address device) external view returns (bool) {
        return _devices[id][device];
    }

    /// @notice Resolve a device address to (merchantId, primary wallet).
    ///         Returns (0, address(0)) if the device is not authorized.
    function resolveDevice(address device) external view returns (bytes32 id, address primary) {
        id = _deviceMerchant[device];
        if (id == bytes32(0) || !_devices[id][device]) return (bytes32(0), address(0));
        primary = _merchants[id].primaryWallet;
    }
}
