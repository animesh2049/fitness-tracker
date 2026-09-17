package com.animesh.fitnesstracker.garmin.sync

import com.animesh.fitnesstracker.garmin.ble.Cobs
import com.animesh.fitnesstracker.garmin.ble.CobsDecoder
import com.animesh.fitnesstracker.garmin.ble.Crc16
import com.animesh.fitnesstracker.garmin.ble.GfdiFrame
import com.animesh.fitnesstracker.garmin.ble.LinkKind
import com.animesh.fitnesstracker.garmin.ble.MlrChannel
import com.animesh.fitnesstracker.garmin.ble.MultiLink
import com.animesh.fitnesstracker.garmin.ble.Transport
import com.animesh.fitnesstracker.garmin.ble.TransportState
import com.animesh.fitnesstracker.garmin.gfdi.GarminProto
import com.animesh.fitnesstracker.garmin.gfdi.GfdiId
import com.animesh.fitnesstracker.garmin.gfdi.GfdiStatus
import com.animesh.fitnesstracker.garmin.gfdi.LeReader
import com.animesh.fitnesstracker.garmin.gfdi.LeWriter
import com.animesh.fitnesstracker.garmin.gfdi.ProtoWriter
import com.animesh.fitnesstracker.garmin.gfdi.SystemEvent
import com.animesh.fitnesstracker.garmin.gfdi.TransferStatus
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A scripted Forerunner: a [Transport] that speaks Multi-Link (optionally with MLR), runs the
 * watch side of the GFDI handshake and serves a directory plus chunked file bodies, with optional CRC
 * corruption, a mid-session SYNCHRONIZATION push and a FILE_AVAILABLE push.
 */
