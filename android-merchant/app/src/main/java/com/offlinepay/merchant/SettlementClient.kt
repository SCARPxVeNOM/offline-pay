package com.offlinepay.merchant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/// Pushes the queued vouchers to the OfflinePay backend's /api/merchant/redeem
/// then /api/merchant/settle endpoints. Keeping the on-chain wallet on the
/// backend means the merchant phone doesn't need to hold MATIC for gas.
class SettlementClient(private val baseUrl: String) {
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable data class VoucherJson(
        val payer: String, val merchant: String, val amount: String,
        val expiry: Long, val nonce: Long, val voucherId: String
    )
    @Serializable data class RedeemItem(val voucher: VoucherJson, val signature: String)
    @Serializable data class RedeemReq(val vouchers: List<RedeemItem>)
    @Serializable data class RedeemResp(val accepted: List<String>, val rejected: List<RejectInfo> = emptyList())
    @Serializable data class RejectInfo(val voucherId: String, val reason: String)
    @Serializable data class SettleResp(val settled: Int = 0, val tx: String? = null, val error: String? = null)

    suspend fun redeemAndSettle(rows: List<VoucherRow>): SettleResp = withContext(Dispatchers.IO) {
        if (rows.isEmpty()) return@withContext SettleResp(0, null)

        val redeemBody = json.encodeToString(
            RedeemReq.serializer(),
            RedeemReq(rows.map {
                RedeemItem(
                    voucher = VoucherJson(
                        it.payer, it.merchant, it.amount,
                        it.expiry, it.nonce, it.voucherId
                    ),
                    signature = it.signature
                )
            })
        )
        http.newCall(
            Request.Builder().url("$baseUrl/api/merchant/redeem")
                .post(redeemBody.toRequestBody("application/json".toMediaType()))
                .build()
        ).execute().close()

        val settleResp = http.newCall(
            Request.Builder().url("$baseUrl/api/merchant/settle")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
        ).execute()
        val text = settleResp.body?.string() ?: "{}"
        settleResp.close()
        json.decodeFromString(SettleResp.serializer(), text)
    }
}
