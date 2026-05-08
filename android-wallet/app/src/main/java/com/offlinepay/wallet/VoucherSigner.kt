package com.offlinepay.wallet

import org.web3j.abi.TypeEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Hash
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.security.SecureRandom

/// Builds and signs vouchers locally using the customer's secp256k1 key.
/// Output is byte-compatible with backend/src/voucher.js and on-chain
/// `OfflineVault.voucherDigest`.
///
/// Production callers MUST use [signNext] so every voucher draws its nonce
/// from the single persisted [NonceTracker] — re-using a nonce is what
/// causes the "stale nonce" revert on the second-to-settle voucher. The
/// raw [sign] entrypoint is kept for tests and the legacy custodial path
/// where the backend manages the nonce.
class VoucherSigner(
    private val chainId: Long,
    private val vaultAddress: String,
    private val keyPair: ECKeyPair,
    private val payerAddress: String,
    private val nonces: NonceTracker? = null,
) {
    data class SignedVoucher(
        val payer: String, val merchant: String,
        val amount: BigInteger, val expiry: Long, val nonce: Long,
        val voucherId: String, val signature: String
    )

    /// THE ONE call site for locally-signed vouchers in this app. Allocates
    /// the next nonce atomically (commit-backed) and never re-derives from
    /// getLast() afterwards.
    fun signNext(
        merchant: String?,
        amountUsdc: BigInteger,
        ttlSeconds: Long,
    ): SignedVoucher {
        val tracker = nonces
            ?: error("VoucherSigner constructed without NonceTracker — refuse to sign")
        return sign(merchant, amountUsdc, ttlSeconds, tracker.next())
    }

    /// Test/legacy entrypoint. Production code must call [signNext] instead.
    fun sign(
        merchant: String?,
        amountUsdc: BigInteger,
        ttlSeconds: Long,
        nonce: Long
    ): SignedVoucher {
        val voucherId = randomBytes32Hex()
        val expiry    = System.currentTimeMillis() / 1000 + ttlSeconds
        val merchantAddr = merchant?.takeIf { it.isNotBlank() } ?: ZERO_ADDR

        val digest = digest(payerAddress, merchantAddr, amountUsdc, expiry, nonce, voucherId)
        val ethDigest = Sign.getEthereumMessageHash(digest)

        val sig = Sign.signMessage(ethDigest, keyPair, false)
        val sigHex = Numeric.toHexString(sig.r) + Numeric.toHexStringNoPrefix(sig.s) +
                     String.format("%02x", sig.v[0].toInt() and 0xFF)

        return SignedVoucher(payerAddress, merchantAddr, amountUsdc, expiry, nonce, voucherId, sigHex)
    }

    private fun digest(
        payer: String, merchant: String, amount: BigInteger,
        expiry: Long, nonce: Long, voucherId: String
    ): ByteArray {
        val encoded =
            TypeEncoder.encode(Address(payer)) +
            TypeEncoder.encode(Address(merchant)) +
            TypeEncoder.encode(Uint256(amount)) +
            TypeEncoder.encode(Uint256(expiry)) +
            TypeEncoder.encode(Uint256(nonce)) +
            TypeEncoder.encode(Bytes32(Numeric.hexStringToByteArray(voucherId))) +
            TypeEncoder.encode(Uint256(chainId)) +
            TypeEncoder.encode(Address(vaultAddress))
        return Hash.sha3(Numeric.hexStringToByteArray(encoded))
    }

    private fun randomBytes32Hex(): String {
        val b = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return "0x" + b.joinToString("") { "%02x".format(it) }
    }

    companion object { const val ZERO_ADDR = "0x0000000000000000000000000000000000000000" }
}
