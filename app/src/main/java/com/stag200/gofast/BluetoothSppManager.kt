package com.stag200.gofast

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors

class BluetoothSppManager(
    private val onConnected: (String) -> Unit,
    private val onDisconnected: (String) -> Unit,
    private val onFrameReceived: (ByteArray, Boolean) -> Unit,
    private val onRawBytesReceived: (ByteArray) -> Unit
) {
    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    @Volatile
    var isConnected: Boolean = false
        private set

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        disconnect()
        ioExecutor.execute {
            try {
                val sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
                sock.connect()
                socket = sock
                inputStream = sock.inputStream
                outputStream = sock.outputStream
                isConnected = true
                onConnected(device.name ?: device.address)
                readLoop()
            } catch (e: IOException) {
                isConnected = false
                onDisconnected("Ошибка подключения: ${e.message}")
            }
        }
    }

    fun send(bytes: ByteArray): Boolean {
        val out = outputStream ?: return false
        return try {
            out.write(bytes)
            out.flush()
            true
        } catch (e: IOException) {
            disconnect("Ошибка отправки: ${e.message}")
            false
        }
    }

    fun disconnect(reason: String = "Отключено") {
        isConnected = false
        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        inputStream = null
        outputStream = null
        socket = null
        onDisconnected(reason)
    }

    private fun readLoop() {
        val buffer = ByteArray(1024)
        val rxQueue = ArrayList<Byte>()
        val input = inputStream ?: return

        while (isConnected) {
            try {
                val n = input.read(buffer)
                if (n <= 0) break
                val chunk = buffer.copyOf(n)
                onRawBytesReceived(chunk)
                for (b in chunk) rxQueue.add(b)
                extractFrames(rxQueue)
            } catch (e: IOException) {
                if (isConnected) disconnect("Связь прервана: ${e.message}")
                break
            }
        }
    }

    private fun extractFrames(queue: ArrayList<Byte>) {
        while (queue.size >= 7) {
            val idxAc = queue.indexOf(UgicProtocol.HEADER_BYTE)
            val idxAf = queue.indexOf(UgicProtocol.ALT_HEADER_BYTE)
            val hdrIdx = when {
                idxAc == -1 && idxAf == -1 -> -1
                idxAc == -1 -> idxAf
                idxAf == -1 -> idxAc
                else -> minOf(idxAc, idxAf)
            }
            if (hdrIdx == -1) {
                queue.clear()
                return
            }
            if (hdrIdx > 0) {
                queue.subList(0, hdrIdx).clear()
            }
            if (queue.size < 4) return
            val lenField = ((queue[2].toInt() and 0xFF) shl 8) or (queue[3].toInt() and 0xFF)
            val totalLen = lenField + 4
            if (totalLen < 7 || totalLen > 1024) {
                queue.removeAt(0)
                continue
            }
            if (queue.size < totalLen) return
            val frame = ByteArray(totalLen) { queue[it] }
            queue.subList(0, totalLen).clear()
            val ok = UgicProtocol.verifyChecksum(frame)
            onFrameReceived(frame, ok)
        }
    }
}
