package com.animesh.fitnesstracker.garmin.ble

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Crc16Test {
    private fun fixtures(): List<File> {
        val url = javaClass.classLoader!!.getResource("fit") ?: error("test resources fit/ missing")
        val files = File(url.toURI()).listFiles { f -> f.name.endsWith(".fit") }!!.sortedBy { it.name }
        assertTrue("expected FIT fixtures", files.size >= 10)
        return files
    }

    @Test
    fun `matches the file CRC stored in every real FIT fixture`() {
        for (f in fixtures()) {
            val bytes = f.readBytes()
            val stored = (bytes[bytes.size - 2].toInt() and 0xFF) or ((bytes[bytes.size - 1].toInt() and 0xFF) shl 8)
            val computed = Crc16.compute(bytes, 0, bytes.size - 2)
            assertEquals("CRC of ${f.name}", stored, computed)
        }
    }

    @Test
    fun `header CRC of a FIT fixture also matches`() {
        // A 14-byte FIT header ends with a CRC over its first 12 bytes (0 when the writer skipped it).
        for (f in fixtures()) {
            val bytes = f.readBytes()
            if (bytes[0].toInt() != 14) continue
            val stored = (bytes[12].toInt() and 0xFF) or ((bytes[13].toInt() and 0xFF) shl 8)
            if (stored == 0) continue
            assertEquals("header CRC of ${f.name}", stored, Crc16.compute(bytes, 0, 12))
        }
    }

    @Test
    fun `running variant equals one-shot computation`() {
        val data = ByteArray(1000) { (it * 31 + 7).toByte() }
        val whole = Crc16.compute(data)
        var running = 0
        var pos = 0
        for (len in listOf(1, 13, 200, 254, 300, 232)) {
            running = Crc16.compute(data, pos, len, running)
            pos += len
        }
        assertEquals(1000, pos)
        assertEquals(whole, running)
    }

    @Test
    fun `known vector`() {
        // CRC-16/ARC of "123456789" is 0xBB3D.
        assertEquals(0xBB3D, Crc16.compute("123456789".toByteArray()))
        assertEquals(0, Crc16.compute(ByteArray(0)))
    }
}
