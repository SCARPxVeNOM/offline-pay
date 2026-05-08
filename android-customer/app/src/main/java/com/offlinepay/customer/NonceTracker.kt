package com.offlinepay.customer

import android.content.Context

/// Persisted "last nonce I have ever signed under this key", used to ensure
/// each freshly-signed voucher has a strictly-increasing nonce per the
/// OfflineVault contract.
///
/// Why this is critical for recovery: when a phone is destroyed mid-payment,
/// vouchers it signed at nonces N, N+1, … may still be in flight (sitting in
/// merchants' SQLite, on mesh peers, or queued for settlement). The contract
/// has only seen `lastNonce` up to whatever has been settled — which could be
/// far behind the locally-tracked counter. If the recovered phone resumes at
/// `lastSettledOnChain + 1`, it will collide with an in-flight voucher and
/// the contract will reject one of the two as "stale nonce".
///
/// Fix: back up the local counter alongside the key, and skip ahead by a
/// safe margin on restore so any in-flight voucher's nonce is strictly less
/// than the new phone's next nonce.
class NonceTracker(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("offlinepay_nonce", Context.MODE_PRIVATE)

    fun getLast(): Long = prefs.getLong(KEY_LAST, 0L)

    /// Allocate the next nonce and persist it before returning. Caller MUST
    /// use the returned value — never re-derive from getLast() afterwards.
    ///
    /// Uses `commit()` (synchronous fsync), NOT `apply()`. With apply(), a
    /// crash between this method returning and the background disk write
    /// would revert the in-memory increment on next launch and we'd reuse
    /// a nonce — guaranteeing a "stale nonce" revert at settle time on one
    /// of the colliding vouchers. The ~1ms commit cost is the right
    /// trade-off for nonce safety.
    @Synchronized
    fun next(): Long {
        val n = getLast() + 1
        prefs.edit().putLong(KEY_LAST, n).commit()
        return n
    }

    /// Force the counter to a specific value. Used on key restore to apply
    /// the skip-ahead margin.
    @Synchronized
    fun setTo(n: Long) {
        prefs.edit().putLong(KEY_LAST, n).commit()
    }

    companion object {
        private const val KEY_LAST = "last_used_nonce"

        /// Margin to skip on recovery. Must exceed the maximum number of
        /// vouchers a single device can plausibly have in flight before
        /// reaching the chain. With $5 max locked balance and ~$0.20 per
        /// voucher, the practical ceiling is ~25 — 1000 is a comfortable
        /// safety buffer.
        const val RECOVERY_SKIP = 1_000L
    }
}
