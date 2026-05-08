package com.offlinepay.wallet

import android.content.Context
import android.util.Log
import java.math.BigInteger

/// Holds the next outgoing payment between Send screen and HCE service.
/// Persisted to SharedPreferences (sync commit) so a process death
/// between Arm and the NFC tap doesn't lose the pending state.
///
/// Option B mode: stores amount + expiry only. HCE signs the voucher at
/// tap time using NonceTracker + KeyVault, with merchant = receiver
/// address taken from the INS 0xC1 APDU.
object PendingPayment {

    data class Armed(val amountUsdc: BigInteger, val expiry: Long)

    data class Outgoing(val voucherId: String, val recipient: String, val amount: BigInteger)

    private const val PREFS = "offlinepay_pending"
    private const val KEY_AMT = "amount"
    private const val KEY_EXP = "expiry"
    private const val KEY_ERR = "last_error"
    private const val KEY_OUT = "last_outgoing"
    private const val TAG = "OfflinePay/Pending"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun arm(ctx: Context, amountUsdc: BigInteger, ttlSeconds: Long = Config.DEFAULT_TTL_SECONDS) {
        val expiry = System.currentTimeMillis() / 1000 + ttlSeconds
        prefs(ctx).edit()
            .putString(KEY_AMT, amountUsdc.toString())
            .putLong(KEY_EXP, expiry)
            .remove(KEY_ERR)
            .commit()
        Log.d(TAG, "armed amount=$amountUsdc expiry=$expiry")
    }

    fun consume(ctx: Context): Armed? {
        val p = prefs(ctx)
        val amt = p.getString(KEY_AMT, null) ?: return null
        val exp = p.getLong(KEY_EXP, 0L)
        p.edit().remove(KEY_AMT).remove(KEY_EXP).commit()
        Log.d(TAG, "consumed amount=$amt expiry=$exp")
        return Armed(BigInteger(amt), exp)
    }

    fun cancel(ctx: Context) {
        prefs(ctx).edit().remove(KEY_AMT).remove(KEY_EXP).commit()
    }

    fun isArmed(ctx: Context): Boolean = prefs(ctx).contains(KEY_AMT)

    fun reportError(ctx: Context, msg: String) {
        prefs(ctx).edit().putString(KEY_ERR, msg).commit()
    }
    fun pollError(ctx: Context): String? {
        val p = prefs(ctx)
        val s = p.getString(KEY_ERR, null) ?: return null
        p.edit().remove(KEY_ERR).commit()
        return s
    }

    /// Recorded by HCE on a successful tap so SendActivity can show the
    /// outgoing details (and a sender-side settle worker could later push
    /// to the chain to mark the funds as transferred from sender's view).
    fun reportOutgoing(ctx: Context, voucherId: String, recipient: String, amount: BigInteger) {
        prefs(ctx).edit()
            .putString(KEY_OUT, "$voucherId|$recipient|$amount")
            .commit()
    }
    fun pollOutgoing(ctx: Context): Outgoing? {
        val p = prefs(ctx)
        val s = p.getString(KEY_OUT, null) ?: return null
        p.edit().remove(KEY_OUT).commit()
        val parts = s.split("|")
        if (parts.size != 3) return null
        return Outgoing(parts[0], parts[1], BigInteger(parts[2]))
    }
}
