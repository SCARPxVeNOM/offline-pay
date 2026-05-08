package com.offlinepay.wallet

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.math.BigInteger

class ReceiveActivity : AppCompatActivity() {
    private lateinit var keyVault: KeyVault
    private lateinit var store: VoucherStore
    private lateinit var verifier: VoucherVerifier
    private lateinit var settle: SettlementClient
    private lateinit var reader: ReaderModeLoop
    private lateinit var status: TextView
    private lateinit var feed: TextView
    private lateinit var addrQr: ImageView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        keyVault = KeyVault(this)
        store    = VoucherStore(this)
        verifier = VoucherVerifier(
            Config.CHAIN_ID, Config.VAULT_ADDRESS, Config.MAX_SINGLE_USDC,
            store, expectedRecipient = keyVault.address
        )
        settle = SettlementClient(
            Config.RPC_URL, Config.VAULT_ADDRESS, Config.CHAIN_ID,
            keyVault.keyPair, keyVault.address
        )

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        val addrView = TextView(this).apply {
            text = "your address (also QR for sender to scan):\n${keyVault.address}"
            textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE
        }
        addrQr = ImageView(this)
        status = TextView(this).apply { text = "scanning… tap a sender phone to your back"; textSize = 14f }
        feed   = TextView(this).apply { textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE }
        val settleBtn = Button(this).apply { text = "Settle now" }
        val scanBtn = Button(this).apply { text = "Scan voucher QR" }

        listOf(addrView, addrQr, status, settleBtn, scanBtn, feed).forEach { root.addView(it) }
        setContentView(root)

        addrQr.setImageBitmap(Qr.render(keyVault.address))

        val launcher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { res ->
            if (res.resultCode == RESULT_OK) {
                val qr = res.data?.getStringExtra("qr") ?: return@registerForActivityResult
                lifecycleScope.launch { handleIncoming(qr); if (isOnline()) tryAutoSettle() }
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
                lifecycleScope.launch {
                    handleIncoming(json)
                    if (isOnline()) tryAutoSettle()
                }
            },
            onError = { msg -> runOnUiThread { status.text = "✗ $msg" } }
        )

        settleBtn.setOnClickListener {
            lifecycleScope.launch { tryAutoSettle(force = true) }
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

    private suspend fun handleIncoming(json: String) {
        val v = try { Voucher.fromCardJson(json) } catch (t: Throwable) {
            runOnUiThread { status.text = "✗ bad voucher json: ${t.message}" }; return
        }
        val result = verifier.verify(v)
        if (result == VerifyResult.VALID) {
            store.saveAccepted(v)
            runOnUiThread { status.text = "✓ received ${"%.2f".format(v.amount.toDouble() / 1e6)} USDC" }
        } else {
            store.saveRejected(v, result.name)
            runOnUiThread { status.text = "✗ rejected: ${result.name}" }
        }
    }

    private suspend fun tryAutoSettle(force: Boolean = false) {
        val pending = store.pendingForSettle()
        if (pending.isEmpty()) {
            if (force) runOnUiThread { status.text = "nothing to settle" }
            return
        }
        runOnUiThread { status.text = "settling ${pending.size} on chain…" }
        try {
            val tx = settle.settleBatch(pending)
            pending.forEach { store.markSettled(it.voucherId, tx) }
            runOnUiThread { status.text = "⛓ settled ${pending.size} — tx ${tx.take(10)}…" }
        } catch (t: Throwable) {
            runOnUiThread { status.text = "settle failed: ${t.message}" }
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val cap = cm.getNetworkCapabilities(net) ?: return false
        return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun onResume() { super.onResume(); reader.start() }
    override fun onPause()  { reader.stop(); super.onPause() }
}
