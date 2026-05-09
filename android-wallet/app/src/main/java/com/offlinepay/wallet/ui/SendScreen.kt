package com.offlinepay.wallet.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SendScreen(
    amount: String,
    onAmountChange: (String) -> Unit,
    armed: Boolean,
    status: String,
    statusKind: StatusKind,
    onArm: () -> Unit,
    onClose: () -> Unit,
    onWriteCard: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(OffpayColors.White)
    ) {
        TopBar(title = "TAP TO PAY", onClose = onClose)
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
                MonoLabel("AMOUNT TO SEND", color = Color.White.copy(alpha = 0.55f))
                Spacer(Modifier.height(10.dp))
                AmountField(amount, onAmountChange, ink = Color.White, currency = "$")
                Spacer(Modifier.height(8.dp))
                MonoLabel("USDC · BEARER VOUCHER", color = Color.White.copy(alpha = 0.45f))
            }
        }
        Spacer(Modifier.height(20.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .height(220.dp),
            contentAlignment = Alignment.Center,
        ) {
            NfcRipples(active = armed)
            Box(
                Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(if (armed) OffpayColors.Teal else OffpayColors.OffWhite),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.Wifi, null,
                    tint = if (armed) OffpayColors.Ink else OffpayColors.InkMuted,
                    modifier = Modifier.size(48.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        StatusLine(status, statusKind, modifier = Modifier.padding(horizontal = 16.dp))

        Spacer(Modifier.weight(1f))

        Box(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Surface(
                onClick = onArm,
                shape = RoundedCornerShape(20.dp),
                color = if (armed) OffpayColors.OffWhite else OffpayColors.Ink,
                modifier = Modifier.fillMaxWidth().height(60.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(if (armed) "Armed · waiting for tap…" else "Arm payment",
                         color = if (armed) OffpayColors.InkSoft else Color.White,
                         fontSize = 16.sp, fontWeight = FontWeight.Bold,
                         letterSpacing = 0.5.sp)
                }
            }
        }
        // Secondary action — write a voucher to a MIFARE card via the
        // bonded ESP32 reader. Visible when the activity wires it; null
        // when the user hasn't paired a reader yet.
        if (onWriteCard != null) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            ) {
                Surface(
                    onClick = onWriteCard,
                    shape = RoundedCornerShape(20.dp),
                    color = OffpayColors.OffWhite,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.CreditCard, null, tint = OffpayColors.Ink)
                            Text("Write to MIFARE card", color = OffpayColors.Ink,
                                fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TopBar(title: String, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .border(1.dp, OffpayColors.HairlineStrong, CircleShape)
                .background(Color.White)
                .clickable { onClose() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.ChevronLeft, null, tint = OffpayColors.Ink)
        }
        MonoLabel(title, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.size(40.dp))
    }
}

@Composable
internal fun AmountField(
    amount: String,
    onAmountChange: (String) -> Unit,
    ink: Color = OffpayColors.Ink,
    currency: String = "$",
) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()) {
        Text(currency, color = ink.copy(alpha = 0.6f), fontSize = 22.sp,
             fontWeight = FontWeight.SemiBold,
             modifier = Modifier.padding(end = 6.dp, top = 6.dp))
        BasicTextField(
            value = amount,
            onValueChange = onAmountChange,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            textStyle = TextStyle(
                color = ink,
                fontSize = 56.sp,
                fontWeight = FontWeight.Black,
                fontFamily = DisplayFamily,
                letterSpacing = (-1).sp,
                textAlign = TextAlign.Center,
            ),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(OffpayColors.Teal),
            modifier = Modifier.widthIn(min = 80.dp, max = 240.dp),
        )
    }
}

enum class StatusKind { Idle, Armed, Working, Success, Error }

@Composable
internal fun StatusLine(text: String, kind: StatusKind, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    val (bg, fg, icon) = when (kind) {
        StatusKind.Success -> Triple(OffpayColors.TealSoft, OffpayColors.TealDeep, Icons.Outlined.CheckCircle)
        StatusKind.Error   -> Triple(Color(0x14E5484D), OffpayColors.Danger, Icons.Outlined.ErrorOutline)
        StatusKind.Working -> Triple(OffpayColors.OffWhite, OffpayColors.InkSoft, Icons.Outlined.Sync)
        StatusKind.Armed   -> Triple(OffpayColors.TealSoft, OffpayColors.TealDeep, Icons.Outlined.Wifi)
        else               -> Triple(OffpayColors.OffWhite, OffpayColors.InkSoft, Icons.Outlined.Info)
    }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
internal fun NfcRipples(active: Boolean) {
    if (!active) return
    val transition = rememberInfiniteTransition(label = "ripples")
    Box(contentAlignment = Alignment.Center) {
        listOf(0, 300, 600).forEach { delayMs ->
            val scale by transition.animateFloat(
                initialValue = 0.4f, targetValue = 1.6f,
                animationSpec = infiniteRepeatable(
                    tween(2200, delayMillis = delayMs, easing = LinearEasing),
                    RepeatMode.Restart,
                ),
                label = "scale-$delayMs",
            )
            val alpha by transition.animateFloat(
                initialValue = 0.6f, targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    tween(2200, delayMillis = delayMs, easing = LinearEasing),
                    RepeatMode.Restart,
                ),
                label = "alpha-$delayMs",
            )
            Box(
                Modifier
                    .size((180 * scale).dp)
                    .clip(CircleShape)
                    .border(1.5.dp, OffpayColors.Teal.copy(alpha = alpha), CircleShape)
            )
        }
    }
}
