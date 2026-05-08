package com.offlinepay.wallet

import org.junit.Assert.assertEquals
import org.junit.Test
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import java.math.BigInteger

class VoucherDigestRoundTripTest {

    private val chainId = 80002L
    private val vault   = "0x1234567890abcdef1234567890abcdef12345678"
    private val emptyStore = object : VoucherStoreLike { override fun exists(id: String) = false }

    @Test
    fun signed_voucher_verifies_against_same_keys_and_recipient() {
        val senderKp   = ECKeyPair.create(BigInteger("a".repeat(64), 16))
        val senderAddr = "0x" + Keys.getAddress(senderKp)
        val receiverKp   = ECKeyPair.create(BigInteger("b".repeat(64), 16))
        val receiverAddr = "0x" + Keys.getAddress(receiverKp)

        val signer = VoucherSigner(chainId, vault, senderKp, senderAddr, nonces = null)
        val signed = signer.sign(receiverAddr, BigInteger.valueOf(1_500_000), 3600, nonce = 1)

        val verifier = VoucherVerifier(
            chainId, vault, BigInteger("2000000"), emptyStore, expectedRecipient = receiverAddr
        )
        val v = Voucher(
            voucherId = signed.voucherId, payer = signed.payer, merchant = signed.merchant,
            amount = signed.amount, expiry = signed.expiry, nonce = signed.nonce,
            signature = signed.signature
        )
        assertEquals(VerifyResult.VALID, verifier.verify(v))
    }

    @Test
    fun voucher_signed_for_other_recipient_is_rejected() {
        val senderKp   = ECKeyPair.create(BigInteger("a".repeat(64), 16))
        val senderAddr = "0x" + Keys.getAddress(senderKp)
        val intended    = "0x" + Keys.getAddress(ECKeyPair.create(BigInteger("b".repeat(64), 16)))
        val attackerAddr = "0x" + Keys.getAddress(ECKeyPair.create(BigInteger("c".repeat(64), 16)))

        val signer = VoucherSigner(chainId, vault, senderKp, senderAddr, nonces = null)
        val signed = signer.sign(intended, BigInteger.valueOf(1_500_000), 3600, nonce = 1)

        val verifier = VoucherVerifier(
            chainId, vault, BigInteger("2000000"), emptyStore, expectedRecipient = attackerAddr
        )
        val v = Voucher(
            voucherId = signed.voucherId, payer = signed.payer, merchant = signed.merchant,
            amount = signed.amount, expiry = signed.expiry, nonce = signed.nonce,
            signature = signed.signature
        )
        assertEquals(VerifyResult.WRONG_RECIPIENT, verifier.verify(v))
    }
}
