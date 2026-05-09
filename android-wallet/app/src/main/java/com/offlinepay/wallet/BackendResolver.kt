package com.offlinepay.wallet

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/// Picks a working `BACKEND_BASE` from the candidates by racing them against
/// each other. The first one whose `/api/health` responds OK wins. The result
/// is written to `Config.BACKEND_BASE` so all subsequent BackendClient and
/// SettlementClient instances pick it up.
///
/// Why this exists: phones move between v4-only Wi-Fi and v6-only mobile
/// data. A static URL only works on one network type.
object BackendResolver {

    private val http = OkHttpClient.Builder()
        .callTimeout(4, TimeUnit.SECONDS)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private const val TAG = "OfflinePay/Resolver"

    @Volatile private var resolved = false

    /// Probe candidates concurrently. Whichever responds OK first wins.
    /// No-op if already resolved successfully.
    suspend fun resolve(): String = withContext(Dispatchers.IO) {
        if (resolved) return@withContext Config.BACKEND_BASE

        val winner = supervisorScope {
            val deferreds = Config.BACKEND_CANDIDATES.map { base ->
                async {
                    val ok = try { probe(base) } catch (t: Throwable) {
                        Log.w(TAG, "probe $base failed: ${t.message}")
                        false
                    }
                    if (ok) base else null
                }
            }
            // Take the first non-null result, cancel the rest.
            var pick: String? = null
            for (d in deferreds) {
                val v = d.await()
                if (v != null) { pick = v; break }
            }
            deferreds.forEach { it.cancel() }
            pick
        }
        if (winner != null) {
            Log.d(TAG, "resolved BACKEND_BASE=$winner")
            Config.BACKEND_BASE = winner
            resolved = true
        } else {
            Log.w(TAG, "no candidate reachable; keeping default ${Config.BACKEND_BASE}")
        }
        Config.BACKEND_BASE
    }

    private fun probe(base: String): Boolean {
        val r = http.newCall(
            Request.Builder().url("$base/api/health").get().build()
        ).execute()
        val ok = r.isSuccessful
        r.close()
        return ok
    }
}
