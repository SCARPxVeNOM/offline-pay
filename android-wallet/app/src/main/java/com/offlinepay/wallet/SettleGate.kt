package com.offlinepay.wallet

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/// Process-wide mutex so concurrent triggers (onResume + NetworkCallback +
/// post-tap callback) don't race the same pending voucher into the chain.
/// Two parallel settleBearerBatch broadcasts of the same voucher result in
/// one revert ("already settled") and a confusing UI error.
object SettleGate {
    private val mutex = Mutex()

    /// Run [block] holding the gate. If another caller already holds it,
    /// returns immediately without running — settle is idempotent against
    /// the local store, and one in-flight attempt is enough.
    suspend fun runIfFree(block: suspend () -> Unit): Boolean {
        if (!mutex.tryLock()) return false
        return try {
            block()
            true
        } finally {
            mutex.unlock()
        }
    }
}
