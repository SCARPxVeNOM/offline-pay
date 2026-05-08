package com.offlinepay.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class FaucetClient(private val baseUrl: String) {
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable data class Req(val address: String, val amountUsdc: String)
    @Serializable data class Resp(val ok: Boolean = false, val tx: String? = null,
                                  val amountUsdc: String? = null, val error: String? = null)

    suspend fun mint(address: String, amountUsdc: String): Resp = withContext(Dispatchers.IO) {
        val body = json.encodeToString(Req.serializer(), Req(address, amountUsdc))
        val r = http.newCall(
            Request.Builder().url("$baseUrl/api/faucet")
                .post(body.toRequestBody("application/json".toMediaType())).build()
        ).execute()
        val text = r.body?.string() ?: "{}"
        r.close()
        json.decodeFromString(Resp.serializer(), text)
    }
}
