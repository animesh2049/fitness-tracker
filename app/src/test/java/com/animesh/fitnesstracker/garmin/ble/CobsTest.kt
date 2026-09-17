package com.animesh.fitnesstracker.garmin.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CobsTest {
    private fun hex(s: String): ByteArray = s.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `encodes with leading and trailing zero`() {
        assertArrayEquals(hex("00 04 11 22 33 00"), Cobs.encode(hex("11 22 33")))
        // A zero inside the data becomes a block boundary.
        assertArrayEquals(hex("00 02 11 02 22 00"), Cobs.encode(hex("11 00 22")))
        // A trailing zero in the data yields a final empty block (code 0x01).
        assertArrayEquals(hex("00 02 11 01 00"), Cobs.encode(hex("11 00")))
        // Empty input is just the delimiters around an empty block.
        assertArrayEquals(hex("00 01 00"), Cobs.encode(ByteArray(0)))
    }

    @Test
    fun `254 byte run uses code FF without an implied zero`() {
        val run = ByteArray(254) { 0x41 }
        val encoded = Cobs.encode(run)
        assertEquals(0x00, encoded[0].toInt())
        assertEquals(0xFF, encoded[1].toInt() and 0xFF)
        assertEquals(0x01, encoded[256].toInt())
        assertEquals(0x00, encoded[257].toInt())
        assertEquals(258, encoded.size)
        assertArrayEquals(run, CobsDecoder().feed(encoded).single())
    }

    @Test
    fun `round trips runs longer than 254 and data with zeros`() {
        val cases = listOf(
            ByteArray(255) { 0x42 },
            ByteArray(508) { (it % 250 + 1).toByte() },
            ByteArray(600) { if (it % 100 == 0) 0 else 0x33 },
            hex("00 00 00"),
            hex("01 00"),
            ByteArray(254) { 0x7 } + byteArrayOf(0) + ByteArray(254) { 0x8 }
        )
        for (c in cases) {
            val frames = CobsDecoder().feed(Cobs.encode(c))
            assertEquals(1, frames.size)
            assertArrayEquals(c, frames[0])
        }
    }

    @Test
    fun `decoder assembles a frame delivered in small notifications`() {
        val payload = ByteArray(300) { (it + 1).toByte() }
        val encoded = Cobs.encode(payload)
        val decoder = CobsDecoder()
        val frames = ArrayList<ByteArray>()
        var pos = 0
        while (pos < encoded.size) {
            val end = minOf(encoded.size, pos + 19)
            frames += decoder.feed(encoded.copyOfRange(pos, end))
            pos = end
        }
        assertEquals(1, frames.size)
        assertArrayEquals(payload, frames[0])
    }

    @Test
    fun `decoder yields two back to back frames from one notification`() {
        val a = hex("01 02 03")
        val b = hex("04 00 05")
        val frames = CobsDecoder().feed(Cobs.encode(a) + Cobs.encode(b))
        assertEquals(2, frames.size)
        assertArrayEquals(a, frames[0])
        assertArrayEquals(b, frames[1])
    }

    @Test
    fun `decoder discards garbage before a frame and malformed bodies`() {
        val decoder = CobsDecoder()
        // Lost frame start: bytes without a leading zero are dropped, then a good frame decodes.
        assertTrue(decoder.feed(hex("05 06 07")).isEmpty())
        assertArrayEquals(hex("AA"), decoder.feed(hex("00 02 AA 00")).single())
        // A code byte claiming more bytes than the body has is malformed and skipped.
        assertTrue(decoder.feed(hex("00 09 01 02 00")).isEmpty())
        assertArrayEquals(hex("BB"), decoder.feed(hex("00 02 BB 00")).single())
    }
}
