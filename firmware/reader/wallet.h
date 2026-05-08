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

} // namespace OfflinePayWallet
