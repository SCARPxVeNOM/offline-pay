package com.offlinepay.customer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class TopupClient(private val baseUrl: String) {
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable data class TopupReq(val customer: String, val amountInrPaise: Long)
    @Serializable data class TopupResp(val ok: Boolean = false, val upiRef: String? = null,
                                       val amountUsdc: String? = null, val error: String? = null)

    @Serializable data class IssueReq(val customer: String, val count: Int, val amountUsdcEach: Long)
    @Serializable data class IssueResp(val ok: Boolean = false,
                                       val vouchers: List<IssuedVoucher> = emptyList(),
                                       val error: String? = null)
    @Serializable data class IssuedVoucher(
        val voucher: VoucherFields,
        val signature: String,
        val cardPayload: String,
    )
    @Serializable data class VoucherFields(
        val payer: String, val merchant: String, val amount: String,
        val expiry: Long, val nonce: Long, val voucherId: String,
    )

    suspend fun topup(customer: String, paise: Long): TopupResp = withContext(Dispatchers.IO) {
        val body = json.encodeToString(TopupReq.serializer(), TopupReq(customer, paise))
        val r = http.newCall(
            Request.Builder().url("$baseUrl/api/topup")
                .post(body.toRequestBody("application/json".toMediaType())).build()
        ).execute()
        val text = r.body?.string() ?: "{}"
        r.close()
        json.decodeFromString(TopupResp.serializer(), text)
    }

    suspend fun issue(customer: String, count: Int, amountUsdc: Long): IssueResp = withContext(Dispatchers.IO) {
        val body = json.encodeToString(IssueReq.serializer(), IssueReq(customer, count, amountUsdc))
        val r = http.newCall(
            Request.Builder().url("$baseUrl/api/vouchers/issue")
                .post(body.toRequestBody("application/json".toMediaType())).build()
        ).execute()
        val text = r.body?.string() ?: "{}"
        r.close()
        json.decodeFromString(IssueResp.serializer(), text)
    }
}
