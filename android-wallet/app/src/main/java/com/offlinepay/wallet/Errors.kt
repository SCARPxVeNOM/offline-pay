package com.offlinepay.wallet

/// Map raw EVM revert reasons + low-level RPC errors to short, human-friendly
/// strings safe to show in the UI. Falls back to a clipped first line.
object Errors {
    fun friendly(t: Throwable): String {
        val msg = t.message ?: return "unknown error"
        // Match common patterns from web3j/ethers reverts and RPC.
        return when {
            "insufficient locked" in msg ->
                "Sender has no funds locked — they need to top up first."
            "already settled" in msg ->
                "Already settled · skipping."
            "expired" in msg ->
                "Voucher expired."
            "bad signature" in msg ->
                "Voucher signature didn't verify."
            "not bearer" in msg ->
                "Wrong voucher type — quarantined."
            "stale nonce" in msg ->
                "Voucher nonce is stale."
            "wrong merchant" in msg ->
                "Voucher recipient mismatch."
            "insufficient funds for gas" in msg || "insufficient funds" in msg ->
                "Wallet needs MATIC for gas — top-up will retry."
            "nonce too low" in msg ->
                "Pending tx in flight — retrying."
            "exceeds cap" in msg ->
                "Amount above contract cap — split into smaller payments."
            "sign failed" in msg || "key" in msg ->
                "Local signing failed — try again."
            else -> msg.lineSequence().firstOrNull()?.take(140) ?: "settle failed"
        }
    }

    /// Same mapping but yields a short tag suitable for storing in the
    /// rejectReason column / activity feed, NOT for the user UI.
    fun rejectTag(t: Throwable): String {
        val msg = t.message ?: return "UNKNOWN"
        return when {
            "insufficient locked" in msg -> "INSUFFICIENT_LOCKED"
            "already settled"     in msg -> "ALREADY_SETTLED"
            "expired"             in msg -> "EXPIRED"
            "bad signature"       in msg -> "BAD_SIGNATURE"
            "not bearer"          in msg -> "NOT_BEARER"
            "stale nonce"         in msg -> "STALE_NONCE"
            "wrong merchant"      in msg -> "WRONG_MERCHANT"
            else -> "ERROR"
        }
    }
}
