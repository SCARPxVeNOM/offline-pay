package com.offlinepay.wallet

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.offlinepay.wallet.ui.EspScreen
import com.offlinepay.wallet.ui.EspScreenState
import com.offlinepay.wallet.ui.OffpayTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/// "Reader control center". Pairs the phone with an ESP32 reader by
/// running the CLAIM auth handshake, persists the bond, and lets the
/// user switch ownership at will.
class EspActivity : ComponentActivity() {
    private lateinit var bondStore: EspBondStore
    private lateinit var keyVault: KeyVault

    private val ui = MutableStateFlow(EspScreenState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bondStore = EspBondStore(this)
        keyVault  = KeyVault(this)

        // Push the persisted bond into UI state, then keep it live.
        lifecycleScope.launch {
            bondStore.stateFlow.collect { bond ->
                ui.value = ui.value.copy(bond = bond)
            }
        }

        refreshDiscovered()

        setContent {
            OffpayTheme {
                val s by ui.collectAsState()
                EspScreen(
                    state = s,
                    onClose = { finish() },
                    onRefresh = { refreshDiscovered() },
                    onPair = { device -> pair(device) },
                    onForget = {
                        bondStore.forget()
                        Toast.makeText(this@EspActivity,
                            "reader forgotten", Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }

    private fun refreshDiscovered() {
        // Reads OS-bonded devices matching the firmware's BT name. If the
        // user hasn't pair-via-OS-settings yet, this comes back empty
        // and we surface a helpful message in the UI.
        ui.value = ui.value.copy(
            discovered = EspPairingClient.discoverBondedReaders(),
        )
    }

    private fun pair(device: DiscoveredEsp) {
        ui.value = ui.value.copy(busy = true, error = null)
        lifecycleScope.launch {
            when (val r = EspPairingClient.pair(applicationContext, device, keyVault)) {
                is EspPairingClient.Result.Paired -> {
                    bondStore.saveBond(r.btMac, r.btName, r.espAddress)
                    ui.value = ui.value.copy(busy = false, error = null)
                    Toast.makeText(this@EspActivity,
                        "paired with ${r.espAddress.take(10)}…", Toast.LENGTH_SHORT).show()
                }
                is EspPairingClient.Result.Failed -> {
                    ui.value = ui.value.copy(busy = false, error = r.reason)
                    Toast.makeText(this@EspActivity,
                        "pairing failed: ${r.reason}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
