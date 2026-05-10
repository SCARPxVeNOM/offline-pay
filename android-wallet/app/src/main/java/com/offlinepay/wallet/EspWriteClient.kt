package com.offlinepay.wallet

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.web3j.crypto.Hash
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric
import java.io.BufferedReader
import java.io.InputStreamReader

/// One-shot SPP socket that sends a signed WRITE command to the bonded
/// ESP32 reader, instructing it to write the given voucher JSON to the
/// next-tapped MIFARE card. Mirrors EspPairingClient's design.
///
/// Wire format (one line, space-separated, \n-terminated):
///   phone -> esp:  REQUEST_CHALLENGE
///   esp   -> phone: CHALLENGE <16B hex>
///   phone -> esp:  WRITE <addr> <pubkey_uncompressed> <sig_65> <json>
///       sig signs over EIP-191(
///           "OFFPAY-WRITE-V1" || esp_bt_mac(6) || challenge(16) ||
///           keccak256(json)(32))
///   esp   -> phone: OK <card_uid>     (success after the user taps a card)
///   esp   -> phone: ERR <reason>      (bad sig / not_owner / card_timeout)
object EspWriteClient {
    private const val TAG = "OfflinePay/EspWrite"
    private const val WRITE_DOMAIN = "OFFPAY-WRITE-V1"
    private const val HANDSHAKE_TIMEOUT_MS = 8_000L
    /// Generous: firmware waits up to 30s for a card tap after entering
    /// write mode; we add a few seconds of slack for BT round-trips.
    private const val WRITE_RESULT_TIMEOUT_MS = 35_000L

    sealed class Result {
        data class Written(val cardUid: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    suspend fun writeVoucherToCard(
        bondedBtMac: String,
        voucherJson: String,
        keyVault: KeyVault,
    ): Result = withContext(Dispatchers.IO) {
        // BT classic SPP to ESP32 BluetoothSerial is flaky — the link
        // can drop in 5-10 seconds if the firmware happens to be in a
        // card-read loop when we try to connect. Retry the whole
        // handshake up to 3 times with a small delay; this turns
        // sporadic socket-closed failures into a single write.
        var lastFailure: Result.Failed? = null
        repeat(3) { attempt ->
            if (attempt > 0) {
                Log.w(TAG, "retrying write (attempt ${attempt + 1}/3) after ${lastFailure?.reason}")
                kotlinx.coroutines.delay(1500)
            }
            when (val r = attemptWriteOnce(bondedBtMac, voucherJson, keyVault)) {
                is Result.Written -> return@withContext r
                is Result.Failed  -> {
                    val transient = r.reason.contains("socket closed", ignoreCase = true) ||
                                    r.reason.contains("read return", ignoreCase = true) ||
                                    r.reason.contains("timeout", ignoreCase = true) ||
                                    r.reason.contains("read failed", ignoreCase = true)
                    if (!transient) return@withContext r
                    lastFailure = r
                }
            }
        }
        lastFailure ?: Result.Failed("write failed after 3 attempts")
    }

    private suspend fun attemptWriteOnce(
        bondedBtMac: String,
        voucherJson: String,
        keyVault: KeyVault,
    ): Result = attemptWriteOnceInner(bondedBtMac, voucherJson, keyVault)

    private suspend fun attemptWriteOnceInner(
        bondedBtMac: String,
        voucherJson: String,
        keyVault: KeyVault,
    ): Result = withContext(Dispatchers.IO) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return@withContext Result.Failed("no Bluetooth adapter")
        @Suppress("MissingPermission")
        val bt: BluetoothDevice = adapter.bondedDevices.firstOrNull { it.address == bondedBtMac }
            ?: return@withContext Result.Failed("reader not bonded")

        // Cancel discovery before connect — the Android docs explicitly
        // require this; running discovery slows/wedges connect attempts.
        runCatching {
            @Suppress("MissingPermission")
            adapter.cancelDiscovery()
        }

        var socket: BluetoothSocket? = null
        try {
            // BT classic SPP connect can hang for a long time on a
            // half-broken session. Bound just the connect step so an
            // attempt times out cleanly while still leaving plenty of
            // time afterwards for the user to physically tap the card.
            val connectResult = withTimeoutOrNull(8_000L) {
                @Suppress("MissingPermission")
                val s = bt.createRfcommSocketToServiceRecord(Config.BT_SPP_UUID)
                try {
                    @Suppress("MissingPermission")
                    s.connect()
                    s
                } catch (e: Throwable) {
                    // Fallback: insecure RFCOMM works when the bond goes
                    // sideways but the device is still discoverable.
                    Log.w(TAG, "secure connect failed (${e.message}); trying insecure")
                    runCatching { s.close() }
                    @Suppress("MissingPermission")
                    val s2 = bt.createInsecureRfcommSocketToServiceRecord(Config.BT_SPP_UUID)
                    @Suppress("MissingPermission")
                    s2.connect()
                    s2
                }
            }
            socket = connectResult
                ?: return@withContext Result.Failed("BT connect timeout — try again")
            val out = socket.outputStream
            val reader = BufferedReader(InputStreamReader(socket.inputStream))

            // 1. Get a fresh challenge bound to this WRITE.
            out.write("REQUEST_CHALLENGE\n".toByteArray()); out.flush()
            val challengeLine = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
                readUntilPrefix(reader, "CHALLENGE ")
            } ?: return@withContext Result.Failed("no CHALLENGE from reader (timeout)")
            val challengeHex = challengeLine.removePrefix("CHALLENGE ").trim()
            val challenge = Numeric.hexStringToByteArray(challengeHex)
            if (challenge.size != 16)
                return@withContext Result.Failed("bad challenge length ${challenge.size}")

            // 2. Build the auth payload + sign with our wallet.
            val jsonHash = keccak256(voucherJson.toByteArray())
            val payload = buildPayload(bondedBtMac, challenge, jsonHash)
            val sig = Sign.signPrefixedMessage(payload, keyVault.keyPair)
            val sigBytes = ByteArray(65).also {
                System.arraycopy(sig.r, 0, it, 0, 32)
                System.arraycopy(sig.s, 0, it, 32, 32)
                it[64] = sig.v[0]
            }
            val sigHex = Numeric.toHexString(sigBytes)
            val pubkeyHex = "04" + Numeric.toHexStringNoPrefixZeroPadded(
                keyVault.keyPair.publicKey, 128
            )

            // 3a. Send AUTH (auth fields only, ~280 bytes — fits cleanly
            //     under the firmware's 512-byte SPP RX queue limit on
            //     Arduino-ESP32 2.0.x).
            val authLine = "AUTH ${keyVault.address} $pubkeyHex $sigHex\n"
            Log.d(TAG, "AUTH outbound (${authLine.length} chars)")
            out.write(authLine.toByteArray()); out.flush()
            val authAck = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
                readUntilOneOf(reader, listOf("AUTH_OK", "ERR "))
            } ?: return@withContext Result.Failed("no AUTH_OK from reader (timeout)")
            if (authAck.startsWith("ERR ")) {
                return@withContext Result.Failed(authAck.removePrefix("ERR ").trim())
            }

