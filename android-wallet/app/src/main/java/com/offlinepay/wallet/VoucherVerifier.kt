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

enum class VerifyResult {
    VALID,
    EXPIRED,
    EXCEEDS_LIMIT,
    BAD_SIGNATURE,
    ALREADY_SEEN,
    WRONG_RECIPIENT,
    NOT_BEARER,
}

/// Reproduces OfflineVault.voucherDigest exactly (v3: includes recipient).
/// EIP-191 prefix is applied and Sign.signedMessageHashToKey recovers the
/// public key, which we compare to the stated `payer` address.
class VoucherVerifier(
    private val chainId: Long,
    private val vaultAddress: String,
    private val maxSinglePayment: BigInteger,
    private val voucherStore: VoucherStoreLike,
    private val expectedRecipient: String,
) {

    fun verify(v: Voucher): VerifyResult {
        if (System.currentTimeMillis() / 1000 > v.expiry)         return VerifyResult.EXPIRED
        if (v.amount > maxSinglePayment)                          return VerifyResult.EXCEEDS_LIMIT
        if (voucherStore.exists(v.voucherId))                     return VerifyResult.ALREADY_SEEN
        // Bearer-only flow: any voucher with a non-zero merchant slot is
        // unsettleable via settleBearerBatch and must be rejected at
        // receive time so it never enters the auto-settle loop.
        if (v.merchant != ZERO)                                   return VerifyResult.NOT_BEARER
        // Recipient binding: the voucher must be signed for THIS device.
        // Accepting a voucher whose recipient is someone else means we'd
        // hold a backup we could never settle to ourselves — and on chain
        // the funds would flow to that other recipient anyway. Mesh peers
        // that just want a replica copy use a different code path that
        // bypasses this check.
        if (!v.recipient.equals(expectedRecipient, ignoreCase = true))
            return VerifyResult.WRONG_RECIPIENT

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

    /// Signature-only check used by mesh replication: peer hands us a
    /// voucher that may be addressed to a different recipient. We just
    /// need to know the payer signed it. The full settle path is still
    /// owned by the receiver (who passes the same recipient check above).
    fun verifySignatureOnly(v: Voucher): Boolean = try {
        val digest = voucherDigest(v)
        val signedDigest = ethSignedMessageHash(digest)
        val sig = parseSignature(v.signature)
        val recoveredPubKey: BigInteger = Sign.signedMessageHashToKey(signedDigest, sig)
        val recoveredAddress = "0x" + Keys.getAddress(recoveredPubKey)
        recoveredAddress.equals(v.payer, ignoreCase = true)
    } catch (_: Throwable) { false }

    /// Receive-by-mesh path: same as `verify` minus the WRONG_RECIPIENT
    /// check, since mesh peers hold backup copies for vouchers addressed
    /// to other receivers. We still want signature, expiry, amount, and
    /// dedupe checks to filter out garbage from peers.
    fun verifyForMesh(v: Voucher): VerifyResult {
        if (System.currentTimeMillis() / 1000 > v.expiry)         return VerifyResult.EXPIRED
        if (v.amount > maxSinglePayment)                          return VerifyResult.EXCEEDS_LIMIT
        if (voucherStore.exists(v.voucherId))                     return VerifyResult.ALREADY_SEEN
        if (v.merchant != ZERO)                                   return VerifyResult.NOT_BEARER
        return if (verifySignatureOnly(v)) VerifyResult.VALID
               else VerifyResult.BAD_SIGNATURE
    }

    /// keccak256(abi.encode(payer, merchant, recipient, amount, expiry,
    ///                      nonce, voucherId, chainId, vault))
    private fun voucherDigest(v: Voucher): ByteArray {
        val encoded =
            TypeEncoder.encode(Address(v.payer)) +
            TypeEncoder.encode(Address(v.merchant)) +
            TypeEncoder.encode(Address(v.recipient)) +
            TypeEncoder.encode(Uint256(v.amount)) +
            TypeEncoder.encode(Uint256(v.expiry)) +
            TypeEncoder.encode(Uint256(v.nonce)) +
            TypeEncoder.encode(Bytes32(Numeric.hexStringToByteArray(v.voucherId))) +
            TypeEncoder.encode(Uint256(chainId)) +
            TypeEncoder.encode(Address(vaultAddress))
        return Hash.sha3(Numeric.hexStringToByteArray(encoded))
    }

    /// Apply EIP-191 personal_sign prefix and hash. Use Web3j's canonical
    /// helper rather than rolling our own — the prefix begins with the
    /// 0x19 byte (""), which our previous hand-rolled string was
    /// silently dropping. That made every signature verify fail.
    private fun ethSignedMessageHash(digest: ByteArray): ByteArray =
        Sign.getEthereumMessageHash(digest)

    private fun parseSignature(hex: String): Sign.SignatureData {
        val bytes = Numeric.hexStringToByteArray(hex)
        require(bytes.size == 65) { "expected 65-byte sig, got ${bytes.size}" }
        val r = bytes.copyOfRange(0, 32)
        val s = bytes.copyOfRange(32, 64)
        var v = bytes[64].toInt() and 0xFF
        if (v < 27) v += 27   // EIP-155 fallback
        return Sign.SignatureData(v.toByte(), r, s)
    }

    companion object {
        private const val ZERO = "0x0000000000000000000000000000000000000000"
    }
}
