package com.offlinepay.wallet

import android.util.Base64
import android.util.Log
import org.web3j.crypto.ECKeyPair
import org.web3j.crypto.Hash
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/// Cryptographic challenge-response between two mesh peers.
///
/// Why: without a handshake the `from` field on mesh messages is
/// self-claimed — a single attacker device could pretend to be 100 different
/// registered merchant addresses and inflate replicaCount with bogus ACKs.
/// The handshake forces every peer to prove ownership of the address it
/// claims by signing a fresh per-connection nonce.
///
/// Flow:
///   A → B  CHALLENGE { nonce_A }
///   B → A  RESPONSE  { addr_B, sig_B over nonce_A }
///   A → B  CHALLENGE { nonce_B } (mirror, so each side verifies the other)
///   B → A  RESPONSE  { addr_A, sig_A over nonce_B }
/// On both successful verifications we mark the endpoint verified.
///
/// Both sides MUST also pass `isRegisteredDevice(addr)` — i.e. the recovered
/// address must resolve to a registered merchant device on-chain.
class HandshakeManager(
    private val keyPair: ECKeyPair,
    val selfAddress: String,
    private val isRegisteredDevice: (String) -> Boolean,
) {
    /// Nonce we sent on this endpoint, awaiting their response.
    private val outboundNonce = ConcurrentHashMap<String, ByteArray>()
    /// Endpoints we have successfully verified, value = peer's address.
    private val verified = ConcurrentHashMap<String, String>()

    fun newChallengeNonce(endpointId: String): ByteArray {
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
        outboundNonce[endpointId] = nonce
        return nonce
    }

    /// Sign a peer's challenge nonce so they can verify our identity.
    /// Signature is over keccak256(nonce); the verifier recovers the address.
    fun signChallenge(nonce: ByteArray): String {
        val sig = Sign.signMessage(nonce, keyPair, true) // hash internally
        return encodeSig(sig)
    }

    /// Verify a peer's response: their `claimedAddress` must equal the
    /// address recovered from `signature` over our originally-sent nonce,
    /// AND must be a registered merchant device.
    /// Returns true on success and marks the endpoint verified.
    fun verifyResponse(endpointId: String, claimedAddress: String, signatureHex: String): Boolean {
        val nonce = outboundNonce.remove(endpointId) ?: run {
            Log.w(TAG, "no pending nonce for $endpointId")
            return false
        }
        val sig = decodeSig(signatureHex) ?: return false
        val recoveredKey = try {
            Sign.signedMessageHashToKey(Hash.sha3(nonce), sig)
        } catch (t: Throwable) {
            Log.w(TAG, "recovery failed: ${t.message}")
            return false
        }
        val recoveredAddress = "0x" + Keys.getAddress(recoveredKey)
        if (!recoveredAddress.equals(claimedAddress, ignoreCase = true)) {
            Log.w(TAG, "address mismatch: claimed=$claimedAddress recovered=$recoveredAddress")
            return false
        }
        if (!isRegisteredDevice(recoveredAddress)) {
            Log.w(TAG, "$recoveredAddress not in MerchantRegistry — ignoring")
            return false
        }
        verified[endpointId] = recoveredAddress.lowercase()
        Log.i(TAG, "verified peer $endpointId = $recoveredAddress")
        return true
    }

    fun isVerified(endpointId: String): Boolean = verified.containsKey(endpointId)

    fun verifiedAddress(endpointId: String): String? = verified[endpointId]

    fun forget(endpointId: String) {
        outboundNonce.remove(endpointId)
        verified.remove(endpointId)
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private fun encodeSig(sig: Sign.SignatureData): String {
        val bytes = ByteArray(65)
        System.arraycopy(sig.r, 0, bytes, 0, 32)
        System.arraycopy(sig.s, 0, bytes, 32, 32)
        bytes[64] = sig.v[0]
        return Numeric.toHexString(bytes)
    }

    private fun decodeSig(hex: String): Sign.SignatureData? {
        return try {
            val bytes = Numeric.hexStringToByteArray(hex)
            if (bytes.size != 65) return null
            val r = bytes.copyOfRange(0, 32)
            val s = bytes.copyOfRange(32, 64)
            var v = bytes[64].toInt() and 0xFF
            if (v < 27) v += 27
            Sign.SignatureData(v.toByte(), r, s)
        } catch (t: Throwable) {
            null
        }
    }

    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
    fun ub64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    companion object { private const val TAG = "Handshake" }
}
