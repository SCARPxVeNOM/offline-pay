package com.offlinepay.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinepay.wallet.DiscoveredEsp
import com.offlinepay.wallet.EspBondState

data class EspScreenState(
    val bond: EspBondState = EspBondState(),
    val discovered: List<DiscoveredEsp> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
)

@Composable
fun EspScreen(
    state: EspScreenState,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onPair: (DiscoveredEsp) -> Unit,
    onForget: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(OffpayColors.White)
    ) {
        TopBar(title = "READER · CONTROL CENTER", onClose = onClose)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            CurrentBondCard(state.bond, onForget = onForget)
            Spacer(Modifier.height(16.dp))

            SectionHeader(
                title = "Available readers",
                more = if (state.busy) "" else "REFRESH",
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !state.busy, onClick = onRefresh)
            ) { Spacer(Modifier.height(2.dp)) }

            if (state.discovered.isEmpty()) {
                EmptyDiscovered()
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.dp, OffpayColors.Hairline, RoundedCornerShape(18.dp))
                        .background(Color.White)
                ) {
                    state.discovered.forEachIndexed { idx, dev ->
                        DeviceRow(
                            dev = dev,
                            isCurrent = (state.bond.btMac == dev.btMac),
                            busy = state.busy,
                            onPair = { onPair(dev) },
                        )
                        if (idx != state.discovered.lastIndex) HairlineDivider()
                    }
                }
            }

            if (state.error != null) {
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFFFFE6E2))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Outlined.ErrorOutline, null,
                            tint = Color(0xFFB30E00), modifier = Modifier.size(16.dp))
                        Text(state.error, color = Color(0xFFB30E00), fontSize = 13.sp)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            HelpBlock()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CurrentBondCard(bond: EspBondState, onForget: () -> Unit) {
    val title = if (bond.isPaired) "Paired reader" else "No reader paired"
    val sub = when {
        !bond.isPaired -> "TAP A DEVICE BELOW TO CLAIM OWNERSHIP"
        bond.lastSeenMs != null -> "LAST CONTACT · ${relativeMs(bond.lastSeenMs)}"
        else -> "READY"
    }
    Box(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(if (bond.isPaired) OffpayColors.TealSoft else OffpayColors.OffWhite)
            .border(1.dp, OffpayColors.Hairline, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Bluetooth, null, tint = OffpayColors.TealDeep,
                    modifier = Modifier.size(18.dp))
                Text(title, color = OffpayColors.Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(6.dp))
            MonoLabel(sub)
            if (bond.isPaired) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "ESP32  ${shortAddr(bond.espAddress)}",
                    color = OffpayColors.Ink,
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                )
                Text(
                    "BT     ${bond.btName ?: "—"}  (${bond.btMac ?: "—"})",
                    color = OffpayColors.InkSoft,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        onClick = onForget,
                        shape = RoundedCornerShape(12.dp),
                        color = OffpayColors.OffWhite,
                        modifier = Modifier.height(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 14.dp)) {
                            Text("Forget reader", color = OffpayColors.Ink,
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    dev: DiscoveredEsp,
    isCurrent: Boolean,
    busy: Boolean,
    onPair: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onPair)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Sensors, null, tint = OffpayColors.TealDeep,
                modifier = Modifier.size(18.dp))
            Column(Modifier.weight(1f)) {
                Text(dev.btName, color = OffpayColors.Ink,
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(dev.btMac, color = OffpayColors.InkSoft,
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = OffpayColors.TealDeep, strokeWidth = 2.dp)
            } else if (isCurrent) {
                MonoLabel("PAIRED", color = OffpayColors.TealDeep)
            } else {
                Text("Pair", color = OffpayColors.Ink,
                    fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun EmptyDiscovered() {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, OffpayColors.Hairline, RoundedCornerShape(18.dp))
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.BluetoothDisabled, null,
                tint = OffpayColors.InkSoft, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text("No bonded readers", color = OffpayColors.Ink,
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Pair the reader once in Android's Bluetooth settings\n(name: OfflinePay_Reader), then come back.",
                color = OffpayColors.InkSoft, fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun HelpBlock() {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(OffpayColors.OffWhite)
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MonoLabel("HOW PAIRING WORKS")
            Text(
                "1. ESP32 sends a fresh challenge over Bluetooth.\n" +
                "2. Your phone signs it with its on-chain wallet key.\n" +
                "3. ESP32 verifies the signature and stores your address as owner.\n" +
                "4. Re-pair from any phone to switch ownership instantly.",
                color = OffpayColors.InkSoft, fontSize = 12.sp,
            )
        }
    }
}

private fun shortAddr(addr: String?): String =
    if (addr == null) "—" else addr.take(8) + "…" + addr.takeLast(6)

private fun relativeMs(ms: Long): String {
    val s = (System.currentTimeMillis() - ms) / 1000
    return when {
        s < 60     -> "JUST NOW"
        s < 3600   -> "${s / 60}M AGO"
        s < 86400  -> "${s / 3600}H AGO"
        else       -> "${s / 86400}D AGO"
    }
}
