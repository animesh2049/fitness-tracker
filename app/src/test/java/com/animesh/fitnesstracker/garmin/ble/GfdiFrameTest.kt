package com.animesh.fitnesstracker.garmin.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GfdiFrameTest {
    @Test
    fun `encodes length id payload and crc little endian`() {
        val frame = GfdiFrame.encode(5007, byteArrayOf(3))
        // length 7 = 2 (length) + 2 (id) + 1 (payload) + 2 (crc); 5007 = 0x138F
        assertArrayEquals(byteArrayOf(0x07, 0x00, 0x8F.toByte(), 0x13, 0x03), frame.copyOf(5))
        val crc = Crc16.compute(frame, 0, 5)
        assertEquals(crc and 0xFF, frame[5].toInt() and 0xFF)
        assertEquals(crc shr 8, frame[6].toInt() and 0xFF)
    }

    @Test
    fun `decodes what it encodes`() {
        val payload = ByteArray(100) { it.toByte() }
        val d = GfdiFrame.decode(GfdiFrame.encode(5004, payload))!!
        assertEquals(5004, d.messageId)
        assertArrayEquals(payload, d.payload)
    }

    @Test
    fun `rejects bad length and bad crc`() {
        val good = GfdiFrame.encode(5000, byteArrayOf(1, 2, 3))
        assertNull(GfdiFrame.decode(good.copyOf(good.size - 1)))
        val flipped = good.copyOf().also { it[4] = (it[4].toInt() xor 0x01).toByte() }
        assertNull(GfdiFrame.decode(flipped))
        assertNull(GfdiFrame.decode(byteArrayOf(1, 2)))
    }

    @Test
    fun `normalises ids with bit 15 set`() {
        assertEquals(5024, GfdiFrame.normaliseId(0x8018))
        assertEquals(5037, GfdiFrame.normaliseId(0x8A25))
        assertEquals(5037, GfdiFrame.normaliseId(5037))
        val raw = GfdiFrame.encode(0x8A25, byteArrayOf(0))
        assertEquals(5037, GfdiFrame.decode(raw)!!.messageId)
    }
}
