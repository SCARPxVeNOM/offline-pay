package com.offlinepay.wallet

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/// User-visible mesh state transitions. Emitted on a SharedFlow so activities
/// can show toasts/badges and the user can see the relay actually doing work.
sealed class MeshEvent {
    data class PeerConnected(val endpointId: String, val total: Int) : MeshEvent()
    data class PeerDisconnected(val endpointId: String, val total: Int) : MeshEvent()
    data class VoucherBroadcast(val voucherId: String, val peerCount: Int) : MeshEvent()
    data class ReplicaStored(val voucherId: String, val from: String) : MeshEvent()
    data class ReplicaRejected(val voucherId: String, val reason: String) : MeshEvent()
    data class ClaimSent(val voucherId: String, val peerCount: Int) : MeshEvent()
    data class ClaimReceived(val voucherId: String, val from: String) : MeshEvent()
    /// Relay (or any other peer) settled on chain and told us about it.
    /// Used by the offline recipient to flip its UI from "pending settle"
    /// to "settled · tx 0x…" without ever touching the chain itself.
    data class SettledByPeer(val voucherId: String, val txHash: String) : MeshEvent()
    data class AdvertiseFailed(val reason: String) : MeshEvent()
    data class DiscoverFailed(val reason: String) : MeshEvent()
}

