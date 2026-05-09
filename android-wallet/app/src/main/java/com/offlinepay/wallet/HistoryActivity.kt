package com.offlinepay.wallet

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
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

class HistoryActivity : ComponentActivity() {

    private val rows = MutableStateFlow<List<ActivityRow>>(emptyList())

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val activity = ActivityStore(this)
        lifecycleScope.launch {
            activity.recent().collect { rows.value = it }
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
                    SectionHeader("All activity", "${r.size} ENTRIES")
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
                            MonoLabel("Nothing yet · top up or tap to start")
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
private fun HistoryRow(r: ActivityRow) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val amt = if (r.amountBaseUnits > 0)
        "$" + "%.2f".format(r.amountBaseUnits.toDouble() / 1e6) else ""
    val (label, accent, kindIcon) = when (r.kind) {
        "topup"    -> Triple("⬆ TOP-UP",            OffpayColors.TealDeep, Icons.Outlined.Add)
        "sent"     -> Triple("→ SENT",              OffpayColors.Ink,      Icons.Outlined.NorthEast)
        "received" -> Triple("← RECEIVED",          OffpayColors.TealDeep, Icons.Outlined.SouthWest)
        "settled"  -> Triple("⛓ SETTLED ON CHAIN",  OffpayColors.TealDeep, Icons.Outlined.AutoAwesome)
        "failed"   -> Triple("✗ FAILED",            OffpayColors.Danger,   Icons.Outlined.ErrorOutline)
        else       -> Triple(r.kind.uppercase(),    OffpayColors.InkMuted, Icons.Outlined.Info)
    }
    val explorer = r.txHash?.let { Config.txUrl(it) }
    Row(
        Modifier
            .fillMaxWidth()
            .let { base -> if (explorer != null) base.clickable {
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(explorer)))
            } else base }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(kindIcon, null, tint = accent, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()) {
                Text(label, color = accent, fontSize = 10.sp,
                     fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                     letterSpacing = 1.6.sp)
                if (amt.isNotEmpty()) {
                    Text(amt, color = OffpayColors.Ink,
                         fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(2.dp))
            val noteLine = buildString {
                if (r.counterparty != null) {
                    append(if (r.kind == "sent") "to " else "from ")
                    append(r.counterparty.take(6) + "…" + r.counterparty.takeLast(4))
                } else if (r.note != null) append(r.note)
            }
            if (noteLine.isNotEmpty()) {
                Text(noteLine, color = OffpayColors.InkSoft, fontSize = 12.sp,
                     fontFamily = FontFamily.Monospace)
            }
            Text(relativeTs(r.ts), color = OffpayColors.InkMuted, fontSize = 10.sp,
                 fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp)
            if (r.txHash != null) {
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("tx ${r.txHash.take(20)}…",
                        color = OffpayColors.TealDeep, fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace)
                    Text("· OPEN ›", color = OffpayColors.TealDeep,
                        fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun relativeTs(ms: Long): String {
    val now = System.currentTimeMillis()
    val diffSec = ((now - ms) / 1000).coerceAtLeast(0)
    return when {
        diffSec < 60     -> "just now"
        diffSec < 3600   -> "${diffSec / 60} min ago"
        diffSec < 86400  -> "${diffSec / 3600} h ago"
        diffSec < 604800 -> "${diffSec / 86400} d ago"
        else             -> "earlier"
    }
}