class FakeWatch(
    files: Map<Int, FakeFile>,
    private val scope: CoroutineScope,
    val maxWrite: Int = 20,
    private val chunkSize: Int = 200,
    private val corruptChunk: Pair<Int, Int>? = null,
    private val syncPushAfterArchiveOf: Int? = null,
    private val lateFiles: Map<Int, FakeFile> = emptyMap(),
    private val fileAvailableAfterArchiveOf: Int? = null,
    private val availableFile: Pair<Int, FakeFile>? = null,
    private val reliable: Boolean = false,
    private val silent: Boolean = false,
    private val dropAfterBytes: Int? = null,
    private val stallDownloads: Boolean = false
) : Transport {
    data class FakeFile(val subType: Int, val garminTimestamp: Long, val bytes: ByteArray, val dataType: Int = 128)

    val directory: MutableMap<Int, FakeFile> = files.toMutableMap()
    val archived = LinkedHashSet<Int>()
    /** Every GFDI message the phone sent, as (id, payload), in order. */
    val received = ArrayList<Pair<Int, ByteArray>>()
    val systemEvents = ArrayList<Int>()
    var currentTimeReply: ByteArray? = null
    var deviceInfoReply: ByteArray? = null
    var connectedNotificationEchoed = false
    var closed = false
    var bytesSentToPhone = 0

    private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected(null))
    override val state: StateFlow<TransportState> = _state
    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()
    private val cobs = CobsDecoder()
    private val gfdiHandle = if (reliable) 0x81 else 0x01
    private var mlr: MlrChannel? = null
    private var download: Download? = null
    private var corrupted = false

    private class Download(val index: Int, val bytes: ByteArray) {
        var offset = 0
        var crc = 0
        var chunkNo = 0
        var pendingCrc = 0
    }

    override suspend fun connect() {
        _state.value = TransportState.Connected(LinkKind.MULTI_LINK, maxWrite)
    }

    override fun send(bytes: ByteArray) {
        if (closed || bytes.isEmpty()) return
        val first = bytes[0].toInt() and 0xFF
        val m = mlr
        if (m != null && first and 0x80 != 0) {
            m.onPacket(bytes)
            return
        }
        when (first) {
            0 -> management(bytes)
            gfdiHandle -> onCobsBytes(bytes.copyOfRange(1, bytes.size))
        }
    }

    override fun close() {
        closed = true
        mlr?.close()
        _state.value = TransportState.Disconnected("closed")
        inbox.close()
    }

    /** Simulates the link dropping under the phone. */
    fun dropConnection() {
        _state.value = TransportState.Disconnected("connection lost")
        inbox.close()
    }

    private fun management(bytes: ByteArray) {
        val type = bytes[1].toInt()
        when (type) {
            MultiLink.CLOSE_ALL_REQ -> notifyRaw(mgmt(MultiLink.CLOSE_ALL_RESP) { it.putShort(0) })
            MultiLink.REGISTER_ML_REQ -> {
                val service = ByteBuffer.wrap(bytes, 10, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
                val wantReliable = bytes[12].toInt() != 0
                val grant = if (reliable && wantReliable) 2 else 0
                if (grant != 0) {
                    mlr = MlrChannel(gfdiHandle, maxWrite, scope, { notifyRaw(it) }, { onCobsBytes(it) })
                }
                notifyRaw(mgmt(MultiLink.REGISTER_ML_RESP) { it.putShort(service.toShort()); it.put(0); it.put(gfdiHandle.toByte()); it.put(grant.toByte()) })
                if (!silent) {
                    sendGfdi(GfdiId.AUTH_NEGOTIATION, LeWriter().u8(1).u32(1).toByteArray())
                    sendGfdi(GfdiId.DEVICE_INFORMATION, deviceInformation())
                }
            }
        }
    }

    private fun deviceInformation(): ByteArray = LeWriter().u16(150).u16(4567).u32(3999999999L).u16(1824).u16(375)
        .string("Forerunner 570").string("Forerunner 570").string("fr570").u8(0).toByteArray()

    private fun onCobsBytes(bytes: ByteArray) {
        for (frame in cobs.feed(bytes)) {
            val d = GfdiFrame.decode(frame) ?: error("phone sent a frame with a bad CRC")
            onMessage(d.messageId, d.payload)
        }
    }

    private fun onMessage(id: Int, payload: ByteArray) {
        received.add(id to payload)
        val r = LeReader(payload)
        when (id) {
            GfdiId.RESPONSE -> onStatus(r, payload)
            GfdiId.CONFIGURATION -> {
                ack(id)
                // Things a real watch pushes right after the capability exchange.
                sendGfdi(GfdiId.NOTIFICATION_SUBSCRIPTION, byteArrayOf(1, 0))
                sendGfdi(GfdiId.MUSIC_CONTROL_CAPABILITIES, byteArrayOf(0))
                sendGfdi(5039, byteArrayOf(5)) // FIND_MY_PHONE_REQUEST, which we do not support
                sendProtobufRequest(0x301, GarminProto.connectedNotification())
            }
            GfdiId.SUPPORTED_FILE_TYPES_REQUEST -> sendGfdi(
                GfdiId.RESPONSE,
                LeWriter().u16(id).u8(GfdiStatus.ACK).u8(3).u8(128).u8(4).string("FIT_TYPE_4").u8(128).u8(32).string("FIT_TYPE_32").u8(128).u8(49).string("FIT_TYPE_49").toByteArray()
            )
            GfdiId.DEVICE_SETTINGS -> ack(id)
            GfdiId.SYSTEM_EVENT -> {
                val event = r.u8()
                systemEvents.add(event)
                ack(id)
                if (event == SystemEvent.TIME_UPDATED) sendGfdi(GfdiId.CURRENT_TIME_REQUEST, LeWriter().u32(0x11223344L).toByteArray())
            }
            GfdiId.PROTOBUF_REQUEST -> {
                val requestId = r.u16(); r.u32(); r.u32(); val len = r.u32(); val body = r.bytes(len.toInt())
                protobufStatus(id, requestId)
                if (body.contentEquals(GarminProto.batteryRequest())) {
                    val smart = ProtoWriter().message(8, ProtoWriter().message(3, ProtoWriter().varint(1, 1).varint(2, 77).varint(3, 1))).toByteArray()
                    sendGfdi(GfdiId.PROTOBUF_RESPONSE, LeWriter().u16(requestId).u32(0).u32(smart.size.toLong()).u32(smart.size.toLong()).bytes(smart).toByteArray())
                }
            }
            GfdiId.PROTOBUF_RESPONSE -> {
                val requestId = r.u16(); r.u32(); r.u32(); val len = r.u32(); val body = r.bytes(len.toInt())
                protobufStatus(id, requestId)
                if (requestId == 0x301 && body.contentEquals(GarminProto.connectedNotification())) connectedNotificationEchoed = true
            }
            GfdiId.FILTER -> sendGfdi(GfdiId.RESPONSE, LeWriter().u16(id).u8(GfdiStatus.ACK).u8(0).toByteArray())
            GfdiId.DOWNLOAD_REQUEST -> {
                val index = r.u16()
                val body = if (index == 0) directoryBytes() else directory[index]?.bytes
                if (body == null) {
                    sendGfdi(GfdiId.RESPONSE, LeWriter().u16(id).u8(GfdiStatus.ACK).u8(1).u32(0).toByteArray())
                    return
                }
                download = Download(index, body)
                sendGfdi(GfdiId.RESPONSE, LeWriter().u16(id).u8(GfdiStatus.ACK).u8(0).u32(body.size.toLong()).toByteArray())
                if (body.isNotEmpty() && !stallDownloads) sendChunk()
            }
            GfdiId.SET_FILE_FLAG -> {
                val index = r.u16()
                val flags = r.u8()
                if (flags and 0x10 != 0) {
                    archived.add(index)
                    directory.remove(index)
                }
                sendGfdi(GfdiId.RESPONSE, LeWriter().u16(id).u8(GfdiStatus.ACK).u8(0).u16(index).u8(flags).toByteArray())
                if (index == syncPushAfterArchiveOf) {
                    directory.putAll(lateFiles)
                    sendGfdi(GfdiId.SYNCHRONIZATION, LeWriter().u8(1).u8(4).u32(1L shl 5).toByteArray())
                }
                if (index == fileAvailableAfterArchiveOf && availableFile != null) {
                    directory[availableFile.first] = availableFile.second
                    sendGfdi(GfdiId.FILE_AVAILABLE, entryBytes(availableFile.first, availableFile.second))
                }
            }
        }
    }

    private fun onStatus(r: LeReader, payload: ByteArray) {
        val originalId = r.u16()
        val status = r.u8()
        when (originalId) {
            GfdiId.DEVICE_INFORMATION -> {
                deviceInfoReply = payload
                sendGfdi(GfdiId.CONFIGURATION, LeWriter().u8(2).u8(0x38).u8(0x00).toByteArray())
            }
            GfdiId.CURRENT_TIME_REQUEST -> currentTimeReply = payload
            GfdiId.FILE_TRANSFER_DATA -> {
                val d = download ?: return
                val transferStatus = r.u8()
                val nextOffset = r.u32().toInt()
                if (status == GfdiStatus.ACK && transferStatus == TransferStatus.OK) {
                    d.offset = nextOffset
                    d.crc = d.pendingCrc
                    if (d.offset >= d.bytes.size) download = null else sendChunk()
                } else {
                    d.offset = nextOffset
                    sendChunk()
                }
            }
        }
    }

    private fun sendChunk() {
        val d = download ?: return
        val n = minOf(chunkSize, d.bytes.size - d.offset)
        val data = d.bytes.copyOfRange(d.offset, d.offset + n)
        var crc = Crc16.compute(data, seed = d.crc)
        d.pendingCrc = crc
        if (corruptChunk != null && !corrupted && corruptChunk.first == d.index && corruptChunk.second == d.chunkNo) {
            corrupted = true
            crc = crc xor 0x5555
        }
        d.chunkNo++
        sendGfdi(GfdiId.FILE_TRANSFER_DATA, LeWriter().u8(0).u16(crc).u32(d.offset.toLong()).bytes(data).toByteArray())
    }

    private fun directoryBytes(): ByteArray {
        val w = LeWriter()
        for ((index, f) in directory) w.bytes(entryBytes(index, f))
        return w.toByteArray()
    }

    private fun entryBytes(index: Int, f: FakeFile): ByteArray =
        LeWriter().u16(index).u8(f.dataType).u8(f.subType).u16(index).u8(0).u8(0).u32(f.bytes.size.toLong()).u32(f.garminTimestamp).toByteArray()

    private fun ack(id: Int) = sendGfdi(GfdiId.RESPONSE, LeWriter().u16(id).u8(GfdiStatus.ACK).toByteArray())

    private fun protobufStatus(id: Int, requestId: Int) =
        sendGfdi(GfdiId.RESPONSE, LeWriter().u16(id).u8(GfdiStatus.ACK).u16(requestId).u32(0).u8(0).u8(0).toByteArray())

    private fun sendProtobufRequest(requestId: Int, smart: ByteArray) =
        sendGfdi(GfdiId.PROTOBUF_REQUEST, LeWriter().u16(requestId).u32(0).u32(smart.size.toLong()).u32(smart.size.toLong()).bytes(smart).toByteArray())

    fun sendGfdi(id: Int, payload: ByteArray) {
        val encoded = Cobs.encode(GfdiFrame.encode(id, payload))
        val m = mlr
        if (m != null) {
            m.send(encoded)
            return
        }
        var pos = 0
        while (pos < encoded.size) {
            val end = minOf(encoded.size, pos + maxWrite - 1)
            notifyRaw(byteArrayOf(gfdiHandle.toByte()) + encoded.copyOfRange(pos, end))
            pos = end
        }
    }

    private fun notifyRaw(bytes: ByteArray) {
        if (closed) return
        bytesSentToPhone += bytes.size
        val limit = dropAfterBytes
        if (limit != null && bytesSentToPhone > limit) {
            dropConnection()
            return
        }
        inbox.trySend(bytes)
    }

    private fun mgmt(type: Int, body: (ByteBuffer) -> Unit): ByteArray {
        val b = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0).put(type.toByte()).putLong(MultiLink.CLIENT_ID)
        body(b)
        return b.array().copyOf(b.position())
    }
}
