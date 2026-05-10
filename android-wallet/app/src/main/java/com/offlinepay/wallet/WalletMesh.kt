package com.offlinepay.wallet

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/// Process-wide holder for the mesh stack. Activities acquire on resume,
/// release on pause; mesh stays alive while at least one activity holds a
/// reference. This way Samsung's mesh keeps advertising/discovering while
/// the user is on the Receive screen — without it, vouchers accepted during
/// receive never get replicated to peers.
///
/// Trade-off: leaks battery if the user backgrounds the app while it has
/// the only acquire(). For the demo we accept this; production would lift
/// mesh into a foreground Service with notification.
object WalletMesh {

    private const val TAG = "OfflinePay/WalletMesh"

    @Volatile private var initialized = false
    private var refCount = 0

    private lateinit var keyVault: KeyVault
    private lateinit var store: VoucherStore
    lateinit var mesh: MeshBroadcaster
        private set
    lateinit var handshake: HandshakeManager
        private set

    @Synchronized
    private fun ensureInit(ctx: Context) {
        if (initialized) return
        keyVault = KeyVault(ctx.applicationContext)
        store    = VoucherStore(ctx.applicationContext)
        // Mesh-side verifier: bypasses recipient check (peers hold backups
        // for vouchers addressed to anyone). expectedRecipient is unused on
        // this path but the constructor still requires a value.
        val verifier = VoucherVerifier(
            Config.CHAIN_ID, Config.VAULT_ADDRESS, Config.MAX_SINGLE_USDC,
            store, expectedRecipient = keyVault.address,
        )
        // Sybil gate is fail-open for the demo (no on-chain registry yet).
        // Production would populate `allowedDevices` from a periodic
        // MerchantRegistry.DeviceAuthorized event poll.
        val registryGate: (String) -> Boolean = { true }
        handshake = HandshakeManager(
            keyPair = keyVault.keyPair,
            selfAddress = keyVault.address,
            isRegisteredDevice = registryGate,
        )
        mesh = MeshBroadcaster(
            ctx = ctx.applicationContext,
            store = store,
            verifier = verifier,
            deviceId = keyVault.address,
            isRegisteredDevice = registryGate,
            handshake = handshake,
        ).also { it.selfAddress = keyVault.address }
        initialized = true
        Log.d(TAG, "initialized; selfAddress=${keyVault.address}")
    }

    @Synchronized
    fun acquire(ctx: Context) {
        ensureInit(ctx)
        refCount += 1
        if (refCount == 1) {
            Log.d(TAG, "starting mesh (first acquire)")
            mesh.start()
        }
    }

    @Synchronized
    fun release() {
        if (refCount == 0) return
        refCount -= 1
        if (refCount == 0) {
            Log.d(TAG, "stopping mesh (last release)")
            mesh.stop()
        }
    }

    /// Broadcast a voucher to all currently-connected mesh peers. Caller
    /// is responsible for having acquire()d already (we call mesh.broadcast
    /// directly which is a no-op if no peers are connected).
    @JvmOverloads
    fun broadcast(v: Voucher, endorsement: Endorsement? = null) {
        if (!initialized) {
            Log.w(TAG, "broadcast called before init; dropping ${v.voucherId.take(10)}")
            return
        }
        mesh.broadcast(v, endorsement)
    }

    /// Tell every connected peer that this voucher just settled on chain.
    /// The offline recipient uses this to flip its UI to "settled · tx 0x…"
    /// without ever needing the chain itself.
    fun broadcastSettled(voucherId: String, txHash: String) {
        if (!initialized) return
        mesh.broadcastSettled(voucherId, txHash)
    }

    val peerCount: Int get() = if (initialized) mesh.peerCount else 0

    /// Bridge fields. Activities collect these even before ensureInit() has
    /// run on a worker thread, so we materialise empty fallbacks up front
    /// and forward to the mesh's flows once it exists.
    private val emptyPeerCount = MutableStateFlow(0)
    private val emptyEvents = MutableSharedFlow<MeshEvent>(replay = 0, extraBufferCapacity = 8)
    val peerCountFlow: StateFlow<Int>
        get() = if (initialized) mesh.peerCountFlow else emptyPeerCount.asStateFlow()
    val events: SharedFlow<MeshEvent>
        get() = if (initialized) mesh.events else emptyEvents.asSharedFlow()
}
