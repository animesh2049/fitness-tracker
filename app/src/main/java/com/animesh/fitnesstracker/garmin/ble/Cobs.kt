package com.animesh.fitnesstracker.garmin.ble

import java.io.ByteArrayOutputStream

/**
 * Garmin's COBS framing: one GFDI frame on the wire is `0x00 | COBS body | 0x00`. The body is
 * standard COBS (code byte = number of following non-zero bytes + 1, 0xFF for a 254-byte run with no
 * implied zero), so it never contains 0x00 and the two zero bytes delimit frames unambiguously.
 */
object Cobs {
    fun encode(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + data.size / 254 + 3)
        out.write(0) // Garmin's leading padding byte
        val block = ByteArrayOutputStream(254)
        fun flush(code: Int) {
            out.write(code)
            block.writeTo(out)
            block.reset()
        }
        var code = 1
        for (b in data) {
            if (b.toInt() == 0) {
                flush(code)
                code = 1
            } else {
                block.write(b.toInt())
                code++
                if (code == 0xFF) {
                    flush(code)
                    code = 1
                }
            }
        }
        flush(code)
        out.write(0)
        return out.toByteArray()
    }

    /** Decodes one COBS body (without the delimiting zeros). Returns null when the body is malformed. */
    fun decodeBody(body: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream(body.size)
        var i = 0
        while (i < body.size) {
            val code = body[i].toInt() and 0xFF
            if (code == 0) return null
            i++
            val n = code - 1
            if (i + n > body.size) return null
            out.write(body, i, n)
            i += n
            if (code != 0xFF && i < body.size) out.write(0)
        }
        return out.toByteArray()
    }
}

/**
 * Accumulates notification payloads and yields decoded frames as soon as a complete
 * `0x00 ... 0x00` frame is present. Bytes before the first 0x00 (a lost frame start) and bodies that
 * fail to decode are discarded, so a dropped notification never wedges the decoder.
 */
class CobsDecoder(private val maxBuffer: Int = 20_000) {
    private var pending = ByteArray(0)

    fun feed(bytes: ByteArray): List<ByteArray> {
        if (bytes.isEmpty()) return emptyList()
        val data = pending + bytes
        if (data.size > maxBuffer) {
            pending = ByteArray(0)
            return emptyList()
        }
        val frames = ArrayList<ByteArray>(1)
        var start = 0
        while (true) {
            while (start < data.size && data[start].toInt() != 0) start++ // garbage before a frame
            if (start >= data.size) break
            var end = start + 1
            while (end < data.size && data[end].toInt() != 0) end++
            if (end >= data.size) break // frame not complete yet
            if (end > start + 1) {
                Cobs.decodeBody(data.copyOfRange(start + 1, end))?.let { frames.add(it) }
            }
            // If a non-zero byte follows, the trailing zero doubles as the next frame's leading zero.
            start = if (end + 1 < data.size && data[end + 1].toInt() != 0) end else end + 1
        }
        pending = if (start >= data.size) ByteArray(0) else data.copyOfRange(start, data.size)
        return frames
    }

    fun reset() {
        pending = ByteArray(0)
    }
}
