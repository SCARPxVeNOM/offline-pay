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
    /// MIFARE / keyfob hardware UID, typed by the user. Twice for
    /// typo-guard. Only 4-byte (8 hex) or 7-byte (14 hex) UIDs are
    /// accepted — the formats RC522 / NFC-A / MIFARE Classic produce.
    /// Spaces, dashes, colons are tolerated and stripped client-side
    /// so the user can paste in any common formatting.
    val cardUid: String = "",
    val cardUidConfirm: String = "",
    val amount: String = "1.00",
    val online: Boolean = false,
    val busy: Boolean = false,
    val status: String = "",
    val statusKind: StatusKind = StatusKind.Idle,
    val error: String? = null,
) {
    /// Both fields must be valid hex AND match exactly. Returns the
    /// canonicalized lowercase UID string; null otherwise.
    val confirmedCardUid: String?
        get() {
            val a = cardUid.normaliseUid()
            val b = cardUidConfirm.normaliseUid()
            return if (a != null && a == b) a else null
        }

    val typoMismatch: Boolean
        get() = cardUid.isNotBlank() && cardUidConfirm.isNotBlank() &&
                cardUid.normaliseUid() != cardUidConfirm.normaliseUid()
}

/// Strip common formatting characters and lowercase. Returns null if
/// the resulting string isn't a 4- or 7-byte hex UID.
private fun String.normaliseUid(): String? {
    val cleaned = trim().lowercase()
        .removePrefix("0x")
        .replace(":", "").replace("-", "").replace(" ", "")
    return if (cleaned.matches(Regex("^[0-9a-f]{8}$|^[0-9a-f]{14}$"))) cleaned else null
}

@Composable
fun CardWriteScreen(
    state: CardWriteState,
    onClose: () -> Unit,
    onAmountChange: (String) -> Unit,
    onCardUidChange: (String) -> Unit,
    onCardUidConfirmChange: (String) -> Unit,
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
            if (!state.online) {
                Spacer(Modifier.height(8.dp))
                OfflineBanner()
            }
            Spacer(Modifier.height(20.dp))

            val uidLabel = when {
                state.confirmedCardUid != null -> "✓ MATCHED"
                state.typoMismatch             -> "✗ DOES NOT MATCH"
                state.cardUid.isBlank()        -> "TYPE TWICE"
                else                           -> "CONFIRM UID"
            }
            SectionHeader("Card UID", uidLabel)
            CardUidCard(
                state = state,
                onChange = onCardUidChange,
                onChangeConfirm = onCardUidConfirmChange,
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
                    state.confirmedCardUid != null &&
                    state.online &&
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
private fun CardUidCard(
    state: CardWriteState,
    onChange: (String) -> Unit,
    onChangeConfirm: (String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, OffpayColors.Hairline, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Column {
            Text(
                "MIFARE / keyfob hardware UID printed on the card. " +
                "4 or 7 bytes hex (e.g. 04A1B2C3 or 04A1B2C3D4E5F6). " +
                "Spaces, dashes, colons OK.",
                color = OffpayColors.InkSoft, fontSize = 12.sp,
            )
            Spacer(Modifier.height(10.dp))
            MonoLabel("CARD UID")
            Spacer(Modifier.height(6.dp))
            AddressBox(
                value = state.cardUid,
                onChange = onChange,
                placeholder = "04A1B2C3…",
            )
            Spacer(Modifier.height(8.dp))
            MonoLabel("RE-TYPE")
            Spacer(Modifier.height(6.dp))
            AddressBox(
                value = state.cardUidConfirm,
                onChange = onChangeConfirm,
                placeholder = "04A1B2C3…",
                outlineColor = when {
                    state.confirmedCardUid != null -> OffpayColors.TealDeep
                    state.typoMismatch             -> Color(0xFFB30E00)
                    else                           -> OffpayColors.Hairline
                },
            )
            if (state.typoMismatch) {
                Spacer(Modifier.height(6.dp))
                Text("UIDs don't match — re-type the second one carefully.",
                    color = Color(0xFFB30E00), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun OfflineBanner() {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFFFFE6E2))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Outlined.CloudOff, null,
                tint = Color(0xFFB30E00), modifier = Modifier.size(16.dp))
            Text(
                "You need internet to write a card. The voucher is signed " +
                "locally but we verify your locked-balance on chain first.",
                color = Color(0xFFB30E00), fontSize = 12.sp,
            )
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
            MonoLabel("HOW BEARER CARDS WORK")
            Text(
                "1. You enter the card's UID twice + the amount (online — we check your locked balance).\n" +
                "2. Your phone signs a true-bearer voucher (recipient = 0) bound to that UID.\n" +
                "3. Voucher gets written to the MIFARE blocks via the bonded ESP32.\n" +
                "4. Anyone holding the card can spend at any merchant. The merchant's reader signs an\n" +
                "   endorsement at tap time committing to the merchant's primary wallet — that's who gets paid.\n" +
                "5. The mesh relays the settle on chain. Card is now empty.",
                color = OffpayColors.InkSoft, fontSize = 12.sp,
            )
        }
    }
}
