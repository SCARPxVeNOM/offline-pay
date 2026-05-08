package com.offlinepay.wallet

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log

/// HCE service for the Send role.
///
/// Custodial mode: vouchers are pre-signed by the backend at topup time.
/// SendActivity picks one or more, stages them as a JSON-array payload via
/// [PendingPayment], and the HCE just emits the bytes — no signing here.
///
/// Protocol:
///   SELECT AID                 → 9000
///   00 C1 00 00 14 <recv addr> → <2-byte BE len><JSON array of card payloads> 9000
///                              → 6A 82  (no pending payment)
///                              → 6F 00  (internal)
class HceVoucherService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        val ctx = applicationContext
        Log.d(TAG, "APDU IN (${commandApdu.size}B): ${commandApdu.joinToString("") { "%02x".format(it) }}")
        if (commandApdu.size >= 2 && commandApdu[0] == 0x00.toByte() && commandApdu[1] == 0xA4.toByte()) {
            Log.d(TAG, "SELECT AID -> 9000")
            return SUCCESS
        }
        if (commandApdu.size < 5 || commandApdu[1] != 0xC1.toByte()) {
            Log.w(TAG, "unknown INS -> 6D 00")
            return SW_INS_NOT_SUPPORTED
        }
        // Receiver address bytes are accepted but unused — bearer vouchers
        // are claimed by whoever forwards them to the backend.
        val pending = PendingPayment.consume(ctx) ?: run {
            Log.w(TAG, "no pending payment armed -> 6A 82")
            PendingPayment.reportError(ctx, "no pending payment armed")
            return SW_FILE_NOT_FOUND
        }

        return try {
            val bytes = pending.payload.toByteArray(Charsets.UTF_8)
            val len = bytes.size
            Log.d(TAG, "emit ${pending.voucherIds.size} voucher(s) totalBytes=$len")
            PendingPayment.reportSpent(ctx, pending.voucherIds)
            byteArrayOf(((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte()) +
                    bytes + SUCCESS
        } catch (t: Throwable) {
            Log.e(TAG, "emit failed", t)
            PendingPayment.reportError(ctx, "emit failed: ${t.message}")
            SW_INTERNAL
        }
    }

    override fun onDeactivated(reason: Int) { Log.d(TAG, "deactivated: $reason") }

    companion object {
        private const val TAG = "OfflinePay/HCE"
        private val SUCCESS              = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val SW_FILE_NOT_FOUND    = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00.toByte())
        private val SW_INTERNAL          = byteArrayOf(0x6F.toByte(), 0x00.toByte())
    }
}
