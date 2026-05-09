package com.offlinepay.wallet

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

/// Compact card payload that crosses NFC / QR. v2 schema includes `recipient`
/// so a relay broadcaster can settle on the offline pair's behalf without
/// being able to redirect the funds.
@Serializable
data class CardVoucherPayload(
    @SerialName("v") val version: Int = 2,
    @SerialName("i") val voucherId: String,
    @SerialName("p") val payer: String,
    /// Defaults to the zero address. The encoder is configured with
    /// `encodeDefaults = false`, so when these fields equal the default
    /// they are omitted from the wire JSON. This shrinks bearer cards
    /// (where both are 0x0) by ~100 bytes — the difference between
    /// fitting a MIFARE Classic 1K (336B) or not. Recipient-bound
    /// vouchers set non-zero recipient explicitly, which gets included.
    @SerialName("m") val merchant: String = "0x0000000000000000000000000000000000000000",
    @SerialName("r") val recipient: String = "0x0000000000000000000000000000000000000000",
    @SerialName("a") val amount: String,
    @SerialName("e") val expiry: Long,
    @SerialName("n") val nonce: Long,
    @SerialName("s") val signature: String,
    /// Hardware UID of the MIFARE card / keyfob this voucher was bound to
    /// at top-up. Optional (null for phone-tap flows). Merchant's ESP32
    /// validates `cardUid == card.PICC_ReadCardSerial` before forwarding,
    /// so a voucher copied to a different card won't pass the reader.
    @SerialName("u") val cardUid: String? = null,
)

/// In-memory voucher fields used by verifier + Room DAO. `recipient` is
/// part of the digest now — the address the sender chose at sign time.
data class Voucher(
    val voucherId: String,
    val payer: String,
    val merchant: String,
    val recipient: String,
    val amount: java.math.BigInteger,
    val expiry: Long,
    val nonce: Long,
    val signature: String,
    /// MIFARE/keyfob hardware UID this voucher was bound to. Null for
    /// phone-tap (HCE) and QR/NFC flows. Set only when the voucher was
    /// loaded onto a physical card via `signBearerForCard`.
    val cardUid: String? = null,
) {
    val isTrueBearer: Boolean
        get() = recipient == "0x0000000000000000000000000000000000000000"

    companion object {
        fun fromCardPayload(p: CardVoucherPayload) = Voucher(
            voucherId = p.voucherId,
            payer     = p.payer,
            merchant  = p.merchant,
            recipient = p.recipient,
            amount    = java.math.BigInteger(p.amount),
            expiry    = p.expiry,
            nonce     = p.nonce,
            signature = p.signature,
            cardUid   = p.cardUid,
        )
    }
}

// Important: encodeDefaults = false. CardVoucherPayload has defaults for
// merchant + recipient (both 0x0) so they're omitted from bearer-card
// JSON, shrinking the payload to fit a MIFARE Classic 1K's 336-byte
// data area. Non-bearer (recipient-bound) vouchers carry non-default
// values which get serialized normally. The deserializer round-trips
// correctly because the missing fields fall back to the defaults above.
private val cardJson = Json { encodeDefaults = false; ignoreUnknownKeys = true }

/// Serialize a freshly-signed voucher into the wire JSON. Matches
/// backend/voucher.js:voucherToCardJson byte-for-byte. `cardUid` is
/// included only for bearer cards bound to a specific MIFARE.
fun VoucherSigner.SignedVoucher.toCardJson(cardUid: String? = null): String =
    cardJson.encodeToString(
        CardVoucherPayload.serializer(),
        CardVoucherPayload(
            voucherId = voucherId,
            payer     = payer,
            merchant  = merchant,
            recipient = recipient,
            amount    = amount.toString(),
            expiry    = expiry,
            nonce     = nonce,
            signature = signature,
            cardUid   = cardUid,
        )
    )

fun Voucher.Companion.fromCardJson(json: String): Voucher {
    val payload = cardJson.decodeFromString(CardVoucherPayload.serializer(), json)
    return fromCardPayload(payload)
}

/// Wire payload over NFC is a JSON ARRAY of card payloads (one or more
/// pre-signed bearer vouchers). Tolerates the legacy single-object form too.
fun Voucher.Companion.listFromWireJson(json: String): List<Voucher> {
    val trimmed = json.trim()
    return if (trimmed.startsWith("[")) {
        val arr = cardJson.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(CardVoucherPayload.serializer()),
            trimmed
        )
        arr.map { fromCardPayload(it) }
    } else {
        listOf(fromCardJson(trimmed))
    }
}

/// Build the JSON-array wire payload from a list of pre-signed cardPayload
/// strings (the exact JSON the backend issued). Avoids re-serialization to
/// preserve the digest-verifying byte order.
fun cardPayloadsToWireJson(cardPayloads: List<String>): String =
    cardPayloads.joinToString(prefix = "[", postfix = "]")
