package com.offlinepay.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TopupScreen(
    amount: String,
    onAmountChange: (String) -> Unit,
    busy: Boolean,
    status: String,
    statusKind: StatusKind,
    onTopup: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(OffpayColors.White)
    ) {
        TopBar(title = "TOP UP", onClose = onClose)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(OffpayColors.Ink)
                .padding(24.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                   modifier = Modifier.fillMaxWidth()) {
                MonoLabel("ADD TO LOCKED BALANCE", color = Color.White.copy(alpha = 0.55f))
                Spacer(Modifier.height(10.dp))
                AmountField(amount, onAmountChange, ink = Color.White, currency = "$")
                Spacer(Modifier.height(8.dp))
                MonoLabel("USDC · ON-CHAIN LOCK", color = Color.White.copy(alpha = 0.45f))
            }
        }

        Spacer(Modifier.height(16.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            FlowRow("1", "Backend funds gas + mints USDC", busy && statusKind == StatusKind.Working)
            FlowRow("2", "Wallet signs approve", busy && statusKind == StatusKind.Working)
            FlowRow("3", "Wallet signs lockFunds", busy && statusKind == StatusKind.Working)
        }

        Spacer(Modifier.height(20.dp))
        StatusLine(status, statusKind, modifier = Modifier.padding(horizontal = 16.dp))

        Spacer(Modifier.weight(1f))

        Box(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Surface(
                onClick = onTopup,
                shape = RoundedCornerShape(20.dp),
                color = if (busy) OffpayColors.OffWhite else OffpayColors.Ink,
                modifier = Modifier.fillMaxWidth().height(60.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(if (busy) "Working…" else "Top up",
                         color = if (busy) OffpayColors.InkSoft else Color.White,
                         fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun FlowRow(num: String, label: String, working: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (working) OffpayColors.TealSoft else OffpayColors.OffWhite)
                .border(1.dp, OffpayColors.Hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(num, color = if (working) OffpayColors.TealDeep else OffpayColors.InkMuted,
                 fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Text(label, color = OffpayColors.InkSoft, fontSize = 13.sp,
             fontWeight = FontWeight.Medium)
    }
}
