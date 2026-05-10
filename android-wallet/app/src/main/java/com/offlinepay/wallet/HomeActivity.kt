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
import android.widget.Toast
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
    private lateinit var balanceCache: BalanceCache
    private lateinit var espBondStore: EspBondStore
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
        balanceCache = BalanceCache(this)
        espBondStore = EspBondStore(this)

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
                    onEsp = { startActivity(Intent(this, EspActivity::class.java)) },
                )
            }
        }

        // Live unified activity feed: topup + sent + received + settled.
        // Also re-render the balance card on every change so the moment
        // the user signs a voucher (sent row inserted) the spendable
        // figure drops, even with no internet.
        lifecycleScope.launch {
            activity.recent().collect { rows ->
                state.value = state.value.copy(recent = rows.take(20).map { it.toRecentRow() })
                renderBalance()
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
        // We re-broadcast every existing accepted row on each tick to cover
        // the case where a peer joined AFTER the row was first inserted —
        // mesh.broadcast is a no-op when there are no peers, so it's safe.
        // Also kick auto-settle whenever a `replica` row appears: this is
        // how a relay device picks up vouchers received via mesh from an
        // offline pair and pushes settle on their behalf.
        lifecycleScope.launch {
            var lastReplicaIds = emptySet<String>()
            received.recent().collect { rows ->
                for (row in rows) {
                    if (row.status == "accepted") {
                        val v = Voucher(
                            voucherId = row.voucherId, payer = row.payer,
                            merchant = row.merchant, recipient = row.recipient,
                            amount = BigInteger(row.amount),
                            expiry = row.expiry, nonce = row.nonce, signature = row.signature,
                            cardUid = row.cardUid,
                        )
                        // Re-broadcast bearer rows with their endorsement
                        // so a relay peer can call settleBearerWithEndorsement
                        // even if we're offline. Recipient-bound rows have
                        // null endorsement fields → no-op.
                        val endorsement = if (
                            row.endorsementSig != null && row.endorsementPrimary != null &&
                            row.endorsementDevice != null && row.endorsementTs != null
                        ) Endorsement(
                            timestamp        = row.endorsementTs,
                            merchantPrimary  = row.endorsementPrimary,
                            deviceAddress    = row.endorsementDevice,
                            signature        = row.endorsementSig,
                        ) else null
                        WalletMesh.broadcast(v, endorsement)
                    }
                }
                val replicaIds = rows.filter { it.status == "replica" }.map { it.voucherId }.toSet()
                val newReplicas = replicaIds - lastReplicaIds
                lastReplicaIds = replicaIds
                if (newReplicas.isNotEmpty()) {
                    Log.d(TAG, "mesh delivered ${newReplicas.size} new replica(s) — kicking autoSettle")
                    autoSettleIfOnline()
                }
            }
        }
        // Live mesh peer count → state. Drives the always-visible mesh
        // status banner; without this collect, the banner reads 0 forever
        // even when a peer is connected.
        lifecycleScope.launch {
            WalletMesh.peerCountFlow.collect { n ->
                state.value = state.value.copy(meshPeerCount = n)
            }
        }
        // Toast on every observable mesh transition. The user can SEE the
        // relay path firing on each phone — no more "is mesh even running?"
        // guessing. Also: when a peer tells us a voucher settled, drop it
        // from in-flight and re-render — spendable rises instantly even
        // if we still have no internet ourselves.
        lifecycleScope.launch {
            WalletMesh.events.collect { ev ->
                showMeshToast(ev)
                if (ev is MeshEvent.SettledByPeer) {
                    balanceCache.markSettled(ev.voucherId)
                    renderBalance()
                }
            }
        }
        // Two cadences:
        //  • Every 1s: renderBalance() — recomputes spendable from the
        //    cache + ledger, refreshes the "synced Xs ago" label.
        //    Cheap (SharedPreferences + ~50 ledger rows, no RPC).
        //  • Every 8s: refreshChainBalance() — pulls lockedBalance(self)
        //    + usedVouchers(sentIds) from chain so on-screen "ON CHAIN"
        //    matches reality. Online-only; no-op when offline.
        // Together: the user sees a balance that updates the instant a
        // voucher is signed (in-flight goes up locally), the instant a
        // settle lands (in-flight goes down via chain refresh OR mesh
        // event), and the freshness label is always honest.
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                renderBalance()
            }
        }
        lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(8_000)
                if (isOnline()) refreshChainBalance()
            }
        }
        // ESP bond → Dash state. Reflects pairing in real time so the
        // Reader pill on Home updates the moment EspActivity returns.
        lifecycleScope.launch {
            espBondStore.stateFlow.collect { bond ->
                state.value = state.value.copy(
                    espPairedAddress = bond.espAddress,
                    espLastSeenMs    = bond.lastSeenMs,
                )
            }
        }
    }

    private fun showMeshToast(ev: MeshEvent) {
        // Trimmed surface — only the milestones a user actually cares
        // about. Everything else stays in logcat for diagnostics.
        val text = when (ev) {
            is MeshEvent.PeerConnected     -> if (ev.total == 1) "🔗 mesh peer connected" else null
            is MeshEvent.ReplicaStored     -> "📥 voucher arrived via mesh"
            is MeshEvent.SettledByPeer     -> "✓ relay settled — tx ${ev.txHash.take(10)}…"
            is MeshEvent.AdvertiseFailed   -> "mesh advertise failed: ${ev.reason}"
            else -> null
        } ?: return
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() {
        super.onResume()
        WalletMesh.acquire(this)
        ensureMeshPermissions()
        registerNetCallback()
        refreshChainBalance()
        autoSettleIfOnline()
    }

    private fun autoSettleIfOnline() {
        if (isOnline()) homeScope.launch { autoSettle() }
    }

    /// Pulls chain truth and rolls it into the cache. Online-only. After
    /// success, every renderBalance() call (which is on every send,
    /// receive, mesh `settled`, and screen resume) will see fresh
    /// numbers without another RPC. On failure or no internet, we just
    /// fall through to the cached values — UI stays live.
    private fun refreshChainBalance() {
        renderBalance()
        if (!isOnline()) return
        homeScope.launch {
            runCatching {
                val locked = settle.lockedBalance(keyVault.address)
                balanceCache.lockedBalance = locked
                balanceCache.lastSyncedMs = System.currentTimeMillis()
                // Look up sent voucherIds we've recorded but never confirmed
                // as settled. Limit to the most recent 50 so we don't blast
                // the RPC for old, almost-certainly-settled entries.
                val sentRows = VoucherDb.get(this@HomeActivity).activityDao().recentList()
                val sentVouchers = sentRows.filter {
                    it.kind == "sent" && it.voucherId != null
                }.take(50)
                val ids = sentVouchers.mapNotNull { it.voucherId }
                val usedMap = if (ids.isNotEmpty()) settle.usedVouchers(ids) else emptyMap()
                balanceCache.markAllSettled(usedMap.filterValues { it == true }.keys)
                renderBalance()
            }.onFailure { Log.w(TAG, "balance refresh failed: ${it.message}") }
        }
    }

    /// Synchronous, offline-safe balance compute. Reads cached locked
    /// balance + the activity ledger; subtracts in-flight (sent vouchers
    /// not yet known-settled, where "known" = chain OR mesh). Cheap
    /// enough to call on every flow tick — no chain access.
    private fun renderBalance() {
        homeScope.launch {
            val locked = balanceCache.lockedBalance
            val sentRows = VoucherDb.get(this@HomeActivity).activityDao().recentList()
            val sentVouchers = sentRows.filter { it.kind == "sent" && it.voucherId != null }.take(50)
            val inFlight = sentVouchers
                .filter { !balanceCache.isSettled(it.voucherId!!) }
                .sumOf { it.amountBaseUnits }
            val lockedLong = locked.toLong()
            val spendableLong = (lockedLong - inFlight).coerceAtLeast(0L)
            val syncedAgo = balanceCache.lastSyncedMs.let { ts ->
                if (ts == 0L) null
                else ((System.currentTimeMillis() - ts) / 1000).toInt().coerceAtLeast(0)
            }
            state.value = state.value.copy(
                lockedUsdc    = "%.2f".format(lockedLong / 1e6),
                inFlightUsdc  = "%.2f".format(inFlight / 1e6),
                spendableUsdc = "%.2f".format(spendableLong / 1e6),
                syncedSecondsAgo = syncedAgo,
            )
        }
    }

    override fun onPause() {
        WalletMesh.release()
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
        val bearerOnly = all.filter { it.merchant == ZERO }
        if (bearerOnly.isEmpty()) return

        // Role split. Vouchers I received via NFC live as `accepted`; vouchers
        // a peer broadcast to me over the mesh live as `replica`. They are
        // for the SAME on-chain voucher but represent different roles —
        // recipient vs relay. If both roles try to settle, we get the dual-
        // claim deadlock observed in the logs (each side back-offs on the
        // other's claim, voucher never settles).
        //
        // New rule:
        //   - Replicas (relay role) — always settle when online. This is
        //     literally the relay's job.
        //   - Accepted (recipient role) — settle only if NO peer is
        //     connected (we're the only path). If a peer is around, defer:
        //     the peer is the more-likely-online relay.
        //
        // This eliminates the deadlock without giving up correctness — the
        // contract's usedVouchers mapping is still the safety net if both
        // roles ever did broadcast.
        val accepted = bearerOnly.filter { it.status == "accepted" }
        val replicas = bearerOnly.filter { it.status == "replica" }
        var pending = if (WalletMesh.peerCount > 0) replicas else (replicas + accepted)
        if (pending.isEmpty()) {
            if (accepted.isNotEmpty()) {
                Log.i(TAG, "deferring ${accepted.size} accepted voucher(s) to ${WalletMesh.peerCount} relay peer(s)")
                state.value = state.value.copy(
                    settleStatus = "${accepted.size} pending — relay will settle",
                )
            }
            return
        }

        // Reachability probe. Android can report a network as "online" when
        // it has a default route but no actual path to our backend (joined
        // a captive Wi-Fi, or the network is IPv6-only and the host's IPv4
        // is unreachable). lockedBalance is a cheap RPC; if it can't even
        // get out, settling will fail with ENETUNREACH after a long
        // timeout. If we're alone, retry later. If a mesh peer is
        // connected, defer entirely — the relay (an actually-online phone)
        // will settle.
        val rpcReachable = runCatching { settle.lockedBalance(keyVault.address) }
            .onFailure { Log.w(TAG, "rpc probe failed: ${it.message}") }
            .isSuccess
        if (!rpcReachable) {
            if (WalletMesh.peerCount > 0) {
                Log.i(TAG, "rpc unreachable, deferring ${pending.size} voucher(s) to ${WalletMesh.peerCount} mesh peer(s)")
                state.value = state.value.copy(
                    settleStatus = "no internet — ${WalletMesh.peerCount} relay will settle",
                )
            } else {
                Log.i(TAG, "rpc unreachable, no mesh peers — will retry later")
                state.value = state.value.copy(settleStatus = "offline — will retry")
            }
            return
        }

        // No claim-and-wait. The role split above means the relay is the
        // only one trying to settle for any given voucher, so claim
        // negotiation has nothing to negotiate. We were observing live
        // deadlocks from stale claim messages (asymmetric peer visibility,
        // residual state from prior test runs). If multiple relays ever
        // race, the contract's `usedVouchers` mapping rejects the loser —
        // wasted gas, but no deadlock and no double-settle.

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
            // Split into three groups by what contract path applies:
            //   1. true-bearer (recipient=0) WITH endorsement → settleBearerWithEndorsement
            //   2. true-bearer (recipient=0) WITHOUT endorsement → unsettleable, quarantine
            //   3. recipient-bound (recipient!=0) → settleBearerBatch
            // Without #2's quarantine, a stale endorsement-less bearer
            // row (e.g. from a pre-B2 APK) keeps cycling through the
            // wrong contract path and reverting forever.
            val ZERO = "0x0000000000000000000000000000000000000000"
            val (endorsed, unendorsedBearer, recipientBound) = run {
                val a = mutableListOf<VoucherRow>()
                val b = mutableListOf<VoucherRow>()
                val c = mutableListOf<VoucherRow>()
                for (row in pending) {
                    when {
                        row.recipient == ZERO && row.endorsementSig != null -> a += row
                        row.recipient == ZERO                               -> b += row
                        else                                                -> c += row
                    }
                }
                Triple(a, b, c)
            }
            // Quarantine unendorsable bearer rows so they don't keep
            // burning gas on settleBearerBatch reverts.
            for (row in unendorsedBearer) {
                Log.w(TAG, "unsettleable bearer voucher (no endorsement): ${row.voucherId}")
                received.markRejected(row.voucherId, "BEARER_NEEDS_ENDORSEMENT")
                activity.recordFailed("Bearer voucher needs reader-tap to settle")
            }
            for (row in endorsed) {
                // Dry-run the call first. If the contract would revert, we
                // get the precise reason from eth_call without spending any
                // gas. Surface it in the activity feed and quarantine the
                // row so we don't loop on the same bad input.
                val reason = settle.preflightBearerWithEndorsement(row)
                if (reason != null) {
                    Log.w(TAG, "preflight rejected ${row.voucherId.take(10)}: $reason")
                    received.markRejected(row.voucherId, "PREFLIGHT_FAIL")
                    activity.recordFailed("Settle would revert: $reason")
                    state.value = state.value.copy(settleStatus = "blocked: $reason")
                    continue
                }
                val tx = settle.settleBearerWithEndorsement(row)
                received.markSettled(row.voucherId, tx)
                WalletMesh.broadcastSettled(row.voucherId, tx)
                balanceCache.markSettled(row.voucherId)
                activity.recordSettled(listOf(row.voucherId), tx)
                Log.i(TAG, "endorsed bearer settled: ${row.voucherId.take(10)} tx=${tx.take(10)}")
            }
            if (recipientBound.isEmpty()) {
                renderBalance()
                // Pull on-chain truth right after a settle so the
                // "ON CHAIN $X" line drops to the new locked figure
                // instantly, not after the next 8s tick.
                if (endorsed.isNotEmpty()) refreshChainBalance()
                if (endorsed.isNotEmpty() || unendorsedBearer.isNotEmpty()) {
                    state.value = state.value.copy(
                        settleStatus = "⛓ settled ${endorsed.size}; quarantined ${unendorsedBearer.size}")
                }
                return
            }
            // Below: existing recipient-bound batch path.
            pending = recipientBound
            val tx = try {
                settle.settleBearerBatch(pending)
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
                                payer = it.payer, merchant = it.merchant, recipient = it.recipient,
                                amount = it.amount, expiry = it.expiry,
                                nonce = it.nonce, voucherId = it.voucherId
                            ),
                            signature = it.signature
                        )
                    }
                    val resp = backend.redeem(items)
                    if (!resp.ok) error(resp.error ?: "relay failed")
                    resp.tx ?: error("relay returned no tx")
                } else throw t
            }
            pending.forEach {
                received.markSettled(it.voucherId, tx)
                // Tell the offline recipient that their voucher landed.
                // No-op if we have no peers; harmless if we settled an
                // accepted row of our own (we'd just echo to ourselves
                // which the dedup set drops).
                WalletMesh.broadcastSettled(it.voucherId, tx)
                // Also drop these from our local in-flight set so the
                // spendable figure updates without waiting for the next
                // chain refresh.
                balanceCache.markSettled(it.voucherId)
            }
            activity.recordSettled(pending.map { it.voucherId }, tx)
            renderBalance()
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
                Log.d(TAG, "network available -> auto-settle + balance refresh")
                homeScope.launch { autoSettle() }
                // Immediately re-pull chain truth so the "ON CHAIN"
                // figure stops being stale the moment the user comes
                // back online — don't wait for the 8s ticker.
                refreshChainBalance()
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
            // BLUETOOTH_ADVERTISE is required for the relay device — it has
            // to advertise so offline peers can discover it. Without this
            // grant, Nearby Connections returns ApiException 8038 from
            // startAdvertising and the relay can only act as discoverer.
            perms += Manifest.permission.BLUETOOTH_ADVERTISE
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
