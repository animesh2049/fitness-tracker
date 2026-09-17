package com.animesh.fitnesstracker.garmin.fit

/**
 * Cursor over a byte array with bounds checking. Every read that would run past [limit] throws
 * [FitDecodeException], so a truncated file surfaces as one clear error instead of an index crash.
 */
internal class FitByteReader(private val bytes: ByteArray, var position: Int = 0, private val limit: Int = bytes.size) {

    val remaining: Int get() = limit - position

    fun require(count: Int, what: String) {
        if (count < 0 || position + count > limit) {
            throw FitDecodeException("Truncated FIT file: needed $count byte(s) for $what at offset $position, only $remaining left")
        }
    }

    fun u8(what: String = "byte"): Int {
        require(1, what)
        return bytes[position++].toInt() and 0xFF
    }

    fun u16(bigEndian: Boolean, what: String = "u16"): Int = unsigned(2, bigEndian, what).toInt()

    fun u32(bigEndian: Boolean, what: String = "u32"): Long = unsigned(4, bigEndian, what)

    /** Reads [size] bytes (1, 2, 4 or 8) as an unsigned bit pattern in a Long. */
    fun unsigned(size: Int, bigEndian: Boolean, what: String = "integer"): Long {
        require(size, what)
        var value = 0L
        if (bigEndian) {
            for (i in 0 until size) value = (value shl 8) or (bytes[position + i].toLong() and 0xFF)
        } else {
            for (i in size - 1 downTo 0) value = (value shl 8) or (bytes[position + i].toLong() and 0xFF)
        }
        position += size
        return value
    }

    fun slice(count: Int, what: String = "bytes"): ByteArray {
        require(count, what)
        val out = bytes.copyOfRange(position, position + count)
        position += count
        return out
    }

    fun skip(count: Int, what: String = "bytes") {
        require(count, what)
        position += count
    }
}
