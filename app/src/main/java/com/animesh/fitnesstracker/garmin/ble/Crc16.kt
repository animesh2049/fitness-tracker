package com.animesh.fitnesstracker.garmin.ble

/**
 * CRC-16/ARC as used by FIT files and by every GFDI frame and file transfer chunk (reflected
 * polynomial 0xA001, initial value 0, nibble table). [compute] accepts a seed so a running CRC can be
 * carried across FILE_TRANSFER_DATA chunks, which is how the watch reports it.
 */
object Crc16 {
    private val TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400
    )

    /** Feeds one byte into [crc] and returns the new value. */
    fun update(crc: Int, byte: Int): Int {
        var c = crc and 0xFFFF
        var tmp = TABLE[c and 0xF]
        c = (c shr 4) and 0x0FFF
        c = c xor tmp xor TABLE[byte and 0xF]
        tmp = TABLE[c and 0xF]
        c = (c shr 4) and 0x0FFF
        c = c xor tmp xor TABLE[(byte shr 4) and 0xF]
        return c
    }

    /** CRC over `bytes[offset until offset + length]`, continuing from [seed]. */
    fun compute(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset, seed: Int = 0): Int {
        var crc = seed
        for (i in offset until offset + length) crc = update(crc, bytes[i].toInt() and 0xFF)
        return crc
    }
}
