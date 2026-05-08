package com.offlinepay.customer

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log

/// HCE service. The merchant phone (acting as an NFC reader app) SELECTs
/// the OfflinePay AID, then issues a small "GET VOUCHER" APDU; we respond
/// with the next signed voucher payload (UTF-8 JSON), framed as
///   <2-byte-length-big-endian> <payload bytes> <0x90 0x00 = success SW>
class HceVoucherService : HostApduService() {

    override fun processCommandApdu(commandApdu: ByteArray, extras: Bundle?): ByteArray {
        val hex = commandApdu.joinToString("") { "%02x".format(it) }
        Log.d(TAG, "APDU IN: $hex")

        // SELECT AID — bytes [00 A4 04 00 LL <aid bytes> 00].
        if (commandApdu.size >= 5 && commandApdu[0] == 0x00.toByte() &&
            commandApdu[1] == 0xA4.toByte()) {
            return SUCCESS
        }

        // Custom INS 0xC0 = "give me one voucher".
        if (commandApdu.size >= 4 && commandApdu[1] == 0xC0.toByte()) {
            val payload = NextVoucherProvider.peek()
                ?: return SW_FILE_NOT_FOUND
            NextVoucherProvider.consume()
            val bytes = payload.toByteArray(Charsets.UTF_8)
            val len = bytes.size
            return byteArrayOf(((len shr 8) and 0xFF).toByte(), (len and 0xFF).toByte()) +
                    bytes + SUCCESS
        }

        return SW_INS_NOT_SUPPORTED
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "deactivated: $reason")
    }

    companion object {
        private const val TAG = "OfflinePay/HCE"
        private val SUCCESS  = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val SW_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val SW_INS_NOT_SUPPORTED = byteArrayOf(0x6D.toByte(), 0x00.toByte())
    }
}

/// Tiny in-process queue of pre-signed voucher card-payloads waiting to be
/// served on the next NFC tap. Populated by MainActivity after the user
/// loads vouchers from the backend or signs them locally.
object NextVoucherProvider {
    private val queue: ArrayDeque<String> = ArrayDeque()
    @Synchronized fun push(cardPayload: String) { queue.addLast(cardPayload) }
    @Synchronized fun peek(): String? = queue.firstOrNull()
    @Synchronized fun consume(): String? = queue.removeFirstOrNull()
    @Synchronized fun size(): Int = queue.size
}
