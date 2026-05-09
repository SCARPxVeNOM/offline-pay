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

    /// secp256k1 curve order N. OpenZeppelin's `ECDSA.recover` rejects
    /// high-s signatures (s > N/2) for malleability protection — a
    /// signature with s and (N - s) are both mathematically valid, so
    /// without normalising, an attacker could forge a "different"
    /// signature for the same message. We canonicalise to low-s.
    private val SECP256K1_N: BigInteger = BigInteger(
        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16
    )
    private val SECP256K1_HALF_N: BigInteger = SECP256K1_N.shiftRight(1)

    /// Normalise the ESP32's raw signature so it's accepted on chain:
    ///
    /// 1. The firmware hardcodes `v = 27` (it can't compute canonical
    ///    y-parity without a richer mbedtls API). Whichever value of
    ///    {27, 28} actually recovers to `expectedDevice` is the right
    ///    one.
    /// 2. The firmware's mbedtls produces signatures with `s` in either
    ///    half of N. OZ's `ECDSA.recover` reverts on high-s with
    ///    `ECDSAInvalidSignatureS(s)`. Flip to low-s by replacing
    ///    `s ← N - s`, which also flips the parity of the y-coordinate
    ///    that v encodes.
    ///
    /// Caller passes the raw 32-byte digest (NOT EIP-191-prefixed —
    /// `Sign.signedMessageHashToKey` applies the prefix itself).
    fun fixSigV(
        digest: ByteArray,
        sigHex: String,
        expectedDevice: String,
    ): String {
        val raw = Numeric.hexStringToByteArray(sigHex)
        require(raw.size == 65) { "expected 65-byte sig, got ${raw.size}" }
        var r = raw.copyOfRange(0, 32)
        var s = raw.copyOfRange(32, 64)

        // 1. Normalise s to low-s.
        val sBig = BigInteger(1, s)
        if (sBig > SECP256K1_HALF_N) {
            val sLow = SECP256K1_N.subtract(sBig)
            s = ByteArray(32).also { dst ->
                val src = sLow.toByteArray()
                // toByteArray may produce 33 bytes (leading 0x00 sign byte)
                // or fewer than 32 (small magnitude). Right-align into 32.
                val from = if (src.size > 32) src.size - 32 else 0
                val len  = src.size - from
                System.arraycopy(src, from, dst, 32 - len, len)
            }
        }

        // 2. Try both v values to find the one that recovers to expected.
        val ethDigest = Sign.getEthereumMessageHash(digest)
        val want = expectedDevice.lowercase().removePrefix("0x")
        for (v in listOf(27, 28)) {
            val sigData = Sign.SignatureData(v.toByte(), r, s)
            try {
                val pub = Sign.signedMessageHashToKey(ethDigest, sigData)
                val recovered = Keys.getAddress(pub).lowercase().removePrefix("0x")
                if (recovered == want) {
                    val out = ByteArray(65)
                    System.arraycopy(r, 0, out, 0, 32)
                    System.arraycopy(s, 0, out, 32, 32)
                    out[64] = v.toByte()
                    return Numeric.toHexString(out)
                }
            } catch (_: Throwable) { /* try next v */ }
        }
        // Neither v matched. Signature is genuinely bad (wrong device,
        // wrong digest, wrong key). Caller should still try to use it
        // so the on-chain failure surfaces a clear revert.
        return sigHex
    }
}
