package com.animesh.fitnesstracker.garmin.gfdi

/** GFDI message ids (section 3 of the protocol reference). */
object GfdiId {
    const val RESPONSE = 5000
    const val DOWNLOAD_REQUEST = 5002
    const val FILE_TRANSFER_DATA = 5004
    const val FILTER = 5007
    const val SET_FILE_FLAG = 5008
    const val FILE_AVAILABLE = 5009
    const val FIT_DEFINITION = 5011
    const val FIT_DATA = 5012
    const val DEVICE_INFORMATION = 5024
    const val DEVICE_SETTINGS = 5026
    const val SYSTEM_EVENT = 5030
    const val SUPPORTED_FILE_TYPES_REQUEST = 5031
    const val NOTIFICATION_SUBSCRIPTION = 5036
    const val SYNCHRONIZATION = 5037
    const val MUSIC_CONTROL_CAPABILITIES = 5042
    const val PROTOBUF_REQUEST = 5043
    const val PROTOBUF_RESPONSE = 5044
    const val CONFIGURATION = 5050
    const val CURRENT_TIME_REQUEST = 5052
    const val AUTH_NEGOTIATION = 5101
}

/** Status byte of a RESPONSE frame. */
object GfdiStatus {
    const val ACK = 0
    const val NAK = 1
    const val UNSUPPORTED = 2
    const val DECODE_ERROR = 3
    const val CRC_ERROR = 4
    const val LENGTH_ERROR = 5
}

object DownloadStatus {
    const val OK = 0
    const val INDEX_UNKNOWN = 1
    const val INDEX_NOT_READABLE = 2
    const val NO_SPACE_LEFT = 3
    const val INVALID = 4
    const val NOT_READY = 5
    const val CRC_INCORRECT = 6
}

object TransferStatus {
    const val OK = 0
    const val RESEND = 1
    const val ABORT = 2
    const val CRC_MISMATCH = 3
    const val OFFSET_MISMATCH = 4
    const val SYNC_PAUSED = 5
}

object SystemEvent {
    const val SYNC_COMPLETE = 0
    const val SYNC_FAIL = 1
    const val PAIR_COMPLETE = 4
    const val HOST_DID_ENTER_FOREGROUND = 6
    const val HOST_DID_ENTER_BACKGROUND = 7
    const val SYNC_READY = 8
    const val SETUP_WIZARD_COMPLETE = 14
    const val TIME_UPDATED = 16
}

object FileFlag {
    const val ARCHIVE = 0x10
    const val DELETE = 0x20
}

object ProtobufChunkStatus {
    const val KEPT = 0
    const val DISCARDED = 1
}

object ProtobufCode {
    const val NO_ERROR = 0
    const val UNKNOWN_REQUEST_ID = 100
    const val DUPLICATE_PACKET = 101
    const val MISSING_PACKET = 102
    const val EXCEEDED_TOTAL_LENGTH = 103
    const val PARSE_ERROR = 200
    const val UNKNOWN = 201
}

/**
 * One 16-byte little-endian directory entry (`u16 index, u8 dataType, u8 subType, u16 number,
 * u8 specificFlags, u8 flags, u32 size, u32 garminTimestamp`), also the payload of FILE_AVAILABLE.
 * dataType 128 means FIT and [subType] is then the FIT file_id.type. A wire timestamp of 0 is unknown.
 */
data class DirectoryEntry(
    val index: Int,
    val dataType: Int,
    val subType: Int,
    val number: Int,
    val specificFlags: Int,
    val flags: Int,
    val size: Long,
    val garminTimestamp: Long
) {
    val isFit: Boolean get() = dataType == 128
    /** Unix seconds, or null when the watch had no date for the file. */
    val unixTimestamp: Long? get() = if (garminTimestamp == 0L) null else GarminTime.toUnix(garminTimestamp)

    companion object {
        const val SIZE = 16

        fun read(r: LeReader): DirectoryEntry = DirectoryEntry(
            index = r.u16(), dataType = r.u8(), subType = r.u8(), number = r.u16(),
            specificFlags = r.u8(), flags = r.u8(), size = r.u32(), garminTimestamp = r.u32()
        )

        /** Parses a directory file body; trailing partial bytes and all-zero entries are skipped. */
        fun parseDirectory(bytes: ByteArray): List<DirectoryEntry> {
            val out = ArrayList<DirectoryEntry>(bytes.size / SIZE)
            val r = LeReader(bytes)
            while (r.remaining >= SIZE) {
                val e = read(r)
                if (e.index == 0 && e.dataType == 0 && e.subType == 0 && e.size == 0L) continue
                out.add(e)
            }
            return out
        }
    }
}

