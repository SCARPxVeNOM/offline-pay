package com.offlinepay.wallet

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.offlinepay.wallet.ui.OffpayTheme
import com.offlinepay.wallet.ui.SplashScreen
import kotlinx.coroutines.launch

class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Touch the KeyVault so the wallet exists by the time Home opens.
        KeyVault(applicationContext)

        // Resolve which backend URL works on this network (v4 vs v6) in
        // parallel with the splash animation so the user doesn't wait extra.
        lifecycleScope.launch { BackendResolver.resolve() }

        setContent {
            OffpayTheme {
                SplashScreen(durationMs = 3200) {
                    startActivity(Intent(this, HomeActivity::class.java))
                    finish()
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                }
            }
        }
    }
}
