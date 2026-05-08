package com.offlinepay.wallet

import java.math.BigInteger

object Config {
    // Polygon Amoy testnet defaults. Replace with deployment outputs.
    // For local Hardhat testing on phones over LAN: 31337L + LAN URL.
    const val CHAIN_ID = 80002L
    const val VAULT_ADDRESS  = "0x0000000000000000000000000000000000000000"
    const val USDC_ADDRESS   = "0x0000000000000000000000000000000000000000"

    // RPC for direct settle. Public Amoy RPC works for the demo.
    const val RPC_URL        = "https://rpc-amoy.polygon.technology"

    // Backend (faucet + key backup). Set to LAN IP for two-phone testing.
    const val BACKEND_BASE   = "http://10.0.2.2:4000"

    val MAX_SINGLE_USDC: BigInteger = BigInteger("2000000")  // $2.00, matches contract
    const val DEFAULT_TTL_SECONDS    = 24L * 3600
}