            // 3b. Send WRITE_DATA carrying just the JSON (~340 bytes).
            //     Firmware enters card-write mode after this and waits
            //     for the user to physically tap the MIFARE.
            val dataLine = "WRITE_DATA $voucherJson\n"
            Log.d(TAG, "WRITE_DATA outbound (${dataLine.length} chars)")
            out.write(dataLine.toByteArray()); out.flush()

            // 4. Wait for the result. May take up to ~30s for the user
            //    to actually tap the card.
            val ack = withTimeoutOrNull(WRITE_RESULT_TIMEOUT_MS) {
                readUntilOneOf(reader, listOf("OK ", "ERR "))
            } ?: return@withContext Result.Failed("no result from reader (timeout)")

            if (ack.startsWith("ERR ")) {
                Result.Failed(ack.removePrefix("ERR ").trim())
            } else {
                val uid = ack.removePrefix("OK ").trim()
                Log.i(TAG, "card written, uid=$uid")
                Result.Written(uid)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "write failed: ${t.message}")
            Result.Failed(t.message ?: t.javaClass.simpleName)
        } finally {
            try { socket?.close() } catch (_: Throwable) {}
        }
    }

    private fun buildPayload(
        bondedBtMac: String,
        challenge16: ByteArray,
        jsonHash32: ByteArray,
    ): ByteArray {
        val macBytes = Numeric.hexStringToByteArray(
            "0x" + bondedBtMac.replace(":", "").lowercase()
        )
        return WRITE_DOMAIN.toByteArray() + macBytes + challenge16 + jsonHash32
    }

    private fun keccak256(data: ByteArray): ByteArray = Hash.sha3(data)

    private fun readUntilPrefix(reader: BufferedReader, prefix: String): String {
        while (true) {
            val line = reader.readLine() ?: throw java.io.IOException("socket closed")
            val trimmed = line.trim()
            if (trimmed.startsWith(prefix)) return trimmed
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
