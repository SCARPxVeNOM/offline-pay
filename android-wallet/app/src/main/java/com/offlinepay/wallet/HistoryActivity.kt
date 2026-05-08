package com.offlinepay.wallet

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.math.BigInteger

class HistoryActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val store = VoucherStore(this)
        val tv = TextView(this).apply { textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE }
        val sv = ScrollView(this).apply { addView(tv); setPadding(48, 96, 48, 48) }
        setContentView(sv)
        lifecycleScope.launch {
            store.recent().collectLatest { rows ->
                tv.text = if (rows.isEmpty()) "no vouchers yet"
                else rows.joinToString("\n\n") { r ->
                    """
                    ${"%.2f".format(BigInteger(r.amount).toDouble() / 1e6)} USDC  [${r.status}]
                    id    : ${r.voucherId.take(20)}…
                    payer : ${r.payer.take(10)}…
                    nonce : ${r.nonce}
                    tx    : ${r.settledTx?.take(20) ?: "—"}
                    """.trimIndent()
                }
            }
        }
    }
}
