package com.offlinepay.wallet

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/// Persistent state for the ESP32 reader pairing.
///
/// We persist *both* the BT MAC (so future connects don't need fresh
/// discovery) and the on-chain wallet address the firmware reported back
/// in the CLAIM OK frame (so the receive path can sanity-check that the
/// ESP32 broadcasting VOUCHER frames is the same one we paired with — a
/// stranger renaming their phone's BT to OfflinePay_Reader can't spoof
/// our reader without also stealing the firmware's secp256k1 key).
class EspBondStore(ctx: Context) {

    private val prefs: SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    /// Live UI-bindable state. Activities collect this and re-render on
    /// every pair / unpair / activity-tick without polling.
    val stateFlow: StateFlow<EspBondState> = _state.asStateFlow()

    fun current(): EspBondState = _state.value

    /// Called once the firmware has acknowledged a CLAIM with `OK <addr>`.
    fun saveBond(btMac: String, btName: String, espAddress: String) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_BT_MAC, btMac)
            .putString(KEY_BT_NAME, btName)
            .putString(KEY_ESP_ADDR, espAddress.lowercase())
            .putLong(KEY_PAIRED_MS, now)
            .putLong(KEY_LAST_SEEN_MS, now)
            .apply()
        _state.value = load()
    }

    /// Called whenever a frame arrives from the bonded reader (a VOUCHER
    /// read, a heartbeat, etc.). Keeps the "last tap" timestamp fresh
    /// for the UI without persisting on every byte.
    fun touchLastSeen() {
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_LAST_SEEN_MS, now).apply()
        _state.value = _state.value.copy(lastSeenMs = now)
    }

    fun forget() {
        prefs.edit().clear().apply()
        _state.value = EspBondState()
    }

    private fun load() = EspBondState(
        btMac      = prefs.getString(KEY_BT_MAC, null),
        btName     = prefs.getString(KEY_BT_NAME, null),
        espAddress = prefs.getString(KEY_ESP_ADDR, null),
        pairedMs   = prefs.getLong(KEY_PAIRED_MS, 0L).takeIf { it > 0L },
        lastSeenMs = prefs.getLong(KEY_LAST_SEEN_MS, 0L).takeIf { it > 0L },
    )

    companion object {
        private const val PREFS         = "offpay_esp_bond"
        private const val KEY_BT_MAC    = "btMac"
        private const val KEY_BT_NAME   = "btName"
        private const val KEY_ESP_ADDR  = "espAddress"
        private const val KEY_PAIRED_MS = "pairedMs"
        private const val KEY_LAST_SEEN_MS = "lastSeenMs"
    }
}

data class EspBondState(
    val btMac: String? = null,
    val btName: String? = null,
    /// The firmware's own secp256k1 wallet address, as reported in the
    /// CLAIM OK frame. Null until first successful pair.
    val espAddress: String? = null,
    val pairedMs: Long? = null,
    val lastSeenMs: Long? = null,
) {
    val isPaired: Boolean get() = btMac != null && espAddress != null
}
