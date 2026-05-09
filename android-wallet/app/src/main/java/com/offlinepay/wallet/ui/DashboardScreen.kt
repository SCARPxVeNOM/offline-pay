package com.offlinepay.wallet.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinepay.wallet.R

data class DashState(
    val walletAddress: String = "0x000…0000",
    val lockedUsdc: String = "0.00",
    val pendingCount: Int = 0,
    val pendingUsdc: String = "0.00",
    val syncedSecondsAgo: Int? = null,
    val settleStatus: String? = null,
    val recent: List<RecentRow> = emptyList(),
    val meshPeerCount: Int = 0,
)

data class RecentRow(
    val title: String,
    val sub: String,
    val amountSigned: String,
    val incoming: Boolean,
    /// If non-null, the row is clickable and opens the Polygon explorer.
    val explorerUrl: String? = null,
)

@Composable
fun DashboardScreen(
    state: DashState,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onTopup: () -> Unit,
    onHistory: () -> Unit,
    onBackup: () -> Unit,
    onSettleNow: () -> Unit,
    onAddressClick: () -> Unit = {},
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(OffpayColors.White)
            .drawBehind {
                // Soft teal halo top-right (matches dashboard.jsx ::before).
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            OffpayColors.TealSoft,
                            OffpayColors.Teal.copy(alpha = 0.04f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 1.05f, -size.height * 0.05f),
                        radius = size.width * 1.15f,
                    )
                )
            }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp, bottom = 28.dp)
        ) {
            DashHeader(walletShort = state.walletAddress.short(), onClick = onAddressClick)
            ConnectWalletPill(walletAddress = state.walletAddress, onClick = onBackup)
            BalanceCard(
                lockedUsdc = state.lockedUsdc,
                syncedSecondsAgo = state.syncedSecondsAgo,
                onTopup = onTopup
            )
            TapHero(onSend = onSend, onReceive = onReceive)
            if (state.settleStatus != null) SettleBanner(state.settleStatus, onSettleNow)
            if (state.pendingCount > 0) PendingBanner(state.pendingCount, state.pendingUsdc, onSettleNow)
            if (state.meshPeerCount > 0) MeshStatusBanner(state.meshPeerCount)
            SectionHeader("Quick actions", "View all ›")
            QuickActions(onTopup = onTopup, onBackup = onBackup, onHistory = onHistory)
            SectionHeader("Recent activity", "See all ›")
            RecentActivity(state.recent)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DashHeader(walletShort: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.clickable { onClick() },
        ) {
            // Avatar with the OFFPAY hand-coin logo, small.
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(OffpayColors.Ink),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(id = R.drawable.offpay_logo),
                    contentDescription = "OFFPAY",
                    modifier = Modifier.size(28.dp),
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White),
                )
            }
            Column {
                MonoLabel("WALLET · LIVE")
                Text(walletShort, color = OffpayColors.Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
        }
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .border(1.dp, OffpayColors.HairlineStrong, CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Outlined.NotificationsNone,
                contentDescription = "alerts",
                tint = OffpayColors.Ink,
                modifier = Modifier.size(20.dp),
            )
            // Teal status dot in upper-right of the bell.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 9.dp)
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(OffpayColors.Teal)
                    .border(2.dp, Color.White, CircleShape)
            )
        }
    }
}

@Composable
private fun ConnectWalletPill(walletAddress: String, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 10.dp)
            .fillMaxWidth()
            .clip(CircleShape)
            .background(Color.White)
            .border(1.dp, OffpayColors.HairlineStrong, CircleShape)
            .clickable(onClick = onClick)
    ) {
        // Left teal stripe.
        Box(
            Modifier
                .fillMaxHeight()
                .width(4.dp)
                .background(OffpayColors.Teal)
                .align(Alignment.CenterStart)
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(OffpayColors.TealSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Link,
                    contentDescription = null,
                    tint = OffpayColors.TealDeep,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text("Wallet · Tap to back up", color = OffpayColors.Ink,
                     fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PulseDot(size = 5.dp)
                    MonoLabel("OFFPAY · ${walletAddress.short()}", fontSize = 9.5.sp)
                }
            }
            InkPill(label = "Manage", onClick = onClick, trailingIcon = {
                Icon(Icons.Outlined.ChevronRight, null, tint = Color.White, modifier = Modifier.size(14.dp))
            })
        }
    }
}

