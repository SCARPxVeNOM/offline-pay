package com.offlinepay.wallet

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log

/// HCE service for the Send role.
///
/// Protocol:
///   SELECT AID                 → 9000
///   00 C1 00 00 14 <20 bytes addr>
///                              → <2-byte BE len><voucher JSON> 9000  (success)
///                              → 6A 82                               (no pending payment)
///                              → 6F 00                               (sign failure)
class HceVoucherService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        if (commandApdu.size >= 2 && commandApdu[0] == 0x00.toByte() && commandApdu[1] == 0xA4.toByte()) {
            return SUCCESS
        }
        if (commandApdu.size < 5 || commandApdu[1] != 0xC1.toByte()) {
            return SW_INS_NOT_SUPPORTED
        }
        val lc = commandApdu[4].toInt() and 0xFF
        if (lc != 20 || commandApdu.size < 5 + lc) {
            PendingPayment.reportError("malformed REQUEST_PAY apdu")
            return SW_WRONG_LENGTH
        }
        val addrBytes = commandApdu.copyOfRange(5, 5 + 20)
        val receiver = "0x" + addrBytes.joinToString("") { "%02x".format(it) }

        val pending = PendingPayment.consume() ?: run {
            PendingPayment.reportError("no pending payment armed")
            return SW_FILE_NOT_FOUND
        }

        return try {
            val ctx = applicationContext
            val keyVault = KeyVault(ctx)
            val nonces   = NonceTracker(ctx)
            val signer   = VoucherSigner(
                chainId = Config.CHAIN_ID, vaultAddress = Config.VAULT_ADDRESS,
                keyPair = keyVault.keyPair, payerAddress = keyVault.address,
                nonces = nonces
            )
            // signNext uses NonceTracker.next() (sync commit). expiry comes from
            // the armed pending payment, NOT recomputed here, so what the user
            // saw on screen is what gets signed.
            val ttl = pending.expiry - System.currentTimeMillis() / 1000
            val signed = signer.signNext(receiver, pending.amountUsdc, ttl.coerceAtLeast(60))
            val payload = signed.toCardJson().toByteArray(Charsets.UTF_8)
            val len = payload.size
            byteArrayOf(((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte()) +
                payload + SUCCESS
        } catch (t: Throwable) {
            Log.e(TAG, "sign failed", t)
            PendingPayment.reportError("sign failed: ${t.message}")
            SW_INTERNAL
        }
    }

    override fun onDeactivated(reason: Int) { Log.d(TAG, "deactivated: $reason") }

    companion object {
        private const val TAG = "OfflinePay/HCE"
        private val SUCCESS              = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val SW_FILE_NOT_FOUND    = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00.toByte())
        private val SW_WRONG_LENGTH      = byteArrayOf(0x67.toByte(), 0x00.toByte())
        private val SW_INTERNAL          = byteArrayOf(0x6F.toByte(), 0x00.toByte())
    }
}
