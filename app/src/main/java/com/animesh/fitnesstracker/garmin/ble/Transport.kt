package com.animesh.fitnesstracker.garmin.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Which Garmin GATT generation the connection ended up on. */
enum class LinkKind {
    /** Service 6A4E2800: every write and notification carries a handle byte (Multi-Link, section 1.7). */
    MULTI_LINK,
    /** Service 6A4E2401: plain COBS on a single characteristic pair, no handle byte. */
    V1
}

sealed class TransportState {
    data class Disconnected(val reason: String?) : TransportState()
    data object Connecting : TransportState()
    data class Connected(val kind: LinkKind, val maxWrite: Int) : TransportState()
}

/**
 * The raw byte pipe to the watch: one BLE characteristic pair with notifications on the receive side
 * and an ordered write queue on the send side. [GattClient] is the Android implementation; tests use a
 * fake so every layer above (Multi-Link, MLR, COBS, GFDI) runs on the JVM.
 */
interface Transport {
    val state: StateFlow<TransportState>

    /** One element per notification on the receive characteristic. Single collector. */
    val incoming: Flow<ByteArray>

    /** Connects, discovers services, negotiates the MTU and subscribes; [state] is Connected on return. */
    suspend fun connect()

    /** Enqueues one write; writes go out in order, each after the previous one was confirmed. */
    fun send(bytes: ByteArray)

    fun close()
}

/**
 * A channel that carries whole GFDI frames (already COBS-decoded on the way in, COBS-encoded on the
 * way out) over a [Transport]. Implemented by [MultiLink] (V2) and [PlainCobsLink] (V1).
 */
interface GfdiLink {
    /** Called with each complete, COBS-decoded GFDI frame. Set before [open]. */
    var onFrame: ((ByteArray) -> Unit)?

    /** Feeds one raw notification payload from the transport. */
    fun onNotification(bytes: ByteArray)

    /** Brings the channel up (handle registration on V2); returns when GFDI frames can be sent. */
    suspend fun open()

    fun send(frame: ByteArray)

    fun close()
}
