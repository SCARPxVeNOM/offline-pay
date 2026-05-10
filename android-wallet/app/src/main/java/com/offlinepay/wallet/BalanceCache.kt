package com.offlinepay.wallet

import android.content.Context
import android.content.SharedPreferences
import java.math.BigInteger

/// Offline-aware balance cache. Persists the last on-chain `lockedBalance`
/// we've seen, plus the set of voucherIds we know are settled (from chain
/// reads OR from mesh `settled` messages).
///
/// HomeActivity reads from here every time it renders so the badge shows
/// live numbers even with no internet — sender's spendable drops the
/// instant they sign a voucher (in-flight subtracted), and rises again
/// the instant a mesh peer tells us "tx 0x… landed". No chain RPC
/// needed for either direction.
class BalanceCache(ctx: Context) {

    private val prefs: SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /// Last on-chain `lockedBalance(self)` we observed, in USDC base
    /// units. Seeded to 0 if never synced. This is funds locked into
    /// the vault for voucher signing — only senders accumulate this.
    var lockedBalance: BigInteger
        get() = prefs.getString(KEY_LOCKED, null)?.let { runCatching { BigInteger(it) }.getOrNull() }
            ?: BigInteger.ZERO
        set(v) { prefs.edit().putString(KEY_LOCKED, v.toString()).apply() }

    /// Last on-chain `usdc.balanceOf(self)` we observed. Receivers
    /// accumulate USDC here when their bearer/recipient-bound vouchers
    /// settle on chain — so a merchant's "ON CHAIN" figure goes up
    /// when they get paid, even though their `lockedBalance` is zero.
    var usdcBalance: BigInteger
        get() = prefs.getString(KEY_USDC, null)?.let { runCatching { BigInteger(it) }.getOrNull() }
            ?: BigInteger.ZERO
        set(v) { prefs.edit().putString(KEY_USDC, v.toString()).apply() }

    /// Wall-clock millis at which lockedBalance was last refreshed from
    /// chain. UI converts this to "synced Xm ago" so the user knows
    /// whether the cached number is fresh or stale.
    var lastSyncedMs: Long
        get() = prefs.getLong(KEY_SYNC_TS, 0L)
        set(v) { prefs.edit().putLong(KEY_SYNC_TS, v).apply() }

    /// Has voucherId been observed as settled? Settlement source can be
    /// either chain read (`usedVouchers`) or a mesh `settled` message
    /// from a peer. Either way, in-flight no longer counts it.
    fun isSettled(voucherId: String): Boolean =
        prefs.getStringSet(KEY_SETTLED, null)?.contains(voucherId) == true

    /// Add voucherId to the settled set. Idempotent. Used by both the
    /// chain refresh path (when `usedVouchers[id] == true`) and the
    /// mesh handler (when a peer broadcasts a `settled` message).
    fun markSettled(voucherId: String) {
        val current = prefs.getStringSet(KEY_SETTLED, null) ?: emptySet()
        if (voucherId in current) return
        // SharedPreferences string-set semantics: must hand back a NEW set,
        // mutating the returned reference is unsupported.
        val next = HashSet(current).also { it.add(voucherId) }
        prefs.edit().putStringSet(KEY_SETTLED, next).apply()
    }

    /// Bulk-mark from a chain `usedVouchers` lookup. Saves one disk write.
    fun markAllSettled(voucherIds: Collection<String>) {
        if (voucherIds.isEmpty()) return
        val current = prefs.getStringSet(KEY_SETTLED, null) ?: emptySet()
        val next = HashSet(current).also { it.addAll(voucherIds) }
        if (next.size == current.size) return
        prefs.edit().putStringSet(KEY_SETTLED, next).apply()
    }

    companion object {
        private const val PREFS = "offpay_balance_cache"
        private const val KEY_LOCKED = "lockedBalance"
        private const val KEY_USDC = "usdcBalance"
        private const val KEY_SYNC_TS = "lastSyncedMs"
        private const val KEY_SETTLED = "settledVoucherIds"
    }
}
