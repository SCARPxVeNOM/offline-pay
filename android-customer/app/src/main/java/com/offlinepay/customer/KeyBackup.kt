package com.offlinepay.customer

import android.content.Context
import android.util.Base64
import androidx.core.content.edit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Keys
import org.web3j.utils.Numeric
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/// Encrypted cloud backup of the customer's private key.
///
/// Threat model:
///   - The backend NEVER sees the plaintext key. It stores opaque ciphertext
///     under a userId (e.g. email). Only someone with the passphrase can
///     decrypt.
///   - Recovery: on a new device, the user enters userId + passphrase. The
///     ciphertext is fetched and decrypted locally; the resulting key
///     reconstitutes their wallet at the same EVM address as before.
///
/// Crypto:
///   - PBKDF2-HMAC-SHA256, 200_000 iterations, 16-byte salt → 256-bit key.
///   - AES-256-GCM, 12-byte IV, 128-bit auth tag.
///
/// Note: PBKDF2 isn't memory-hard; for a high-value wallet you'd swap in
/// scrypt/argon2id. For an MVP with $5 caps this is acceptable.
class KeyBackup(
    private val ctx: Context,
    private val backendBaseUrl: String,
) {
    private val prefs = ctx.getSharedPreferences("offlinepay_keys", Context.MODE_PRIVATE)
    private val http = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val nonces by lazy { NonceTracker(ctx) }

    @Serializable
    data class BackupBlob(
        val userId: String,
        val address: String,
        val saltB64: String,
        val ivB64: String,
        val ciphertextB64: String,
        val iterations: Int,
    )

    /// Plaintext payload encrypted into BackupBlob.ciphertextB64. We store
    /// the nonce alongside the private key (encrypted) so a server compromise
    /// can't reveal it; the nonce is sensitive only because it tells an
    /// attacker how many vouchers a wallet has signed.
    @Serializable
    private data class InnerPayload(
        val privKeyHex: String,
        val lastNonce: Long,
    )

    @Serializable
    private data class PepperResp(val userId: String, val pepperB64: String)

    class WeakPassphrase(message: String) : IllegalArgumentException(message)

    private fun requireStrong(passphrase: CharArray) {
        if (passphrase.size < 12) throw WeakPassphrase("passphrase must be ≥12 chars")
        var hasLetter = false; var hasDigitOrSym = false
        for (c in passphrase) {
            if (c.isLetter()) hasLetter = true
            else hasDigitOrSym = true
        }
        if (!hasLetter || !hasDigitOrSym)
            throw WeakPassphrase("passphrase must mix letters with digits or symbols")
    }

    private fun fetchPepper(userId: String): ByteArray {
        val req = Request.Builder().url("$backendBaseUrl/api/keybackup/pepper/$userId").get().build()
        val text = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("pepper fetch failed: ${resp.code}")
            resp.body?.string() ?: throw RuntimeException("empty pepper")
        }
        val r = json.decodeFromString(PepperResp.serializer(), text)
        return ub64(r.pepperB64)
    }

    /// Combine user passphrase with the server-issued pepper before PBKDF2.
    /// Result: a blob-only attacker (server DB leak without server secret)
    /// cannot offline-brute-force the passphrase.
    private fun pepperedPassphrase(passphrase: CharArray, pepper: ByteArray): CharArray {
        val pepperChars = b64(pepper).toCharArray()
        val out = CharArray(passphrase.size + 1 + pepperChars.size)
        System.arraycopy(passphrase, 0, out, 0, passphrase.size)
        out[passphrase.size] = ':'
        System.arraycopy(pepperChars, 0, out, passphrase.size + 1, pepperChars.size)
        java.util.Arrays.fill(pepperChars, '0')
        return out
    }

    /// Encrypts the currently-stored private key and uploads it to the backend
    /// keyed by `userId`. Idempotent — calling it again replaces the blob.
    fun backup(userId: String, passphrase: CharArray): Result<String> {
        try { requireStrong(passphrase) } catch (e: WeakPassphrase) { return Result.failure(e) }

        val privHex = prefs.getString(KEY_HEX, null)
            ?: return Result.failure(IllegalStateException("no key to back up"))
        val priv = Numeric.hexStringToByteArray(privHex)
        val kp = ECKeyPair.create(priv)
        val address = "0x" + Keys.getAddress(kp)

        val pepper = try { fetchPepper(userId) } catch (e: Exception) { return Result.failure(e) }
        val peppered = pepperedPassphrase(passphrase, pepper)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv   = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key  = deriveKey(peppered, salt, ITERATIONS)
        java.util.Arrays.fill(peppered, '0')
        java.util.Arrays.fill(pepper, 0)

        // Encrypt {privKey, lastNonce} as a single payload — the nonce
        // travels inside the encrypted blob, never plaintext on the server.
        val inner = InnerPayload(
            privKeyHex = Numeric.toHexStringNoPrefix(priv),
            lastNonce = nonces.getLast(),
        )
        val innerBytes = json.encodeToString(InnerPayload.serializer(), inner).toByteArray()
        val ciphertext = aesGcmEncrypt(key, iv, innerBytes)
        val blob = BackupBlob(
            userId = userId,
            address = address,
            saltB64 = b64(salt),
            ivB64 = b64(iv),
            ciphertextB64 = b64(ciphertext),
            iterations = ITERATIONS,
        )
        // Wipe derived material before network call.
        java.util.Arrays.fill(key, 0)

        val body = json.encodeToString(blob).toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$backendBaseUrl/api/keybackup").post(body).build()
        return http.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) Result.success(address)
            else Result.failure(RuntimeException("backup failed: ${resp.code}"))
        }
    }

    /// Fetches the encrypted blob for `userId`, decrypts with `passphrase`,
    /// and persists the recovered private key into the local KeyVault store.
    /// On success the user's wallet is fully restored — same address as before.
    fun restore(userId: String, passphrase: CharArray): Result<String> {
        val req = Request.Builder().url("$backendBaseUrl/api/keybackup/$userId").get().build()
        val blob: BackupBlob = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return Result.failure(RuntimeException("no backup: ${resp.code}"))
            val text = resp.body?.string() ?: return Result.failure(RuntimeException("empty body"))
            try { json.decodeFromString(BackupBlob.serializer(), text) }
            catch (e: Exception) { return Result.failure(e) }
        }

        val pepper = try { fetchPepper(userId) } catch (e: Exception) { return Result.failure(e) }
        val peppered = pepperedPassphrase(passphrase, pepper)
        val key = deriveKey(peppered, ub64(blob.saltB64), blob.iterations)
        java.util.Arrays.fill(peppered, '0')
        java.util.Arrays.fill(pepper, 0)
        val plaintext = try {
            aesGcmDecrypt(key, ub64(blob.ivB64), ub64(blob.ciphertextB64))
        } catch (e: Exception) {
            java.util.Arrays.fill(key, 0)
            return Result.failure(RuntimeException("wrong passphrase or corrupted backup"))
        }
        java.util.Arrays.fill(key, 0)

        // Decode the inner payload. Older blobs (pre-nonce) used a raw
        // 32-byte private key; fall back to that format if JSON decode fails.
        val (privHex, restoredNonce) = try {
            val inner = json.decodeFromString(InnerPayload.serializer(), String(plaintext))
            inner.privKeyHex to inner.lastNonce
        } catch (e: Exception) {
            // Legacy: ciphertext was the raw 32-byte private key.
            if (plaintext.size != 32) return Result.failure(RuntimeException("corrupt backup payload"))
            Numeric.toHexStringNoPrefix(plaintext) to 0L
        }

        val priv = Numeric.hexStringToByteArray(privHex)
        val kp = ECKeyPair.create(priv)
        val derivedAddr = "0x" + Keys.getAddress(kp)
        if (!derivedAddr.equals(blob.address, ignoreCase = true)) {
            return Result.failure(RuntimeException("address mismatch — backup tampered"))
        }

        prefs.edit { putString(KEY_HEX, Numeric.toHexStringNoPrefix(priv)) }

        // Skip-ahead so any voucher signed on the lost device that's still in
        // flight (sitting on a merchant's SQLite or in the mesh) settles
        // before this device starts signing. Without this, both phones could
        // pick the same nonce and the contract would reject one as stale.
        val resumeAt = restoredNonce + NonceTracker.RECOVERY_SKIP
        nonces.setTo(resumeAt)
        return Result.success(derivedAddr)
    }

    /// Convenience for callers that want to know the next nonce to sign with
    /// after a successful restore. Equivalent to `NonceTracker(ctx).getLast() + 1`.
    fun nextNonce(): Long = nonces.next()

    // ─── Crypto primitives ────────────────────────────────────────────────

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iter: Int): ByteArray {
        val spec: KeySpec = PBEKeySpec(passphrase, salt, iter, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    private fun aesGcmEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(plaintext)
    }

    private fun aesGcmDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun ub64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        private const val KEY_HEX = "payer_priv_hex"
        private const val ITERATIONS = 200_000
    }
}
