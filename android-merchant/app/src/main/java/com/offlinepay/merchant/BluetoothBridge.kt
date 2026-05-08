package com.offlinepay.merchant

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID

/// Connects to the ESP32 Bluetooth SPP, parses incoming `VOUCHER <uid> <json>`
/// frames, exposes them as a Flow, and writes ACCEPT / REJECT decisions back.
class BluetoothBridge(
    private val ctx: Context,
    private val deviceName: String = "OfflinePay_Reader",
    private val scope: CoroutineScope,
) {
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private val _vouchers = MutableSharedFlow<Voucher>(extraBufferCapacity = 16)
    val vouchers: SharedFlow<Voucher> = _vouchers

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
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                @Suppress("MissingPermission")
                socket!!.connect()
                output = socket!!.outputStream
                Log.d(TAG, "connected to $deviceName")

                val reader = BufferedReader(InputStreamReader(socket!!.inputStream))
                while (isActive) {
                    val line = reader.readLine() ?: break
                    parseFrame(line)?.let { _vouchers.emit(it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "BT loop error: ${e.message}")
            }
        }
    }

    fun sendDecision(accept: Boolean) {
        val msg = if (accept) "ACCEPT\n" else "REJECT\n"
        try { output?.write(msg.toByteArray()); output?.flush() }
        catch (e: Exception) { Log.e(TAG, "decision write failed: ${e.message}") }
    }

    fun close() { try { socket?.close() } catch (_: Exception) {} }

    private fun parseFrame(line: String): Voucher? {
        // Reader prints: VOUCHER <uid> <json>
        return try {
            val trimmed = line.trim()
            if (!trimmed.startsWith("VOUCHER ")) return null
            val parts = trimmed.split(" ", limit = 3)
            if (parts.size < 3) return null
            val payload = json.decodeFromString(CardVoucherPayload.serializer(), parts[2])
            Voucher.fromCardPayload(payload)
        } catch (e: Exception) {
            Log.e(TAG, "bad frame: $line", e); null
        }
    }

    companion object { private const val TAG = "OfflinePay/BT" }
}
