package com.animesh.fitnesstracker.garmin.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MultiLinkTest {
    private fun hex(s: String): ByteArray = s.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    private fun registerResp(service: Int, status: Int, handle: Int, reliable: Int, clientId: Long = 2): ByteArray {
        val b = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0).put(1).putLong(clientId).putShort(service.toShort()).put(status.toByte()).put(handle.toByte()).put(reliable.toByte())
        return b.array()
    }

    private fun closeAllResp(clientId: Long = 2): ByteArray {
        val b = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0).put(6).putLong(clientId).putShort(0)
        return b.array()
    }

    private fun closeHandleResp(service: Int, handle: Int): ByteArray {
        val b = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0).put(3).putLong(2).putShort(service.toShort()).put(handle.toByte()).put(0)
        return b.array()
    }

    @Test
    fun `management frames are 13 bytes with client id 2`() {
        assertArrayEquals(hex("00 05 02 00 00 00 00 00 00 00 00 00"), MultiLink.closeAllRequest().copyOf(12))
        assertEquals(13, MultiLink.closeAllRequest().size)
        assertArrayEquals(hex("00 00 02 00 00 00 00 00 00 00 01 00 02"), MultiLink.registerRequest(1, true))
        assertArrayEquals(hex("00 00 02 00 00 00 00 00 00 00 06 00 00"), MultiLink.registerRequest(6, false))
        assertArrayEquals(hex("00 02 02 00 00 00 00 00 00 00 13 00 05"), MultiLink.closeHandleRequest(19, 5))
    }

    @Test
    fun `open sends CLOSE_ALL then REGISTER and splits outgoing frames with the handle byte`() = runTest {
        val writes = ArrayList<ByteArray>()
        val link = MultiLink({ writes.add(it) }, maxWrite = 8, scope = backgroundScope, requestReliable = false)
        val frames = ArrayList<ByteArray>()
        link.onFrame = { frames.add(it) }
        val opening = launch { link.open() }
        runCurrent()
        assertEquals(1, writes.size)
        assertEquals(5, writes[0][1].toInt())
        link.onNotification(closeAllResp())
        runCurrent()
        assertEquals(2, writes.size)
        assertEquals(0, writes[1][1].toInt())
        link.onNotification(registerResp(1, 0, 0x03, 0))
        opening.join()
        assertEquals(3, link.handle)
        assertFalse(link.reliable)

        writes.clear()
        val frame = GfdiFrame.encode(5007, byteArrayOf(3))
        link.send(frame)
        val cobs = Cobs.encode(frame)
        val pieces = (cobs.size + 6) / 7
        assertEquals(pieces, writes.size)
        for (w in writes) {
            assertEquals(3, w[0].toInt())
            assertTrue(w.size <= 8)
        }
        assertArrayEquals(cobs, writes.fold(ByteArray(0)) { acc, w -> acc + w.copyOfRange(1, w.size) })

        // Incoming: handle byte stripped, COBS reassembled across notifications, other handles ignored.
        val incoming = Cobs.encode(GfdiFrame.encode(5024, byteArrayOf(9, 9)))
        link.onNotification(byteArrayOf(7) + incoming)
        assertTrue(frames.isEmpty())
        link.onNotification(byteArrayOf(3) + incoming.copyOfRange(0, 4))
        link.onNotification(byteArrayOf(3) + incoming.copyOfRange(4, incoming.size))
        assertEquals(1, frames.size)
        assertEquals(5024, GfdiFrame.decode(frames[0])!!.messageId)
    }

    @Test
    fun `frames for another client id are ignored`() = runTest {
        val writes = ArrayList<ByteArray>()
        val link = MultiLink({ writes.add(it) }, 20, backgroundScope, requestReliable = false, timeoutMs = 500)
        val opening = launch { runCatching { link.open() } }
        runCurrent()
        link.onNotification(closeAllResp(clientId = 9))
        runCurrent()
        assertEquals(1, writes.size) // still waiting, no REGISTER sent
        link.onNotification(closeAllResp())
        runCurrent()
        assertEquals(2, writes.size)
        link.onNotification(registerResp(1, 0, 1, 0, clientId = 9))
        runCurrent()
        assertEquals(null, link.handle)
        link.onNotification(registerResp(1, 0, 1, 0))
        opening.join()
        assertEquals(1, link.handle)
    }

    @Test
    fun `re-registers GFDI when the watch closes the handle`() = runTest {
        val writes = ArrayList<ByteArray>()
        val link = MultiLink({ writes.add(it) }, 20, backgroundScope, requestReliable = true)
        val opening = launch { link.open() }
        runCurrent()
        link.onNotification(closeAllResp())
        runCurrent()
        link.onNotification(registerResp(1, 0, 0x81, 2))
        opening.join()
        assertTrue(link.reliable)
        writes.clear()
        link.onNotification(closeHandleResp(1, 0x81))
        assertEquals(1, writes.size)
        assertArrayEquals(MultiLink.registerRequest(1, true), writes[0])
        assertEquals(null, link.handle)
    }

    @Test
    fun `reliable handle wraps the COBS stream in MLR packets`() = runTest {
        val writes = ArrayList<ByteArray>()
        val link = MultiLink({ writes.add(it) }, 20, backgroundScope, requestReliable = true)
        val opening = launch { link.open() }
        runCurrent()
        link.onNotification(closeAllResp())
        runCurrent()
        link.onNotification(registerResp(1, 0, 0x81, 2))
        opening.join()
        writes.clear()
        val frame = GfdiFrame.encode(5031, ByteArray(0))
        link.send(frame)
        assertEquals(1, writes.size)
        val p = writes[0]
        assertEquals(0x90, p[0].toInt() and 0xFF) // flag, handle 1, reqNum 0
        assertEquals(0x00, p[1].toInt() and 0xFF) // seq 0
        assertArrayEquals(Cobs.encode(frame), p.copyOfRange(2, p.size))
    }
}