@Composable
private fun BalanceCard(lockedUsdc: String, syncedSecondsAgo: Int?, onTopup: () -> Unit) {
    val (whole, dec) = lockedUsdc.split(".", limit = 2).let {
        (it.getOrNull(0) ?: "0") to (it.getOrNull(1) ?: "00").padEnd(2, '0').take(2)
    }
    Box(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(OffpayColors.Ink)
            .padding(20.dp)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(OffpayColors.TealWash, Color.Transparent),
                        radius = size.width * 0.6f,
                    ),
                    radius = size.width * 0.55f,
                    center = Offset(size.width + 30f, -30f),
                )
            }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.Visibility, null,
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp))
                    Text("LOCKED BALANCE",
                        color = Color.White.copy(alpha = 0.7f),
                        fontFamily = MonoFamily, fontSize = 10.sp,
                        letterSpacing = 2.sp, fontWeight = FontWeight.Medium)
                }
                Box(Modifier
                    .size(32.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.ChevronRight, null,
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(16.dp))
                }
            }
            Row(verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("$", color = Color.White.copy(alpha = 0.85f),
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 6.dp))
                Text(whole, color = Color.White, fontSize = 38.sp,
                    fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp,
                    fontFamily = DisplayFamily)
                Text(".$dec", color = Color.White.copy(alpha = 0.7f),
                    fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 4.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    PulseDot(size = 6.dp)
                    Text(
                        if (syncedSecondsAgo == null) "Offline · last cached"
                        else "Synced ${syncedSecondsAgo}s ago · Offline ready",
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 11.sp,
                    )
                }
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f))
                        .clickable(onClick = onTopup)
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Outlined.Add, null, tint = Color.White,
                             modifier = Modifier.size(14.dp))
                        Text("Top up", color = Color.White, fontSize = 11.sp,
                             fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun TapHero(onSend: () -> Unit, onReceive: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TapCard(
            title = "Tap to Pay",
            sub = "SEND · NFC",
            background = OffpayColors.Teal,
            iconBg = OffpayColors.Ink.copy(alpha = 0.14f),
            iconTint = OffpayColors.Ink,
            cornerBg = OffpayColors.Ink.copy(alpha = 0.10f),
            cornerTint = OffpayColors.Ink,
            cornerIcon = Icons.Outlined.NorthEast,
            modifier = Modifier.weight(1f),
            onClick = onSend,
        )
        TapCard(
            title = "Tap to Receive",
            sub = "ACCEPT · NFC",
            background = Color.White,
            iconBg = OffpayColors.Ink,
            iconTint = Color.White,
            cornerBg = OffpayColors.TealSoft,
            cornerTint = OffpayColors.TealDeep,
            cornerIcon = Icons.Outlined.SouthWest,
            modifier = Modifier.weight(1f),
            onClick = onReceive,
            border = true,
        )
    }
}

@Composable
private fun TapCard(
    title: String,
    sub: String,
    background: Color,
    iconBg: Color,
    iconTint: Color,
    cornerBg: Color,
    cornerTint: Color,
    cornerIcon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    border: Boolean = false,
) {
    Box(
        modifier
            .height(150.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(background)
            .let { if (border) it.border(1.dp, OffpayColors.Hairline, RoundedCornerShape(22.dp)) else it }
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        // Corner arrow
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(28.dp)
                .clip(CircleShape)
                .background(cornerBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(cornerIcon, null, tint = cornerTint, modifier = Modifier.size(14.dp))
        }
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(iconBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Wifi, null, tint = iconTint, modifier = Modifier.size(28.dp))
            }
            Column {
                Text(title, color = OffpayColors.Ink, fontSize = 18.sp,
                     fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp)
                Spacer(Modifier.height(4.dp))
                MonoLabel(sub, fontSize = 11.sp, letterSpacing = 1.6.sp,
                    color = OffpayColors.Ink.copy(alpha = 0.65f))
            }
        }
    }
}

@Composable
private fun SettleBanner(text: String, onSettle: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(OffpayColors.OffWhite)
            .border(1.dp, OffpayColors.Hairline, RoundedCornerShape(14.dp))
            .clickable(onClick = onSettle)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = OffpayColors.TealDeep, modifier = Modifier.size(16.dp))
            Text(text, color = OffpayColors.InkSoft, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun PendingBanner(count: Int, totalUsdc: String, onSettleNow: () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(OffpayColors.TealSoft)
            .clickable(onClick = onSettleNow)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("$count voucher${if (count==1) "" else "s"} pending settle",
                     color = OffpayColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                MonoLabel("\$$totalUsdc · TAPPED OFFLINE")
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = OffpayColors.TealDeep)
        }
    }
}

@Composable
private fun MeshStatusBanner(peerCount: Int) {
    Box(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(OffpayColors.OffWhite)
            .border(1.dp, OffpayColors.Hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Outlined.Hub, null, tint = OffpayColors.TealDeep, modifier = Modifier.size(16.dp))
            Column {
                Text(
                    "Mesh: $peerCount peer${if (peerCount == 1) "" else "s"} connected",
                    color = OffpayColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                )
                MonoLabel("P2P BACKUP ACTIVE")
            }
        }
    }
}

@Composable
private fun QuickActions(onTopup: () -> Unit, onBackup: () -> Unit, onHistory: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        QuickAction(
            icon = Icons.Outlined.Link,
            label = "Wallet Connect",
            meta = "LINK",
            accent = true,
            onClick = onBackup,
            modifier = Modifier.weight(1f),
        )
        QuickAction(
            icon = Icons.Outlined.LocalActivity,
            label = "Topup Voucher",
            meta = "REDEEM",
            onClick = onTopup,
            modifier = Modifier.weight(1f),
        )
        QuickAction(
            icon = Icons.Outlined.History,
            label = "History",
            meta = "ALL TXNS",
            onClick = onHistory,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    meta: String,
    onClick: () -> Unit,
    accent: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, OffpayColors.Hairline, RoundedCornerShape(18.dp))
            .background(Color.White)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 14.dp),
    ) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (accent) OffpayColors.TealSoft else OffpayColors.OffWhite),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null,
                    tint = if (accent) OffpayColors.TealDeep else OffpayColors.Ink,
                    modifier = Modifier.size(20.dp))
            }
            Text(label, color = OffpayColors.Ink, fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            MonoLabel(meta, fontSize = 8.5.sp)
        }
    }
}

@Composable
private fun RecentActivity(rows: List<RecentRow>) {
    if (rows.isEmpty()) {
        Box(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, OffpayColors.Hairline, RoundedCornerShape(18.dp))
                .padding(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            MonoLabel("No activity yet · Tap to start")
        }
        return
    }
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, OffpayColors.Hairline, RoundedCornerShape(18.dp))
            .background(Color.White)
    ) {
        rows.forEachIndexed { idx, r ->
            ActivityRow(r)
            if (idx != rows.lastIndex) HairlineDivider()
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

private fun String.short(prefix: Int = 6, suffix: Int = 4): String {
    if (length <= prefix + suffix + 1) return this
    return take(prefix) + "…" + takeLast(suffix)
}
