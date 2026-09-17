package com.animesh.fitnesstracker.garmin.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The GFDI frame that travels inside one COBS frame, all little endian:
 * `u16 length (including this field and the CRC) | u16 messageId | payload | u16 crc16` where the
 * CRC covers everything before it. Incoming ids with bit 15 set carry a sequence number in bits 8..14
 * and the message number in the low byte; they are normalised to `(id and 0xFF) + 5000`.
 */
object GfdiFrame {
    data class Decoded(val messageId: Int, val payload: ByteArray)

    fun encode(messageId: Int, payload: ByteArray): ByteArray {
        val length = 2 + 2 + payload.size + 2
        val buf = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        buf.putShort(length.toShort())
        buf.putShort(messageId.toShort())
        buf.put(payload)
        val crc = Crc16.compute(buf.array(), 0, length - 2)
        buf.putShort(crc.toShort())
        return buf.array()
    }

    /** Returns null when the length field or the CRC does not match. */
    fun decode(frame: ByteArray): Decoded? {
        if (frame.size < 6) return null
        val buf = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        val length = buf.getShort(0).toInt() and 0xFFFF
        if (length != frame.size) return null
        val storedCrc = buf.getShort(length - 2).toInt() and 0xFFFF
        if (storedCrc != Crc16.compute(frame, 0, length - 2)) return null
        val rawId = buf.getShort(2).toInt() and 0xFFFF
        val id = normaliseId(rawId)
        return Decoded(id, frame.copyOfRange(4, length - 2))
    }

    fun normaliseId(rawId: Int): Int = if (rawId and 0x8000 != 0) (rawId and 0xFF) + 5000 else rawId
}
