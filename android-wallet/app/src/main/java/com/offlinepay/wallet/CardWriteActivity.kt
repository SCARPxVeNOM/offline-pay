package com.offlinepay.wallet

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.offlinepay.wallet.ui.CardWriteScreen
import com.offlinepay.wallet.ui.CardWriteState
import com.offlinepay.wallet.ui.OffpayTheme
import com.offlinepay.wallet.ui.StatusKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.math.BigInteger

/// "Top up card" — signs a v3 voucher with a chosen recipient and asks
/// the bonded ESP32 to write it to the next-tapped MIFARE card.
///
/// This is the second-layer entry point for sending: instead of an NFC
/// tap or a QR transfer, the sender embeds the voucher into a physical
/// card the customer can carry to a merchant stall. The merchant's
/// reader (a different ESP32, paired to the merchant's phone) reads the
/// card, the merchant phone receives the VOUCHER frame over BT, the
/// mesh handles relay-settle. End-to-end offline.
class CardWriteActivity : ComponentActivity() {
    private lateinit var keyVault: KeyVault
    private lateinit var bondStore: EspBondStore
    private lateinit var voucherSigner: VoucherSigner
    private lateinit var activityStore: ActivityStore

    private val ui = MutableStateFlow(CardWriteState())

    private val qrLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            val text = res.data?.getStringExtra("qr") ?: return@registerForActivityResult
            // QR payload from ReceiveActivity is just the address. Tolerate
            // a leading "ethereum:" prefix from external wallets too.
            val addr = text.trim().removePrefix("ethereum:").trim()
            if (addr.matches(Regex("^0x[0-9a-fA-F]{40}$"))) {
                // Fill BOTH fields so the typo-guard goes green immediately;
                // user already opted into "I trust the QR".
                val low = addr.lowercase()
                ui.value = ui.value.copy(recipient = low, recipientConfirm = low, error = null)
            } else {
                ui.value = ui.value.copy(error = "Scanned QR is not a 0x address: $text")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        keyVault      = KeyVault(this)
        bondStore     = EspBondStore(this)
        activityStore = ActivityStore(this)
        voucherSigner = VoucherSigner(
            chainId      = Config.CHAIN_ID,
            vaultAddress = Config.VAULT_ADDRESS,
            keyPair      = keyVault.keyPair,
            payerAddress = keyVault.address,
            nonces       = NonceTracker(this),
        )

        // Reflect the current bond into UI so the screen shows whether a
        // reader is even available.
        lifecycleScope.launch {
            bondStore.stateFlow.collect { ui.value = ui.value.copy(bond = it) }
        }

        setContent {
            OffpayTheme {
                val s by ui.collectAsState()
                CardWriteScreen(
                    state = s,
                    onClose = { finish() },
                    onAmountChange = { ui.value = ui.value.copy(amount = it, error = null) },
                    onRecipientChange = { ui.value = ui.value.copy(recipient = it, error = null) },
                    onRecipientConfirmChange = {
                        ui.value = ui.value.copy(recipientConfirm = it, error = null)
                    },
                    onScanQr = {
                        qrLauncher.launch(Intent(this, QrScanActivity::class.java))
                    },
                    onWrite = { writeCard() },
                )
            }
        }
    }

    private fun writeCard() {
        val s = ui.value
        val bond = s.bond
        if (!bond.isPaired || bond.btMac == null) {
            ui.value = s.copy(error = "Pair an ESP32 reader first (Home → Reader)")
            return
        }
        val recipient = s.confirmedRecipient
            ?: run {
                ui.value = s.copy(
                    error = if (s.typoMismatch) "Recipient addresses don't match — re-type to confirm"
                            else "Enter the recipient address twice (or scan a QR)",
                )
                return
            }
        val amountBaseUnits = parseUsdc(s.amount)
        if (amountBaseUnits == null || amountBaseUnits <= 0L) {
            ui.value = s.copy(error = "Enter an amount in USDC")
            return
        }
        ui.value = s.copy(busy = true, status = "Signing voucher…",
            statusKind = StatusKind.Working, error = null)

        lifecycleScope.launch {
            val signed = voucherSigner.signNext(
                merchant = null,                          // bearer-shape (recipient holds binding)
                recipient = recipient,
                amountUsdc = BigInteger.valueOf(amountBaseUnits),
                ttlSeconds = 24 * 3600L,                  // 24h — long enough for cards
            )
            val voucherJson = signed.toCardJson()

            ui.value = ui.value.copy(
                status = "Tap MIFARE card on the reader…",
                statusKind = StatusKind.Working,
            )
            when (val r = EspWriteClient.writeVoucherToCard(
                bondedBtMac = bond.btMac,
                voucherJson = voucherJson,
                keyVault = keyVault,
            )) {
                is EspWriteClient.Result.Written -> {
                    activityStore.recordSent(
                        voucherId = signed.voucherId,
                        recipient = recipient,
                        amountBaseUnits = amountBaseUnits,
                    )
                    val pretty = "%.2f".format(amountBaseUnits / 1e6)
                    ui.value = ui.value.copy(
                        busy = false,
                        status = "✓ wrote $pretty USDC to card ${r.cardUid.take(10)}",
                        statusKind = StatusKind.Success,
                        error = null,
                    )
                }
                is EspWriteClient.Result.Failed -> {
                    ui.value = ui.value.copy(
                        busy = false,
                        status = "Card write failed",
                        statusKind = StatusKind.Error,
                        error = r.reason,
                    )
                }
            }
        }
    }

    private fun parseUsdc(text: String): Long? = try {
        val parts = text.trim().split(".")
        val whole = parts[0].toLongOrNull() ?: return null
        val frac = parts.getOrNull(1)?.padEnd(6, '0')?.take(6)?.toLongOrNull() ?: 0L
        whole * 1_000_000 + frac
    } catch (_: Throwable) { null }
}