data class FileTypeInfo(val dataType: Int, val subType: Int, val name: String)

/** Every incoming GFDI message the stack understands; anything else parses to [GfdiMessage.Unknown]. */
sealed class GfdiMessage {
    abstract val id: Int

    /** RESPONSE (5000) whose original id has no typed decoder here, or a decode of one that failed. */
    data class GenericStatus(val originalId: Int, val status: Int, val extra: ByteArray) : GfdiMessage() {
        override val id: Int get() = GfdiId.RESPONSE
    }

    data class DownloadRequestStatus(val status: Int, val downloadStatus: Int, val maxFileSize: Long) : GfdiMessage() {
        override val id: Int get() = GfdiId.RESPONSE
        val ok: Boolean get() = status == GfdiStatus.ACK && downloadStatus == DownloadStatus.OK
    }

    data class FileTransferDataStatus(val status: Int, val transferStatus: Int, val nextOffset: Long) : GfdiMessage() {
        override val id: Int get() = GfdiId.RESPONSE
    }

    data class SetFileFlagStatus(val status: Int, val flagsStatus: Int, val index: Int, val flags: Int) : GfdiMessage() {
        override val id: Int get() = GfdiId.RESPONSE
    }

    data class SupportedFileTypesStatus(val status: Int, val types: List<FileTypeInfo>) : GfdiMessage() {
        override val id: Int get() = GfdiId.RESPONSE
    }

    data class ProtobufStatus(
        val originalId: Int, val status: Int, val requestId: Int, val offset: Long, val chunkStatus: Int, val code: Int
    ) : GfdiMessage() {
        override val id: Int get() = GfdiId.RESPONSE
    }

    data class FileTransferData(val flags: Int, val crc: Int, val offset: Long, val data: ByteArray) : GfdiMessage() {
        override val id: Int get() = GfdiId.FILE_TRANSFER_DATA
    }

    data class FileAvailable(val entry: DirectoryEntry) : GfdiMessage() {
        override val id: Int get() = GfdiId.FILE_AVAILABLE
    }

    data class FitDefinition(val payload: ByteArray) : GfdiMessage() {
        override val id: Int get() = GfdiId.FIT_DEFINITION
    }

    data class FitData(val payload: ByteArray) : GfdiMessage() {
        override val id: Int get() = GfdiId.FIT_DATA
    }

    data class DeviceInformation(
        val protocolVersion: Int,
        val productNumber: Int,
        val unitNumber: Long,
        val softwareVersion: Int,
        val maxPacketSize: Int,
        val bluetoothName: String,
        val deviceName: String,
        val deviceModel: String
    ) : GfdiMessage() {
        override val id: Int get() = GfdiId.DEVICE_INFORMATION
        /** "18.24" style string from the watch's `softwareVersion` (major * 100 + minor). */
        val softwareVersionString: String get() = "%d.%02d".format(softwareVersion / 100, softwareVersion % 100)
    }

    data class Configuration(val bitmap: ByteArray) : GfdiMessage() {
        override val id: Int get() = GfdiId.CONFIGURATION
    }

    data class NotificationSubscription(val enable: Int, val unknown: Int) : GfdiMessage() {
        override val id: Int get() = GfdiId.NOTIFICATION_SUBSCRIPTION
    }

    data class Synchronization(val type: Int, val bitmask: Long) : GfdiMessage() {
        override val id: Int get() = GfdiId.SYNCHRONIZATION
    }

    data class MusicControlCapabilities(val capabilities: Int) : GfdiMessage() {
        override val id: Int get() = GfdiId.MUSIC_CONTROL_CAPABILITIES
    }

    data class Protobuf(
        override val id: Int, val requestId: Int, val offset: Long, val totalLength: Long, val chunkLength: Long, val bytes: ByteArray
    ) : GfdiMessage() {
        val isRequest: Boolean get() = id == GfdiId.PROTOBUF_REQUEST
        val isComplete: Boolean get() = offset == 0L && totalLength == chunkLength
    }

    data class CurrentTimeRequest(val referenceId: Long) : GfdiMessage() {
        override val id: Int get() = GfdiId.CURRENT_TIME_REQUEST
    }

    data class AuthNegotiation(val unknown: Int, val flags: Long) : GfdiMessage() {
        override val id: Int get() = GfdiId.AUTH_NEGOTIATION
    }

