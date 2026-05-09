package com.offlinepay.wallet

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private val hceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/// HCE service for the Send role.
///
/// Option B (non-custodial): the SENDER signs the voucher at tap time
/// using its own private key + persistent NonceTracker. payer field of
/// the voucher = this device's wallet address. Receiver verifies the
/// signature recovers to that address.
///
/// Protocol:
///   SELECT AID                       → 9000
///   00 C1 00 00 14 <recv addr>       → <2-byte BE len><card-payload JSON> 9000
///                                    → 6A 82  (no pending payment armed)
///                                    → 6F 00  (signing failed)
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
        val lc = commandApdu[4].toInt() and 0xFF
        if (lc != 20 || commandApdu.size < 5 + lc) {
            PendingPayment.reportError(ctx, "malformed REQUEST_PAY apdu")
            return SW_WRONG_LENGTH
        }
        val addrBytes = commandApdu.copyOfRange(5, 5 + 20)
        val receiver = "0x" + addrBytes.joinToString("") { "%02x".format(it) }
        Log.d(TAG, "INS 0xC1 receiver=$receiver")

        val pending = PendingPayment.consume(ctx) ?: run {
            Log.w(TAG, "no pending payment armed -> 6A 82")
            PendingPayment.reportError(ctx, "no pending payment armed")
            return SW_FILE_NOT_FOUND
        }

        return try {
            val keyVault = KeyVault(ctx)
            val nonces = NonceTracker(ctx)
            val signer = VoucherSigner(
                chainId = Config.CHAIN_ID,
                vaultAddress = Config.VAULT_ADDRESS,
                keyPair = keyVault.keyPair,
                payerAddress = keyVault.address,
                nonces = nonces
            )
            // Bearer voucher (merchant=0x0). recipient = receiver address
            // from the 0xC1 APDU. Anyone can broadcast settle, but funds
            // flow only to this recipient — the digest is signed over it.
            val ttl = (pending.expiry - System.currentTimeMillis() / 1000).coerceAtLeast(60)
            val signed = signer.signNext(
                merchant   = null,
                recipient  = receiver,
                amountUsdc = pending.amountUsdc,
                ttlSeconds = ttl,
            )
            val payload = signed.toCardJson().toByteArray(Charsets.UTF_8)
            val len = payload.size
            Log.d(TAG, "signed voucher id=${signed.voucherId.take(10)} len=$len")
            PendingPayment.reportOutgoing(ctx, signed.voucherId, receiver, pending.amountUsdc)
            // Audit log for sender's "Recent activity" feed. Run on IO scope —
            // Room rejects writes on the binder thread processCommandApdu uses.
            val capturedId = signed.voucherId
            val capturedAmount = pending.amountUsdc.toLong()
            hceScope.launch {
                try {
                    VoucherDb.get(ctx).activityDao().insert(
                        ActivityRow(
                            kind = "sent",
                            amountBaseUnits = capturedAmount,
                            counterparty = receiver,
                            voucherId = capturedId,
                            txHash = null,
                            ts = System.currentTimeMillis(),
                            note = "Tapped offline",
                        )
                    )
                    Log.d(TAG, "logged SENT id=${capturedId.take(10)} to=${receiver.take(10)}…")
                } catch (t: Throwable) {
                    Log.w(TAG, "could not log activity: ${t.message}")
                }
            }
            // Wrap as JSON array to match the receiver's listFromWireJson parser.
            val wireBytes = ("[" + signed.toCardJson() + "]").toByteArray(Charsets.UTF_8)
            val wlen = wireBytes.size
            byteArrayOf(((wlen shr 8) and 0xFF).toByte(), (wlen and 0xFF).toByte()) +
                    wireBytes + SUCCESS
        } catch (t: Throwable) {
            Log.e(TAG, "sign failed", t)
            PendingPayment.reportError(ctx, "sign failed: ${t.message}")
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
