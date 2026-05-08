package com.offlinepay.wallet

import java.math.BigInteger
import java.util.concurrent.atomic.AtomicReference

/// State shared between the Send screen and the HCE service. The Send
/// screen "arms" a single pending payment (amount + expiry); the HCE
/// service consumes it on the next 0xC1 APDU and clears it. A second
/// tap with no rearming returns 6A 82 to the receiver.
object PendingPayment {

    data class Armed(val amountUsdc: BigInteger, val expiry: Long)

    private val current = AtomicReference<Armed?>(null)
    private val errorChannel = AtomicReference<String?>(null)

    fun arm(amountUsdc: BigInteger, ttlSeconds: Long = Config.DEFAULT_TTL_SECONDS) {
        val expiry = System.currentTimeMillis() / 1000 + ttlSeconds
        current.set(Armed(amountUsdc, expiry))
        errorChannel.set(null)
    }

    fun consume(): Armed? = current.getAndSet(null)

    fun cancel() { current.set(null) }

    fun isArmed(): Boolean = current.get() != null

    /// HCE writes a one-shot error string here so the Send UI can surface it.
    fun reportError(msg: String) { errorChannel.set(msg) }
    fun pollError(): String? = errorChannel.getAndSet(null)
}
