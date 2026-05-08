package com.offlinepay.merchant

import android.content.Context
import androidx.core.content.edit
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric
import java.security.SecureRandom

/// Per-device EVM keypair for the merchant phone. The address must be
/// authorized in MerchantRegistry via `authorizeDevice` so this device's
/// signatures count for Sybil-resistant ACKs and for direct on-chain
/// settlement via `settleVoucherAsDevice`.
///
/// Demo persistence is plaintext SharedPreferences. Production must use
/// Android Keystore (StrongBox where available) so the key never leaves
/// secure hardware.
class MerchantKeyVault(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("offlinepay_merchant_keys", Context.MODE_PRIVATE)

    private fun loadOrCreate(): ECKeyPair {
        val hex = prefs.getString(KEY_HEX, null)
        if (hex != null) return ECKeyPair.create(Numeric.toBigInt(hex))
        val priv = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val kp = ECKeyPair.create(priv)
        prefs.edit { putString(KEY_HEX, Numeric.toHexStringNoPrefix(priv)) }
        return kp
    }

    val keyPair: ECKeyPair = loadOrCreate()
    val address: String = "0x" + Keys.getAddress(keyPair)

    companion object { private const val KEY_HEX = "merchant_priv_hex" }
}
