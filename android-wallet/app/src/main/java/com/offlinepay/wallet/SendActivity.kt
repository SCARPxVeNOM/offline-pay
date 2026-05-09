package com.offlinepay.wallet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.offlinepay.wallet.ui.OffpayTheme
import com.offlinepay.wallet.ui.SendScreen
import com.offlinepay.wallet.ui.StatusKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.math.BigInteger

class SendActivity : ComponentActivity() {

    private val amount = MutableStateFlow("0.50")
    private val armed  = MutableStateFlow(false)
    private val status = MutableStateFlow("ready · enter amount and arm")
    private val statusKind = MutableStateFlow(StatusKind.Idle)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val keyVault = KeyVault(this)
        val settle   = SettlementClient(
            rpcUrl = Config.RPC_URL, vaultAddress = Config.VAULT_ADDRESS,
            chainId = Config.CHAIN_ID, keyPair = keyVault.keyPair,
            fromAddress = keyVault.address
        )
        // On screen open, check locked balance once. If 0, hint at topup.
        lifecycleScope.launch {
            runCatching { settle.lockedBalance(keyVault.address) }.onSuccess { locked ->
                if (locked.signum() == 0) {
                    status.value = "no funds locked — top up before sending"
                    statusKind.value = StatusKind.Error
                }
            }
        }

        setContent {
            OffpayTheme {
                val a by amount.collectAsState()
                val ar by armed.collectAsState()
                val st by status.collectAsState()
                val sk by statusKind.collectAsState()
                SendScreen(
                    amount = a,
                    onAmountChange = { amount.value = it },
                    armed = ar,
                    status = st,
                    statusKind = sk,
                    onArm = { onArm() },
                    onClose = { finish() },
                )
            }
        }
    }

    private fun onArm() {
        val baseUnits = parseUsdc(amount.value)
        if (baseUnits == null || baseUnits <= 0L) {
            status.value = "bad amount"; statusKind.value = StatusKind.Error
            return
        }
        PendingPayment.arm(this, BigInteger.valueOf(baseUnits))
        armed.value = true
        status.value = "armed · hold near receiver"
        statusKind.value = StatusKind.Armed

        lifecycleScope.launch {
            while (PendingPayment.isArmed(this@SendActivity)) delay(200)
            armed.value = false
            val err = PendingPayment.pollError(this@SendActivity)
            val out = PendingPayment.pollOutgoing(this@SendActivity)
            when {
                err != null -> { status.value = err; statusKind.value = StatusKind.Error }
                out != null -> {
                    status.value = "✓ paid ${"%.2f".format(out.amount.toDouble()/1e6)} USDC to ${out.recipient.take(10)}…"
                    statusKind.value = StatusKind.Success
                }
                else -> { status.value = "tap completed"; statusKind.value = StatusKind.Success }
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
