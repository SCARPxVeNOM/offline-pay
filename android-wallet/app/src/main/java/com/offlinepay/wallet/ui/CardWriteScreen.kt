package com.offlinepay.wallet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlinepay.wallet.EspBondState

data class CardWriteState(
    val bond: EspBondState = EspBondState(),
    val recipient: String = "",
    val amount: String = "1.00",
    val busy: Boolean = false,
    val status: String = "",
    val statusKind: StatusKind = StatusKind.Idle,
    val error: String? = null,
)

@Composable
fun CardWriteScreen(
    state: CardWriteState,
    onClose: () -> Unit,
    onAmountChange: (String) -> Unit,
    onScanQr: () -> Unit,
    onWrite: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(OffpayColors.White)
    ) {
        TopBar(title = "WRITE TO CARD", onClose = onClose)

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            // Reader status — without a paired ESP32, this whole flow is dead.
            ReaderState(state.bond)
            Spacer(Modifier.height(20.dp))

            SectionHeader("Recipient", if (state.recipient.isBlank()) "REQUIRED" else "")
            RecipientCard(state.recipient, onScanQr = onScanQr)
            Spacer(Modifier.height(16.dp))

            SectionHeader("Amount", "USDC")
            AmountField(state.amount, onAmountChange)

            if (state.status.isNotBlank()) {
                Spacer(Modifier.height(20.dp))
                StatusLine(state.status, state.statusKind)
            }
            if (state.error != null) {
                Spacer(Modifier.height(8.dp))
                ErrorLine(state.error)
            }

            Spacer(Modifier.height(20.dp))
            HelpText()
            Spacer(Modifier.height(28.dp))
        }

        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            Surface(
                onClick = { if (!state.busy) onWrite() },
                shape = RoundedCornerShape(20.dp),
                color = if (state.bond.isPaired && !state.busy) OffpayColors.Ink
                        else OffpayColors.InkSoft,
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (state.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.CreditCard, null, tint = Color.White)
                            Text("Write voucher to card", color = Color.White,
                                fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderState(bond: EspBondState) {
    val (label, sub) = if (bond.isPaired) {
        "Reader: paired" to "ESP32 ${bond.espAddress?.take(10)}…"
    } else {
        "Reader: not paired" to "PAIR AN ESP32 BEFORE WRITING (HOME → READER)"
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (bond.isPaired) OffpayColors.TealSoft else OffpayColors.OffWhite)
            .border(1.dp, OffpayColors.Hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.Sensors, null, tint = OffpayColors.TealDeep,
                modifier = Modifier.size(16.dp))
            Column {
                Text(label, color = OffpayColors.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                MonoLabel(sub)
            }
        }
    }
}

@Composable
private fun RecipientCard(recipient: String, onScanQr: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, OffpayColors.Hairline, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Column {
            if (recipient.isBlank()) {
                Text("Scan the merchant's QR code to lock the recipient.",
                    color = OffpayColors.InkSoft, fontSize = 13.sp)
            } else {
                MonoLabel("RECIPIENT (V3 — SIGNED INTO VOUCHER)")
                Spacer(Modifier.height(6.dp))
                Text(recipient, color = OffpayColors.Ink,
                    fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(12.dp))
            Surface(
                onClick = onScanQr,
                shape = RoundedCornerShape(14.dp),
                color = OffpayColors.OffWhite,
                modifier = Modifier.height(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Outlined.QrCodeScanner, null, tint = OffpayColors.Ink,
                            modifier = Modifier.size(16.dp))
                        Text(
                            if (recipient.isBlank()) "Scan recipient QR" else "Re-scan",
                            color = OffpayColors.Ink, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AmountField(amount: String, onChange: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, OffpayColors.Hairline, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("$", color = OffpayColors.InkSoft, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            BasicTextField(
                value = amount,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = OffpayColors.Ink,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            Text("USDC", color = OffpayColors.InkSoft,
                fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ErrorLine(reason: String) {
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
            Text(reason, color = Color(0xFFB30E00), fontSize = 13.sp)
        }
    }
}

@Composable
private fun HelpText() {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(OffpayColors.OffWhite)
            .padding(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MonoLabel("HOW WRITE WORKS")
            Text(
                "1. Phone signs a v3 voucher with the recipient address (no chain access).\n" +
                "2. Phone sends a signed WRITE command to the bonded reader over BT.\n" +
                "3. Reader verifies you're the owner, enters write mode, asks you to tap a card.\n" +
                "4. JSON payload lands on the MIFARE; carry it to the merchant's stall.",
                color = OffpayColors.InkSoft, fontSize = 12.sp,
            )
        }
    }
}
