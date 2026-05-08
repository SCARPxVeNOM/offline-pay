package com.offlinepay.wallet

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigInteger

class SendActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        val amountView = EditText(this).apply { hint = "amount in USDC (e.g. 1.50)"; setText("1.00") }
        val payBtn  = Button(this).apply { text = "Arm payment (HCE on)" }
        val qrBtn   = Button(this).apply { text = "Show QR instead" }
        val status  = TextView(this).apply { text = "ready"; textSize = 14f }
        val qrImg   = ImageView(this)

        listOf(amountView, payBtn, qrBtn, status, qrImg).forEach { root.addView(it) }
        setContentView(root)

        payBtn.setOnClickListener {
            val baseUnits = parseUsdc(amountView.text.toString()) ?: run {
                status.text = "bad amount"; return@setOnClickListener
            }
            PendingPayment.arm(baseUnits)
            status.text = "armed for ${amountView.text} USDC — hold near receiver"
            // Surface async errors from the HCE service.
            lifecycleScope.launch {
                while (PendingPayment.isArmed()) delay(200)
                val err = PendingPayment.pollError()
                status.text = if (err != null) "✗ $err" else "✓ paid ${amountView.text} USDC"
            }
        }

        qrBtn.setOnClickListener {
            // Sender QR path: needs receiver's address first. For MVP keep simple —
            // require user to paste receiver address into amountView's helper later.
            // Here we just sign with merchant=0 (bearer) for the QR demo.
            val baseUnits = parseUsdc(amountView.text.toString()) ?: run {
                status.text = "bad amount"; return@setOnClickListener
            }
            val keyVault = KeyVault(this)
            val nonces = NonceTracker(this)
            val signer = VoucherSigner(
                Config.CHAIN_ID, Config.VAULT_ADDRESS,
                keyVault.keyPair, keyVault.address, nonces
            )
            val signed = signer.signNext(merchant = null, amountUsdc = baseUnits, ttlSeconds = Config.DEFAULT_TTL_SECONDS)
            qrImg.setImageBitmap(Qr.render(signed.toCardJson()))
            status.text = "QR rendered (bearer voucher; receiver scans)"
        }
    }

    private fun parseUsdc(text: String): BigInteger? = try {
        val parts = text.trim().split(".")
        val whole = parts[0].toLong()
        val frac = parts.getOrNull(1)?.padEnd(6, '0')?.take(6)?.toLong() ?: 0L
        BigInteger.valueOf(whole * 1_000_000 + frac)
    } catch (_: Throwable) { null }
}
