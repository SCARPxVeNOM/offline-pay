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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import com.offlinepay.wallet.ui.OffpayTheme
import com.offlinepay.wallet.ui.ReceiveScreen
import com.offlinepay.wallet.ui.ReceiveState
import com.offlinepay.wallet.ui.RecentRow
import com.offlinepay.wallet.ui.StatusKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.math.BigInteger

private val walletScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class ReceiveActivity : ComponentActivity() {
    private lateinit var keyVault: KeyVault
    private lateinit var store: VoucherStore
    private lateinit var verifier: VoucherVerifier
    private lateinit var backend: BackendClient
    private lateinit var settle: SettlementClient
    private lateinit var reader: ReaderModeLoop
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val state = MutableStateFlow(
        ReceiveState(walletAddress = "", status = "scanning… tap a sender phone to your back")
    )

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            val qr = res.data?.getStringExtra("qr") ?: return@registerForActivityResult
            walletScope.launch { handleIncoming(qr); if (isOnline()) tryAutoSettle() }
        }
    }
    private val camPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launcher.launch(Intent(this, QrScanActivity::class.java))
        else state.value = state.value.copy(status = "camera permission denied", statusKind = StatusKind.Error)
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        keyVault = KeyVault(this)
        store    = VoucherStore(this)
        verifier = VoucherVerifier(
            Config.CHAIN_ID, Config.VAULT_ADDRESS, Config.MAX_SINGLE_USDC,
            store, expectedRecipient = "0x0000000000000000000000000000000000000000"
        )
        backend = BackendClient(Config.BACKEND_BASE)
        settle  = SettlementClient(
            rpcUrl = Config.RPC_URL, vaultAddress = Config.VAULT_ADDRESS,
            chainId = Config.CHAIN_ID, keyPair = keyVault.keyPair,
            fromAddress = keyVault.address
        )
        state.value = state.value.copy(walletAddress = keyVault.address)

        reader = ReaderModeLoop(
            this,
            ourAddressHex = keyVault.address,
            onVoucher = { json ->
                walletScope.launch {
                    handleIncoming(json)
                    if (isOnline()) tryAutoSettle()
                }
            },
            onError = { msg -> state.value = state.value.copy(status = msg, statusKind = StatusKind.Error) }
        )

        val qrBitmap = com.offlinepay.wallet.Qr.render(keyVault.address, size = 600)

        setContent {
            OffpayTheme {
                val s by state.collectAsState()
                ReceiveScreen(
                    state = s,
                    qrBitmap = qrBitmap,
                    onSettleNow = { walletScope.launch { tryAutoSettle(force = true) } },
                    onScanQr = {
                        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                                PackageManager.PERMISSION_GRANTED
                        if (granted) launcher.launch(Intent(this, QrScanActivity::class.java))
                        else camPermLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onClose = { finish() },
                )
            }
        }

        // Live recent
        walletScope.launch {
            store.recent().collect { rows ->
                val mapped = rows.take(10).map { r ->
                    val amt = "%.2f".format(BigInteger(r.amount).toDouble() / 1e6)
                    when (r.status) {
                        "settled"  -> RecentRow("Settled on chain", "INCOMING · ${r.voucherId.take(10)}…", "+ \$$amt", true)
                        "accepted" -> RecentRow("Voucher received", "OFFLINE · PENDING SETTLE", "+ \$$amt", true)
                        else       -> RecentRow("Rejected voucher", "ERROR · ${r.rejectReason ?: ""}", "  \$$amt", false)
                    }
                }
                state.value = state.value.copy(
                    recent = mapped,
                    pendingCount = rows.count { it.status == "accepted" },
                )
            }
        }
    }

    private suspend fun handleIncoming(wireJson: String) {
        Log.d(TAG, "handleIncoming raw=$wireJson")
        val list = try {
            Voucher.listFromWireJson(wireJson)
        } catch (t: Throwable) {
            Log.e(TAG, "bad json", t)
            state.value = state.value.copy(status = "bad voucher json: ${t.message}", statusKind = StatusKind.Error)
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
        val summary = if (rejected == 0)
            "✓ received ${"%.2f".format(totalAccepted)} USDC"
        else
            "received ${list.size - rejected}/${list.size} ($rejected rejected)"
        state.value = state.value.copy(status = summary,
            statusKind = if (rejected == 0) StatusKind.Success else StatusKind.Error)
    }

    private suspend fun tryAutoSettle(force: Boolean = false) {
        val pending = store.pendingForSettle()
        Log.d(TAG, "tryAutoSettle pending=${pending.size} force=$force")
        if (pending.isEmpty()) {
            if (force) state.value = state.value.copy(status = "nothing to settle", statusKind = StatusKind.Idle)
            return
        }
        state.value = state.value.copy(status = "settling ${pending.size} on chain…", statusKind = StatusKind.Working)
        try {
            runCatching { backend.init(keyVault.address, 0L) }
            val tx = try {
                settle.settleBearerBatch(pending, keyVault.address)
            } catch (t: Throwable) {
                Log.w(TAG, "direct settle failed, fallback: ${t.message}")
                relaySettle(pending) ?: throw t
            }
            pending.forEach { store.markSettled(it.voucherId, tx) }
            state.value = state.value.copy(status = "⛓ settled ${pending.size} — tx ${tx.take(10)}…",
                statusKind = StatusKind.Success)
        } catch (t: Throwable) {
            Log.e(TAG, "settle failed", t)
            state.value = state.value.copy(status = "settle failed: ${t.message}", statusKind = StatusKind.Error)
        }
    }

    private suspend fun relaySettle(pending: List<VoucherRow>): String? {
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
        return if (resp.ok) resp.tx else null
    }

    override fun onResume() {
        super.onResume()
        reader.start()
        registerNetCallback()
        if (isOnline()) walletScope.launch { tryAutoSettle() }
    }

    override fun onPause() {
        unregisterNetCallback()
        reader.stop()
        super.onPause()
    }

    private fun registerNetCallback() {
        if (netCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
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

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val cap = cm.getNetworkCapabilities(net) ?: return false
        return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object { private const val TAG = "OfflinePay/Receive" }
}
