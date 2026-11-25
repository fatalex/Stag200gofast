package com.stag200.gofast

import java.nio.ByteBuffer
import java.nio.ByteOrder

object UgicProtocol {
    const val HEADER_BYTE: Byte = 0xAC.toByte()
    const val ALT_HEADER_BYTE: Byte = 0xAF.toByte()

    // Verified supported commands on user's STAG-200 GoFast ECU
    const val CMD_DEVICE_INFO: Byte = 0x00
    const val CMD_PING: Byte = 0x01
    const val CMD_SUPPORTED_CMDS: Byte = 0x03
    const val CMD_STATUS: Byte = 0x04
    const val CMD_SUPPORTED_LIVE: Byte = 0x10
    const val CMD_ERR_COUNTERS: Byte = 0x40
    const val CMD_GET_ERRORS: Byte = 0x41
    const val CMD_RTC_CLOCK: Byte = 0x49
    const val CMD_HW_INFO: Byte = 0x51
    const val CMD_FW_INFO: Byte = 0x52
    const val CMD_LIVE_DUMP: Byte = 0x64

    fun buildFrame(
        cmd: Byte,
        sub: Byte = 0x00,
        payload: ByteArray = ByteArray(0),
        header: Byte = HEADER_BYTE,
        flags: Byte = 0x00
    ): ByteArray {
        val lenField = payload.size + 3
        val out = ByteArray(6 + payload.size + 1)
        out[0] = header
        out[1] = flags
        out[2] = ((lenField shr 8) and 0xFF).toByte()
        out[3] = (lenField and 0xFF).toByte()
        out[4] = cmd
        out[5] = sub
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, out, 6, payload.size)
        }
        var sum = 0
        for (i in 0 until 6 + payload.size) {
            sum = (sum + (out[i].toInt() and 0xFF)) and 0xFF
        }
        out[6 + payload.size] = sum.toByte()
        return out
    }

    fun verifyChecksum(frame: ByteArray): Boolean {
        if (frame.size < 7) return false
        var sum = 0
        for (i in 0 until frame.size - 1) {
            sum = (sum + (frame[i].toInt() and 0xFF)) and 0xFF
        }
        return (sum and 0xFF) == (frame.last().toInt() and 0xFF)
    }

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    data class LiveDump64(
        val ch0: Int,
        val ch1: Int,
        val ch2: Int,
        val ch3: Int,
        val ch4: Int,
        val ch5: Int,
        val injA: List<Int>,
        val injB: List<Int>,
        val asciiInfo: String
    )

    fun parseLiveDump64(payload: ByteArray): LiveDump64? {
        if (payload.size < 68) return null
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        val ch0 = bb.short.toInt() and 0xFFFF
        val ch1 = bb.short.toInt() and 0xFFFF
        val ch2 = bb.short.toInt() and 0xFFFF
        val ch3 = bb.short.toInt() and 0xFFFF
        val ch4 = bb.short.toInt() and 0xFFFF
        val ch5 = bb.short.toInt() and 0xFFFF

        // 4-cylinder timing blocks starting at offset 20 (after 16B header + 4B flags)
        val injA = (0 until 4).map { i ->
            bb.getInt(20 + i * 4)
        }
        val injB = (0 until 4).map { i ->
            bb.getInt(52 + i * 4)
        }

        val asciiStart = 78.coerceAtMost(payload.size)
        val asciiBytes = payload.copyOfRange(asciiStart, payload.size)
            .takeWhile { it != 0.toByte() }
            .toByteArray()
        val asciiText = String(asciiBytes, Charsets.US_ASCII).trim()

        return LiveDump64(ch0, ch1, ch2, ch3, ch4, ch5, injA, injB, asciiText)
    }

    fun parseRtc49(payload: ByteArray): String? {
        if (payload.size < 7) return null
        val yr = (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
        val mo = payload[2].toInt() and 0xFF
        val dy = payload[3].toInt() and 0xFF
        val hh = payload[4].toInt() and 0xFF
        val mm = payload[5].toInt() and 0xFF
        val ss = payload[6].toInt() and 0xFF
        return "%04d-%02d-%02d %02d:%02d:%02d".format(yr, mo, dy, hh, mm, ss)
    }

    fun parseFwDate00(payload: ByteArray): String? {
        if (payload.size < 16) return null
        val day = payload[10].toInt() and 0xFF
        val month = payload[11].toInt() and 0xFF
        val year = ((payload[12].toInt() and 0xFF) shl 8) or (payload[13].toInt() and 0xFF)
        return "Прошивка: %02d.%02d.%04d".format(day, month, year)
    }
}
