package com.animesh.fitnesstracker.garmin.ble

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeout

/**
 * Garmin Multi-Link (V2) handle layer over service 6A4E2800. Every write and notification starts with
 * a handle byte; handle 0 is the management channel with 13-byte little-endian requests
 * `00 | type | clientId u64 | service u16 | (reliable u8 | handle u8 | 00 00)` and their responses
 * (REGISTER_ML_RESP adds `status u8, handle u8, reliable u8`). Bring-up is CLOSE_ALL_REQ, then
 * REGISTER_ML_REQ(GFDI = 1, reliable = 2). On the GFDI handle the payload is the COBS stream, split into
 * `maxWrite - 1` byte pieces each prefixed with the handle byte, or wrapped in [MlrChannel] packets when
 * the watch granted a reliable handle. Frames for other client ids are ignored, and a GFDI handle the
 * watch closes on its own is re-registered.
 */
class MultiLink(
    private val sendRaw: (ByteArray) -> Unit,
    maxWrite: Int,
    private val scope: CoroutineScope,
    private val requestReliable: Boolean = true,
    private val timeoutMs: Long = 10_000,
    private val log: (String) -> Unit = {}
) : GfdiLink {
    @Volatile var maxWrite: Int = maxWrite
        set(value) {
            field = value
            mlr?.maxWrite = value
        }

    override var onFrame: ((ByteArray) -> Unit)? = null

    private val lock = Any()
    private var gfdiHandle: Int? = null
    private var mlr: MlrChannel? = null
    private val decoders = HashMap<Int, CobsDecoder>()
    private var closingAll = false
    private var closeAllDone: CompletableDeferred<Unit>? = null
    private var registered: CompletableDeferred<Int>? = null
    private var closed = false

    /** The GFDI handle byte as the watch assigned it (bit 7 set means reliable), null before registration. */
    val handle: Int? get() = gfdiHandle
    val reliable: Boolean get() = mlr != null

    override suspend fun open() {
        val closeAll = CompletableDeferred<Unit>()
        synchronized(lock) {
            closingAll = true
            closeAllDone = closeAll
        }
        sendRaw(closeAllRequest())
        withTimeout(timeoutMs) { closeAll.await() }
        val reg = CompletableDeferred<Int>()
        synchronized(lock) { registered = reg }
        sendRaw(registerRequest(SERVICE_GFDI, requestReliable))
        withTimeout(timeoutMs) { reg.await() }
    }

    override fun onNotification(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val first = bytes[0].toInt() and 0xFF
        val reliableChannel = mlr
        val currentHandle = gfdiHandle
        if (reliableChannel != null && currentHandle != null && MlrChannel.isFor(currentHandle, first)) {
            reliableChannel.onPacket(bytes)
            return
        }
        // Some non-MLR handles also have the MSB set (Gadgetbridge issue 5476), so fall through.
        if (first == 0) {
            handleManagement(bytes)
            return
        }
        if (first == currentHandle) {
            deliverCobs(first, bytes, 1)
        }
    }

    override fun send(frame: ByteArray) {
        val handle = gfdiHandle ?: run {
            log("ML: dropping GFDI frame, handle not registered")
            return
        }
        val cobs = Cobs.encode(frame)
        val reliableChannel = mlr
        if (reliableChannel != null) {
            reliableChannel.send(cobs)
            return
        }
        val piece = (maxWrite - 1).coerceAtLeast(1)
        var pos = 0
        while (pos < cobs.size) {
            val end = minOf(cobs.size, pos + piece)
            val out = ByteArray(1 + end - pos)
            out[0] = handle.toByte()
            System.arraycopy(cobs, pos, out, 1, end - pos)
            sendRaw(out)
            pos = end
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            mlr?.close()
            mlr = null
            gfdiHandle = null
            closeAllDone?.cancel()
            registered?.cancel()
        }
    }

    private fun deliverCobs(handle: Int, bytes: ByteArray, offset: Int) {
        val decoder = synchronized(lock) { decoders.getOrPut(handle) { CobsDecoder() } }
        val frames = decoder.feed(bytes.copyOfRange(offset, bytes.size))
        for (f in frames) onFrame?.invoke(f)
    }

    private fun handleManagement(bytes: ByteArray) {
        if (bytes.size < 10) return
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.get() // handle 0
        val type = buf.get().toInt() and 0xFF
        val clientId = buf.long
        if (clientId != CLIENT_ID) {
            log("ML: ignoring frame for client $clientId")
            return
        }
        when (type) {
            REGISTER_ML_RESP -> {
                if (buf.remaining() < 5) return
                val service = buf.short.toInt() and 0xFFFF
                val status = buf.get().toInt() and 0xFF
                val handle = buf.get().toInt() and 0xFF
                val reliable = buf.get().toInt() and 0xFF
                if (status != 0) {
                    log("ML: register of service $service failed with status $status")
                    registered?.completeExceptionally(IllegalStateException("REGISTER_ML_RESP status $status"))
                    return
                }
                if (service != SERVICE_GFDI) return
                synchronized(lock) {
                    mlr?.close()
                    mlr = null
                    gfdiHandle = handle
                    decoders.remove(handle)
                    if (reliable != 0) {
                        mlr = MlrChannel(handle, maxWrite, scope, sendRaw, { data -> deliverCobs(handle, data, 0) }, log)
                    }
                }
                log("ML: GFDI handle $handle reliable=$reliable")
                registered?.complete(handle)
            }
            CLOSE_HANDLE_RESP -> {
                if (buf.remaining() < 3) return
                val service = buf.short.toInt() and 0xFFFF
                val handle = buf.get().toInt() and 0xFF
                var reRegister = false
                synchronized(lock) {
                    if (service == SERVICE_GFDI || handle == gfdiHandle) {
                        mlr?.close()
                        mlr = null
                        gfdiHandle = null
                        decoders.remove(handle)
                        reRegister = !closingAll && !closed
                    }
                }
                if (reRegister) {
                    log("ML: watch closed the GFDI handle, re-registering")
                    sendRaw(registerRequest(SERVICE_GFDI, requestReliable))
                }
            }
            CLOSE_ALL_RESP -> {
                synchronized(lock) {
                    closingAll = false
                    mlr?.close()
                    mlr = null
                    gfdiHandle = null
                    decoders.clear()
                }
                closeAllDone?.complete(Unit)
            }
            else -> log("ML: ignoring management type $type")
        }
    }

    companion object {
        const val CLIENT_ID = 2L
        const val REGISTER_ML_REQ = 0
        const val REGISTER_ML_RESP = 1
        const val CLOSE_HANDLE_REQ = 2
        const val CLOSE_HANDLE_RESP = 3
        const val CLOSE_ALL_REQ = 5
        const val CLOSE_ALL_RESP = 6

        const val SERVICE_GFDI = 1
        const val SERVICE_REALTIME_HR = 6
        const val SERVICE_REALTIME_STEPS = 7
        const val SERVICE_REALTIME_SPO2 = 19
        const val SERVICE_REALTIME_RESPIRATION = 21

        fun closeAllRequest(): ByteArray = management(CLOSE_ALL_REQ) { it.putShort(0) }

        fun registerRequest(service: Int, reliable: Boolean): ByteArray = management(REGISTER_ML_REQ) {
            it.putShort(service.toShort())
            it.put((if (reliable) 2 else 0).toByte())
        }

        fun closeHandleRequest(service: Int, handle: Int): ByteArray = management(CLOSE_HANDLE_REQ) {
            it.putShort(service.toShort())
            it.put(handle.toByte())
        }

        private fun management(type: Int, body: (ByteBuffer) -> Unit): ByteArray {
            val buf = ByteBuffer.allocate(13).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(0)
            buf.put(type.toByte())
            buf.putLong(CLIENT_ID)
            body(buf)
            return buf.array()
        }
    }
}

/**
 * V1 transport (service 6A4E2401): the COBS stream goes straight onto the send characteristic in
 * `maxWrite - 1` byte writes and every notification feeds one COBS decoder. No handles, no MLR.
 */
class PlainCobsLink(private val sendRaw: (ByteArray) -> Unit, @Volatile var maxWrite: Int) : GfdiLink {
    override var onFrame: ((ByteArray) -> Unit)? = null
    private val decoder = CobsDecoder()

    override fun onNotification(bytes: ByteArray) {
        for (f in decoder.feed(bytes)) onFrame?.invoke(f)
    }

    override suspend fun open() = Unit

    override fun send(frame: ByteArray) {
        val cobs = Cobs.encode(frame)
        val piece = (maxWrite - 1).coerceAtLeast(1)
        var pos = 0
        while (pos < cobs.size) {
            val end = minOf(cobs.size, pos + piece)
            sendRaw(cobs.copyOfRange(pos, end))
            pos = end
        }
    }

    override fun close() = decoder.reset()
}
