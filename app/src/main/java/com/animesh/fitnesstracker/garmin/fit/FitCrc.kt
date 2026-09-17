package com.animesh.fitnesstracker.garmin.fit

/**
 * The CRC-16 used by FIT files (nibble-table variant of CRC-16/ARC). A file's trailing two bytes
 * hold the CRC of everything before them; a 14-byte header may also carry the CRC of its first
 * twelve bytes.
 */
internal object FitCrc {
    private val table = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400
    )

    /** CRC of `bytes[from until to)`, starting from [seed] (0 for a fresh computation). */
    fun compute(bytes: ByteArray, from: Int = 0, to: Int = bytes.size, seed: Int = 0): Int {
        var crc = seed
        for (i in from until to) {
            val b = bytes[i].toInt() and 0xFF
            crc = step(crc, b and 0x0F)
            crc = step(crc, (b shr 4) and 0x0F)
        }
        return crc
    }

    private fun step(crc: Int, nibble: Int): Int {
        val tmp = table[crc and 0x0F]
        val shifted = (crc shr 4) and 0x0FFF
        return shifted xor tmp xor table[nibble]
    }
}
