package com.offlinepay.wallet

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

/// Compact card payload as written on the MIFARE card, matching the JSON
/// produced by `backend/src/voucher.js:voucherToCardJson`.
@Serializable
data class CardVoucherPayload(
    @SerialName("v") val version: Int = 1,
    @SerialName("i") val voucherId: String,
    @SerialName("p") val payer: String,
    @SerialName("m") val merchant: String,
    @SerialName("a") val amount: String,
    @SerialName("e") val expiry: Long,
    @SerialName("n") val nonce: Long,
    @SerialName("s") val signature: String
)

/// In-memory voucher fields (fully-typed) used by the verifier and Room DAO.
data class Voucher(
    val voucherId: String,
    val payer: String,
    val merchant: String,
    val amount: java.math.BigInteger,
    val expiry: Long,
    val nonce: Long,
    val signature: String
) {
    companion object {
        fun fromCardPayload(p: CardVoucherPayload) = Voucher(
            voucherId = p.voucherId,
            payer     = p.payer,
            merchant  = p.merchant,
            amount    = java.math.BigInteger(p.amount),
            expiry    = p.expiry,
            nonce     = p.nonce,
            signature = p.signature
        )
    }
}

private val cardJson = Json { encodeDefaults = true }

/// Serialize a freshly-signed voucher into the same compact JSON the
/// merchant phone parses on receive (matches backend voucher.js output).
fun VoucherSigner.SignedVoucher.toCardJson(): String =
    cardJson.encodeToString(
        CardVoucherPayload.serializer(),
        CardVoucherPayload(
            voucherId = voucherId,
            payer     = payer,
            merchant  = merchant,
            amount    = amount.toString(),
            expiry    = expiry,
            nonce     = nonce,
            signature = signature
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
