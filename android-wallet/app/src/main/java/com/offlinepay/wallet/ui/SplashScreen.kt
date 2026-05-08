package com.offlinepay.wallet.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinepay.wallet.R
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(durationMs: Long = 3500, onDone: () -> Unit) {
    var elapsed by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        delay(durationMs)
        onDone()
    }

    val transition = rememberInfiniteTransition(label = "ripples")
    val rippleScale by transition.animateFloat(
        initialValue = 0.5f, targetValue = 2.0f,
        animationSpec = infiniteRepeatable(
            tween(2000, easing = LinearEasing),
            RepeatMode.Restart,
        ),
        label = "rippleScale",
    )
    val rippleAlpha by transition.animateFloat(
        initialValue = 0.55f, targetValue = 0f,
        animationSpec = infiniteRepeatable(
            tween(2000, easing = LinearEasing),
            RepeatMode.Restart,
        ),
        label = "rippleAlpha",
    )

    val logoScale by animateFloatAsState(
        targetValue = 1f, animationSpec = tween(900, delayMillis = 400, easing = EaseOutBack),
        label = "logoScale",
    )
    val logoAlpha by animateFloatAsState(
        targetValue = 1f, animationSpec = tween(900, delayMillis = 400),
        label = "logoAlpha",
    )

    val wordmarkAlpha by animateFloatAsState(
        targetValue = 1f, animationSpec = tween(700, delayMillis = 1300),
        label = "wordAlpha",
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFFFAFDFC), Color(0xFFE8EFEE), Color(0xFFD6DFDE)),
                    radius = 1200f,
                )
            )
    ) {
        // bottom-left teal wash
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            OffpayColors.Teal.copy(alpha = 0.35f),
                            OffpayColors.Teal.copy(alpha = 0.10f),
                            Color.Transparent,
                        ),
                        center = androidx.compose.ui.geometry.Offset(0f, Float.POSITIVE_INFINITY),
                        radius = 900f,
                    )
                )
        )

        // Top corner meta
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MonoLabel("OFFPAY · v1.0", color = OffpayColors.Ink.copy(alpha = 0.55f))
            MonoLabel("SECURE BOOT", color = OffpayColors.Ink.copy(alpha = 0.55f))
        }

        // Center stage: ripples + logo
        Box(
            Modifier
                .align(Alignment.Center)
                .padding(bottom = 80.dp)
                .size(320.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 3 ripples staggered
            listOf(0f, 0.3f, 0.6f).forEach { delayFraction ->
                Box(
                    Modifier
                        .size(220.dp)
                        .scale(rippleScale + delayFraction)
                        .alpha((rippleAlpha - delayFraction * 0.2f).coerceAtLeast(0f))
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(Color.Transparent, OffpayColors.Teal.copy(alpha = 0.15f), Color.Transparent),
                                radius = 200f,
                            )
                        )
                )
            }

            Image(
                painter = painterResource(id = R.drawable.offpay_logo),
                contentDescription = "OFFPAY",
                modifier = Modifier
                    .size(180.dp)
                    .scale(logoScale)
                    .alpha(logoAlpha),
            )
        }

        // Wordmark + slogan
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 100.dp)
                .fillMaxWidth()
                .alpha(wordmarkAlpha),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "OFFPAY",
                color = OffpayColors.Ink,
                fontSize = 44.sp,
                fontWeight = FontWeight.Black,
                fontFamily = DisplayFamily,
                letterSpacing = (-1).sp,
            )
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .width(56.dp)
                    .height(2.dp)
                    .background(OffpayColors.Teal)
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoLabel("TAP NOW", color = OffpayColors.Ink, fontSize = 11.sp, letterSpacing = 2.4.sp)
                Box(Modifier.padding(horizontal = 8.dp).size(5.dp).clip(CircleShape).background(OffpayColors.Teal))
                MonoLabel("CHAIN LATER", color = OffpayColors.Ink, fontSize = 11.sp, letterSpacing = 2.4.sp)
            }
        }

        // Bottom progress
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 32.dp, vertical = 36.dp)
                .fillMaxWidth(),
        ) {
            // animated progress
            val progress by produceState(initialValue = 0f) {
                val start = System.currentTimeMillis()
                while (true) {
                    val v = ((System.currentTimeMillis() - start).toFloat() / durationMs).coerceIn(0f, 1f)
                    value = v
                    if (v >= 1f) break
                    delay(16L)
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                    .background(OffpayColors.Hairline)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .height(2.dp)
                        .background(OffpayColors.Ink)
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()) {
                MonoLabel("INITIALISING WALLET", color = OffpayColors.Ink.copy(alpha = 0.55f), fontSize = 10.sp, letterSpacing = 1.2.sp)
                MonoLabel("· · ·", color = OffpayColors.Ink.copy(alpha = 0.55f))
            }
        }
    }
}
