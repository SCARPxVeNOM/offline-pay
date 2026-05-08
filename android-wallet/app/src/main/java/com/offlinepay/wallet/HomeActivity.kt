package com.offlinepay.wallet

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.offlinepay.wallet.ui.DashState
import com.offlinepay.wallet.ui.DashboardScreen
import com.offlinepay.wallet.ui.OffpayTheme
import com.offlinepay.wallet.ui.RecentRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.math.BigInteger

private val homeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class HomeActivity : ComponentActivity() {
    private lateinit var keyVault: KeyVault
    private lateinit var received: VoucherStore
    private lateinit var backend: BackendClient
    private lateinit var settle: SettlementClient
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val state = MutableStateFlow(DashState())

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        keyVault = KeyVault(this)
        received = VoucherStore(this)
        backend  = BackendClient(Config.BACKEND_BASE)
        settle   = SettlementClient(
            rpcUrl = Config.RPC_URL, vaultAddress = Config.VAULT_ADDRESS,
            chainId = Config.CHAIN_ID, keyPair = keyVault.keyPair,
            fromAddress = keyVault.address
        )

        state.value = state.value.copy(walletAddress = keyVault.address)

        setContent {
            OffpayTheme {
                val s by state.collectAsState()
                DashboardScreen(
                    state = s,
                    onSend     = { startActivity(Intent(this, SendActivity::class.java)) },
                    onReceive  = { startActivity(Intent(this, ReceiveActivity::class.java)) },
                    onTopup    = { startActivity(Intent(this, TopupActivity::class.java)) },
                    onHistory  = { startActivity(Intent(this, HistoryActivity::class.java)) },
                    onBackup   = { startActivity(Intent(this, BackupRestoreActivity::class.java)) },
                    onSettleNow= { homeScope.launch { autoSettle() } },
                )
            }
        }

        // Live recent activity from local store.
        lifecycleScope.launch {
            received.recent().collect { rows ->
                val mapped = rows.take(5).map { r ->
                    val amt = "%.2f".format(BigInteger(r.amount).toDouble() / 1e6)
                    when (r.status) {
                        "settled"  -> RecentRow("On-chain settle", "INCOMING · ${r.voucherId.take(10)}…", "+ \$$amt", true)
                        "accepted" -> RecentRow("Voucher received", "PENDING SETTLE · NFC", "+ \$$amt", true)
                        else       -> RecentRow("Rejected voucher", "ERROR · ${r.rejectReason ?: ""}", "  \$$amt", false)
                    }
                }
                val pendingCount = rows.count { it.status == "accepted" }
                val pendingTotal = "%.2f".format(
                    rows.filter { it.status == "accepted" }
                        .sumOf { BigInteger(it.amount) }.toDouble() / 1e6
                )
                state.value = state.value.copy(
                    recent = mapped,
                    pendingCount = pendingCount,
                    pendingUsdc = pendingTotal,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerNetCallback()
        if (isOnline()) {
            homeScope.launch {
                runCatching {
                    val locked = settle.lockedBalance(keyVault.address)
                    val str = "%.2f".format(locked.toDouble() / 1e6)
                    state.value = state.value.copy(lockedUsdc = str, syncedSecondsAgo = 0)
                }
                autoSettle()
            }
        } else {
            state.value = state.value.copy(syncedSecondsAgo = null)
        }
    }

    override fun onPause() {
        unregisterNetCallback()
        super.onPause()
    }

    private suspend fun autoSettle() {
        val pending = received.pendingForSettle()
        if (pending.isEmpty()) return
        state.value = state.value.copy(settleStatus = "settling ${pending.size} voucher(s)…")
        try {
            runCatching { backend.init(keyVault.address, 0L) }
            val tx = try {
                settle.settleBearerBatch(pending, keyVault.address)
            } catch (t: Throwable) {
                Log.w(TAG, "direct settle failed, fallback relay: ${t.message}")
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
                if (!resp.ok) error(resp.error ?: "relay failed")
                resp.tx ?: error("relay returned no tx")
            }
            pending.forEach { received.markSettled(it.voucherId, tx) }
            state.value = state.value.copy(settleStatus = "⛓ settled ${pending.size} — tx ${tx.take(10)}…")
        } catch (t: Throwable) {
            Log.e(TAG, "settle failed", t)
            state.value = state.value.copy(settleStatus = "settle failed: ${t.message}")
        }
    }

    private fun registerNetCallback() {
        if (netCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
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
