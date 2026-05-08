package com.offlinepay.merchant

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.math.BigInteger

class MainActivity : AppCompatActivity() {

    // ─── Demo defaults — change for your environment ────────────────────────
    // Local hardhat node by default. For Polygon Amoy, set chainId=80002
    // and the deployed vault address.
    private val CHAIN_ID         = 31337L
    private val VAULT_ADDRESS    = "0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512"
    private val MAX_SINGLE_USDC  = BigInteger("2000000")  // $2.00 (matches contract default)
    private val BACKEND_BASE_URL = "http://10.0.2.2:4000"  // 10.0.2.2 = localhost from Android emulator

    private lateinit var status: TextView
    private lateinit var feed: TextView
    private lateinit var settleBtn: Button

    private lateinit var store: VoucherStore
    private lateinit var verifier: VoucherVerifier
    private lateinit var bt: BluetoothBridge
    private lateinit var mesh: MeshBroadcaster
    private val client by lazy { SettlementClient(BACKEND_BASE_URL) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Build a UI in code so the project doesn't need an XML round-trip.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        status = TextView(this).apply {
            text = "Tap a card on the OfflinePay reader…"; textSize = 16f
        }
        feed = TextView(this).apply { text = ""; textSize = 13f; typeface = android.graphics.Typeface.MONOSPACE }
        settleBtn = Button(this).apply { text = "Settle queued vouchers" }
        root.addView(status); root.addView(feed); root.addView(settleBtn)
        setContentView(root)

        store    = VoucherStore(this)
        verifier = VoucherVerifier(CHAIN_ID, VAULT_ADDRESS, MAX_SINGLE_USDC, store)
        bt       = BluetoothBridge(this, scope = lifecycleScope)
        // This phone's own EVM keypair. Its address must be authorized in
        // MerchantRegistry via authorizeDevice() so peers (and the vault)
        // recognize it during handshake and on-chain settlement.
        val keyVault = MerchantKeyVault(this)

        // Sybil gate: only count ACKs from peers whose device address is
        // present in the local registry cache. Production fills this from
        // a periodic MerchantRegistry RPC poll; for the demo we leave the
        // allowlist empty + the gate accepts nothing (handshake will fail
        // closed). Adjust to `{ true }` to run in trust-all dev mode.
        val allowedDevices = mutableSetOf<String>()
        val registryGate: (String) -> Boolean = { addr ->
            allowedDevices.contains(addr.lowercase())
        }
        val hsManager = HandshakeManager(
            keyPair = keyVault.keyPair,
            selfAddress = keyVault.address,
            isRegisteredDevice = registryGate,
        )
        mesh = MeshBroadcaster(
            ctx = this, store = store, verifier = verifier,
            deviceId = keyVault.address,
            isRegisteredDevice = registryGate,
            handshake = hsManager,
        ).also { it.selfAddress = keyVault.address }

        ensurePermissions()
        bt.connect()
        mesh.start()

        // Stream vouchers from the ESP32, verify, persist, ack.
        lifecycleScope.launch {
            bt.vouchers.collect { v ->
                val result = verifier.verify(v)
                if (result == VerifyResult.VALID) {
                    store.saveAccepted(v)
                    mesh.broadcast(v)  // replicate across nearby peers
                    bt.sendDecision(true)
                    setStatus("✅ ${"%.2f".format(v.amount.toDouble() / 1e6)} USDC accepted (id ${v.voucherId.take(10)}…)")
                } else {
                    store.saveRejected(v, result.name)
                    bt.sendDecision(false)
                    setStatus("❌ rejected: ${result.name}")
                }
            }
        }

        // Live feed.
        lifecycleScope.launch {
            store.recent().collectLatest { rows ->
                feed.text = rows.joinToString("\n") { r ->
                    val st = when (r.status) {
                        "accepted" -> "✓"; "settled" -> "⛓"; "replica" -> "↻"; else -> "✗"
                    }
                    val amt = "%.2f".format(BigInteger(r.amount).toDouble() / 1e6)
                    val backup = if (r.status == "accepted") "  [${confidenceLevel(r.replicaCount)}]" else ""
                    "$st $amt  ${r.voucherId.take(10)}…  ${r.status}$backup"
                }
            }
        }

        settleBtn.setOnClickListener {
            lifecycleScope.launch {
                val pending = store.pendingForSettle()
                setStatus("claiming ${pending.size}…")
                // Claim-before-settle: announce intent on the mesh and drop
                // any voucher a peer has claimed first. This stops two
                // backups racing on-chain and burning gas on revert.
                val toSettle = pending.filter { mesh.claimAndWait(it.voucherId) }
                val skipped = pending.size - toSettle.size
                if (toSettle.isEmpty()) {
                    setStatus("all ${pending.size} claimed by peers — backing off")
                    return@launch
                }
                setStatus("settling ${toSettle.size} (skipped $skipped claimed by peers)…")
                val resp = client.redeemAndSettle(toSettle)
                if (resp.settled > 0 && resp.tx != null) {
                    toSettle.forEach { store.markSettled(it.voucherId, resp.tx!!) }
                    setStatus("⛓ settled ${resp.settled} on chain — tx ${resp.tx!!.take(10)}…")
                } else {
                    setStatus("settle response: ${resp.error ?: "no rows"}")
                }
            }
        }
    }

    private fun setStatus(msg: String) = runOnUiThread { status.text = msg }

    private fun ensurePermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        } else {
            perms += Manifest.permission.BLUETOOTH
            perms += Manifest.permission.BLUETOOTH_ADMIN
        }
        permLauncher.launch(perms.toTypedArray())
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* ignored — connect() will retry once user grants */ }

    override fun onDestroy() { mesh.stop(); bt.close(); super.onDestroy() }
}
