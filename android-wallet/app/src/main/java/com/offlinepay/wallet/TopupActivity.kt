package com.offlinepay.wallet

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class TopupActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val keyVault = KeyVault(this)
        val store = UnspentStore(this)
        val backend = BackendClient(Config.BACKEND_BASE)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        val total = EditText(this).apply { hint = "total USDC"; setText("5.00") }
        val denom = EditText(this).apply { hint = "denomination USDC"; setText("0.50") }
        val btn   = Button(this).apply { text = "Top up" }
        val status = TextView(this).apply { textSize = 14f }
        val hint = TextView(this).apply {
            text = "topup runs online. The backend mints, locks, and pre-signs N bearer\n" +
                   "vouchers of the chosen denomination — phone stores them for offline taps."
            textSize = 12f; setTextColor(0xFF6E7280.toInt()); setPadding(0, 24, 0, 0)
        }
        listOf(total, denom, btn, status, hint).forEach { root.addView(it) }
        setContentView(root)

        btn.setOnClickListener {
            val totalBase = parseUsdc(total.text.toString())
            val denomBase = parseUsdc(denom.text.toString())
            if (totalBase == null || denomBase == null || denomBase <= 0L) {
                status.text = "bad amount"; return@setOnClickListener
            }
            if (totalBase % denomBase != 0L) {
                status.text = "total must be a multiple of denomination"; return@setOnClickListener
            }
            val count = (totalBase / denomBase).toInt()
            if (count <= 0 || count > 50) {
                status.text = "count out of range (1-50)"; return@setOnClickListener
            }
            lifecycleScope.launch {
                status.text = "minting + locking + pre-signing $count vouchers…"
                runCatching {
                    val r = backend.topup(keyVault.address, totalBase, denomBase, count)
                    if (!r.ok) error(r.error ?: "backend rejected")
                    val rows = r.vouchers.map { iv ->
                        UnspentRow(
                            voucherId = iv.voucher.voucherId,
                            amountBaseUnits = iv.voucher.amount.toLong(),
                            cardPayload = iv.cardPayload,
                            addedAtMs = System.currentTimeMillis(),
                            status = "unspent",
                            spentAtMs = null,
                        )
                    }
                    store.add(rows)
                    status.text = "✓ topped up ${total.text} USDC — ${rows.size} × ${denom.text} ready to tap"
                }.onFailure { status.text = "✗ ${it.message}" }
            }
        }
    }

    private fun parseUsdc(text: String): Long? = try {
        val parts = text.trim().split(".")
        val whole = parts[0].toLong()
        val frac = parts.getOrNull(1)?.padEnd(6, '0')?.take(6)?.toLong() ?: 0L
        whole * 1_000_000 + frac
    } catch (_: Throwable) { null }
}
