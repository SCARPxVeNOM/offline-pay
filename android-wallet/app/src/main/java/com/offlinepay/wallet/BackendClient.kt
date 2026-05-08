package com.offlinepay.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/// Talks to the OfflinePay backend over HTTP. The phone never opens an
/// Ethereum RPC connection in custodial mode — backend is the only thing
/// that touches Polygon. All requests are idempotent at the contract layer
/// (voucherId uniqueness, mint/lock under backend's wallet).
class BackendClient(private val baseUrl: String) {
    // 5-minute call timeout: Amoy testnet txs are slow (~5-10s each) and a
    // topup does mint + approve + lock + sign-batch serialized via the
    // backend's chain mutex. Several stacked requests can take minutes.
    private val http = OkHttpClient.Builder()
        .callTimeout(5, TimeUnit.MINUTES)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable data class InitReq(val address: String, val amountUsdc: String)
    @Serializable data class InitResp(
        val ok: Boolean = false,
        val address: String? = null,
        val amountUsdc: String? = null,
        val error: String? = null,
    )

    @Serializable data class TopupReq(
        val address: String,
        val amountUsdc: String,
        val denomUsdc: String? = null,
        val count: Int? = null,
    )
    @Serializable data class VoucherFields(
        val payer: String, val merchant: String, val amount: String,
        val expiry: Long, val nonce: Long, val voucherId: String
    )
    @Serializable data class IssuedVoucher(
        val voucher: VoucherFields,
        val signature: String,
        val cardPayload: String,
    )
    @Serializable data class TopupResp(
        val ok: Boolean = false,
        val owner: String? = null,
        val amountUsdc: String? = null,
        val vouchers: List<IssuedVoucher> = emptyList(),
        val error: String? = null,
    )

    @Serializable data class RedeemItem(val voucher: VoucherFields, val signature: String)
    @Serializable data class RedeemReq(val recipient: String, val vouchers: List<RedeemItem>)
    @Serializable data class Reject(val voucherId: String, val reason: String)
    @Serializable data class RedeemResp(
        val ok: Boolean = false,
        val settled: Int = 0,
        val tx: String? = null,
        val rejected: List<Reject> = emptyList(),
        val error: String? = null,
    )

    suspend fun init(address: String, amountBaseUnits: Long): InitResp =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                InitReq.serializer(),
                InitReq(address, amountBaseUnits.toString())
            )
            val r = http.newCall(
                Request.Builder().url("$baseUrl/api/wallet/init")
                    .post(body.toRequestBody("application/json".toMediaType())).build()
            ).execute()
            val text = r.body?.string() ?: "{}"
            r.close()
            json.decodeFromString(InitResp.serializer(), text)
        }

    suspend fun topup(address: String, amountBaseUnits: Long, denomBaseUnits: Long, count: Int): TopupResp =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                TopupReq.serializer(),
                TopupReq(address, amountBaseUnits.toString(), denomBaseUnits.toString(), count)
            )
            val r = http.newCall(
                Request.Builder().url("$baseUrl/api/wallet/topup")
                    .post(body.toRequestBody("application/json".toMediaType())).build()
            ).execute()
            val text = r.body?.string() ?: "{}"
            r.close()
            json.decodeFromString(TopupResp.serializer(), text)
        }

    suspend fun redeem(recipient: String, items: List<RedeemItem>): RedeemResp =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                RedeemReq.serializer(),
                RedeemReq(recipient, items)
            )
            val r = http.newCall(
                Request.Builder().url("$baseUrl/api/wallet/redeem")
                    .post(body.toRequestBody("application/json".toMediaType())).build()
            ).execute()
            val text = r.body?.string() ?: "{}"
            r.close()
            json.decodeFromString(RedeemResp.serializer(), text)
        }
}
