package com.offlinepay.wallet

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.offlinepay.wallet.ui.OffpayTheme
import com.offlinepay.wallet.ui.SplashScreen

class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Touch the KeyVault so the wallet exists by the time Home opens.
        KeyVault(applicationContext)
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
