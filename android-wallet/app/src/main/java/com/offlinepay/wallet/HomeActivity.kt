package com.offlinepay.wallet

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.math.BigInteger

// Process-scoped: auto-settle must outlive the activity if user navigates
// during the redeem call.
private val homeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class HomeActivity : AppCompatActivity() {
    private lateinit var keyVault: KeyVault
    private lateinit var unspent: UnspentStore
    private lateinit var received: VoucherStore
    private lateinit var backend: BackendClient
    private lateinit var addrView: TextView
    private lateinit var balView: TextView
    private lateinit var settleStatus: TextView
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        keyVault = KeyVault(this)
        unspent  = UnspentStore(this)
        received = VoucherStore(this)
        backend  = BackendClient(Config.BACKEND_BASE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        addrView = TextView(this).apply { textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE }
        balView  = TextView(this).apply { textSize = 14f }
        settleStatus = TextView(this).apply { textSize = 12f; setTextColor(0xFF6E7280.toInt()) }
        val sendBtn    = Button(this).apply { text = "Send" }
        val recvBtn    = Button(this).apply { text = "Receive" }
        val topupBtn   = Button(this).apply { text = "Top up" }
        val historyBtn = Button(this).apply { text = "History" }
        val backupBtn  = Button(this).apply { text = "Backup / restore wallet" }

        listOf(addrView, balView, settleStatus, sendBtn, recvBtn, topupBtn, historyBtn, backupBtn)
            .forEach { root.addView(it) }
        setContentView(root)

        addrView.text = "wallet: ${keyVault.address}"

        sendBtn.setOnClickListener    { startActivity(Intent(this, SendActivity::class.java)) }
        recvBtn.setOnClickListener    { startActivity(Intent(this, ReceiveActivity::class.java)) }
        topupBtn.setOnClickListener   { startActivity(Intent(this, TopupActivity::class.java)) }
        historyBtn.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        backupBtn.setOnClickListener  { startActivity(Intent(this, BackupRestoreActivity::class.java)) }

        // Live local-wallet state. No chain RPC.
        lifecycleScope.launch {
            unspent.unspentTotalFlow().combine(received.recent()) { totalBase, rows ->
                val unspentUsdc = "%.2f".format(totalBase.toDouble() / 1e6)
                val pending = rows.count { it.status == "accepted" }
                val pendingTotal = "%.2f".format(
                    rows.filter { it.status == "accepted" }
                        .sumOf { BigInteger(it.amount) }.toDouble() / 1e6
                )
                "spendable: $unspentUsdc USDC\nincoming pending settle: $pending vouchers ($pendingTotal USDC)"
            }.collectLatest { balView.text = it }
        }
    }

    override fun onResume() {
        super.onResume()
        registerNetCallback()
        if (isOnline()) homeScope.launch { autoSettle() }
    }

    override fun onPause() {
        unregisterNetCallback()
        super.onPause()
    }

    private suspend fun autoSettle() {
        val pending = received.pendingForSettle()
        if (pending.isEmpty()) return
        runOnUiThread { settleStatus.text = "settling ${pending.size} pending voucher(s)…" }
        try {
            val items = pending.map {
                BackendClient.RedeemItem(
                    voucher = BackendClient.VoucherFields(
                        payer = it.payer, merchant = it.merchant,
                        amount = it.amount, expiry = it.expiry,
                        nonce = it.nonce, voucherId = it.voucherId
                    ),
                    signature = it.signature
                )
            }
            val resp = backend.redeem(keyVault.address, items)
            if (!resp.ok) {
                runOnUiThread { settleStatus.text = "settle failed: ${resp.error ?: "unknown"}" }
                return
            }
            val tx = resp.tx ?: ""
            val rejectedIds = resp.rejected.map { it.voucherId }.toSet()
            pending.filter { it.voucherId !in rejectedIds }
                .forEach { received.markSettled(it.voucherId, tx) }
            runOnUiThread {
                settleStatus.text = "⛓ settled ${resp.settled} — tx ${tx.take(10)}…"
            }
        } catch (t: Throwable) {
            Log.e(TAG, "settle failed", t)
            runOnUiThread { settleStatus.text = "settle failed: ${t.message}" }
        }
    }

    private fun registerNetCallback() {
        if (netCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "network available -> auto-settle")
                homeScope.launch { autoSettle() }
            }
        }
        cm.registerNetworkCallback(req, cb)
        netCallback = cb
    }

    private fun unregisterNetCallback() {
        val cb = netCallback ?: return
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .unregisterNetworkCallback(cb)
        } catch (_: Throwable) {}
        netCallback = null
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val cap = cm.getNetworkCapabilities(net) ?: return false
        return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object { private const val TAG = "OfflinePay/Home" }
}
