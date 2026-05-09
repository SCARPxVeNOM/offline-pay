package com.offlinepay.wallet

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
    private lateinit var activity: ActivityStore
    private lateinit var settle: SettlementClient
    private lateinit var mesh: MeshBroadcaster
    private lateinit var handshake: HandshakeManager
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private val state = MutableStateFlow(DashState())

    private val meshPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* mesh.start() will retry once granted */ }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        keyVault = KeyVault(this)
        received = VoucherStore(this)
        backend  = BackendClient(Config.BACKEND_BASE)
        activity = ActivityStore(this)
        settle   = SettlementClient(
            rpcUrl = Config.RPC_URL, vaultAddress = Config.VAULT_ADDRESS,
            chainId = Config.CHAIN_ID, keyPair = keyVault.keyPair,
            fromAddress = keyVault.address
        )

        // Mesh replication (merchant-role feature).
        val registryGate: (String) -> Boolean = { false } // fail-closed; production fills from RPC poll
        handshake = HandshakeManager(
            keyPair = keyVault.keyPair,
            selfAddress = keyVault.address,
            isRegisteredDevice = registryGate,
        )
        val meshVerifier = VoucherVerifier(
            Config.CHAIN_ID, Config.VAULT_ADDRESS, Config.MAX_SINGLE_USDC,
            received, expectedRecipient = "0x0000000000000000000000000000000000000000"
        )
        mesh = MeshBroadcaster(
            ctx = this, store = received, verifier = meshVerifier,
            deviceId = keyVault.address,
            isRegisteredDevice = registryGate,
            handshake = handshake,
        ).also { it.selfAddress = keyVault.address }

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
                    onAddressClick = {
                        startActivity(Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse(Config.addressUrl(keyVault.address))))
                    },
                )
            }
        }

        // Live unified activity feed: topup + sent + received + settled.
        lifecycleScope.launch {
            activity.recent().collect { rows ->
                state.value = state.value.copy(recent = rows.take(20).map { it.toRecentRow() })
            }
        }
        // Pending-receive count comes from the voucher store separately.
        lifecycleScope.launch {
            received.recent().collect { rows ->
                val pendingCount = rows.count { it.status == "accepted" }
                val pendingTotal = "%.2f".format(
                    rows.filter { it.status == "accepted" }
                        .sumOf { BigInteger(it.amount) }.toDouble() / 1e6
                )
                state.value = state.value.copy(
                    pendingCount = pendingCount,
                    pendingUsdc = pendingTotal,
                )
            }
        }
        // Broadcast newly-accepted vouchers on the mesh for replication.
        lifecycleScope.launch {
            val seenIds = mutableSetOf<String>()
            received.recent().collect { rows ->
                for (row in rows) {
                    if (row.status == "accepted" && seenIds.add(row.voucherId)) {
                        val v = Voucher(
                            voucherId = row.voucherId, payer = row.payer,
                            merchant = row.merchant, amount = BigInteger(row.amount),
                            expiry = row.expiry, nonce = row.nonce, signature = row.signature,
                        )
                        mesh.broadcast(v)
                    }
                }
                state.value = state.value.copy(meshPeerCount = mesh.peerCount)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mesh.start()
        ensureMeshPermissions()
        registerNetCallback()
        refreshChainBalance()
        autoSettleIfOnline()
    }

    private fun autoSettleIfOnline() {
        if (isOnline()) homeScope.launch { autoSettle() }
    }

    /// Pulls chain truth: lockedBalance(self) + which sent voucherIds are
    /// already settled. Computes a spendable = locked - in-flight (sent
    /// vouchers not yet seen as `usedVouchers`). Updates UI atomically so
    /// numbers don't race.
    private fun refreshChainBalance() {
        if (!isOnline()) {
            state.value = state.value.copy(syncedSecondsAgo = null)
            return
        }
        homeScope.launch {
            runCatching {
                val locked = settle.lockedBalance(keyVault.address)
                // Look up sent voucherIds we've recorded but never confirmed
                // as settled. Limit to the most recent 50 so we don't blast
                // the RPC for old, almost-certainly-settled entries.
                val sentRows = VoucherDb.get(this@HomeActivity).activityDao().recentList()
                val sentVouchers = sentRows.filter {
                    it.kind == "sent" && it.voucherId != null
                }.take(50)
                val ids = sentVouchers.mapNotNull { it.voucherId }
                val usedMap = if (ids.isNotEmpty()) settle.usedVouchers(ids) else emptyMap()
                val inFlight = sentVouchers
                    .filter { usedMap[it.voucherId] != true }
                    .sumOf { it.amountBaseUnits }
                val lockedLong = locked.toLong()
                val spendableLong = (lockedLong - inFlight).coerceAtLeast(0L)
                state.value = state.value.copy(
                    lockedUsdc    = "%.2f".format(lockedLong / 1e6),
                    inFlightUsdc  = "%.2f".format(inFlight / 1e6),
                    spendableUsdc = "%.2f".format(spendableLong / 1e6),
                    syncedSecondsAgo = 0,
                )
            }.onFailure { Log.w(TAG, "balance refresh failed: ${it.message}") }
        }
    }

    override fun onPause() {
        mesh.stop()
        unregisterNetCallback()
        super.onPause()
    }

    private suspend fun autoSettle() {
        SettleGate.runIfFree { autoSettleInner() }
    }

    private suspend fun autoSettleInner() {
        val all = received.pendingForSettle()
        val ZERO = "0x0000000000000000000000000000000000000000"
        // Quarantine non-bearer leftovers — they can't go through bearer settle.
        all.filter { it.merchant != ZERO }.forEach {
            received.markRejected(it.voucherId, "NOT_BEARER")
            activity.recordFailed("Non-bearer voucher rejected")
        }
        var pending = all.filter { it.merchant == ZERO }
        if (pending.isEmpty()) return

        // Claim-before-settle: announce intent on the mesh and drop any
        // voucher a peer has claimed first.
        val claimed = mutableListOf<VoucherRow>()
        for (v in pending) {
            if (mesh.claimAndWait(v.voucherId)) claimed += v
        }
        val meshSkipped = pending.size - claimed.size
        pending = claimed
        if (meshSkipped > 0) {
            Log.i(TAG, "mesh claim: skipped $meshSkipped claimed by peers")
        }
        if (pending.isEmpty()) {
            state.value = state.value.copy(settleStatus = "all claimed by mesh peers — backing off")
            return
        }

        // Pre-flight: drop any voucher whose payer has insufficient locked funds.
        // This avoids broadcasting a tx that's guaranteed to revert.
        val deadIds = mutableListOf<String>()
        val live = mutableListOf<VoucherRow>()
        for (v in pending) {
            val locked = runCatching { settle.lockedBalance(v.payer) }.getOrNull()
            if (locked != null && locked < BigInteger(v.amount)) {
                received.markRejected(v.voucherId, "INSUFFICIENT_LOCKED")
                activity.recordFailed("Sender has no locked funds — voucher dropped")
                deadIds += v.voucherId
            } else {
                live += v
            }
        }
        pending = live
        if (deadIds.isNotEmpty()) {
            state.value = state.value.copy(
                settleStatus = "skipped ${deadIds.size} unbacked voucher(s)"
            )
        }
        if (pending.isEmpty()) return
        state.value = state.value.copy(settleStatus = "settling ${pending.size} on chain…")

        try {
            runCatching { backend.init(keyVault.address, 0L) }
            val tx = try {
                settle.settleBearerBatch(pending, keyVault.address)
            } catch (t: Throwable) {
                Log.w(TAG, "direct settle failed: ${t.message}")
                // Try backend relay only if the failure is gas-related; on-chain
                // reverts (insufficient locked, expired) will hit the same wall.
                val isGasIssue = t.message?.contains("insufficient funds") == true ||
                                 t.message?.contains("nonce") == true
                if (isGasIssue) {
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
                } else throw t
            }
            pending.forEach { received.markSettled(it.voucherId, tx) }
            activity.recordSettled(pending.map { it.voucherId }, tx)
            state.value = state.value.copy(settleStatus = "⛓ settled ${pending.size} — tx ${tx.take(10)}…")
            // Refresh balances now that chain state changed.
            refreshChainBalance()
        } catch (t: Throwable) {
            Log.e(TAG, "settle failed", t)
            // Mark the offending voucher rejected so we don't keep retrying.
            val tag = Errors.rejectTag(t)
            if (tag != "UNKNOWN" && tag != "ERROR") {
                pending.forEach { received.markRejected(it.voucherId, tag) }
            }
            activity.recordFailed(Errors.friendly(t))
            state.value = state.value.copy(settleStatus = Errors.friendly(t))
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

    private fun ensureMeshPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
            perms += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        meshPermLauncher.launch(perms.toTypedArray())
    }

    companion object { private const val TAG = "OfflinePay/Home" }
}

private fun ActivityRow.toRecentRow(): RecentRow {
    val amt = "%.2f".format(amountBaseUnits.toDouble() / 1e6)
    val ts = relativeTs(ts)
    val explorer = txHash?.let { Config.txUrl(it) }
    val short = counterparty?.let { it.take(6) + "…" + it.takeLast(4) }
    return when (kind) {
        "topup"    -> RecentRow(
            title = "Top-up locked",
            sub = "ON-CHAIN · $ts",
            amountSigned = "+ \$$amt",
            incoming = true,
            explorerUrl = explorer,
        )
        "sent"     -> RecentRow(
            title = "Sent to ${short ?: "—"}",
            sub = "TAPPED · $ts",
            amountSigned = "− \$$amt",
            incoming = false,
            // Sender doesn't broadcast a tx — receiver does. Link to the
            // receiver's address page; their incoming USDC transfers show
            // up there once they settle.
            explorerUrl = counterparty?.let { Config.addressUrl(it) },
        )
        "received" -> RecentRow(
            title = "Received from ${short ?: "—"}",
            sub = "TAPPED · $ts",
            amountSigned = "+ \$$amt",
            incoming = true,
            // Voucher arrived offline — link to the sender's address page so
            // user can see who paid them.
            explorerUrl = counterparty?.let { Config.addressUrl(it) },
        )
        "settled"  -> RecentRow(
            title = note ?: "Settled on chain",
            sub = "TX ${txHash?.take(10) ?: ""}… · $ts",
            amountSigned = "⛓",
            incoming = true,
            explorerUrl = explorer,
        )
        else       -> RecentRow(
            title = note ?: kind,
            sub = ts.uppercase(),
            amountSigned = "",
            incoming = false,
        )
    }
}

private fun relativeTs(ms: Long): String {
    val now = System.currentTimeMillis()
    val diffSec = ((now - ms) / 1000).coerceAtLeast(0)
    return when {
        diffSec < 60     -> "JUST NOW"
        diffSec < 3600   -> "${diffSec / 60} MIN AGO"
        diffSec < 86400  -> "${diffSec / 3600} H AGO"
        diffSec < 604800 -> "${diffSec / 86400} D AGO"
        else             -> "EARLIER"
    }
}
