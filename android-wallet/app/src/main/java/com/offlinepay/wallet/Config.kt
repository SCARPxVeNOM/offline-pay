package com.offlinepay.wallet

import java.math.BigInteger

object Config {
    // Polygon Amoy testnet.
    const val CHAIN_ID = 80002L
    const val VAULT_ADDRESS  = "0x30b01f8e5Ed5E3b958f0009019fd3f3b9b5d6cE5"
    const val USDC_ADDRESS   = "0x03Ad909F2b68328ED1606dDD894816978A0CE7a1"

    // OFFPAY backend on AWS EC2 (Sydney), dual-stack v4 + v6.
    // Phone networks vary: Airtel/Jio LTE = IPv6-only, home Wi-Fi = IPv4-only.
    // BackendResolver.kt picks whichever URL responds first at startup so
    // the app works on either flavor of network without configuration.
    val BACKEND_CANDIDATES = listOf(
        "http://[2406:da1c:f46:b001:a8e8:aa05:48d4:2a44]",  // IPv6 (works on Airtel/Jio LTE)
        "http://16.176.155.145"                              // IPv4 (works on home Wi-Fi)
    )
    /// Default — overridden at runtime by BackendResolver. Pre-fill with v4
    /// because most Wi-Fi tests will hit it first.
    @Volatile var BACKEND_BASE: String = BACKEND_CANDIDATES[1]

    // RPC goes through backend's /rpc proxy. Computed dynamically because
    // BACKEND_BASE is var — the resolver may flip it between v4 / v6 at
    // startup based on which network the phone is on.
    val RPC_URL: String get() = "$BACKEND_BASE/rpc"

    val MAX_SINGLE_USDC: BigInteger = BigInteger("2000000")  // $2.00, matches contract
    const val DEFAULT_TTL_SECONDS    = 24L * 3600

    /// Block explorer base — used to render live links from receipts.
    const val EXPLORER_BASE = "https://amoy.polygonscan.com"
    fun txUrl(hash: String) = "$EXPLORER_BASE/tx/$hash"
    fun addressUrl(addr: String) = "$EXPLORER_BASE/address/$addr"

    // Mesh (Nearby Connections)
    const val MESH_SERVICE_ID  = "com.offlinepay.wallet.mesh"
    const val CLAIM_WAIT_MS    = 5_000L
    const val CLAIM_BACKOFF_MS = 30_000L

    // Bluetooth SPP (ESP32 reader)
    const val BT_DEVICE_NAME   = "OfflinePay_Reader"
    val BT_SPP_UUID: java.util.UUID = java.util.UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
}
