package com.animesh.fitnesstracker.garmin.ble

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Multi-Link Reliable (MLR) sub-layer, used when REGISTER_ML_RESP reports `reliable != 0`.
 * Packet layout: byte 0 = `1 | hhh | rrrr` (bit 7 MLR flag, bits 6..4 handle and 7, bits 3..0 reqNum
 * bits 5..2), byte 1 = `rr | ssssss` (bits 7..6 reqNum bits 1..0, bits 5..0 seqNum), then a slice of the
 * COBS stream (empty for a pure ACK). `seqNum` numbers this packet, `reqNum` is the cumulative ACK (next
 * sequence number expected). Fragment size is `maxWrite - 2`; the send window starts at 32 and halves
 * on every retransmission timeout, which itself starts at 1 s and doubles up to 20 s. A pure ACK is sent
 * after 5 unacknowledged packets or 250 ms after the first. Out-of-order packets are dropped and the
 * expected ACK is re-sent. Timers run as coroutines on [scope], so tests drive them with virtual time.
 */
class MlrChannel(
    val handle: Int,
    maxWrite: Int,
    private val scope: CoroutineScope,
    private val sendPacket: (ByteArray) -> Unit,
    private val onData: (ByteArray) -> Unit,
    private val log: (String) -> Unit = {}
) {
    @Volatile var maxWrite: Int = maxWrite

    private val lock = Any()
    private var lastSendAck = 0
    private var nextSendSeq = 0
    private var nextRcvSeq = 0
    private var lastRcvAck = 0
    private var maxUnacked = INITIAL_WINDOW
    private var retransmitTimeout = INITIAL_RTO_MS
    private val queue = ArrayDeque<ByteArray>()
    private val sent = arrayOfNulls<Fragment>(SEQ_MODULO)
    private var ackJob: Job? = null
    private var retransmitJob: Job? = null
    private var closed = false

    /** Current send window, exposed for tests. */
    val window: Int get() = synchronized(lock) { maxUnacked }

    fun send(payload: ByteArray) {
        if (payload.isEmpty()) return
        synchronized(lock) {
            if (closed) return
            val size = (maxWrite - 2).coerceAtLeast(1)
            var pos = 0
            while (pos < payload.size) {
                val end = minOf(payload.size, pos + size)
                queue.addLast(payload.copyOfRange(pos, end))
                pos = end
            }
            pump()
        }
    }

    fun onPacket(packet: ByteArray) {
        if (packet.size < 2) return
        val b0 = packet[0].toInt() and 0xFF
        val b1 = packet[1].toInt() and 0xFF
        if (b0 and FLAG == 0) return
        if ((b0 and HANDLE_MASK) shr 4 != handle and 0x07) return
        val reqNum = ((b0 and 0x0F) shl 2) or ((b1 shr 6) and 0x03)
        val seqNum = b1 and 0x3F
        synchronized(lock) {
            if (closed) return
            if (reqNum != lastRcvAck) processAck(reqNum)
            if (packet.size > 2) {
                if (seqNum == nextRcvSeq) {
                    val data = packet.copyOfRange(2, packet.size)
                    nextRcvSeq = (nextRcvSeq + 1) % SEQ_MODULO
                    try {
                        onData(data)
                    } catch (e: Exception) {
                        log("MLR receiver failed: ${e.message}")
                    }
                    scheduleAckIfNeeded()
                } else {
                    log("MLR out of order: expected $nextRcvSeq got $seqNum")
                    sendAck()
                }
            }
            pump()
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            ackJob?.cancel(); ackJob = null
            retransmitJob?.cancel(); retransmitJob = null
            queue.clear()
        }
    }

    private fun processAck(reqNum: Int) {
        retransmitJob?.cancel(); retransmitJob = null
        var i = lastRcvAck
        while (i != reqNum) {
            sent[i] = null
            i = (i + 1) % SEQ_MODULO
        }
        lastRcvAck = reqNum
        if (lastRcvAck != nextSendSeq) startRetransmitTimer()
    }

    private fun scheduleAckIfNeeded() {
        ackJob?.cancel(); ackJob = null
        val unacked = (nextRcvSeq - lastSendAck + SEQ_MODULO) % SEQ_MODULO
        if (unacked >= ACK_THRESHOLD) {
            sendAck()
        } else {
            ackJob = scope.launch {
                delay(ACK_DELAY_MS)
                synchronized(lock) { if (!closed) sendAck() }
            }
        }
    }

    private fun sendAck() {
        ackJob?.cancel(); ackJob = null
        // Book-keep before handing the packet out: a synchronous peer may re-enter us from sendPacket.
        val req = nextRcvSeq
        lastSendAck = req
        sendPacket(packet(req, 0, EMPTY))
    }

    private fun pump() {
        while (true) {
            val unacked = (nextSendSeq - lastRcvAck + SEQ_MODULO) % SEQ_MODULO
            if (unacked >= maxUnacked) return
            val data = queue.removeFirstOrNull() ?: return
            val seq = nextSendSeq
            val req = nextRcvSeq
            sent[seq] = Fragment(data, req)
            nextSendSeq = (seq + 1) % SEQ_MODULO
            // A data packet carries reqNum, so it acknowledges everything received so far (Gadgetbridge
            // still sends a separate pure ACK afterwards; the piggyback is standard MLR behaviour).
            ackJob?.cancel(); ackJob = null
            lastSendAck = req
            if (unacked == 0) startRetransmitTimer()
            sendPacket(packet(req, seq, data))
        }
    }

    private fun startRetransmitTimer() {
        retransmitJob?.cancel()
        val timeout = retransmitTimeout
        retransmitJob = scope.launch {
            delay(timeout)
            synchronized(lock) { if (!closed) onRetransmitTimeout() }
        }
    }

    private fun onRetransmitTimeout() {
        retransmitTimeout = minOf(retransmitTimeout * 2, MAX_RTO_MS)
        maxUnacked = maxOf(1, maxUnacked / 2)
        log("MLR retransmit: rto=${retransmitTimeout}ms window=$maxUnacked")
        var i = lastRcvAck
        while (i != nextSendSeq) {
            sent[i]?.let { sendPacket(packet(it.reqNum, i, it.data)) }
            i = (i + 1) % SEQ_MODULO
        }
        startRetransmitTimer()
    }

    private fun packet(reqNum: Int, seqNum: Int, data: ByteArray): ByteArray {
        val out = ByteArray(2 + data.size)
        out[0] = (FLAG or ((handle and 0x07) shl 4) or ((reqNum shr 2) and 0x0F)).toByte()
        out[1] = (((reqNum and 0x03) shl 6) or (seqNum and 0x3F)).toByte()
        System.arraycopy(data, 0, out, 2, data.size)
        return out
    }

    private class Fragment(val data: ByteArray, val reqNum: Int)

    companion object {
        const val FLAG = 0x80
        const val HANDLE_MASK = 0x70
        const val SEQ_MODULO = 64
        const val INITIAL_WINDOW = 32
        const val INITIAL_RTO_MS = 1_000L
        const val MAX_RTO_MS = 20_000L
        const val ACK_DELAY_MS = 250L
        const val ACK_THRESHOLD = 5
        private val EMPTY = ByteArray(0)

        /** True when a notification's first byte marks an MLR packet for [handle]. */
        fun isFor(handle: Int, firstByte: Int): Boolean =
            firstByte and FLAG != 0 && (firstByte and HANDLE_MASK) shr 4 == handle and 0x07
    }
}
