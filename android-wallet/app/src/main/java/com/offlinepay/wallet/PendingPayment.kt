package com.offlinepay.wallet

import android.content.Context
import android.util.Log

/// Holds the pre-staged tap payload between the Send screen and the HCE
/// service. Persisted to SharedPreferences (sync commit) so a process death
/// between Arm and the NFC tap doesn't lose state — Android freely kills
/// processes, including ours, between activity pause and HCE wakeup.
///
/// Flow:
///   SendActivity picks 1+ unspent vouchers, calls [arm] with the JSON-array
///   wire payload + the list of voucherIds those bytes correspond to.
///   HCE 0xC1 calls [consume] once, returning the bytes; receiver then
///   stores them, and the sender separately marks those voucherIds as spent
///   on its UnspentStore.
object PendingPayment {

    data class Armed(val payload: String, val voucherIds: List<String>)

    private const val PREFS = "offlinepay_pending"
    private const val KEY_PAYLOAD = "payload"
    private const val KEY_IDS = "voucher_ids"
    private const val KEY_ERR = "last_error"
    private const val TAG = "OfflinePay/Pending"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun arm(ctx: Context, payload: String, voucherIds: List<String>) {
        prefs(ctx).edit()
            .putString(KEY_PAYLOAD, payload)
            .putString(KEY_IDS, voucherIds.joinToString(","))
            .remove(KEY_ERR)
            .commit()
        Log.d(TAG, "armed payloadLen=${payload.length} ids=$voucherIds")
    }

    fun consume(ctx: Context): Armed? {
        val p = prefs(ctx)
        val payload = p.getString(KEY_PAYLOAD, null) ?: return null
        val ids = p.getString(KEY_IDS, "")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
        p.edit().remove(KEY_PAYLOAD).remove(KEY_IDS).commit()
        Log.d(TAG, "consumed payloadLen=${payload.length} ids=$ids")
        return Armed(payload, ids)
    }

    fun cancel(ctx: Context) {
        prefs(ctx).edit().remove(KEY_PAYLOAD).remove(KEY_IDS).commit()
    }

    fun isArmed(ctx: Context): Boolean = prefs(ctx).contains(KEY_PAYLOAD)

    fun reportError(ctx: Context, msg: String) {
        prefs(ctx).edit().putString(KEY_ERR, msg).commit()
    }
    fun pollError(ctx: Context): String? {
        val p = prefs(ctx)
        val s = p.getString(KEY_ERR, null) ?: return null
        p.edit().remove(KEY_ERR).commit()
        return s
    }

    /// Set by HceVoucherService after a successful tap so the SendActivity
    /// can mark the corresponding UnspentRows as spent on its next poll.
    fun reportSpent(ctx: Context, ids: List<String>) {
        if (ids.isEmpty()) return
        prefs(ctx).edit().putString("spent", ids.joinToString(",")).commit()
    }
    fun pollSpent(ctx: Context): List<String> {
        val p = prefs(ctx)
        val s = p.getString("spent", null) ?: return emptyList()
        p.edit().remove("spent").commit()
        return s.split(",").filter { it.isNotEmpty() }
    }
}
