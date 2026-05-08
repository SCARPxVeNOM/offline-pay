package com.offlinepay.wallet.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/// OFFPAY palette pulled from the design mockup. Ink = navy, teal = primary
/// accent, warm offwhite for surfaces.
object OffpayColors {
    val Ink           = Color(0xFF1E2A3A)
    val InkSoft       = Color(0xFF3A4A5F)
    val InkMuted      = Color(0xFF6E7B8C)
    val White         = Color(0xFFFFFFFF)
    val OffWhite      = Color(0xFFF4F8F7)
    val Hairline      = Color(0x141E2A3A)   // alpha 8%
    val HairlineStrong= Color(0x241E2A3A)   // alpha 14%
    val Teal          = Color(0xFF14D0C4)
    val TealDeep      = Color(0xFF0FAFA4)
    val TealSoft      = Color(0x2414D0C4)   // alpha 14%
    val TealWash      = Color(0x4814D0C4)   // alpha 28%

    val Bg            = Color(0xFFFAFDFC)
    val BgEnd         = Color(0xFFD6DFDE)
    val Danger        = Color(0xFFE5484D)
    val Success       = TealDeep
}

private val OffpayLight = lightColorScheme(
    primary = OffpayColors.Teal,
    onPrimary = OffpayColors.Ink,
    secondary = OffpayColors.Ink,
    onSecondary = OffpayColors.White,
    background = OffpayColors.White,
    onBackground = OffpayColors.Ink,
    surface = OffpayColors.White,
    onSurface = OffpayColors.Ink,
    surfaceVariant = OffpayColors.OffWhite,
    onSurfaceVariant = OffpayColors.InkSoft,
    error = OffpayColors.Danger,
)

private val OffpayShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small      = RoundedCornerShape(12.dp),
    medium     = RoundedCornerShape(18.dp),
    large      = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/// Inter for UI, Archivo Black for big numerics, JetBrains Mono for label
/// micro-meta. We don't ship them yet — falls back to system sans + monospace.
val InterFamily      = FontFamily.Default
val DisplayFamily    = FontFamily.SansSerif
val MonoFamily       = FontFamily.Monospace

private val OffpayType = Typography(
    displayLarge = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.Black, fontSize = 38.sp, letterSpacing = (-0.5).sp),
    displayMedium = TextStyle(fontFamily = DisplayFamily, fontWeight = FontWeight.Black, fontSize = 28.sp, letterSpacing = (-0.3).sp),
    titleLarge   = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleMedium  = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    bodyLarge    = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodyMedium   = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp),
    labelLarge   = TextStyle(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
    labelMedium  = TextStyle(fontFamily = MonoFamily,  fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.6.sp),
    labelSmall   = TextStyle(fontFamily = MonoFamily,  fontWeight = FontWeight.Medium, fontSize = 9.sp, letterSpacing = 1.4.sp),
)

@Composable
fun OffpayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OffpayLight,
        typography  = OffpayType,
        shapes      = OffpayShapes,
        content     = content
    )
}