    data class Unknown(override val id: Int, val payload: ByteArray) : GfdiMessage()
}

/** Turns a decoded GFDI frame (normalised id + payload) into a [GfdiMessage]. Never throws. */
object GfdiParser {
    fun parse(id: Int, payload: ByteArray): GfdiMessage {
        return try {
            val r = LeReader(payload)
            when (id) {
                GfdiId.RESPONSE -> parseStatus(r, payload)
                GfdiId.FILE_TRANSFER_DATA -> GfdiMessage.FileTransferData(r.u8(), r.u16(), r.u32(), r.rest())
                GfdiId.FILE_AVAILABLE -> GfdiMessage.FileAvailable(DirectoryEntry.read(r))
                GfdiId.FIT_DEFINITION -> GfdiMessage.FitDefinition(payload)
                GfdiId.FIT_DATA -> GfdiMessage.FitData(payload)
                GfdiId.DEVICE_INFORMATION -> GfdiMessage.DeviceInformation(
                    protocolVersion = r.u16(), productNumber = r.u16(), unitNumber = r.u32(),
                    softwareVersion = r.u16(), maxPacketSize = r.u16(),
                    bluetoothName = r.string(), deviceName = r.string(), deviceModel = r.string()
                )
                GfdiId.CONFIGURATION -> GfdiMessage.Configuration(r.bytes(r.u8()))
                GfdiId.NOTIFICATION_SUBSCRIPTION -> GfdiMessage.NotificationSubscription(r.u8(), if (r.remaining > 0) r.u8() else 0)
                GfdiId.SYNCHRONIZATION -> {
                    val type = r.u8()
                    val size = r.u8()
                    val mask = when (size) {
                        8 -> r.u64()
                        4 -> r.u32()
                        else -> 0L
                    }
                    GfdiMessage.Synchronization(type, mask)
                }
                GfdiId.MUSIC_CONTROL_CAPABILITIES -> GfdiMessage.MusicControlCapabilities(if (r.remaining > 0) r.u8() else 0)
                GfdiId.PROTOBUF_REQUEST, GfdiId.PROTOBUF_RESPONSE -> {
                    val requestId = r.u16()
                    val offset = r.u32()
                    val total = r.u32()
                    val chunk = r.u32()
                    GfdiMessage.Protobuf(id, requestId, offset, total, chunk, r.bytes(chunk.toInt()))
                }
                GfdiId.CURRENT_TIME_REQUEST -> GfdiMessage.CurrentTimeRequest(r.u32())
                GfdiId.AUTH_NEGOTIATION -> GfdiMessage.AuthNegotiation(r.u8(), r.u32())
                else -> GfdiMessage.Unknown(id, payload)
            }
        } catch (e: IllegalArgumentException) {
            GfdiMessage.Unknown(id, payload)
        }
    }

    private fun parseStatus(r: LeReader, payload: ByteArray): GfdiMessage {
        val originalId = r.u16()
        val status = r.u8()
        return try {
            when (originalId) {
                GfdiId.DOWNLOAD_REQUEST -> GfdiMessage.DownloadRequestStatus(status, r.u8(), r.u32())
                GfdiId.FILE_TRANSFER_DATA -> GfdiMessage.FileTransferDataStatus(status, r.u8(), r.u32())
                GfdiId.SET_FILE_FLAG -> GfdiMessage.SetFileFlagStatus(status, r.u8(), r.u16(), r.u8())
                GfdiId.SUPPORTED_FILE_TYPES_REQUEST -> {
                    if (status != GfdiStatus.ACK) return GfdiMessage.SupportedFileTypesStatus(status, emptyList())
                    val n = r.u8()
                    val types = ArrayList<FileTypeInfo>(n)
                    repeat(n) { types.add(FileTypeInfo(r.u8(), r.u8(), r.string())) }
                    GfdiMessage.SupportedFileTypesStatus(status, types)
                }
                GfdiId.PROTOBUF_REQUEST, GfdiId.PROTOBUF_RESPONSE ->
                    GfdiMessage.ProtobufStatus(originalId, status, r.u16(), r.u32(), r.u8(), r.u8())
                else -> GfdiMessage.GenericStatus(originalId, status, r.rest())
            }
        } catch (e: IllegalArgumentException) {
            GfdiMessage.GenericStatus(originalId, status, payload.copyOfRange(minOf(3, payload.size), payload.size))
        }
    }
}