/// Mesh replication over Nearby Connections (BLE + WiFi-Direct).
///
/// Why: a voucher only exists on the merchant phone until the next time it
/// reaches the internet. If that phone is lost, the customer's funds stay
/// locked on-chain with no way to claim them. By gossiping each accepted
/// voucher to nearby peers (other merchant phones, the customer's own phone,
/// any ESP32 gateway in range) we make every nearby device a backup node.
///
/// Strategy = P2P_CLUSTER (full mesh, not star). Service ID is the package
/// id so only OfflinePay devices peer with each other.
class MeshBroadcaster(
    private val ctx: Context,
    private val store: VoucherStore,
    private val verifier: VoucherVerifier,
    private val deviceId: String,
    /// Sybil-resistance gate: returns true iff the supplied address resolves
    /// to a registered merchant device on-chain. Used by the handshake to
    /// reject peers that aren't on the registry.
    private val isRegisteredDevice: (String) -> Boolean = { false },
    /// Optional handshake module. When non-null, ACKs and replicas are only
    /// accepted from peers that have completed challenge-response. When
    /// null, the broadcaster runs in trust-all dev mode (NOT for pilot).
    private val handshake: HandshakeManager? = null,
) {
    private val client = Nearby.getConnectionsClient(ctx)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connected = mutableSetOf<String>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /// Maps endpointId → handshake-verified peer address. Lets us dedupe
    /// the peer count by address: Nearby Connections gives a fresh
    /// endpointId per medium (BLE, BT-Classic, Wi-Fi LAN, Wi-Fi Direct),
    /// so the same physical phone can show up two or three times in
    /// `connected`. Counting by address gives the user a number that
    /// matches reality (1 nearby phone = "1 peer").
    private val endpointAddress = ConcurrentHashMap<String, String>()
    /// voucherId → set of peer addresses we've received an ACK from. We
    /// stop retrying once the set is non-empty (any single peer holding
    /// the replica is sufficient for the demo).
    private val ackedBy = ConcurrentHashMap<String, MutableSet<String>>()
    /// voucherIds we've seen a "settled" message for. Prevents duplicate
    /// SettledByPeer events when multiple peers broadcast settlement
    /// (e.g. relay + a backup that also tried).
    private val seenSettled: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /// voucherId → (Voucher, optional Endorsement) bundle we still need
    /// to gossip. Drained as ACKs arrive and as new peers connect. This
    /// is the durable retry queue that papers over Nearby Connections'
    /// silent payload drops on flaky channels (BLE-only handshake
    /// without a Wi-Fi/BT-classic upgrade).
    ///
    /// The optional endorsement is what makes relay-settle work for
    /// bearer cards: when a merchant phone is offline but a peer is
    /// online, the peer can submit `settleBearerWithEndorsement` on
    /// the merchant's behalf only if it has the endorsement bundle.
    private data class PendingItem(val v: Voucher, val e: Endorsement?)
    private val pendingBroadcast = ConcurrentHashMap<String, PendingItem>()

    private val _peerCountFlow = MutableStateFlow(0)
    /// Live peer count. Updated synchronously on every connect/disconnect so
    /// the UI's mesh-status pill reflects reality without polling.
    val peerCountFlow: StateFlow<Int> = _peerCountFlow.asStateFlow()

    /// SharedFlow of human-meaningful mesh state transitions. Replay=0,
    /// extraBufferCapacity is generous so tryEmit() never drops on a busy
    /// gossip burst. Activities collect this to drive toasts.
    private val _events = MutableSharedFlow<MeshEvent>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<MeshEvent> = _events.asSharedFlow()

    /// True peer count = unique handshake-verified addresses. Falls back
    /// to raw endpoint count for endpoints we haven't verified yet, so
    /// the badge updates immediately on connection (handshake takes a
    /// few hundred ms more).
    private fun computePeerCount(): Int {
        val verified = endpointAddress.values.toSet().size
        val unverified = connected.count { it !in endpointAddress.keys }
        return verified + unverified
    }
    private fun publishPeerCount() { _peerCountFlow.value = computePeerCount() }

    /// voucherId → wall-clock millis at which the most recent SETTLEMENT_CLAIM
    /// from a peer was observed. Local settle calls back off until this
    /// expires.
    private val claimedUntil = ConcurrentHashMap<String, Long>()

    @Serializable
    private data class MeshMessage(
        // "replica" | "ack" | "claim" | "settled" | "challenge" | "response"
        @SerialName("type") val type: String,
        @SerialName("voucherId") val voucherId: String = "",
        @SerialName("payload") val payload: CardVoucherPayload? = null,
        /// Sender's on-chain device address (0x…). For ACK/claim/replica
        /// messages this is informational; verified identity comes from the
        /// handshake. For RESPONSE messages this is the address being proven.
        @SerialName("from") val fromAddress: String? = null,
        /// CHALLENGE: 32-byte nonce (base64) the recipient must sign.
        @SerialName("nonceB64") val nonceB64: String? = null,
        /// RESPONSE: 65-byte ECDSA signature (hex) over keccak256(nonce we sent).
        @SerialName("sig") val signatureHex: String? = null,
        /// SETTLED: on-chain settlement tx hash. Carried so the recipient
        /// can deep-link straight to the explorer without ever needing
        /// internet itself.
        @SerialName("txHash") val txHash: String? = null,
        /// Bearer-card endorsement, attached to `replica` messages when
        /// the voucher is a true-bearer (recipient = 0x0). All four
        /// fields are required as a set — the relay needs them to call
        /// `settleBearerWithEndorsement`. Null for recipient-bound
        /// vouchers (the legacy v3 path).
        @SerialName("endTs")      val endorsementTs: Long?       = null,
        @SerialName("endPrimary") val endorsementPrimary: String? = null,
        @SerialName("endDevice")  val endorsementDevice: String?  = null,
        @SerialName("endSig")     val endorsementSig: String?     = null,
    )

    /// Address of THIS device — placed in every outbound message so peers can
    /// gate Sybil ACKs against the on-chain MerchantRegistry. Wired by the
    /// activity from the local key vault.
    var selfAddress: String? = null

    /// Number of currently connected mesh peers. Exposed for UI status display.
    val peerCount: Int get() = connected.size

    fun start() {
        startAdvertise()
        startDiscover()

        // Retry pump. Without it, a payload that silently fails on the
        // initial broadcast (Nearby's payload upgrade fails on a thin
        // BLE-only channel) is gone forever — Samsung says "broadcast"
        // but Nothing never sees the replica. Re-broadcasting every few
        // seconds gives the channel time to upgrade or for a stronger
        // peer to come into range.
        scope.launch {
            while (true) {
                delay(3_000)
                if (pendingBroadcast.isEmpty() || connected.isEmpty()) continue
                val snapshot = pendingBroadcast.values.toList()
                Log.i(TAG, "retry broadcast: ${snapshot.size} unacked voucher(s) → ${connected.size} endpoint(s)")
                for (item in snapshot) sendReplicaTo(connected.toList(), item.v, item.e)
            }
        }

        // Discovery rescan loop. Nearby Connections P2P_CLUSTER is
        // asymmetric — phone A may find B before B finds A, leading to
        // a topology where phone A claims 2 peers while B + C only see
        // 1 each. Periodically restarting discovery (without touching
        // advertise or existing connections) re-broadcasts our scan
        // window so peers we missed at boot have another chance to
        // be found. 25-second cadence is a balance: short enough to
        // recover quickly, long enough not to thrash the BLE radio.
        scope.launch {
            while (true) {
                delay(25_000)
                Log.d(TAG, "rescan: restarting discovery (peers=${connected.size})")
                runCatching { client.stopDiscovery() }
                delay(500)  // brief pause so the stop fully unwinds before restart
                startDiscover()
            }
        }
    }

    private fun startAdvertise() {
        val opts = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startAdvertising(deviceId, Config.MESH_SERVICE_ID, lifecycle, opts)
            .addOnFailureListener {
                Log.w(TAG, "advertise failed: $it")
                _events.tryEmit(MeshEvent.AdvertiseFailed(it.message ?: it.javaClass.simpleName))
            }
    }

    private fun startDiscover() {
        val dopts = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startDiscovery(Config.MESH_SERVICE_ID, discovery, dopts)
            .addOnFailureListener {
                Log.w(TAG, "discover failed: $it")
                _events.tryEmit(MeshEvent.DiscoverFailed(it.message ?: it.javaClass.simpleName))
            }
    }

    fun stop() {
        client.stopAllEndpoints()
        client.stopAdvertising()
        client.stopDiscovery()
        connected.clear()
        endpointAddress.clear()
        publishPeerCount()
    }

    /// Broadcast a freshly-accepted voucher to every connected mesh peer.
    /// Each peer that stores it will reply with an ACK; replicaCount goes
    /// up as ACKs come in. Pass `endorsement` when the voucher is a
    /// bearer card — the endorsement bundle is what lets a relay peer
    /// call `settleBearerWithEndorsement` on the merchant's behalf.
    @JvmOverloads
    fun broadcast(v: Voucher, endorsement: Endorsement? = null) {
        // Always queue. Even if no peer is connected right now, a peer
        // might come into range in seconds; the retry pump will deliver
        // it. If we already have an ACK, this is a no-op.
        if (ackedBy[v.voucherId]?.isNotEmpty() == true) return
        pendingBroadcast[v.voucherId] = PendingItem(v, endorsement)

        val targets = connected.toList()
        if (targets.isEmpty()) {
            Log.i(TAG, "broadcast: no peers yet for ${v.voucherId} — queued for retry")
            _events.tryEmit(MeshEvent.VoucherBroadcast(v.voucherId, 0))
            return
        }
        sendReplicaTo(targets, v, endorsement)
        Log.i(TAG, "broadcast voucher ${v.voucherId} → ${targets.size} endpoint(s)" +
                (if (endorsement != null) " [+endorsement]" else "") +
                "; queued for retry until acked")
        _events.tryEmit(MeshEvent.VoucherBroadcast(v.voucherId, targets.size))
    }

    /// Tell every connected peer that voucherId has been settled on chain
    /// with txHash. The recipient (offline phone) uses this to flip its
    /// "pending settle" UI to "settled · tx 0x…" without ever needing
    /// internet itself. Idempotent: peers dedupe via seenSettled.
    fun broadcastSettled(voucherId: String, txHash: String) {
        seenSettled.add(voucherId)  // we settled it ourselves; suppress
                                     // any echo from a peer
        if (connected.isEmpty()) {
            Log.d(TAG, "broadcastSettled: no peers — skipping (recipient already in pending state)")
            return
        }
        val msg = MeshMessage(type = "settled", voucherId = voucherId,
                              fromAddress = selfAddress, txHash = txHash)
        val payload = Payload.fromBytes(json.encodeToString(msg).toByteArray())
        for (endpointId in connected) {
            client.sendPayload(endpointId, payload)
        }
        Log.i(TAG, "broadcastSettled ${voucherId.take(10)} tx=${txHash.take(10)} → ${connected.size} endpoint(s)")
    }

    private fun sendReplicaTo(endpoints: List<String>, v: Voucher, e: Endorsement? = null) {
        val msg = MeshMessage(
            type = "replica",
            voucherId = v.voucherId,
            payload = CardVoucherPayload(
                voucherId = v.voucherId, payer = v.payer,
                merchant = v.merchant, recipient = v.recipient,
                amount = v.amount.toString(), expiry = v.expiry, nonce = v.nonce,
                signature = v.signature,
                cardUid = v.cardUid,
            ),
            fromAddress = selfAddress,
            endorsementTs      = e?.timestamp,
            endorsementPrimary = e?.merchantPrimary,
            endorsementDevice  = e?.deviceAddress,
            endorsementSig     = e?.signature,
        )
        val payload = Payload.fromBytes(json.encodeToString(msg).toByteArray())
        for (endpointId in endpoints) {
            client.sendPayload(endpointId, payload)
                .addOnSuccessListener { Log.d(TAG, "sent replica ${v.voucherId.take(10)} → $endpointId OK") }
                .addOnFailureListener { Log.w(TAG, "sendPayload $endpointId failed: ${it.message}") }
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // Skip if we already have this endpoint — saves a guaranteed
            // STATUS_ALREADY_CONNECTED failure log on every rescan tick.
            if (connected.contains(endpointId)) return

            // Deterministic tie-break: only the phone with the lower
            // wallet address initiates the connection; the other one
            // waits for the incoming request. Without this, when both
            // phones discover each other simultaneously they BOTH call
            // requestConnection → Nearby gets confused and neither side
            // completes, leaving one (or both) stuck without the link.
            //
            // Both sides of the comparison are the device's `deviceId`
            // (= wallet address, set when we called startAdvertising).
            // Symmetric across all peers, total order, no coordination.
            val mine = deviceId.lowercase()
            val theirs = info.endpointName.lowercase()
            if (mine >= theirs) {
                Log.d(TAG, "endpoint $endpointId (theirs=$theirs) found — passive side, " +
                        "waiting for them to initiate")
                return
            }
            Log.d(TAG, "endpoint $endpointId (theirs=$theirs) found — initiating connection")
            client.requestConnection(deviceId, endpointId, lifecycle)
                .addOnFailureListener {
                    val msg = it.message ?: it.javaClass.simpleName
                    // 8003 = ALREADY_CONNECTED is benign (rescan re-finds
                    // a peer we're already linked to). Quiet log so it
                    // doesn't drown out real failures.
                    if (msg.contains("8003")) Log.v(TAG, "request $endpointId already connected (ok)")
                    else                      Log.w(TAG, "request $endpointId failed: $msg")
                }
        }
        override fun onEndpointLost(endpointId: String) {
            if (connected.remove(endpointId)) {
                publishPeerCount()
                _events.tryEmit(MeshEvent.PeerDisconnected(endpointId, connected.size))
            }
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            client.acceptConnection(endpointId, payloads)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connected.add(endpointId)
                publishPeerCount()
                val n = computePeerCount()
                Log.i(TAG, "peer connected: $endpointId (${connected.size} endpoint(s), $n unique)")
                _events.tryEmit(MeshEvent.PeerConnected(endpointId, n))
                // Kick off the handshake: send a fresh CHALLENGE nonce.
                handshake?.let { hs ->
                    val nonce = hs.newChallengeNonce(endpointId)
                    val msg = MeshMessage(type = "challenge", nonceB64 = hs.b64(nonce), fromAddress = hs.selfAddress)
                    val out = Payload.fromBytes(json.encodeToString(msg).toByteArray())
                    client.sendPayload(endpointId, out)
                }
                // Drain the pending-broadcast queue to this fresh peer.
                // Critical for the relay use case: the relay (Nothing) often
                // joins AFTER the receiver (Samsung) has already accepted
                // a voucher, so without this we'd never push the backlog.
                val pending = pendingBroadcast.values.toList()
                if (pending.isNotEmpty()) {
                    Log.i(TAG, "draining ${pending.size} pending voucher(s) to fresh peer $endpointId")
                    for (item in pending) sendReplicaTo(listOf(endpointId), item.v, item.e)
                }
            }
        }
        override fun onDisconnected(endpointId: String) {
            if (connected.remove(endpointId)) {
                endpointAddress.remove(endpointId)
                publishPeerCount()
                _events.tryEmit(MeshEvent.PeerDisconnected(endpointId, computePeerCount()))
            }
            handshake?.forget(endpointId)
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val bytes = payload.asBytes() ?: return
            scope.launch { handleIncoming(endpointId, bytes) }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    private suspend fun handleIncoming(endpointId: String, bytes: ByteArray) {
        val msg = try { json.decodeFromString<MeshMessage>(String(bytes)) }
                  catch (e: Exception) { Log.w(TAG, "bad mesh msg: ${e.message}"); return }

        // Handshake messages always pre-empt the verified-peer gate; everything
        // else is dropped until the peer has cryptographically proven its
        // claimed device address (and that address is registered on-chain).
        when (msg.type) {
            "challenge" -> {
                val hs = handshake ?: return
                val nonceB64 = msg.nonceB64 ?: return
                val nonce = hs.ub64(nonceB64)
                val sig = hs.signChallenge(nonce)
                val resp = MeshMessage(type = "response", fromAddress = hs.selfAddress, signatureHex = sig)
                val out = Payload.fromBytes(json.encodeToString(resp).toByteArray())
                client.sendPayload(endpointId, out)
                return
            }
            "response" -> {
                val hs = handshake ?: return
                val claimed = msg.fromAddress ?: return
                val sig = msg.signatureHex ?: return
                hs.verifyResponse(endpointId, claimed, sig)
                // Track endpoint→address. Lets us dedupe peer count
                // across the multiple endpointIds Nearby creates per
                // medium for the same physical phone.
                hs.verifiedAddress(endpointId)?.let { addr ->
                    endpointAddress[endpointId] = addr.lowercase()
                    publishPeerCount()
                }
                return
            }
        }

        // Gate non-handshake traffic on a verified peer.
        if (handshake != null && !handshake.isVerified(endpointId)) {
            Log.w(TAG, "dropping ${msg.type} from unverified peer $endpointId")
            return
        }

        when (msg.type) {
            "replica" -> {
                val p = msg.payload ?: return
                val v = Voucher.fromCardPayload(p)
                // Reconstruct the endorsement bundle if the sender
                // attached one. Bearer cards (recipient = 0x0) need
                // it for relay-settle to work; for recipient-bound
                // vouchers all four fields are null and we ignore.
                val endorsement: Endorsement? =
                    if (msg.endorsementSig != null && msg.endorsementPrimary != null &&
                        msg.endorsementDevice != null && msg.endorsementTs != null)
                        Endorsement(
                            timestamp        = msg.endorsementTs,
                            merchantPrimary  = msg.endorsementPrimary,
                            deviceAddress    = msg.endorsementDevice,
                            signature        = msg.endorsementSig,
                        )
                    else null
                // Verify signature/expiry before storing — never trust a peer.
                // ALREADY_SEEN is fine: ack it so the sender knows we already
                // hold the backup and counts us as a replica.
                val verdict = verifier.verifyForMesh(v)
                val storeIt = verdict == VerifyResult.VALID
                val ackIt   = storeIt || verdict == VerifyResult.ALREADY_SEEN
                if (storeIt) {
                    store.saveReplica(v, endorsement)
                    Log.i(TAG, "stored replica ${v.voucherId} from $endpointId" +
                            (if (endorsement != null) " [+endorsement]" else ""))
                    _events.tryEmit(MeshEvent.ReplicaStored(v.voucherId, endpointId))
                }
                if (ackIt) {
                    val ack = MeshMessage(
                        type = "ack", voucherId = v.voucherId, fromAddress = selfAddress
                    )
                    val out = Payload.fromBytes(json.encodeToString(ack).toByteArray())
                    client.sendPayload(endpointId, out)
                } else {
                    Log.w(TAG, "rejected mesh replica ${v.voucherId}: $verdict")
                    _events.tryEmit(MeshEvent.ReplicaRejected(v.voucherId, verdict.name))
                }
            }
            "ack" -> {
                // Use the cryptographically-verified peer address from the
                // handshake — NEVER msg.fromAddress, which is self-claimed.
                // In trust-all dev mode (no handshake) we fall back to the
                // endpoint id so ACKs are still distinct per peer.
                val peerKey = handshake?.verifiedAddress(endpointId) ?: endpointId
                store.recordReplicaAck(msg.voucherId, peerKey)
                // Stop retrying — at least one peer holds a copy now.
                ackedBy.getOrPut(msg.voucherId) { mutableSetOf() }.add(peerKey)
                if (pendingBroadcast.remove(msg.voucherId) != null) {
                    Log.i(TAG, "ack from $peerKey for ${msg.voucherId} — removing from retry queue")
                }
            }
            "claim" -> {
                // Another device is about to settle this voucher. Back off so
                // we don't burn gas racing them.
                val deadline = System.currentTimeMillis() + Config.CLAIM_BACKOFF_MS
                claimedUntil[msg.voucherId] = deadline
                Log.i(TAG, "peer ${msg.fromAddress ?: endpointId} claimed ${msg.voucherId} — backing off ${Config.CLAIM_BACKOFF_MS}ms")
                _events.tryEmit(MeshEvent.ClaimReceived(msg.voucherId, msg.fromAddress ?: endpointId))
            }
            "settled" -> {
                // Relay (or any peer) settled on chain. Update our local
                // row and surface a single, polite toast — even if we were
                // offline at settle time. Idempotent across multiple peers
                // re-broadcasting the same news.
                val tx = msg.txHash ?: return
                if (!seenSettled.add(msg.voucherId)) return
                store.markSettled(msg.voucherId, tx)
                pendingBroadcast.remove(msg.voucherId)
                Log.i(TAG, "peer ${msg.fromAddress ?: endpointId} settled ${msg.voucherId.take(10)} tx=${tx.take(10)}")
                _events.tryEmit(MeshEvent.SettledByPeer(msg.voucherId, tx))
            }
        }
    }

    /// Claim-before-settle: shout SETTLEMENT_CLAIM to peers, wait briefly for
    /// any conflicting claim, then return whether we should proceed.
    /// If a peer claimed first within the window, this returns false and
    /// the caller should drop this voucher from its settle batch.
    suspend fun claimAndWait(voucherId: String, waitMs: Long = Config.CLAIM_WAIT_MS): Boolean {
        // If a peer already claimed and the deadline hasn't passed, bail.
        val existing = claimedUntil[voucherId] ?: 0L
        if (existing > System.currentTimeMillis()) return false

        if (connected.isNotEmpty()) {
            val claim = MeshMessage(type = "claim", voucherId = voucherId, fromAddress = selfAddress)
            val payload = Payload.fromBytes(json.encodeToString(claim).toByteArray())
            connected.forEach { client.sendPayload(it, payload) }
            _events.tryEmit(MeshEvent.ClaimSent(voucherId, connected.size))
        }
        delay(waitMs)

        val peerDeadline = claimedUntil[voucherId] ?: 0L
        return peerDeadline <= System.currentTimeMillis()
    }

    companion object {
        private const val TAG = "MeshBroadcaster"
    }
}
