package com.offlinepay.wallet

import java.math.BigInteger

object Config {
    // Polygon Amoy testnet defaults. Replace with deployment outputs.
    // For local Hardhat testing on phones over LAN: 31337L + LAN URL.
    const val CHAIN_ID = 80002L
    const val VAULT_ADDRESS  = "0x30b01f8e5Ed5E3b958f0009019fd3f3b9b5d6cE5"
    const val USDC_ADDRESS   = "0x03Ad909F2b68328ED1606dDD894816978A0CE7a1"

    // RPC unused on the phone in custodial mode — backend does all chain ops.
    const val RPC_URL        = "https://polygon-amoy.infura.io/v3/63a92704fd4c46b5957bf6a6764f21d2"

    // Backend reachable via `adb reverse tcp:4000 tcp:4000` (USB tunnel).
    // For LAN testing replace with the laptop's IPv4, e.g. http://192.168.x.x:4000.
    const val BACKEND_BASE   = "http://127.0.0.1:4000"

    val MAX_SINGLE_USDC: BigInteger = BigInteger("2000000")  // $2.00, matches contract
    const val DEFAULT_TTL_SECONDS    = 24L * 3600

    /// Block explorer base — used to render live links from receipts.
    const val EXPLORER_BASE = "https://amoy.polygonscan.com"
    fun txUrl(hash: String) = "$EXPLORER_BASE/tx/$hash"
    fun addressUrl(addr: String) = "$EXPLORER_BASE/address/$addr"
}
