package com.animesh.fitnesstracker.garmin.gfdi

import java.io.ByteArrayOutputStream
import java.time.ZoneId

/**
 * Watch-driven GFDI handshake and the always-on responder. The watch opens with DEVICE_INFORMATION
 * (answered with ours), then CONFIGURATION (ACK, then our capability bitmap, then the initialisation
 * burst: SUPPORTED_FILE_TYPES_REQUEST, DEVICE_SETTINGS, SYSTEM_EVENT TIME_UPDATED, SYSTEM_EVENT
 * SYNC_READY, the protobuf battery request, and on a first connect PAIR_COMPLETE, SYNC_COMPLETE and
 * SETUP_WIZARD_COMPLETE). AUTH_NEGOTIATION, CURRENT_TIME_REQUEST, NOTIFICATION_SUBSCRIPTION, music
 * capabilities, FIT definitions/data, protobuf pings and unknown ids are answered whenever they arrive,
 * status frame first and reply second. Readiness is the SUPPORTED_FILE_TYPES reply, or CONFIGURATION plus
 * a 2 s grace when the watch never answers it. Pure Kotlin: [send] is the only side effect.
 */
class Handshake(
    private val send: (ByteArray) -> Unit,
    private val phone: PhoneInfo,
    private val firstConnect: Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val log: (String) -> Unit = {}
) {
    var deviceInfo: GfdiMessage.DeviceInformation? = null
        private set
    var watchCapabilities: ByteArray? = null
        private set
    var batteryPercent: Int? = null
        private set
    var supportedTypes: List<FileTypeInfo>? = null
        private set
    var configuredAtMillis: Long? = null
        private set

    /** SYNCHRONIZATION and FILE_AVAILABLE pushes, already ACKed, for whoever runs the file sync. */
    var onPush: ((GfdiMessage) -> Unit)? = null

    private var nextRequestId = 1
    private val partialProtobufs = HashMap<Int, ByteArrayOutputStream>()

    /** True once the watch answered SUPPORTED_FILE_TYPES_REQUEST, or [GRACE_MS] after CONFIGURATION. */
    fun isReady(nowMillis: Long = clock()): Boolean {
        if (supportedTypes != null) return true
        val at = configuredAtMillis ?: return false
        return nowMillis - at >= GRACE_MS
    }

    /** Milliseconds until the grace period makes us ready, or null when CONFIGURATION has not arrived. */
    fun graceRemainingMs(nowMillis: Long = clock()): Long? = configuredAtMillis?.let { (it + GRACE_MS - nowMillis).coerceAtLeast(0) }

    fun allocateRequestId(): Int {
        val id = nextRequestId
        nextRequestId = (nextRequestId + 1) and 0xFFFF
        if (nextRequestId == 0) nextRequestId = 1
        return id
    }

    fun onMessage(msg: GfdiMessage) {
        when (msg) {
            is GfdiMessage.DeviceInformation -> {
                deviceInfo = msg
                log("Watch: ${msg.deviceName} (${msg.deviceModel}) fw ${msg.softwareVersionString} unit ${msg.unitNumber} protocol ${msg.protocolVersion} maxPacket ${msg.maxPacketSize}")
                send(GfdiOut.deviceInformationReply(msg.protocolVersion, phone))
            }
            is GfdiMessage.Configuration -> {
                send(GfdiOut.ack(GfdiId.CONFIGURATION))
                send(GfdiOut.configuration(Capabilities.OURS))
                if (configuredAtMillis == null) {
                    watchCapabilities = msg.bitmap
                    configuredAtMillis = clock()
                    sendInitialisationBurst()
                } else {
                    log("Handshake: repeated CONFIGURATION, not re-initialising")
                }
            }
            is GfdiMessage.AuthNegotiation -> send(GfdiOut.authNegotiationReply(msg.unknown, msg.flags))
            is GfdiMessage.CurrentTimeRequest -> {
                val t = GarminTime.currentTime(clock(), zone)
                log("Time request #${msg.referenceId}: offset ${t.tzOffsetSeconds}s")
                send(GfdiOut.currentTimeReply(msg.referenceId, t))
            }
            is GfdiMessage.NotificationSubscription -> send(GfdiOut.notificationSubscriptionReply(msg.enable, msg.unknown))
            is GfdiMessage.MusicControlCapabilities -> send(GfdiOut.musicCapabilitiesReply())
            is GfdiMessage.FitDefinition -> send(GfdiOut.fitStatusApplied(GfdiId.FIT_DEFINITION))
            is GfdiMessage.FitData -> send(GfdiOut.fitStatusApplied(GfdiId.FIT_DATA))
            is GfdiMessage.Protobuf -> onProtobuf(msg)
            is GfdiMessage.Synchronization -> {
                send(GfdiOut.ack(GfdiId.SYNCHRONIZATION))
                log("Watch asks to sync (type ${msg.type}, mask 0x${msg.bitmask.toString(16)})")
                onPush?.invoke(msg)
            }
            is GfdiMessage.FileAvailable -> {
                send(GfdiOut.ack(GfdiId.FILE_AVAILABLE))
                onPush?.invoke(msg)
            }
            is GfdiMessage.SupportedFileTypesStatus -> {
                supportedTypes = msg.types
                log("Watch supports ${msg.types.size} file types")
            }
            is GfdiMessage.GenericStatus -> if (msg.status != GfdiStatus.ACK) log("Watch answered ${msg.originalId} with status ${msg.status}")
            is GfdiMessage.ProtobufStatus -> if (msg.status != GfdiStatus.ACK || msg.chunkStatus != ProtobufChunkStatus.KEPT) {
                log("Protobuf #${msg.requestId} status ${msg.status}/${msg.chunkStatus}/${msg.code}")
            }
            is GfdiMessage.DownloadRequestStatus, is GfdiMessage.FileTransferDataStatus, is GfdiMessage.SetFileFlagStatus -> Unit
            is GfdiMessage.FileTransferData -> {
                // A chunk nobody asked for: tell the watch to stop instead of leaving it waiting for an ACK.
                send(GfdiOut.fileTransferDataStatus(TransferStatus.ABORT, 0))
            }
            is GfdiMessage.Unknown -> {
                log("Unsupported message ${msg.id} (${msg.payload.size} bytes)")
                send(GfdiOut.unsupported(msg.id))
            }
        }
    }

    private fun sendInitialisationBurst() {
        send(GfdiOut.supportedFileTypesRequest())
        send(GfdiOut.deviceSettings())
        send(GfdiOut.systemEvent(SystemEvent.TIME_UPDATED))
        send(GfdiOut.systemEvent(SystemEvent.SYNC_READY))
        send(GfdiOut.protobufRequest(allocateRequestId(), GarminProto.batteryRequest()))
        if (firstConnect) {
            log("First connect: completing pairing on the watch")
            send(GfdiOut.systemEvent(SystemEvent.PAIR_COMPLETE))
            send(GfdiOut.systemEvent(SystemEvent.SYNC_COMPLETE))
            send(GfdiOut.systemEvent(SystemEvent.SETUP_WIZARD_COMPLETE))
        }
    }

    private fun onProtobuf(msg: GfdiMessage.Protobuf) {
        val bytes: ByteArray
        if (msg.isComplete) {
            bytes = msg.bytes
        } else {
            val buf = if (msg.offset == 0L) ByteArrayOutputStream().also { partialProtobufs[msg.requestId] = it }
            else partialProtobufs[msg.requestId]
            if (buf == null || buf.size().toLong() != msg.offset) {
                partialProtobufs.remove(msg.requestId)
                send(GfdiOut.protobufStatus(msg.id, msg.requestId, msg.offset, ProtobufChunkStatus.DISCARDED, ProtobufCode.MISSING_PACKET))
                return
            }
            buf.write(msg.bytes, 0, msg.bytes.size)
            if (buf.size() < msg.totalLength) {
                send(GfdiOut.protobufStatus(msg.id, msg.requestId, msg.offset, ProtobufChunkStatus.KEPT, ProtobufCode.NO_ERROR))
                return
            }
            partialProtobufs.remove(msg.requestId)
            bytes = buf.toByteArray()
        }
        when (val incoming = GarminProto.classify(bytes)) {
            is GarminProto.Incoming.BatteryResponse -> {
                batteryPercent = incoming.level
                log("Battery ${incoming.level ?: "?"}%")
                kept(msg)
            }
            GarminProto.Incoming.ConnectedNotification -> {
                kept(msg)
                send(GfdiOut.protobufResponse(msg.requestId, GarminProto.connectedNotification()))
            }
            GarminProto.Incoming.GetLocationRequest -> {
                kept(msg)
                send(GfdiOut.protobufResponse(msg.requestId, GarminProto.locationDeclined()))
            }
            GarminProto.Incoming.LocationUpdatesRequest -> {
                kept(msg)
                send(GfdiOut.protobufResponse(msg.requestId, GarminProto.locationUpdatesDeclined()))
            }
            is GarminProto.Incoming.Other -> {
                log("Protobuf #${msg.requestId} for Smart field ${incoming.smartField}: discarded")
                send(GfdiOut.protobufStatus(msg.id, msg.requestId, msg.offset, ProtobufChunkStatus.DISCARDED, ProtobufCode.UNKNOWN_REQUEST_ID))
            }
        }
    }

    private fun kept(msg: GfdiMessage.Protobuf) =
        send(GfdiOut.protobufStatus(msg.id, msg.requestId, msg.offset, ProtobufChunkStatus.KEPT, ProtobufCode.NO_ERROR))

    companion object {
        const val GRACE_MS = 2_000L
        const val TIMEOUT_MS = 20_000L
    }
}
