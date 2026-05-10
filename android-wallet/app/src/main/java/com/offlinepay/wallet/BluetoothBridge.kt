package com.offlinepay.wallet

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import org.web3j.crypto.Keys
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream

/// Endorsement signed by the ESP32 reader committing to the merchant
/// primary at tap time. Reaches the contract via
/// `settleBearerWithEndorsement` for true-bearer cards (recipient = 0).
data class Endorsement(
    val timestamp: Long,
    val merchantPrimary: String,
    val deviceAddress: String,
    val signature: String,
)

/// What the merchant phone receives on every card tap. The endorsement is
/// non-null when the firmware emitted an ENDORSE line right after the
/// VOUCHER frame (B2 bearer-card path); null when only a recipient-bound
/// VOUCHER arrived (legacy / phone-tap fallback).
data class IncomingVoucher(
    val voucher: Voucher,
    val endorsement: Endorsement?,
)

/// Connects to the ESP32 Bluetooth SPP, parses incoming VOUCHER (optionally
/// followed by ENDORSE) frames, exposes them as a Flow, and writes
/// ACCEPT / REJECT decisions back.
class BluetoothBridge(
    private val ctx: Context,
    private val deviceName: String = Config.BT_DEVICE_NAME,
    private val scope: CoroutineScope,
    /// Optional owner gate. When non-null, only VOUCHER frames whose
    /// ESP32-address prefix matches the bonded reader are emitted —
    /// anyone within BT range advertising the same `OfflinePay_Reader`
    /// name can no longer slip frames into our store. Disabled (null)
    /// for backward compatibility with code that hasn't paired yet.
    private val bondStore: EspBondStore? = null,
) {
    private val _incoming = MutableSharedFlow<IncomingVoucher>(extraBufferCapacity = 16)
    /// Primary stream consumers should use. Each emission carries the
    /// voucher plus the endorsement when one accompanied it.
    val incoming: SharedFlow<IncomingVoucher> = _incoming

    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null

    private val json = Json { ignoreUnknownKeys = true }

    fun connect() {
        scope.launch(Dispatchers.IO) {
            try {
                @Suppress("MissingPermission")
                val adapter = BluetoothAdapter.getDefaultAdapter()
                    ?: run { Log.e(TAG, "no BT adapter"); return@launch }
                @Suppress("MissingPermission")
                val device: BluetoothDevice = adapter.bondedDevices.firstOrNull { it.name == deviceName }
                    ?: run { Log.e(TAG, "device $deviceName not paired — pair in BT settings"); return@launch }

                @Suppress("MissingPermission")
                socket = device.createRfcommSocketToServiceRecord(Config.BT_SPP_UUID)
                @Suppress("MissingPermission")
                socket!!.connect()
                output = socket!!.outputStream
                Log.d(TAG, "connected to $deviceName")

                // Tell the firmware we're ready for card reads. Without
                // this it stays in IDLE and won't process card taps —
                // the new mode-aware firmware boots in IDLE so it
                // doesn't hammer the RC522 by default.
                runCatching {
                    output?.write("MODE READ\n".toByteArray())
                    output?.flush()
                    Log.d(TAG, "sent MODE READ")
                }

                val reader = BufferedReader(InputStreamReader(socket!!.inputStream))
                runReadLoop(reader)
            } catch (e: Exception) {
                Log.e(TAG, "BT loop error: ${e.message}")
            }
        }
    }

    /// Stateful line parser: a VOUCHER frame may be immediately followed
    /// by an ENDORSE frame on the next line. We hold the voucher until
    /// the next line arrives — if it's an ENDORSE we bundle, otherwise
    /// we flush the voucher solo and process the new line.
    private suspend fun runReadLoop(reader: BufferedReader) {
        var pendingVoucher: Voucher? = null
        while (kotlin.coroutines.coroutineContext[Job]?.isActive != false) {
            val line = reader.readLine() ?: break
            val trimmed = line.trim()
            when {
                trimmed.startsWith("VOUCHER ") -> {
                    pendingVoucher?.let { _incoming.emit(IncomingVoucher(it, null)) }
                    pendingVoucher = parseVoucherFrame(trimmed)
                }
                trimmed.startsWith("ENDORSE ") -> {
                    val rawEndorse = parseEndorseFrame(trimmed)
                    val v = pendingVoucher
                    if (rawEndorse != null && v != null) {
                        // Repair the firmware's hardcoded v=27 byte. The
                        // contract's ECDSA.recover uses v as-is, so we
                        // try both 27/28 and pick the one that actually
                        // recovers to the device address. Without this,
                        // ~50% of endorsed settles revert on chain.
                        val digest = EndorsementDigest.digest(
                            voucherId       = v.voucherId,
                            device          = rawEndorse.deviceAddress,
                            merchantPrimary = rawEndorse.merchantPrimary,
                            endorsementTs   = rawEndorse.timestamp,
                            chainId         = Config.CHAIN_ID,
                            vaultAddress    = Config.VAULT_ADDRESS,
                        )
                        val fixedSig = EndorsementDigest.fixSigV(
                            digest          = digest,
                            sigHex          = rawEndorse.signature,
                            expectedDevice  = rawEndorse.deviceAddress,
                        )
                        val endorse = rawEndorse.copy(signature = fixedSig)
                        Log.d(TAG, "voucher ${v.voucherId.take(10)} endorsed by ${endorse.deviceAddress.take(10)} → ${endorse.merchantPrimary.take(10)}")
                        _incoming.emit(IncomingVoucher(v, endorse))
                    } else {
                        Log.w(TAG, "ENDORSE without preceding VOUCHER (or bad endorse frame): '$trimmed'")
                    }
                    pendingVoucher = null
                }
                trimmed.isNotEmpty() -> {
                    Log.v(TAG, "ignored frame: $trimmed")
                }
            }
        }
        // Flush any pending voucher on socket close.
        pendingVoucher?.let { _incoming.emit(IncomingVoucher(it, null)) }
    }

    fun sendDecision(accept: Boolean) {
        val msg = if (accept) "ACCEPT\n" else "REJECT\n"
        try { output?.write(msg.toByteArray()); output?.flush() }
        catch (e: Exception) { Log.e(TAG, "decision write failed: ${e.message}") }
    }

    fun close() {
        // Tell the firmware to stop hammering RFID before we tear the
        // socket down. Best-effort — if the write fails the firmware
        // will detect the closed socket on its next pump.
        runCatching {
            output?.write("MODE IDLE\n".toByteArray())
            output?.flush()
            Log.d(TAG, "sent MODE IDLE")
        }
        try { socket?.close() } catch (_: Exception) {}
    }

    private fun parseVoucherFrame(trimmed: String): Voucher? {
        // Reader prints either:
        //   VOUCHER <uid> <json>                     (legacy)
        //   VOUCHER <deviceAddr> <uid> <json>        (current — addr starts 0x)
        return try {
            val parts = trimmed.split(" ", limit = 4)
            val readerAddr: String?
            val jsonText: String
            when {
                parts.size == 4 && parts[1].startsWith("0x") -> {
                    readerAddr = parts[1].lowercase()
                    jsonText   = parts[3]
                }
                parts.size >= 3 -> {
                    readerAddr = null  // legacy frame, no reader identity
                    jsonText   = parts[2]
                }
                else -> return null
            }
            // Owner gate. If we have a bonded reader, frames must come
            // from THAT reader (matched by the firmware's wallet
            // address printed at the start of the frame). Legacy frames
            // without an address are dropped on the bonded path.
            val bond = bondStore?.current()
            if (bond != null && bond.isPaired) {
                if (readerAddr == null || readerAddr != bond.espAddress) {
                    Log.w(TAG, "drop voucher from unbonded reader $readerAddr (paired=${bond.espAddress})")
                    return null
                }
                bondStore.touchLastSeen()
            }
            val payload = json.decodeFromString(CardVoucherPayload.serializer(), jsonText)
            Voucher.fromCardPayload(payload)
        } catch (e: Exception) {
            Log.e(TAG, "bad voucher frame: $trimmed", e); null
        }
    }

    /// Format: ENDORSE <ts> <merchantPrimary 0x…> <deviceAddr 0x…> <sig 0x…>
    private fun parseEndorseFrame(trimmed: String): Endorsement? = try {
        val parts = trimmed.split(" ").filter { it.isNotBlank() }
        if (parts.size != 5) {
            Log.w(TAG, "endorse frame has ${parts.size} tokens, expected 5"); null
        } else Endorsement(
            timestamp        = parts[1].toLong(),
            merchantPrimary  = parts[2].lowercase(),
            deviceAddress    = parts[3].lowercase(),
            signature        = parts[4],
        )
    } catch (e: Exception) {
        Log.e(TAG, "bad endorse frame: $trimmed", e); null
    }

    companion object { private const val TAG = "OfflinePay/BT" }
}
