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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
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
    private lateinit var activity: ActivityStore
    private lateinit var settle: SettlementClient
    private lateinit var reader: ReaderModeLoop
    private var btBridge: BluetoothBridge? = null
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
        // v3: voucher's `recipient` field is signed by the payer; verifier
        // accepts only vouchers addressed to this device's wallet.
        verifier = VoucherVerifier(
            Config.CHAIN_ID, Config.VAULT_ADDRESS, Config.MAX_SINGLE_USDC,
            store, expectedRecipient = keyVault.address,
        )
        backend  = BackendClient(Config.BACKEND_BASE)
        activity = ActivityStore(this)
        settle   = SettlementClient(
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

        // ESP32 Bluetooth bridge (merchant receive via reader hardware).
        btBridge = try {
            BluetoothBridge(this, scope = lifecycleScope, bondStore = EspBondStore(this)).also { bt ->
                lifecycleScope.launch {
                    bt.vouchers.collect { v ->
                        walletScope.launch { handleIncomingVoucher(v) }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "BT bridge init skipped: ${t.message}")
            null
        }

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
                    val explorer = r.settledTx?.let { Config.txUrl(it) }
                    when (r.status) {
                        "settled"  -> RecentRow(
                            "Settled on chain",
                            "INCOMING · TX ${r.settledTx?.take(10)}…",
                            "+ \$$amt", true, explorerUrl = explorer,
                        )
                        "accepted" -> RecentRow(
                            "Voucher received",
                            "OFFLINE · PENDING SETTLE",
                            "+ \$$amt", true, explorerUrl = null,
                        )
                        else       -> RecentRow(
                            "Rejected voucher",
                            "ERROR · ${r.rejectReason ?: ""}",
                            "  \$$amt", false, explorerUrl = null,
                        )
                    }
                }
                state.value = state.value.copy(
                    recent = mapped,
                    pendingCount = rows.count { it.status == "accepted" },
                )
            }
        }
        // Mirror Home's mesh observability on the Receive screen so the
        // receiver can see the relay path firing while the sender is taps
        // away. peer count drives the status line; events become toasts.
        walletScope.launch {
            WalletMesh.peerCountFlow.collect { n ->
                state.value = state.value.copy(meshPeerCount = n)
            }
        }
        lifecycleScope.launch {
            WalletMesh.events.collect { ev ->
                showMeshToast(ev)
                // For the receiver, settlement-by-relay is THE moment
                // worth showing. Promote it to the prominent status
                // line so the user sees it without scrolling, with the
                // explorer link wired through `recent` once the row's
                // settledTx column is populated by the mesh handler.
                if (ev is MeshEvent.SettledByPeer) {
                    state.value = state.value.copy(
                        status = "⛓ settled by relay — tx ${ev.txHash.take(10)}…",
                        statusKind = StatusKind.Success,
                    )
                }
            }
        }
    }

    private fun showMeshToast(ev: MeshEvent) {
        // Same surface as Home — keep the receiver screen quiet so the
        // user only sees real progress (peer found, relay settled).
        val text = when (ev) {
            is MeshEvent.PeerConnected     -> if (ev.total == 1) "🔗 mesh peer in range" else null
            is MeshEvent.VoucherBroadcast  -> if (ev.peerCount > 0)
                "📤 voucher gossiped to ${ev.peerCount} peer${if (ev.peerCount == 1) "" else "s"}"
                else null
            is MeshEvent.SettledByPeer     -> "✓ settled by relay — tx ${ev.txHash.take(10)}…"
            else -> null
        } ?: return
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private suspend fun handleIncomingVoucher(v: Voucher) {
        val result = verifier.verify(v)
        Log.d(TAG, "BT verify ${v.voucherId.take(10)} -> $result")
        if (result == VerifyResult.VALID) {
            store.saveAccepted(v)
            activity.recordReceived(v.voucherId, v.payer, v.amount.toLong())
            // Gossip to mesh peers so a third (online) device can relay-settle
            // even if both sender and receiver stay offline. No-op if no peers.
            WalletMesh.broadcast(v)
            btBridge?.sendDecision(true)
            val amt = "%.2f".format(v.amount.toDouble() / 1e6)
            state.value = state.value.copy(
                status = "received $amt USDC via ESP32",
                statusKind = StatusKind.Success,
            )
        } else {
            store.saveRejected(v, result.name)
            btBridge?.sendDecision(false)
            state.value = state.value.copy(
                status = "BT voucher rejected: ${result.name}",
                statusKind = StatusKind.Error,
            )
        }
        if (isOnline()) tryAutoSettle()
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
                activity.recordReceived(v.voucherId, v.payer, v.amount.toLong())
                WalletMesh.broadcast(v)
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
        if (!SettleGate.runIfFree { tryAutoSettleInner(force) }) {
            Log.d(TAG, "tryAutoSettle skipped — another settle in flight")
        }
    }

    private suspend fun tryAutoSettleInner(force: Boolean = false) {
        val all = store.pendingForSettle()
        val ZERO = "0x0000000000000000000000000000000000000000"
        // Quarantine non-bearer leftovers.
        all.filter { it.merchant != ZERO }.forEach {
            store.markRejected(it.voucherId, "NOT_BEARER")
            activity.recordFailed("Non-bearer voucher rejected")
        }
        val bearerOnly = all.filter { it.merchant == ZERO }
        Log.d(TAG, "tryAutoSettle pending=${bearerOnly.size} force=$force peers=${WalletMesh.peerCount}")
        if (bearerOnly.isEmpty()) {
            if (force) state.value = state.value.copy(status = "nothing to settle", statusKind = StatusKind.Idle)
            return
        }

        // Same role split as HomeActivity. The Receive screen sees its own
        // accepted vouchers; if a mesh peer is connected, the peer holds
        // the replica and is the relay — we defer. If no peer, settle
        // ourselves (we're the only path). `force=true` (manual "Settle
        // Now") always proceeds so the user can override.
        val accepted = bearerOnly.filter { it.status == "accepted" }
        val replicas = bearerOnly.filter { it.status == "replica" }
        var pending = if (!force && WalletMesh.peerCount > 0) replicas else (replicas + accepted)
        if (pending.isEmpty()) {
            if (accepted.isNotEmpty()) {
                Log.i(TAG, "deferring ${accepted.size} voucher(s) to ${WalletMesh.peerCount} relay peer(s)")
                state.value = state.value.copy(
                    status = "${accepted.size} pending — relay will settle",
                    statusKind = StatusKind.Working,
                )
            } else if (force) {
                state.value = state.value.copy(status = "nothing to settle", statusKind = StatusKind.Idle)
            }
            return
        }

        // Pre-flight: drop vouchers whose payer has no locked funds.
        val live = mutableListOf<VoucherRow>()
        var droppedUnbacked = 0
        for (v in pending) {
            val locked = runCatching { settle.lockedBalance(v.payer) }
                .onFailure { Log.w(TAG, "lockedBalance(${v.payer.take(10)}) failed: ${it.message}") }
                .getOrNull()
            Log.d(TAG, "preflight payer=${v.payer.take(10)} amount=${v.amount} locked=$locked")
            if (locked != null && locked < BigInteger(v.amount)) {
                store.markRejected(v.voucherId, "INSUFFICIENT_LOCKED")
                activity.recordFailed("Sender has no locked funds — voucher dropped")
                droppedUnbacked += 1
            } else {
                live += v
            }
        }
        pending = live
        Log.d(TAG, "preflight result: live=${pending.size} unbacked=$droppedUnbacked")
        if (pending.isEmpty()) {
            state.value = state.value.copy(
                status = "skipped $droppedUnbacked unbacked voucher(s)",
                statusKind = StatusKind.Error,
            )
            return
        }
        if (droppedUnbacked > 0) {
            state.value = state.value.copy(
                status = "skipped $droppedUnbacked unbacked, settling ${pending.size}…",
                statusKind = StatusKind.Working,
            )
        } else {
            state.value = state.value.copy(
                status = "settling ${pending.size} on chain…",
                statusKind = StatusKind.Working,
            )
        }

        try {
            Log.d(TAG, "init for gas top-up")
            runCatching { backend.init(keyVault.address, 0L) }
                .onFailure { Log.w(TAG, "init failed (non-fatal): ${it.message}") }
            Log.d(TAG, "broadcasting settleBearerBatch for ${pending.size}")
            val tx = try {
                settle.settleBearerBatch(pending)
            } catch (t: Throwable) {
                Log.w(TAG, "direct settle failed: ${t.message}", t)
                val isGasIssue = t.message?.contains("insufficient funds") == true ||
                                 t.message?.contains("nonce") == true
                if (isGasIssue) {
                    Log.d(TAG, "falling back to backend relay")
                    relaySettle(pending) ?: throw t
                } else throw t
            }
            Log.d(TAG, "settle tx=$tx")
            pending.forEach {
                store.markSettled(it.voucherId, tx)
                WalletMesh.broadcastSettled(it.voucherId, tx)
            }
            activity.recordSettled(pending.map { it.voucherId }, tx)
            state.value = state.value.copy(status = "⛓ settled ${pending.size} — tx ${tx.take(10)}…",
                statusKind = StatusKind.Success)
        } catch (t: Throwable) {
            Log.e(TAG, "settle failed", t)
            val tag = Errors.rejectTag(t)
            if (tag != "UNKNOWN" && tag != "ERROR") {
                pending.forEach { store.markRejected(it.voucherId, tag) }
            }
            activity.recordFailed(Errors.friendly(t))
            state.value = state.value.copy(status = Errors.friendly(t),
                statusKind = StatusKind.Error)
        }
    }

    private suspend fun relaySettle(pending: List<VoucherRow>): String? {
        val items = pending.map {
            BackendClient.RedeemItem(
                voucher = BackendClient.VoucherFields(
                    payer = it.payer, merchant = it.merchant, recipient = it.recipient,
                    amount = it.amount, expiry = it.expiry,
                    nonce = it.nonce, voucherId = it.voucherId
                ),
                signature = it.signature
            )
        }
        val resp = backend.redeem(items)
        return if (resp.ok) resp.tx else null
    }

    override fun onResume() {
        super.onResume()
        // Keep mesh alive on this screen too — otherwise vouchers we accept
        // here can't be replicated to peers, and a third online device has
        // nothing to relay.
        WalletMesh.acquire(this)
        reader.start()
        btBridge?.connect()
        registerNetCallback()
        if (isOnline()) walletScope.launch { tryAutoSettle() }
    }

    override fun onPause() {
        unregisterNetCallback()
        reader.stop()
        btBridge?.close()
        WalletMesh.release()
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
