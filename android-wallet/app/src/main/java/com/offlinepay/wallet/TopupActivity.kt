package com.offlinepay.wallet

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.math.BigInteger

class TopupActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val keyVault = KeyVault(this)
        val faucet = FaucetClient(Config.BACKEND_BASE)
        val settle = SettlementClient(
            Config.RPC_URL, Config.VAULT_ADDRESS, Config.CHAIN_ID,
            keyVault.keyPair, keyVault.address
        )

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        val amount = EditText(this).apply { hint = "USDC to top up"; setText("5.00") }
        val btn    = Button(this).apply { text = "Mint + lock" }
        val status = TextView(this).apply { textSize = 14f }
        listOf(amount, btn, status).forEach { root.addView(it) }
        setContentView(root)

        btn.setOnClickListener {
            val baseUnits = parseUsdc(amount.text.toString()) ?: run {
                status.text = "bad amount"; return@setOnClickListener
            }
            lifecycleScope.launch {
                runCatching {
                    status.text = "minting…"
                    val r = faucet.mint(keyVault.address, baseUnits.toString())
                    if (!r.ok) error(r.error ?: "faucet failed")
                    status.text = "approving vault…"
                    settle.approveUsdc(Config.USDC_ADDRESS, Config.VAULT_ADDRESS, baseUnits)
                    status.text = "locking…"
                    val tx = settle.lockFunds(baseUnits)
                    status.text = "✓ locked ${amount.text} USDC — tx ${tx.take(10)}…"
                }.onFailure { status.text = "✗ ${it.message}" }
            }
        }
    }

    private fun parseUsdc(text: String): BigInteger? = try {
        val parts = text.trim().split(".")
        val whole = parts[0].toLong()
        val frac = parts.getOrNull(1)?.padEnd(6, '0')?.take(6)?.toLong() ?: 0L
        BigInteger.valueOf(whole * 1_000_000 + frac)
    } catch (_: Throwable) { null }
}
