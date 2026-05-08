package com.offlinepay.wallet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.offlinepay.wallet.ui.OffpayTheme
import com.offlinepay.wallet.ui.StatusKind
import com.offlinepay.wallet.ui.TopupScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.math.BigInteger

class TopupActivity : ComponentActivity() {

    private val amount = MutableStateFlow("5.00")
    private val busy   = MutableStateFlow(false)
    private val status = MutableStateFlow("")
    private val statusKind = MutableStateFlow(StatusKind.Idle)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val keyVault = KeyVault(this)
        val backend  = BackendClient(Config.BACKEND_BASE)
        val settle   = SettlementClient(
            rpcUrl = Config.RPC_URL, vaultAddress = Config.VAULT_ADDRESS,
            chainId = Config.CHAIN_ID, keyPair = keyVault.keyPair,
            fromAddress = keyVault.address
        )

        setContent {
            OffpayTheme {
                val a by amount.collectAsState()
                val b by busy.collectAsState()
                val st by status.collectAsState()
                val sk by statusKind.collectAsState()
                TopupScreen(
                    amount = a,
                    onAmountChange = { amount.value = it },
                    busy = b,
                    status = st,
                    statusKind = sk,
                    onClose = { finish() },
                    onTopup = {
                        if (b) return@TopupScreen
                        val baseUnits = parseUsdc(amount.value)
                        if (baseUnits == null || baseUnits <= 0L) {
                            status.value = "bad amount"; statusKind.value = StatusKind.Error
                            return@TopupScreen
                        }
                        val amtBI = BigInteger.valueOf(baseUnits)
                        busy.value = true
                        statusKind.value = StatusKind.Working
                        lifecycleScope.launch {
                            runCatching {
                                status.value = "(1/3) backend funding gas + minting…"
                                val initResp = backend.init(keyVault.address, baseUnits)
                                if (!initResp.ok) error(initResp.error ?: "init failed")
                                status.value = "(2/3) signing approve…"
                                settle.approveUsdc(Config.USDC_ADDRESS, Config.VAULT_ADDRESS, amtBI)
                                status.value = "(3/3) signing lockFunds…"
                                val lockTx = settle.lockFunds(amtBI)
                                status.value = "✓ locked ${amount.value} USDC — tx ${lockTx.take(10)}…"
                                statusKind.value = StatusKind.Success
                            }.onFailure {
                                status.value = it.message ?: "topup failed"
                                statusKind.value = StatusKind.Error
                            }
                            busy.value = false
                        }
                    },
                )
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
