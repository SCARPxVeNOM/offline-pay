package com.offlinepay.wallet

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

/// Top-up flow for true-bearer MIFARE cards.
///
/// User flow: enter the card's hardware UID twice (typo guard) + amount;
/// while online, the phone verifies its locked balance, signs a v3 voucher
/// with `recipient = 0x0` (settled later via `settleBearerWithEndorsement`)
/// + `cardUid = <user-typed>` as metadata, then sends a signed WRITE
/// command to the bonded ESP32 which writes the JSON across MIFARE blocks
/// 4-30 on the next-tapped card.
///
/// At spend time the merchant's reader signs an endorsement committing to
/// its bonded primary wallet; the on-chain settle pays the primary, not
/// whoever broadcasts. So the card is genuinely cash-like — anyone can
/// spend it, but only at a merchant whose reader the customer actually
/// taps.
class CardWriteActivity : ComponentActivity() {
    private lateinit var keyVault: KeyVault
    private lateinit var bondStore: EspBondStore
    private lateinit var voucherSigner: VoucherSigner
    private lateinit var activityStore: ActivityStore
    private lateinit var settle: SettlementClient

    private val ui = MutableStateFlow(CardWriteState())

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
        settle = SettlementClient(
            rpcUrl = Config.RPC_URL, vaultAddress = Config.VAULT_ADDRESS,
            chainId = Config.CHAIN_ID, keyPair = keyVault.keyPair,
            fromAddress = keyVault.address,
        )

        // Bond + connectivity feed into UI state.
        lifecycleScope.launch {
            bondStore.stateFlow.collect { ui.value = ui.value.copy(bond = it) }
        }
        ui.value = ui.value.copy(online = isOnline())

        setContent {
            OffpayTheme {
                val s by ui.collectAsState()
                CardWriteScreen(
                    state = s,
                    onClose = { finish() },
                    onAmountChange = { ui.value = ui.value.copy(amount = it, error = null) },
                    onCardUidChange = {
                        ui.value = ui.value.copy(cardUid = it, error = null)
                    },
                    onCardUidConfirmChange = {
                        ui.value = ui.value.copy(cardUidConfirm = it, error = null)
                    },
                    onWrite = { writeCard() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check online state on resume so toggling Wi-Fi off/on flips
        // the offline banner without leaving the screen.
        ui.value = ui.value.copy(online = isOnline())
    }

    private fun writeCard() {
        val s = ui.value
        val bond = s.bond
        if (!bond.isPaired || bond.btMac == null) {
            ui.value = s.copy(error = "Pair an ESP32 reader first (Home → Reader)")
            return
        }
        if (!isOnline()) {
            ui.value = s.copy(
                online = false,
                error = "Internet required: we verify your locked balance before signing.",
            )
            return
        }
        val cardUid = s.confirmedCardUid
            ?: run {
                ui.value = s.copy(
                    error = if (s.typoMismatch) "Card UIDs don't match — re-type carefully"
                            else "Enter the card UID twice (8 or 14 hex chars)",
                )
                return
            }
        val amountBaseUnits = parseUsdc(s.amount)
        if (amountBaseUnits == null || amountBaseUnits <= 0L) {
            ui.value = s.copy(error = "Enter an amount in USDC")
            return
        }

        ui.value = s.copy(
            busy = true, error = null,
            status = "Checking locked balance…", statusKind = StatusKind.Working,
        )

        lifecycleScope.launch {
            // Verify locked balance covers the new card amount. We don't
            // factor in already-issued card balances here (that lives in
            // ActivityStore + BalanceCache) — the chain's lockedBalance
            // is the hard ceiling.
            val locked = runCatching { settle.lockedBalance(keyVault.address) }.getOrNull()
            if (locked == null) {
                ui.value = ui.value.copy(busy = false, status = "Network error",
                    statusKind = StatusKind.Error,
                    error = "Couldn't reach chain — try again on a stronger network.")
                return@launch
            }
            if (locked < BigInteger.valueOf(amountBaseUnits)) {
                ui.value = ui.value.copy(busy = false, status = "Insufficient locked",
                    statusKind = StatusKind.Error,
                    error = "Locked balance is ${"%.2f".format(locked.toLong() / 1e6)}; " +
                            "top up before writing this card.")
                return@launch
            }

            ui.value = ui.value.copy(status = "Signing voucher…")
            val signed = voucherSigner.signNextBearerForCard(
                amountUsdc = BigInteger.valueOf(amountBaseUnits),
                ttlSeconds = 24 * 3600L,
            )
            val voucherJson = signed.toCardJson(cardUid = cardUid)

            ui.value = ui.value.copy(
                status = "Tap MIFARE card on the reader…",
            )
            when (val r = EspWriteClient.writeVoucherToCard(
                bondedBtMac = bond.btMac,
                voucherJson = voucherJson,
                keyVault = keyVault,
            )) {
                is EspWriteClient.Result.Written -> {
                    activityStore.recordSent(
                        voucherId = signed.voucherId,
                        recipient = "card:$cardUid",
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

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val cap = cm.getNetworkCapabilities(net) ?: return false
        return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun parseUsdc(text: String): Long? = try {
        val parts = text.trim().split(".")
        val whole = parts[0].toLongOrNull() ?: return null
        val frac = parts.getOrNull(1)?.padEnd(6, '0')?.take(6)?.toLongOrNull() ?: 0L
        whole * 1_000_000 + frac
    } catch (_: Throwable) { null }
}
