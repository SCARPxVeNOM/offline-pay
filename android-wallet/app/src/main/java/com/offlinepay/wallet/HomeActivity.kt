package com.offlinepay.wallet

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.web3j.crypto.ECKeyPair
import java.math.BigInteger

class HomeActivity : AppCompatActivity() {
    private lateinit var keyVault: KeyVault
    private lateinit var settle: SettlementClient
    private lateinit var addrView: TextView
    private lateinit var balView: TextView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        keyVault = KeyVault(this)
        settle = SettlementClient(
            rpcUrl = Config.RPC_URL, vaultAddress = Config.VAULT_ADDRESS,
            chainId = Config.CHAIN_ID, keyPair = keyVault.keyPair, fromAddress = keyVault.address
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        addrView = TextView(this).apply { textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE }
        balView = TextView(this).apply { textSize = 14f }
        val sendBtn    = Button(this).apply { text = "Send" }
        val recvBtn    = Button(this).apply { text = "Receive" }
        val topupBtn   = Button(this).apply { text = "Top up" }
        val historyBtn = Button(this).apply { text = "History" }
        val backupBtn  = Button(this).apply { text = "Backup / restore wallet" }

        listOf(addrView, balView, sendBtn, recvBtn, topupBtn, historyBtn, backupBtn).forEach { root.addView(it) }
        setContentView(root)

        addrView.text = "wallet: ${keyVault.address}"

        sendBtn.setOnClickListener    { startActivity(Intent(this, SendActivity::class.java)) }
        recvBtn.setOnClickListener    { startActivity(Intent(this, ReceiveActivity::class.java)) }
        topupBtn.setOnClickListener   { startActivity(Intent(this, TopupActivity::class.java)) }
        historyBtn.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        backupBtn.setOnClickListener  { startActivity(Intent(this, BackupRestoreActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val matic = runCatching { settle.maticBalance(keyVault.address) }.getOrDefault(BigInteger.ZERO)
            val locked = runCatching { settle.lockedBalance(keyVault.address) }.getOrDefault(BigInteger.ZERO)
            balView.text = "locked USDC: ${locked.toDouble() / 1e6}\nMATIC (gas): ${matic.toDouble() / 1e18}"
        }
    }
}
