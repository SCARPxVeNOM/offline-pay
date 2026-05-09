package com.offlinepay.wallet.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ReceiveState(
    val walletAddress: String,
    val status: String = "scanning… tap a sender phone to your back",
    val statusKind: StatusKind = StatusKind.Idle,
    val recent: List<RecentRow> = emptyList(),
    val pendingCount: Int = 0,
    val btConnected: Boolean = false,
    val meshPeerCount: Int = 0,
)

@Composable
fun ReceiveScreen(
    state: ReceiveState,
    qrBitmap: android.graphics.Bitmap?,
    onSettleNow: () -> Unit,
    onScanQr: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(OffpayColors.White)
    ) {
        TopBar(title = "TAP TO RECEIVE", onClose = onClose)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // QR card
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(OffpayColors.OffWhite)
                    .padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MonoLabel("YOUR ADDRESS")
                    Spacer(Modifier.height(8.dp))
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "address QR",
                            modifier = Modifier
                                .size(200.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White)
                                .padding(8.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        state.walletAddress,
                        color = OffpayColors.InkSoft,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            StatusLine(state.status, state.statusKind)
            if (state.btConnected) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(OffpayColors.TealSoft)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PulseDot(size = 6.dp)
                        MonoLabel("ESP32 READER CONNECTED", color = OffpayColors.TealDeep)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            MeshPill(state.meshPeerCount)
            Spacer(Modifier.height(12.dp))

            if (state.pendingCount > 0) {
                PendingPill(state.pendingCount, onSettleNow)
                Spacer(Modifier.height(12.dp))
            }

            SectionHeader("Recent received", if (state.recent.isEmpty()) "" else "SEE ALL ›")
            if (state.recent.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.dp, OffpayColors.Hairline, RoundedCornerShape(18.dp))
                        .padding(20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    MonoLabel("Nothing yet · tap to receive")
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.dp, OffpayColors.Hairline, RoundedCornerShape(18.dp))
                        .background(Color.White)
                ) {
                    state.recent.forEachIndexed { idx, r ->
                        ActivityRow(r)
                        if (idx != state.recent.lastIndex) HairlineDivider()
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                onClick = onScanQr,
                shape = RoundedCornerShape(20.dp),
                color = OffpayColors.OffWhite,
                modifier = Modifier.weight(1f).height(54.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.QrCodeScanner, null, tint = OffpayColors.Ink)
                        Text("Scan QR", color = OffpayColors.Ink,
                             fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Surface(
                onClick = onSettleNow,
                shape = RoundedCornerShape(20.dp),
                color = OffpayColors.Ink,
                modifier = Modifier.weight(1f).height(54.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.AutoAwesome, null, tint = Color.White)
                        Text("Settle now", color = Color.White,
                             fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun MeshPill(peerCount: Int) {
    val (label, sub) = when (peerCount) {
        0 -> "Mesh: searching for peers…" to "ADVERTISING + DISCOVERING"
        1 -> "Mesh: 1 peer connected" to "P2P RELAY ACTIVE"
        else -> "Mesh: $peerCount peers connected" to "P2P RELAY ACTIVE"
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (peerCount > 0) OffpayColors.TealSoft else OffpayColors.OffWhite)
            .border(1.dp, OffpayColors.Hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Hub, null, tint = OffpayColors.TealDeep, modifier = Modifier.size(16.dp))
            Column {
                Text(label, color = OffpayColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                MonoLabel(sub)
            }
        }
    }
}

@Composable
private fun PendingPill(count: Int, onSettleNow: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(OffpayColors.TealSoft)
            .clickable(onClick = onSettleNow)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("$count voucher${if (count==1) "" else "s"} pending settle",
                     color = OffpayColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                MonoLabel("WAITING FOR INTERNET · WILL AUTO-SETTLE")
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = OffpayColors.TealDeep)
        }
    }
}

@Composable
private fun ActivityRow(r: RecentRow) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val rowMod = Modifier
        .fillMaxWidth()
        .let { base ->
            if (r.explorerUrl != null) base.clickable {
                ctx.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(r.explorerUrl))
                )
            } else base
        }
        .padding(horizontal = 14.dp, vertical = 12.dp)
    Row(
        rowMod,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (r.incoming) OffpayColors.TealSoft else OffpayColors.OffWhite),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (r.incoming) Icons.Outlined.SouthWest else Icons.Outlined.NorthEast,
                null,
                tint = if (r.incoming) OffpayColors.TealDeep else OffpayColors.Ink,
                modifier = Modifier.size(16.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(r.title, color = OffpayColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            MonoLabel(r.sub, fontSize = 9.5.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(r.amountSigned, color = if (r.incoming) OffpayColors.TealDeep else OffpayColors.Ink,
                 fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (r.explorerUrl != null) {
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    MonoLabel("EXPLORER", fontSize = 8.sp, letterSpacing = 1.4.sp,
                              color = OffpayColors.TealDeep)
                    Icon(Icons.Outlined.OpenInNew, null, tint = OffpayColors.TealDeep,
                         modifier = Modifier.size(10.dp))
                }
            }
        }
    }
}
