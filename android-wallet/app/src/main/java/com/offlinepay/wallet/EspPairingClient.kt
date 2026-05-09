package com.offlinepay.wallet

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric
import java.io.BufferedReader
import java.io.InputStreamReader

/// One-shot pairing client that does the CLAIM handshake with an ESP32
/// reader. Independent of `BluetoothBridge` (which owns the long-lived
/// voucher-receive socket on the Receive screen) so the two flows don't
/// fight over the SPP channel.
///
/// Wire format, line-based, \n-terminated:
///   phone -> esp:  REQUEST_CHALLENGE
///   esp   -> phone: CHALLENGE <16-byte hex>          # 32 hex chars
///   phone -> esp:  CLAIM <addr> <pubkey> <sig>
///       addr   = "0x" + 40 hex   (phone's wallet)
///       pubkey = 130 hex chars   (uncompressed: 04 || X || Y)
///       sig    = "0x" + 130 hex  (r || s || v, 65 bytes)
///   esp   -> phone: OK <esp_addr>                    # success
///   esp   -> phone: ERR <reason>                     # bad sig / replay / timeout
///
/// Signature is over EIP-191(`OFFPAY-CLAIM-V1` || esp_addr_20B || nonce_16B).
/// This binds the claim to a specific ESP32 (so a sig captured for one
/// reader can't be replayed against another) and to a fresh nonce (so
/// a sig captured once can't be replayed against the same reader).
object EspPairingClient {
    private const val TAG = "OfflinePay/EspPair"
    private const val CLAIM_DOMAIN = "OFFPAY-CLAIM-V1"
    private const val HANDSHAKE_TIMEOUT_MS = 8_000L

    sealed class Result {
        data class Paired(val btMac: String, val btName: String, val espAddress: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    /// Lists OS-bonded BT devices that look like our reader (matching
    /// `Config.BT_DEVICE_NAME`). The user must already have OS-paired
    /// the reader through Android Settings — we don't drive bond
    /// creation ourselves to avoid re-implementing the system pair UI.
    @Suppress("MissingPermission")
    fun discoverBondedReaders(): List<DiscoveredEsp> {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
        return adapter.bondedDevices
            .filter { it.name == Config.BT_DEVICE_NAME }
            .map { DiscoveredEsp(it.address, it.name) }
    }

    /// Performs the full handshake against the chosen device. Caller is
    /// expected to be on a coroutine; this suspends across the BT IO.
    suspend fun pair(ctx: Context, device: DiscoveredEsp, keyVault: KeyVault): Result =
        withContext(Dispatchers.IO) {
            val adapter = BluetoothAdapter.getDefaultAdapter()
                ?: return@withContext Result.Failed("no Bluetooth adapter")
            @Suppress("MissingPermission")
            val bt: BluetoothDevice = adapter.bondedDevices.firstOrNull { it.address == device.btMac }
                ?: return@withContext Result.Failed("device not bonded — pair in OS settings first")

            var socket: BluetoothSocket? = null
            try {
                @Suppress("MissingPermission")
                socket = bt.createRfcommSocketToServiceRecord(Config.BT_SPP_UUID)
                @Suppress("MissingPermission")
                socket.connect()
                val out = socket.outputStream
                val reader = BufferedReader(InputStreamReader(socket.inputStream))

                // Step 1: ask for a fresh challenge.
                out.write("REQUEST_CHALLENGE\n".toByteArray()); out.flush()

                val challengeLine = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
                    readUntilPrefix(reader, "CHALLENGE ")
                } ?: return@withContext Result.Failed("no CHALLENGE from reader (timeout)")
                val challengeHex = challengeLine.removePrefix("CHALLENGE ").trim()
                val challenge = Numeric.hexStringToByteArray(challengeHex)
                if (challenge.size != 16)
                    return@withContext Result.Failed("bad challenge length: ${challenge.size}")

                // Step 2: build the claim payload + sign with our wallet.
                // Reader sends its own address along with the challenge in
                // some firmwares; we don't strictly need it because the
                // CLAIM frame echos it back via OK, but for stronger
                // binding we include the firmware's BT MAC of the device
                // we're talking to — close enough for v1.
                val payload = buildPayload(device.btMac, challenge)
                val sig = Sign.signPrefixedMessage(payload, keyVault.keyPair)
                // sig.r / sig.s are 32-byte fixed; sig.v is a single byte
                // wrapped in a 1-element array. Concatenate r||s||v.
                val sigBytes = ByteArray(65).also {
                    System.arraycopy(sig.r, 0, it, 0, 32)
                    System.arraycopy(sig.s, 0, it, 32, 32)
                    it[64] = sig.v[0]
                }
                val sigHex = Numeric.toHexString(sigBytes)
                val pubkeyHex = "04" + Numeric.toHexStringNoPrefixZeroPadded(
                    keyVault.keyPair.publicKey, 128
                )

                // Step 3: send CLAIM.
                val claim = "CLAIM ${keyVault.address} $pubkeyHex $sigHex\n"
                Log.d(TAG, "claim outbound (${claim.length} chars)")
                out.write(claim.toByteArray()); out.flush()

                val ack = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
                    readUntilOneOf(reader, listOf("OK ", "ERR "))
                } ?: return@withContext Result.Failed("no ACK from reader (timeout)")

                if (ack.startsWith("ERR ")) {
                    return@withContext Result.Failed(ack.removePrefix("ERR ").trim())
                }
                val espAddr = ack.removePrefix("OK ").trim().lowercase()
                if (!espAddr.matches(Regex("^0x[0-9a-f]{40}$"))) {
                    return@withContext Result.Failed("malformed OK from reader: '$ack'")
                }
                Log.i(TAG, "paired with ${device.btMac} (esp=$espAddr)")
                Result.Paired(device.btMac, device.btName, espAddr)
            } catch (t: Throwable) {
                Log.e(TAG, "pairing failed: ${t.message}")
                Result.Failed(t.message ?: t.javaClass.simpleName)
            } finally {
                try { socket?.close() } catch (_: Throwable) {}
            }
        }

    /// Helper: build the bytes the firmware will keccak256 (with EIP-191
    /// prefix, applied automatically by `Sign.signPrefixedMessage` and
    /// in the ESP32 verifier).
    private fun buildPayload(espIdentifier: String, challenge16: ByteArray): ByteArray {
        // espIdentifier is the BT MAC for v1 (firmware doesn't know its
        // EVM address until pairing succeeds; using MAC keeps the binding
        // device-specific). Future: swap to the EVM address once the
        // firmware exposes it via a STATUS frame.
        val idBytes = espIdentifier.replace(":", "").lowercase()
            .let { Numeric.hexStringToByteArray("0x$it") }
        return CLAIM_DOMAIN.toByteArray() + idBytes + challenge16
    }

    private fun readUntilPrefix(reader: BufferedReader, prefix: String): String {
        while (true) {
            val line = reader.readLine() ?: throw java.io.IOException("socket closed")
            val trimmed = line.trim()
            if (trimmed.startsWith(prefix)) return trimmed
            // ignore anything else (heartbeats, stale VOUCHER frames, etc.)
        }
    }

    private fun readUntilOneOf(reader: BufferedReader, prefixes: List<String>): String {
        while (true) {
            val line = reader.readLine() ?: throw java.io.IOException("socket closed")
            val trimmed = line.trim()
            if (prefixes.any { trimmed.startsWith(it) }) return trimmed
        }
    }
}

data class DiscoveredEsp(val btMac: String, val btName: String)
