package com.animesh.fitnesstracker.garmin.ble

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MlrChannelTest {
    /** Two MLR endpoints joined by a pipe that can drop or hold packets. */
    private class Pair(scope: TestScope, maxWrite: Int = 20) {
        val aToB = ArrayList<ByteArray>()
        val bToA = ArrayList<ByteArray>()
        val receivedByB = ByteArrayOutputStream()
        val receivedByA = ByteArrayOutputStream()
        var dropFromA: (ByteArray) -> Boolean = { false }
        var holdFromA: (ByteArray) -> Boolean = { false }
        val held = ArrayList<ByteArray>()
        lateinit var a: MlrChannel
        lateinit var b: MlrChannel

        init {
            a = MlrChannel(0x81, maxWrite, scope.backgroundScope, { p ->
                aToB.add(p)
                when {
                    dropFromA(p) -> Unit
                    holdFromA(p) -> held.add(p)
                    else -> b.onPacket(p)
                }
            }, { receivedByA.write(it, 0, it.size) })
            b = MlrChannel(0x81, maxWrite, scope.backgroundScope, { p -> bToA.add(p); a.onPacket(p) }, { receivedByB.write(it, 0, it.size) })
        }
    }

    private fun isData(p: ByteArray) = p.size > 2
    private fun seq(p: ByteArray) = p[1].toInt() and 0x3F
    private fun req(p: ByteArray) = ((p[0].toInt() and 0x0F) shl 2) or ((p[1].toInt() and 0xC0) ushr 6)

    @Test
    fun `header layout`() = runTest {
        val out = ArrayList<ByteArray>()
        val c = MlrChannel(0x85, 20, backgroundScope, { out.add(it) }, {})
        c.send(byteArrayOf(1, 2, 3))
        val p = out.single()
        assertEquals(0x80 or (5 shl 4), p[0].toInt() and 0xFF)
        assertEquals(0x00, p[1].toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(1, 2, 3), p.copyOfRange(2, p.size))
        // A pure ACK from the other side after 7 received packets encodes reqNum 7 across both bytes.
        val peerOut = ArrayList<ByteArray>()
        val peer = MlrChannel(0x85, 20, backgroundScope, { peerOut.add(it) }, {})
        repeat(7) { i -> peer.onPacket(byteArrayOf((0x80 or (5 shl 4)).toByte(), i.toByte(), 9)) }
        assertEquals(5, req(peerOut.single())) // immediate ACK after the fifth packet
        advanceTimeBy(300)
        runCurrent()
        val ack = peerOut.last()
        assertEquals(2, ack.size)
        assertEquals(7, req(ack))
        assertEquals(0x80 or (5 shl 4) or (7 shr 2), ack[0].toInt() and 0xFF)
        assertEquals((7 and 3) shl 6, ack[1].toInt() and 0xFF)
    }

    @Test
    fun `fragments to maxWrite minus 2 and the peer reassembles in order`() = runTest {
        val pair = Pair(this, maxWrite = 20)
        val payload = ByteArray(1000) { it.toByte() }
        pair.a.send(payload)
        advanceTimeBy(60_000); runCurrent() // background timers only run while time is advanced explicitly
        assertArrayEquals(payload, pair.receivedByB.toByteArray())
        val data = pair.aToB.filter(::isData)
        assertEquals(56, data.size) // ceil(1000 / 18)
        assertTrue(data.all { it.size <= 20 })
        assertEquals((0 until 56).toList(), data.map(::seq))
    }

    @Test
    fun `window of 32 holds further packets until an ACK arrives`() = runTest {
        val pair = Pair(this, maxWrite = 20)
        pair.dropFromA = { true } // B never sees anything, so no ACKs come back
        pair.a.send(ByteArray(18 * 40))
        runCurrent()
        assertEquals(32, pair.aToB.count(::isData))
    }

    @Test
    fun `delayed ACK after 250 ms or immediately after 5 packets`() = runTest {
        val pair = Pair(this, maxWrite = 20)
        pair.a.send(ByteArray(18))
        runCurrent()
        assertEquals(0, pair.bToA.size)
        advanceTimeBy(249)
        runCurrent()
        assertEquals(0, pair.bToA.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, pair.bToA.size)
        assertEquals(1, req(pair.bToA[0]))

        pair.bToA.clear()
        pair.a.send(ByteArray(18 * 5))
        runCurrent()
        assertEquals(1, pair.bToA.size) // fifth packet triggered an immediate ACK
        assertEquals(6, req(pair.bToA[0]))
    }

    @Test
    fun `dropped packet is retransmitted after the timeout and the window halves`() = runTest {
        val pair = Pair(this, maxWrite = 20)
        var dropped = false
        pair.dropFromA = { p -> if (isData(p) && seq(p) == 2 && !dropped) { dropped = true; true } else false }
        val payload = ByteArray(18 * 6) { (it + 1).toByte() }
        pair.a.send(payload)
        runCurrent()
        assertEquals(32, pair.a.window)
        // B saw 0,1 then 3,4,5 out of order: it re-sent ACK(2) for each, and delivered only 0 and 1.
        assertEquals(36, pair.receivedByB.size())
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(16, pair.a.window)
        advanceTimeBy(60_000); runCurrent() // background timers only run while time is advanced explicitly
        assertArrayEquals(payload, pair.receivedByB.toByteArray())
        assertTrue(pair.aToB.filter(::isData).count { seq(it) == 2 } >= 2)
    }

    @Test
    fun `reordered packets are dropped and recovered by retransmission`() = runTest {
        val pair = Pair(this, maxWrite = 20)
        var holding = true
        pair.holdFromA = { p -> isData(p) && seq(p) == 0 && holding }
        val payload = ByteArray(18 * 3) { (it * 3).toByte() }
        pair.a.send(payload)
        runCurrent()
        // Deliver the held first packet after packets 1 and 2 were already seen (and dropped) by B.
        holding = false
        pair.held.forEach { pair.b.onPacket(it) }
        runCurrent()
        assertEquals(18, pair.receivedByB.size())
        advanceTimeBy(60_000); runCurrent() // background timers only run while time is advanced explicitly
        assertArrayEquals(payload, pair.receivedByB.toByteArray())
    }

    @Test
    fun `retransmission timeout doubles up to 20 s`() = runTest {
        val pair = Pair(this, maxWrite = 20)
        pair.dropFromA = { true }
        pair.a.send(ByteArray(18))
        runCurrent()
        val expectedWindows = listOf(16, 8, 4, 2, 1, 1, 1)
        // Each retransmission timer starts when the previous one fired: 1 s, then 2, 4, 8, 16, 20, 20 s.
        val timeouts = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 20_000L, 20_000L)
        var firesAt = 0L
        for ((i, t) in timeouts.withIndex()) {
            firesAt += t
            advanceTimeBy(firesAt - 1 - currentTime)
            runCurrent()
            assertEquals("window before timeout ${i + 1}", if (i == 0) 32 else expectedWindows[i - 1], pair.a.window)
            advanceTimeBy(2)
            runCurrent()
            assertEquals("window after timeout ${i + 1}", expectedWindows[i], pair.a.window)
        }
        assertEquals(8, pair.aToB.count(::isData)) // original plus seven retransmissions
    }

    @Test
    fun `sequence numbers wrap at 64`() = runTest {
        val pair = Pair(this, maxWrite = 20)
        val payload = ByteArray(18 * 70) { it.toByte() }
        pair.a.send(payload)
        advanceTimeBy(60_000); runCurrent() // background timers only run while time is advanced explicitly
        assertArrayEquals(payload, pair.receivedByB.toByteArray())
        val seqs = pair.aToB.filter(::isData).map(::seq)
        assertEquals(70, seqs.size)
        assertEquals(0, seqs[64])
        assertEquals(5, seqs[69])
    }
}
