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
    /// First entry of the recipient address. Both fields must match
    /// AND be a valid 0x + 40 hex string for `confirmedRecipient` to
    /// resolve to non-null. QR scanner fills both at once.
    val recipient: String = "",
    val recipientConfirm: String = "",
    val amount: String = "1.00",
    val busy: Boolean = false,
    val status: String = "",
    val statusKind: StatusKind = StatusKind.Idle,
    val error: String? = null,
) {
    val confirmedRecipient: String?
        get() {
            val a = recipient.trim().lowercase()
            val b = recipientConfirm.trim().lowercase()
            return if (a.isNotBlank() && a == b && a.matches(Regex("^0x[0-9a-f]{40}$"))) a
                   else null
        }

    val typoMismatch: Boolean
        get() = recipient.isNotBlank() && recipientConfirm.isNotBlank() &&
                recipient.trim().lowercase() != recipientConfirm.trim().lowercase()
}

@Composable
fun CardWriteScreen(
    state: CardWriteState,
    onClose: () -> Unit,
    onAmountChange: (String) -> Unit,
    onRecipientChange: (String) -> Unit,
    onRecipientConfirmChange: (String) -> Unit,
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

            val recipientLabel = when {
                state.confirmedRecipient != null -> "✓ MATCHED"
                state.typoMismatch               -> "✗ DOES NOT MATCH"
                state.recipient.isBlank()        -> "REQUIRED"
                else                             -> "RE-TYPE TO CONFIRM"
            }
            SectionHeader("Recipient", recipientLabel)
            RecipientCard(
                state = state,
                onChange = onRecipientChange,
                onChangeConfirm = onRecipientConfirmChange,
                onScanQr = onScanQr,
            )
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
            val canWrite = state.bond.isPaired &&
                    state.confirmedRecipient != null &&
                    !state.busy
            Surface(
                onClick = { if (canWrite) onWrite() },
                shape = RoundedCornerShape(20.dp),
                color = if (canWrite) OffpayColors.Ink else OffpayColors.InkSoft,
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
private fun RecipientCard(
    state: CardWriteState,
    onChange: (String) -> Unit,
    onChangeConfirm: (String) -> Unit,
    onScanQr: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, OffpayColors.Hairline, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Column {
            MonoLabel("ENTER RECIPIENT 0x… ADDRESS")
            Spacer(Modifier.height(6.dp))
            AddressBox(
                value = state.recipient,
                onChange = onChange,
                placeholder = "0x…",
            )
            Spacer(Modifier.height(8.dp))
            MonoLabel("RE-TYPE TO CONFIRM")
            Spacer(Modifier.height(6.dp))
            AddressBox(
                value = state.recipientConfirm,
                onChange = onChangeConfirm,
                placeholder = "0x…",
                outlineColor = when {
                    state.confirmedRecipient != null -> OffpayColors.TealDeep
                    state.typoMismatch               -> Color(0xFFB30E00)
                    else                             -> OffpayColors.Hairline
                },
            )

            if (state.typoMismatch) {
                Spacer(Modifier.height(6.dp))
                Text("Addresses don't match — type the second one again or scan a QR.",
                    color = Color(0xFFB30E00), fontSize = 12.sp)
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
                            if (state.confirmedRecipient != null) "Re-scan QR (overwrite)"
                            else "Or scan recipient QR",
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
private fun AddressBox(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    outlineColor: Color = OffpayColors.Hairline,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, outlineColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        if (value.isBlank()) {
            Text(placeholder, color = OffpayColors.InkMuted,
                fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(
                color = OffpayColors.Ink,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
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
