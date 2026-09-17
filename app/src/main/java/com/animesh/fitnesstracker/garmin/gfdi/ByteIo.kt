package com.animesh.fitnesstracker.garmin.gfdi

import java.io.ByteArrayOutputStream

/**
 * Little-endian writer for GFDI payloads. Strings are `u8 length + UTF-8` as everywhere in GFDI.
 */
class LeWriter {
    private val out = ByteArrayOutputStream()

    fun u8(v: Int): LeWriter { out.write(v and 0xFF); return this }
    fun u16(v: Int): LeWriter { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); return this }
    fun u32(v: Long): LeWriter {
        out.write((v and 0xFF).toInt()); out.write(((v shr 8) and 0xFF).toInt())
        out.write(((v shr 16) and 0xFF).toInt()); out.write(((v shr 24) and 0xFF).toInt())
        return this
    }
    fun i32(v: Int): LeWriter = u32(v.toLong() and 0xFFFFFFFFL)
    fun u64(v: Long): LeWriter { u32(v and 0xFFFFFFFFL); u32((v ushr 32) and 0xFFFFFFFFL); return this }
    fun bytes(b: ByteArray): LeWriter { out.write(b, 0, b.size); return this }
    fun string(s: String): LeWriter {
        val b = s.toByteArray(Charsets.UTF_8)
        require(b.size <= 255) { "string too long" }
        u8(b.size)
        return bytes(b)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/** Little-endian reader with bounds checks; every method throws [IllegalArgumentException] when short. */
class LeReader(private val data: ByteArray, private var pos: Int = 0, private val limit: Int = data.size) {
    val remaining: Int get() = limit - pos
    val position: Int get() = pos

    private fun need(n: Int) {
        if (remaining < n) throw IllegalArgumentException("payload too short: need $n, have $remaining")
    }

    fun u8(): Int { need(1); return data[pos++].toInt() and 0xFF }
    fun u16(): Int { need(2); val v = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8); pos += 2; return v }
    fun u32(): Long {
        need(4)
        val v = (data[pos].toLong() and 0xFF) or ((data[pos + 1].toLong() and 0xFF) shl 8) or
            ((data[pos + 2].toLong() and 0xFF) shl 16) or ((data[pos + 3].toLong() and 0xFF) shl 24)
        pos += 4
        return v
    }
    fun i32(): Int = u32().toInt()
    fun u64(): Long { val lo = u32(); val hi = u32(); return (hi shl 32) or lo }
    fun bytes(n: Int): ByteArray { need(n); val b = data.copyOfRange(pos, pos + n); pos += n; return b }
    fun rest(): ByteArray = bytes(remaining)
    fun string(): String = String(bytes(u8()), Charsets.UTF_8)
    fun skip(n: Int) { need(n); pos += n }
}
