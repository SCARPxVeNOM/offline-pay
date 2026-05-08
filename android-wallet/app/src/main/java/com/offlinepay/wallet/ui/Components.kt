package com.offlinepay.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/// All-caps mono micro label. Used heavily in the design.
@Composable
fun MonoLabel(
    text: String,
    color: Color = OffpayColors.InkMuted,
    fontSize: androidx.compose.ui.unit.TextUnit = 9.sp,
    letterSpacing: androidx.compose.ui.unit.TextUnit = 1.6.sp,
) {
    Text(
        text = text.uppercase(),
        color = color,
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = fontSize,
        letterSpacing = letterSpacing,
    )
}

/// Pulsing teal status dot used on the balance card and the connect-wallet pill.
@Composable
fun PulseDot(color: Color = OffpayColors.Teal, size: androidx.compose.ui.unit.Dp = 6.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}

/// Section header with right-side mono "more" affordance.
@Composable
fun SectionHeader(title: String, more: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, color = OffpayColors.Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        MonoLabel(more, fontSize = 10.sp, letterSpacing = 1.6.sp)
    }
}

/// Soft hairline divider used inside cards.
@Composable
fun HairlineDivider(color: Color = OffpayColors.Hairline) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(color))
}

/// Pill button rendered in the navy ink color.
@Composable
fun InkPill(
    label: String,
    onClick: () -> Unit,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    fillContainer: Boolean = false,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = OffpayColors.Ink,
        modifier = if (fillContainer) Modifier.fillMaxWidth() else Modifier
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            if (leadingIcon != null) leadingIcon()
            Text(label.uppercase(), color = Color.White, fontWeight = FontWeight.Bold,
                 fontSize = 11.sp, letterSpacing = 0.6.sp)
            if (trailingIcon != null) trailingIcon()
        }
    }
}

/// Compose representation of the radial teal halo (top-right) used on the dashboard background.
fun tealHaloBrush(): Brush = Brush.radialGradient(
    colors = listOf(OffpayColors.TealSoft, Color.Transparent),
    radius = 540f,
)
