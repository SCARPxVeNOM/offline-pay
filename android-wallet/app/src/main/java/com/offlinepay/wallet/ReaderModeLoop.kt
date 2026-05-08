package com.offlinepay.wallet

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import kotlinx.coroutines.*

/// Activates reader-mode while the Receive screen is foregrounded. On a
/// successful tap, sends SELECT AID + INS 0xC1 (with our address payload),
/// reads the length-prefixed voucher JSON, decodes, and emits via [onVoucher].
class ReaderModeLoop(
    private val activity: Activity,
    private val ourAddressHex: String,    // "0x..." 42 chars
    private val onVoucher: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val flags = NfcAdapter.FLAG_READER_NFC_A or
                        NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
    private val callback = NfcAdapter.ReaderCallback { tag -> onTag(tag) }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun start() {
        val adapter = NfcAdapter.getDefaultAdapter(activity)
            ?: return onError.invoke("NFC not available on this device")
        adapter.enableReaderMode(activity, callback, flags, null)
    }

    fun stop() {
        NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity)
        scope.cancel()
    }

    private fun onTag(tag: Tag) {
        val isoDep = IsoDep.get(tag) ?: return onError.invoke("tag is not IsoDep")
        scope.launch {
            try {
                isoDep.connect()
                isoDep.timeout = 3000

                val select = byteArrayOf(
                    0x00, 0xA4.toByte(), 0x04, 0x00,
                    0x08, 0xF0.toByte(), 0x01, 0x1A, 0xC0.toByte(), 0xDE.toByte(),
                    0x10, 0xAC.toByte(), 0x1D
                )
                val selResp = isoDep.transceive(select)
                if (!endsWith9000(selResp)) {
                    onError.invoke("SELECT failed: ${hex(selResp)}"); return@launch
                }

                val addrBytes = hexToBytes(ourAddressHex.removePrefix("0x"))
                require(addrBytes.size == 20) { "bad addr size" }
                val req = byteArrayOf(0x00, 0xC1.toByte(), 0x00, 0x00, 0x14) + addrBytes
                val resp = isoDep.transceive(req)
                if (!endsWith9000(resp)) {
                    onError.invoke("REQUEST_PAY rejected: ${hex(resp.takeLastSw())}")
                    return@launch
                }
                if (resp.size < 4) { onError.invoke("short response"); return@launch }
                val len = ((resp[0].toInt() and 0xFF) shl 8) or (resp[1].toInt() and 0xFF)
                if (resp.size < 2 + len + 2) { onError.invoke("truncated payload"); return@launch }
                val payload = resp.copyOfRange(2, 2 + len).toString(Charsets.UTF_8)
                onVoucher.invoke(payload)
            } catch (t: Throwable) {
                Log.e(TAG, "reader loop error", t)
                onError.invoke("nfc error: ${t.message}")
            } finally {
                try { isoDep.close() } catch (_: Throwable) {}
            }
        }
    }

    private fun endsWith9000(b: ByteArray): Boolean =
        b.size >= 2 && b[b.size - 2] == 0x90.toByte() && b[b.size - 1] == 0x00.toByte()

    private fun ByteArray.takeLastSw(): ByteArray =
        if (size >= 2) copyOfRange(size - 2, size) else this

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it*2, it*2 + 2).toInt(16).toByte() }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    companion object { private const val TAG = "OfflinePay/Reader" }
}
