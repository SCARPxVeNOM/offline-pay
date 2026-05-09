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
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return@withContext Result.Failed("no Bluetooth adapter")
        @Suppress("MissingPermission")
        val bt: BluetoothDevice = adapter.bondedDevices.firstOrNull { it.address == bondedBtMac }
            ?: return@withContext Result.Failed("reader not bonded")

        var socket: BluetoothSocket? = null
        try {
            @Suppress("MissingPermission")
            socket = bt.createRfcommSocketToServiceRecord(Config.BT_SPP_UUID)
            @Suppress("MissingPermission")
            socket.connect()
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

            // 3. Send WRITE. Firmware enters card-write mode and now waits
            //    for the user to physically tap the MIFARE on the reader.
            val writeLine = "WRITE ${keyVault.address} $pubkeyHex $sigHex $voucherJson\n"
            Log.d(TAG, "WRITE outbound (${writeLine.length} chars)")
            out.write(writeLine.toByteArray()); out.flush()

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
