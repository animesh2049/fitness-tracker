package com.animesh.fitnesstracker.garmin.sync

import com.animesh.fitnesstracker.garmin.ble.GfdiFrame
import com.animesh.fitnesstracker.garmin.ble.GfdiLink
import com.animesh.fitnesstracker.garmin.gfdi.GfdiMessage
import com.animesh.fitnesstracker.garmin.gfdi.GfdiParser
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** Thrown when the watch stops talking, refuses a step, or the link drops while a sync step is waiting. */
class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Message-level view of a [GfdiLink]: incoming frames are length/CRC checked, parsed into
 * [GfdiMessage]s and queued; [receive] hands them out one at a time with a silence timeout, and
 * [send] pushes a complete outgoing frame down the link. [fail] wakes a waiting receiver when the
 * transport dies underneath it.
 */
class GfdiEndpoint(private val link: GfdiLink, private val log: (String) -> Unit = {}) {
    private val inbox = Channel<GfdiMessage>(Channel.UNLIMITED)
    @Volatile private var failure: String? = null

    init {
        link.onFrame = { frame ->
            val decoded = GfdiFrame.decode(frame)
            if (decoded == null) {
                log("Dropped GFDI frame with bad length or CRC (${frame.size} bytes)")
            } else {
                inbox.trySend(GfdiParser.parse(decoded.messageId, decoded.payload))
            }
        }
    }

    fun send(frame: ByteArray) = link.send(frame)

    /** Next message, null after [timeoutMs] of silence; throws [SyncException] when the link failed. */
    suspend fun receive(timeoutMs: Long): GfdiMessage? {
        failure?.let { throw SyncException(it) }
        val result = withTimeoutOrNull(timeoutMs) { inbox.receiveCatching() } ?: return null
        return result.getOrNull() ?: throw SyncException(failure ?: "Link closed")
    }

    /** Marks the link dead; the first reason wins because it is the most specific one. */
    fun fail(reason: String) {
        if (failure == null) failure = reason
        inbox.close()
    }
}
