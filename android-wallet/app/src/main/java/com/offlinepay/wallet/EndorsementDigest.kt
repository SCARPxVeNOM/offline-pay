package com.offlinepay.wallet

import org.web3j.abi.TypeEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric
import java.math.BigInteger

/// Helpers for the ESP32-signed endorsement.
///
/// MIRROR `OfflineVault.endorsementDigest` exactly:
///   keccak256(abi.encode(
///       keccak256("OFFPAY-ENDORSE-V1"),
///       voucherId,
///       device,
///       merchantPrimary,
///       endorsementTs,
///       block.chainid,
///       address(this)
///   ))
/// If you change the contract digest, change this in lock-step.
object EndorsementDigest {
    private val DOMAIN_HASH: ByteArray = Hash.sha3("OFFPAY-ENDORSE-V1".toByteArray())

    fun digest(
        voucherId: String,
        device: String,
        merchantPrimary: String,
        endorsementTs: Long,
        chainId: Long,
        vaultAddress: String,
    ): ByteArray {
        val encoded =
            // bytes32 (already a 32-byte value, encode as Bytes32)
            TypeEncoder.encode(Bytes32(DOMAIN_HASH)) +
            TypeEncoder.encode(Bytes32(Numeric.hexStringToByteArray(voucherId))) +
            TypeEncoder.encode(Address(device)) +
            TypeEncoder.encode(Address(merchantPrimary)) +
            TypeEncoder.encode(Uint256(BigInteger.valueOf(endorsementTs))) +
            TypeEncoder.encode(Uint256(BigInteger.valueOf(chainId))) +
            TypeEncoder.encode(Address(vaultAddress))
        return Hash.sha3(Numeric.hexStringToByteArray(encoded))
    }

    /// ESP32 firmware hardcodes the recovery id `v` to 27 in every
    /// signature it produces (it can't compute the canonical y-parity
    /// without a richer mbedtls API). Web3j's recovery is forgiving and
    /// tries both, but Solidity's `ECDSA.recover` uses whatever `v` is
    /// in the signature bytes — so on-chain calls revert ~50% of the
    /// time with "bad endorsement sig".
    ///
    /// This helper takes the raw firmware signature, recovers for both
    /// v=27 and v=28, and returns a copy of the signature with whichever
    /// v actually recovers to `expectedDevice`. Caller is responsible
    /// for passing the correct payload that the ESP32 signed (i.e. the
    /// raw 32-byte digest, NOT the EIP-191-prefixed one — Sign.signedMessageHashToKey
    /// applies the prefix internally).
    fun fixSigV(
        digest: ByteArray,
        sigHex: String,
        expectedDevice: String,
    ): String {
        val raw = Numeric.hexStringToByteArray(sigHex)
        require(raw.size == 65) { "expected 65-byte sig, got ${raw.size}" }
        val r = raw.copyOfRange(0, 32)
        val s = raw.copyOfRange(32, 64)
        val ethDigest = Sign.getEthereumMessageHash(digest)
        val want = expectedDevice.lowercase().removePrefix("0x")
        for (v in listOf(27, 28)) {
            val sigData = Sign.SignatureData(v.toByte(), r, s)
            try {
                val pub = Sign.signedMessageHashToKey(ethDigest, sigData)
                val recovered = Keys.getAddress(pub).lowercase().removePrefix("0x")
                if (recovered == want) {
                    val out = raw.copyOf()
                    out[64] = v.toByte()
                    return Numeric.toHexString(out)
                }
            } catch (_: Throwable) { /* try next v */ }
        }
        // Neither v matched. The signature is genuinely bad (wrong
        // device, wrong digest, wrong key). Caller should reject.
        return sigHex
    }
}
