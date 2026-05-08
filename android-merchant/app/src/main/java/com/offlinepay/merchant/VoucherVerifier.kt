package com.offlinepay.merchant

import org.web3j.abi.DefaultFunctionEncoder
import org.web3j.abi.TypeEncoder
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric
import java.math.BigInteger

enum class VerifyResult { VALID, EXPIRED, EXCEEDS_LIMIT, BAD_SIGNATURE, ALREADY_SEEN }

/// Reproduces OfflineVault.voucherDigest exactly. Then EIP-191 prefix is
/// applied and Sign.signedMessageHashToKey recovers the public key, which
/// we compare to the stated `payer` address.
class VoucherVerifier(
    private val chainId: Long,
    private val vaultAddress: String,
    private val maxSinglePayment: BigInteger,
    private val voucherStore: VoucherStore,
) {

    fun verify(v: Voucher): VerifyResult {
        if (System.currentTimeMillis() / 1000 > v.expiry)         return VerifyResult.EXPIRED
        if (v.amount > maxSinglePayment)                          return VerifyResult.EXCEEDS_LIMIT
        if (voucherStore.exists(v.voucherId))                     return VerifyResult.ALREADY_SEEN

        return try {
            val digest = voucherDigest(v)
            val signedDigest = ethSignedMessageHash(digest)
            val sig = parseSignature(v.signature)
            val recoveredPubKey: BigInteger =
                Sign.signedMessageHashToKey(signedDigest, sig)
            val recoveredAddress = "0x" + Keys.getAddress(recoveredPubKey)
            if (recoveredAddress.equals(v.payer, ignoreCase = true)) VerifyResult.VALID
            else VerifyResult.BAD_SIGNATURE
        } catch (t: Throwable) {
            VerifyResult.BAD_SIGNATURE
        }
    }

    /// keccak256(abi.encode(payer, merchant, amount, expiry, nonce,
    ///                      voucherId, chainId, vault))
    private fun voucherDigest(v: Voucher): ByteArray {
        val encoded =
            TypeEncoder.encode(Address(v.payer)) +
            TypeEncoder.encode(Address(v.merchant)) +
            TypeEncoder.encode(Uint256(v.amount)) +
            TypeEncoder.encode(Uint256(v.expiry)) +
            TypeEncoder.encode(Uint256(v.nonce)) +
            TypeEncoder.encode(Bytes32(Numeric.hexStringToByteArray(v.voucherId))) +
            TypeEncoder.encode(Uint256(chainId)) +
            TypeEncoder.encode(Address(vaultAddress))
        return Hash.sha3(Numeric.hexStringToByteArray(encoded))
    }

    private fun ethSignedMessageHash(digest: ByteArray): ByteArray {
        val prefix = "Ethereum Signed Message:\n${digest.size}".toByteArray(Charsets.UTF_8)
        return Hash.sha3(prefix + digest)
    }

    private fun parseSignature(hex: String): Sign.SignatureData {
        val bytes = Numeric.hexStringToByteArray(hex)
        require(bytes.size == 65) { "expected 65-byte sig, got ${bytes.size}" }
        val r = bytes.copyOfRange(0, 32)
        val s = bytes.copyOfRange(32, 64)
        var v = bytes[64].toInt() and 0xFF
        if (v < 27) v += 27   // EIP-155 fallback
        return Sign.SignatureData(v.toByte(), r, s)
    }
}
