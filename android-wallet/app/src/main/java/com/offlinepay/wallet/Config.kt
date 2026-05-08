package com.offlinepay.wallet

import java.math.BigInteger

object Config {
    // Polygon Amoy testnet defaults. Replace with deployment outputs.
    // For local Hardhat testing on phones over LAN: 31337L + LAN URL.
    const val CHAIN_ID = 80002L
    const val VAULT_ADDRESS  = "0x0b34C69769efAEf83426e701D0Eba72B638cd818"
    const val USDC_ADDRESS   = "0xFE3BdFF9Da209197b30Ab1A4CcdA240665cf15d0"

    // RPC for direct settle. Public Amoy RPC works for the demo.
    const val RPC_URL        = "https://rpc-amoy.polygon.technology"

    // Backend reachable via `adb reverse tcp:4000 tcp:4000` (USB tunnel).
    // For LAN testing replace with the laptop's IPv4, e.g. http://192.168.x.x:4000.
    const val BACKEND_BASE   = "http://127.0.0.1:4000"

    val MAX_SINGLE_USDC: BigInteger = BigInteger("2000000")  // $2.00, matches contract
    const val DEFAULT_TTL_SECONDS    = 24L * 3600
}
