package com.offlinepay.wallet

import android.content.Context
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

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

    /// voucherId → wall-clock millis at which the most recent SETTLEMENT_CLAIM
    /// from a peer was observed. Local settle calls back off until this
    /// expires.
    private val claimedUntil = ConcurrentHashMap<String, Long>()

    @Serializable
    private data class MeshMessage(
        // "replica" | "ack" | "claim" | "challenge" | "response"
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
    )

    /// Address of THIS device — placed in every outbound message so peers can
    /// gate Sybil ACKs against the on-chain MerchantRegistry. Wired by the
    /// activity from the local key vault.
    var selfAddress: String? = null

    /// Number of currently connected mesh peers. Exposed for UI status display.
    val peerCount: Int get() = connected.size

    fun start() {
        val opts = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startAdvertising(deviceId, Config.MESH_SERVICE_ID, lifecycle, opts)
            .addOnFailureListener { Log.w(TAG, "advertise failed: $it") }

        val dopts = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startDiscovery(Config.MESH_SERVICE_ID, discovery, dopts)
            .addOnFailureListener { Log.w(TAG, "discover failed: $it") }
    }

    fun stop() {
        client.stopAllEndpoints()
        client.stopAdvertising()
        client.stopDiscovery()
        connected.clear()
    }

    /// Broadcast a freshly-accepted voucher to every connected mesh peer.
    /// Each peer that stores it will reply with an ACK; replicaCount goes
    /// up as ACKs come in.
    fun broadcast(v: Voucher) {
        if (connected.isEmpty()) {
            Log.i(TAG, "broadcast: no peers — voucher ${v.voucherId} has no backups")
            return
        }
        val msg = MeshMessage(
            type = "replica",
            voucherId = v.voucherId,
            payload = CardVoucherPayload(
                voucherId = v.voucherId, payer = v.payer,
                merchant = v.merchant, recipient = v.recipient,
                amount = v.amount.toString(), expiry = v.expiry, nonce = v.nonce,
                signature = v.signature
            ),
            fromAddress = selfAddress,
        )
        val payload = Payload.fromBytes(json.encodeToString(msg).toByteArray())
        connected.forEach { endpointId ->
            client.sendPayload(endpointId, payload)
        }
        Log.i(TAG, "broadcast voucher ${v.voucherId} → ${connected.size} peers")
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            client.requestConnection(deviceId, endpointId, lifecycle)
                .addOnFailureListener { Log.w(TAG, "request $endpointId failed: $it") }
        }
        override fun onEndpointLost(endpointId: String) {
            connected.remove(endpointId)
        }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            client.acceptConnection(endpointId, payloads)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connected.add(endpointId)
                Log.i(TAG, "peer connected: $endpointId (${connected.size} total)")
                // Kick off the handshake: send a fresh CHALLENGE nonce.
                handshake?.let { hs ->
                    val nonce = hs.newChallengeNonce(endpointId)
                    val msg = MeshMessage(type = "challenge", nonceB64 = hs.b64(nonce), fromAddress = hs.selfAddress)
                    val out = Payload.fromBytes(json.encodeToString(msg).toByteArray())
                    client.sendPayload(endpointId, out)
                }
            }
        }
        override fun onDisconnected(endpointId: String) {
            connected.remove(endpointId)
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
                // Verify signature/expiry before storing — never trust a peer.
                // ALREADY_SEEN is fine: ack it so the sender knows we already
                // hold the backup and counts us as a replica.
                val verdict = verifier.verifyForMesh(v)
                val storeIt = verdict == VerifyResult.VALID
                val ackIt   = storeIt || verdict == VerifyResult.ALREADY_SEEN
                if (storeIt) {
                    store.saveReplica(v)
                    Log.i(TAG, "stored replica ${v.voucherId} from $endpointId")
                }
                if (ackIt) {
                    val ack = MeshMessage(
                        type = "ack", voucherId = v.voucherId, fromAddress = selfAddress
                    )
                    val out = Payload.fromBytes(json.encodeToString(ack).toByteArray())
                    client.sendPayload(endpointId, out)
                } else {
                    Log.w(TAG, "rejected mesh replica ${v.voucherId}: $verdict")
                }
            }
            "ack" -> {
                // Use the cryptographically-verified peer address from the
                // handshake — NEVER msg.fromAddress, which is self-claimed.
                // In trust-all dev mode (no handshake) we fall back to the
                // endpoint id so ACKs are still distinct per peer.
                val peerKey = handshake?.verifiedAddress(endpointId) ?: endpointId
                store.recordReplicaAck(msg.voucherId, peerKey)
            }
            "claim" -> {
                // Another device is about to settle this voucher. Back off so
                // we don't burn gas racing them.
                val deadline = System.currentTimeMillis() + Config.CLAIM_BACKOFF_MS
                claimedUntil[msg.voucherId] = deadline
                Log.i(TAG, "peer ${msg.fromAddress ?: endpointId} claimed ${msg.voucherId} — backing off ${Config.CLAIM_BACKOFF_MS}ms")
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
        }
        delay(waitMs)

        val peerDeadline = claimedUntil[voucherId] ?: 0L
        return peerDeadline <= System.currentTimeMillis()
    }

    companion object {
        private const val TAG = "MeshBroadcaster"
    }
}
