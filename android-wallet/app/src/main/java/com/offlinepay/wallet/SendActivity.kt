package com.offlinepay.wallet

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SendActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val unspent = UnspentStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        val balanceLbl = TextView(this).apply { textSize = 14f }
        val amountView = EditText(this).apply { hint = "amount in USDC (e.g. 0.50)"; setText("0.50") }
        val payBtn  = Button(this).apply { text = "Arm payment (HCE on)" }
        val qrBtn   = Button(this).apply { text = "Show QR instead" }
        val status  = TextView(this).apply { text = "ready"; textSize = 14f }
        val qrImg   = ImageView(this)
        val hint = TextView(this).apply {
            text = "Vouchers were pre-signed by backend at topup. NFC tap emits one or\n" +
                   "more matching the entered amount. No internet needed at tap time."
            textSize = 12f; setTextColor(0xFF6E7280.toInt()); setPadding(0, 24, 0, 0)
        }

        listOf(balanceLbl, amountView, payBtn, qrBtn, status, qrImg, hint).forEach { root.addView(it) }
        setContentView(root)

        lifecycleScope.launch {
            unspent.unspentTotalFlow().collectLatest { totalBase ->
                balanceLbl.text = "spendable: ${"%.2f".format(totalBase.toDouble() / 1e6)} USDC"
            }
        }

        payBtn.setOnClickListener {
            val baseUnits = parseUsdc(amountView.text.toString()) ?: run {
                status.text = "bad amount"; return@setOnClickListener
            }
            lifecycleScope.launch {
                val pick = unspent.pickForExactAmount(baseUnits) ?: run {
                    status.text = "✗ can't make ${amountView.text} from current denominations — topup more"
                    return@launch
                }
                val payload = cardPayloadsToWireJson(pick.map { it.cardPayload })
                val ids = pick.map { it.voucherId }
                PendingPayment.arm(this@SendActivity, payload, ids)
                status.text = "armed ${pick.size} voucher(s) totaling ${amountView.text} USDC — hold near receiver"

                // Wait for HCE to consume + report spent ids.
                while (PendingPayment.isArmed(this@SendActivity)) delay(200)
                val err = PendingPayment.pollError(this@SendActivity)
                if (err != null) {
                    status.text = "✗ $err"
                } else {
                    val spent = PendingPayment.pollSpent(this@SendActivity)
                    if (spent.isNotEmpty()) unspent.markSpent(spent)
                    status.text = "✓ paid ${amountView.text} USDC"
                }
            }
        }

        qrBtn.setOnClickListener {
            val baseUnits = parseUsdc(amountView.text.toString()) ?: run {
                status.text = "bad amount"; return@setOnClickListener
            }
            lifecycleScope.launch {
                val pick = unspent.pickForExactAmount(baseUnits) ?: run {
                    status.text = "✗ can't make ${amountView.text}"; return@launch
                }
                val payload = cardPayloadsToWireJson(pick.map { it.cardPayload })
                qrImg.setImageBitmap(Qr.render(payload))
                unspent.markSpent(pick.map { it.voucherId })
                status.text = "QR rendered — receiver scans (vouchers marked spent)"
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
