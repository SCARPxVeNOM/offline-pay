package com.offlinepay.customer

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class MainActivity : AppCompatActivity() {

    private val BACKEND = "http://10.0.2.2:4000"   // emulator → host loopback
    private val client by lazy { TopupClient(BACKEND) }
    private lateinit var vault: KeyVault

    private lateinit var addressView: TextView
    private lateinit var queueView: TextView
    private lateinit var topupAmount: EditText
    private lateinit var issueCount: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vault = KeyVault(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        addressView = TextView(this).apply {
            text = "wallet: ${vault.address}"; textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        topupAmount = EditText(this).apply { hint = "top-up INR"; setText("100") }
        val topupBtn = Button(this).apply { text = "Top up via mock UPI" }
        issueCount = EditText(this).apply { hint = "voucher count"; setText("3") }
        val issueBtn = Button(this).apply { text = "Load vouchers onto phone" }
        queueView = TextView(this).apply {
            text = "queue: 0 vouchers ready to tap"; textSize = 14f
        }
        val tapHint = TextView(this).apply {
            text = "Tap on a merchant phone to pay. " +
                   "Each tap drains one voucher from the queue."
            setPadding(0, 24, 0, 0); textSize = 12f; setTextColor(0xFF6E7280.toInt())
        }

        // Backup banner — only shown if the user has never backed up. Tap
        // opens BackupRestoreActivity. Without a backup, a lost phone =
        // permanently inaccessible USDC, so we nudge prominently.
        val keyPrefs = getSharedPreferences("offlinepay_keys", MODE_PRIVATE)
        val backupBanner = TextView(this).apply {
            text = "⚠ Tap here to back up your wallet — funds at risk if you lose this phone"
            textSize = 13f
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFFFFF4D6.toInt())
            setTextColor(0xFF7A4F00.toInt())
            visibility = if (keyPrefs.getLong(BackupRestoreActivity.KEY_LAST_BACKUP_TS, 0L) == 0L)
                            android.view.View.VISIBLE
                        else android.view.View.GONE
            setOnClickListener {
                startActivity(Intent(this@MainActivity, BackupRestoreActivity::class.java))
            }
        }
        val openBackup = Button(this).apply {
            text = "Wallet backup / restore"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, BackupRestoreActivity::class.java))
            }
        }

        listOf(backupBanner, addressView, topupAmount, topupBtn, issueCount, issueBtn,
               queueView, tapHint, openBackup)
            .forEach { root.addView(it) }
        setContentView(root)

        topupBtn.setOnClickListener {
            lifecycleScope.launch {
                val paise = topupAmount.text.toString().toLongOrNull()?.times(100) ?: 10000L
                val resp = client.topup(vault.address, paise)
                Toast.makeText(
                    this@MainActivity,
                    if (resp.ok) "topped up — UPI ${resp.upiRef}, locked ${resp.amountUsdc}"
                    else "topup failed: ${resp.error}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        issueBtn.setOnClickListener {
            lifecycleScope.launch {
                val n = issueCount.text.toString().toIntOrNull() ?: 3
                val resp = client.issue(vault.address, n, 200000L)
                if (resp.ok) {
                    resp.vouchers.forEach { NextVoucherProvider.push(it.cardPayload) }
                    refreshQueue()
                    Toast.makeText(this@MainActivity, "loaded ${resp.vouchers.size} vouchers",
                        Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "issue failed: ${resp.error}",
                        Toast.LENGTH_LONG).show()
                }
            }
        }

        refreshQueue()
    }

    override fun onResume() { super.onResume(); refreshQueue() }

    private fun refreshQueue() {
        queueView.text = "queue: ${NextVoucherProvider.size()} vouchers ready to tap"
    }
}
