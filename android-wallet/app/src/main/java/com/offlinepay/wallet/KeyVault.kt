package com.offlinepay.wallet

import android.content.Context
import androidx.core.content.edit
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric
import java.security.SecureRandom

/// Owns the customer's secp256k1 key. The demo persists the raw key in
/// SharedPreferences for simplicity; production must use the Android
/// hardware-backed Keystore (StrongBox if available) and never expose the
/// private key to userspace.
class KeyVault(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("offlinepay_keys", Context.MODE_PRIVATE)

    private fun loadOrCreate(): ECKeyPair {
        val hex = prefs.getString(KEY_HEX, null)
        if (hex != null) {
            return ECKeyPair.create(Numeric.toBigInt(hex))
        }
        val priv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val kp = ECKeyPair.create(priv)
        prefs.edit { putString(KEY_HEX, Numeric.toHexStringNoPrefix(priv)) }
        return kp
    }

    val keyPair: ECKeyPair = loadOrCreate()

    val address: String = "0x" + Keys.getAddress(keyPair)

    companion object {
        private const val KEY_HEX = "payer_priv_hex"
    }
}
