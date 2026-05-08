package com.offlinepay.wallet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.offlinepay.wallet.ui.HairlineDivider
import com.offlinepay.wallet.ui.MonoLabel
import com.offlinepay.wallet.ui.OffpayColors
import com.offlinepay.wallet.ui.OffpayTheme
import com.offlinepay.wallet.ui.SectionHeader
import com.offlinepay.wallet.ui.TopBar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.math.BigInteger

class HistoryActivity : ComponentActivity() {

    private val rows = MutableStateFlow<List<VoucherRow>>(emptyList())

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val store = VoucherStore(this)
        lifecycleScope.launch {
            store.recent().collect { rows.value = it }
        }
        setContent {
            OffpayTheme {
                val r by rows.collectAsState()
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(OffpayColors.White)
                ) {
                    TopBar(title = "HISTORY", onClose = { finish() })
                    SectionHeader("Vouchers received", "${r.size} ENTRIES")
                    if (r.isEmpty()) {
                        Box(
                            Modifier
                                .padding(horizontal = 16.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .border(1.dp, OffpayColors.Hairline, RoundedCornerShape(18.dp))
                                .padding(40.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            MonoLabel("Nothing yet · tap to receive")
                        }
                    } else {
                        LazyColumn(
                            Modifier
                                .padding(horizontal = 16.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .border(1.dp, OffpayColors.Hairline, RoundedCornerShape(18.dp))
                                .background(Color.White)
                        ) {
                            items(r) { row ->
                                HistoryRow(row)
                                HairlineDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun HistoryRow(r: VoucherRow) {
    val amt = "%.2f".format(BigInteger(r.amount).toDouble() / 1e6)
    val (label, accent) = when (r.status) {
        "settled"  -> "⛓ SETTLED" to OffpayColors.TealDeep
        "accepted" -> "✓ PENDING SETTLE" to OffpayColors.Ink
        "rejected" -> "✗ REJECTED · ${r.rejectReason ?: ""}" to OffpayColors.Danger
        else       -> r.status.uppercase() to OffpayColors.InkMuted
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("\$$amt USDC", color = OffpayColors.Ink, fontSize = 15.sp,
                 fontWeight = FontWeight.Bold)
            Text(label, color = accent, fontSize = 10.sp,
                 fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
                 letterSpacing = 1.6.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "from ${r.payer.take(10)}… nonce ${r.nonce}",
            color = OffpayColors.InkMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
        )
        if (r.settledTx != null) {
            Text(
                "tx ${r.settledTx.take(20)}…",
                color = OffpayColors.TealDeep, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
            )
        }
    }
}
