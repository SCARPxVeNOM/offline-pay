package com.offlinepay.wallet

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.math.BigInteger

// Process-scoped: outlives ReceiveActivity. Critical for the receive path —
// Room writes must finish even if the user backs out or screen-off pauses
// the activity before the suspend `saveAccepted` completes.
private val walletScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class ReceiveActivity : AppCompatActivity() {
    private lateinit var keyVault: KeyVault
    private lateinit var store: VoucherStore
    private lateinit var verifier: VoucherVerifier
    private lateinit var backend: BackendClient
    private lateinit var reader: ReaderModeLoop
    private lateinit var status: TextView
    private lateinit var feed: TextView
    private lateinit var addrQr: ImageView
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        keyVault = KeyVault(this)
        store    = VoucherStore(this)
        // Bearer-only flow: vouchers carry merchant=0x0, so any receiver
        // can claim. WRONG_RECIPIENT check is therefore disabled here.
        verifier = VoucherVerifier(
            Config.CHAIN_ID, Config.VAULT_ADDRESS, Config.MAX_SINGLE_USDC,
            store, expectedRecipient = "0x0000000000000000000000000000000000000000"
        )
        backend = BackendClient(Config.BACKEND_BASE)

        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        val root = ScrollView(this).apply { addView(inner) }
        val addrView = TextView(this).apply {
            text = "your address (also QR for sender to scan):\n${keyVault.address}"
            textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE
        }
        addrQr = ImageView(this).apply {
            // Cap QR size so the buttons below stay on screen.
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 480
            )
        }
        status = TextView(this).apply {
            text = "scanning… tap a sender phone to your back"; textSize = 14f
        }
        feed   = TextView(this).apply { textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE }
        val settleBtn = Button(this).apply { text = "Settle now (online)" }
        val scanBtn = Button(this).apply { text = "Scan voucher QR" }

        listOf(addrView, status, settleBtn, scanBtn, addrQr, feed).forEach { inner.addView(it) }
        setContentView(root)

        addrQr.setImageBitmap(Qr.render(keyVault.address))

        val launcher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { res ->
            if (res.resultCode == RESULT_OK) {
                val qr = res.data?.getStringExtra("qr") ?: return@registerForActivityResult
                walletScope.launch { handleIncoming(qr); if (isOnline()) tryAutoSettle() }
            }
        }
        val camPermLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) launcher.launch(Intent(this, QrScanActivity::class.java))
            else runOnUiThread { status.text = "✗ camera permission denied" }
        }
        scanBtn.setOnClickListener {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) launcher.launch(Intent(this, QrScanActivity::class.java))
            else camPermLauncher.launch(Manifest.permission.CAMERA)
        }

        reader = ReaderModeLoop(
            this,
            ourAddressHex = keyVault.address,
            onVoucher = { json ->
                // Save on the process-scoped IO scope so it completes even if
                // the activity gets paused/destroyed during the suspend insert.
                walletScope.launch {
                    handleIncoming(json)
                    if (isOnline()) tryAutoSettle()
                }
            },
            onError = { msg -> runOnUiThread { status.text = "✗ $msg" } }
        )

        settleBtn.setOnClickListener {
            walletScope.launch { tryAutoSettle(force = true) }
        }

        lifecycleScope.launch {
            store.recent().collectLatest { rows ->
                feed.text = rows.joinToString("\n") { r ->
                    val st = when (r.status) { "accepted" -> "✓"; "settled" -> "⛓"; else -> "✗" }
                    "$st  ${"%.2f".format(BigInteger(r.amount).toDouble() / 1e6)}  ${r.voucherId.take(10)}…  ${r.status}"
                }
            }
        }
    }

    private suspend fun handleIncoming(wireJson: String) {
        Log.d(TAG, "handleIncoming raw=$wireJson")
        val list = try {
            Voucher.listFromWireJson(wireJson)
        } catch (t: Throwable) {
            Log.e(TAG, "bad json", t)
            runOnUiThread { status.text = "✗ bad voucher json: ${t.message}" }
            return
        }
        var totalAccepted = 0.0
        var rejected = 0
        for (v in list) {
            val result = verifier.verify(v)
            Log.d(TAG, "verify ${v.voucherId.take(10)} -> $result")
            if (result == VerifyResult.VALID) {
                store.saveAccepted(v)
                totalAccepted += v.amount.toDouble() / 1e6
            } else {
                store.saveRejected(v, result.name)
                rejected += 1
            }
        }
        runOnUiThread {
            status.text = if (rejected == 0)
                "✓ received ${"%.2f".format(totalAccepted)} USDC (${list.size} voucher${if (list.size==1) "" else "s"})"
            else
                "⚠ received ${list.size - rejected} of ${list.size} (${"%.2f".format(totalAccepted)} USDC; $rejected rejected)"
        }
    }

    private suspend fun tryAutoSettle(force: Boolean = false) {
        val pending = store.pendingForSettle()
        Log.d(TAG, "tryAutoSettle pending=${pending.size} force=$force")
        if (pending.isEmpty()) {
            if (force) runOnUiThread { status.text = "nothing to settle" }
            return
        }
        runOnUiThread { status.text = "settling ${pending.size} via backend…" }
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
                runOnUiThread { status.text = "settle failed: ${resp.error ?: "unknown"}" }
                return
            }
            val tx = resp.tx ?: ""
            // Mark only the ones the backend actually settled. Rejected ones stay as 'accepted'.
            val rejectedIds = resp.rejected.map { it.voucherId }.toSet()
            val settledRows = pending.filter { it.voucherId !in rejectedIds }
            settledRows.forEach { store.markSettled(it.voucherId, tx) }
            runOnUiThread {
                status.text = if (resp.rejected.isEmpty())
                    "⛓ settled ${resp.settled} — tx ${tx.take(10)}…"
                else
                    "⛓ settled ${resp.settled}, rejected ${resp.rejected.size} — tx ${tx.take(10)}…"
            }
        } catch (t: Throwable) {
            Log.e(TAG, "settle failed", t)
            runOnUiThread { status.text = "settle failed: ${t.message}" }
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val cap = cm.getNetworkCapabilities(net) ?: return false
        return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onResume() {
        super.onResume()
        reader.start()
        registerNetCallback()
        // If we're already online and have pending vouchers, settle them now.
        if (isOnline()) walletScope.launch { tryAutoSettle() }
    }

    override fun onPause()  {
        unregisterNetCallback()
        reader.stop()
        super.onPause()
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
                walletScope.launch { tryAutoSettle() }
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

    companion object { private const val TAG = "OfflinePay/Receive" }
}
