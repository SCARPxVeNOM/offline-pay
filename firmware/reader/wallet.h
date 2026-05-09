// =============================================================================
//  OfflinePay — ESP32 Device Wallet
// =============================================================================
//  Each ESP32 reader gets its own secp256k1 keypair, persisted in encrypted
//  NVS (non-volatile storage). The address is registered with MerchantRegistry
//  so the reader can settle vouchers as an authorized device — funds always
//  land in the merchant's primary wallet, so a stolen ESP32 means zero loss.
//
//  Key recovery: lost reader → merchant calls revokeDevice(esp32Address) and
//  authorizeDevice(newEsp32Address). No funds at risk.
// =============================================================================

#pragma once

#include <Arduino.h>
#include <stdint.h>

namespace OfflinePayWallet {

// Initialize: load the persisted key from NVS, or generate one on first boot.
// Must be called once from setup() before any other function.
bool begin();

// Returns the EVM-style address (20 bytes) of the device key, hex-encoded
// with the "0x" prefix, e.g. "0x4e0c...3a91".
String address();

// Same address, raw 20 bytes — useful when constructing on-chain
// digests that abi-encode the address into a 32-byte slot.
void addressBytes(uint8_t out20[20]);

// Returns the secp256k1 public key (65 bytes uncompressed: 0x04 || X || Y),
// hex-encoded without prefix.
String publicKeyHex();

// Sign a 32-byte digest with EIP-191 personal_sign semantics:
//   keccak256("\x19Ethereum Signed Message:\n32" || digest)
// Returns the 65-byte signature (r || s || v) hex-encoded with "0x" prefix.
// `digest` must be exactly 32 bytes.
String signEthMessage(const uint8_t* digest, size_t len);

// Wipes the stored key. Use for factory-reset on a confiscated device after
// the corresponding device address has been revoked on-chain.
void wipe();

// Verify an EIP-191 personal_sign signature against a 65-byte uncompressed
// public key (`pub65 = 0x04 || X || Y`). The 32-byte digest passed in is the
// raw payload — this function applies the EIP-191 prefix internally to match
// what the phone signs with `Sign.signPrefixedMessage`.
//
// `payload` is the application-level bytes (e.g. "OFFPAY-CLAIM-V1" || espId ||
// nonce) — typically NOT 32 bytes itself, so we sign the keccak256 of the
// EIP-191-wrapped bytes.
//
// Returns true iff the signature is valid AND the address derived from
// `pub65` (last 20 bytes of keccak256(pub65[1..])) equals `expectedAddr20`.
bool verifyEthPersonalSig(
    const uint8_t* payload, size_t payloadLen,
    const uint8_t pub65[65],
    const uint8_t r[32], const uint8_t s[32],
    const uint8_t expectedAddr20[20]);

// Compute the 20-byte EVM address from an uncompressed pubkey.
void addressFromPubkey(const uint8_t pub65[65], uint8_t out20[20]);

// Persist the bonded owner address (the phone wallet that successfully
// completed CLAIM). Returns true on success.
bool setOwner(const uint8_t addr20[20]);
bool getOwner(uint8_t addr20[20]);
bool hasOwner();
void clearOwner();

// Returns the owner address as a 0x… string, or "0x0000…" when unset.
String ownerAddressHex();

} // namespace OfflinePayWallet
