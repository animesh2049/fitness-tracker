package com.animesh.fitnesstracker.garmin.gfdi

import com.animesh.fitnesstracker.garmin.ble.GfdiFrame

/** What we tell the watch about ourselves in the DEVICE_INFORMATION reply. */
data class PhoneInfo(val bluetoothName: String, val manufacturer: String, val device: String)

/**
 * Builders for every outgoing GFDI frame. Each function returns the complete frame
 * (`u16 len | u16 id | payload | u16 crc`) ready for COBS encoding. Payload layouts follow the
 * protocol reference section 3 and the Gadgetbridge message classes.
 */
object GfdiOut {
    fun frame(id: Int, payload: LeWriter): ByteArray = GfdiFrame.encode(id, payload.toByteArray())

    /** Generic RESPONSE: `u16 originalId, u8 status`. */
    fun status(originalId: Int, status: Int): ByteArray =
        frame(GfdiId.RESPONSE, LeWriter().u16(originalId).u8(status))

    fun ack(originalId: Int): ByteArray = status(originalId, GfdiStatus.ACK)
    fun unsupported(originalId: Int): ByteArray = status(originalId, GfdiStatus.UNSUPPORTED)

    /** `u16 index, u32 offset, u8 requestType (0 CONTINUE, 1 NEW), u16 crcSeed, u32 size, u8 flags`. */
    fun downloadRequest(index: Int, offset: Long = 0, newRequest: Boolean = true, crcSeed: Int = 0, size: Long = 0): ByteArray =
        frame(GfdiId.DOWNLOAD_REQUEST, LeWriter().u16(index).u32(offset).u8(if (newRequest) 1 else 0).u16(crcSeed).u32(size).u8(0))

    /**
     * CREATE_FILE (5005): `u32 size, u8 dataType, u8 subType, u16 fileIndex 0 (watch chooses), u8 reserved 0,
     * u8 subTypeMask 0, u16 numberMask 0xFFFF, u16 pathLength 0, u64 fileId`. 128/5 is a workout FIT file.
     */
    fun createFile(size: Long, dataType: Int = FitFileType.DATA_TYPE_FIT, subType: Int = FitFileType.WORKOUT, fileId: Long = randomFileId()): ByteArray =
        frame(
            GfdiId.CREATE_FILE,
            LeWriter().u32(size).u8(dataType).u8(subType).u16(0).u8(0).u8(0).u16(0xFFFF).u16(0).u64(fileId)
        )

    /** UPLOAD_REQUEST (5003): `u16 fileIndex, u32 size, u32 dataOffset, u16 crcSeed`. */
    fun uploadRequest(fileIndex: Int, size: Long, offset: Long = 0, crcSeed: Int = 0): ByteArray =
        frame(GfdiId.UPLOAD_REQUEST, LeWriter().u16(fileIndex).u32(size).u32(offset).u16(crcSeed))

    /**
     * Outgoing FILE_TRANSFER_DATA (5004): `u8 flags 0, u16 runningCrc, u32 offset, bytes`. The CRC is
     * CRC-16 over every file byte sent so far, this chunk included.
     */
    fun fileTransferData(offset: Long, runningCrc: Int, chunk: ByteArray): ByteArray =
        frame(GfdiId.FILE_TRANSFER_DATA, LeWriter().u8(0).u16(runningCrc).u32(offset).bytes(chunk))

    /** Frame overhead of one outgoing FILE_TRANSFER_DATA: 2 length + 2 id + 1 flags + 2 crc + 4 offset + 2 frame CRC. */
    const val FILE_TRANSFER_DATA_OVERHEAD = 13

    private fun randomFileId(): Long = java.security.SecureRandom().nextLong()

    /** RESPONSE(5004): `u8 ACK, u8 transferStatus, u32 nextOffset`. */
    fun fileTransferDataStatus(transferStatus: Int, nextOffset: Long): ByteArray =
        frame(GfdiId.RESPONSE, LeWriter().u16(GfdiId.FILE_TRANSFER_DATA).u8(GfdiStatus.ACK).u8(transferStatus).u32(nextOffset))

    /** FILTER with `u8 3` (ONLY_NEW). */
    fun filterOnlyNew(): ByteArray = frame(GfdiId.FILTER, LeWriter().u8(3))

    /** `u16 index, u8 flags` (0x10 ARCHIVE, 0x20 DELETE). */
    fun setFileFlag(index: Int, flags: Int): ByteArray = frame(GfdiId.SET_FILE_FLAG, LeWriter().u16(index).u8(flags))

    /** RESPONSE for FIT_DEFINITION / FIT_DATA: `u8 ACK, u8 0 (APPLIED)`. */
    fun fitStatusApplied(originalId: Int): ByteArray =
        frame(GfdiId.RESPONSE, LeWriter().u16(originalId).u8(GfdiStatus.ACK).u8(0))

    /**
     * RESPONSE(5024) with our identity: `u8 ACK, u16 protocolVersion 150, u16 productNumber -1,
     * u32 unitNumber -1, u16 softwareVersion 7791, u16 maxPacketSize -1, string btName,
     * string manufacturer, string device, u8 protocolFlags` (1 when the watch protocol / 100 == 1).
     */
    fun deviceInformationReply(watchProtocolVersion: Int, phone: PhoneInfo): ByteArray {
        val flags = if (watchProtocolVersion / 100 == 1) 1 else 0
        val w = LeWriter().u16(GfdiId.DEVICE_INFORMATION).u8(GfdiStatus.ACK)
            .u16(150).u16(0xFFFF).u32(0xFFFFFFFFL).u16(7791).u16(0xFFFF)
            .string(phone.bluetoothName.ifBlank { "Fitness Tracker" })
            .string(phone.manufacturer)
            .string(phone.device)
            .u8(flags)
        return frame(GfdiId.RESPONSE, w)
    }

    /** CONFIGURATION: `u8 len, bitmap`. */
    fun configuration(bitmap: ByteArray = Capabilities.OURS): ByteArray =
        frame(GfdiId.CONFIGURATION, LeWriter().u8(bitmap.size).bytes(bitmap))

    /**
     * DEVICE_SETTINGS: `u8 count`, then per setting `u8 id, u8 len 1, u8 bool`. Ids 6 AUTO_UPLOAD_ENABLED,
     * 7 WEATHER_CONDITIONS_ENABLED, 8 WEATHER_ALERTS_ENABLED. Gadgetbridge enables weather conditions;
     * we have no weather source, so both weather settings are off.
     */
    fun deviceSettings(): ByteArray = frame(
        GfdiId.DEVICE_SETTINGS,
        LeWriter().u8(3).u8(6).u8(1).u8(1).u8(7).u8(1).u8(0).u8(8).u8(1).u8(0)
    )

    /** SYSTEM_EVENT: `u8 eventType, u8 value`. */
    fun systemEvent(type: Int, value: Int = 0): ByteArray = frame(GfdiId.SYSTEM_EVENT, LeWriter().u8(type).u8(value))

    fun supportedFileTypesRequest(): ByteArray = frame(GfdiId.SUPPORTED_FILE_TYPES_REQUEST, LeWriter())

    /** RESPONSE(5036): `u8 ACK, u8 DISABLED (1), u8 enable, u8 unknown`. */
    fun notificationSubscriptionReply(enable: Int, unknown: Int): ByteArray =
        frame(GfdiId.RESPONSE, LeWriter().u16(GfdiId.NOTIFICATION_SUBSCRIPTION).u8(GfdiStatus.ACK).u8(1).u8(enable).u8(unknown))

    /** RESPONSE(5042): `u8 ACK, u8 count 0` (we control no music). */
    fun musicCapabilitiesReply(): ByteArray =
        frame(GfdiId.RESPONSE, LeWriter().u16(GfdiId.MUSIC_CONTROL_CAPABILITIES).u8(GfdiStatus.ACK).u8(0))

    /** PROTOBUF_REQUEST / PROTOBUF_RESPONSE: `u16 requestId, u32 offset, u32 totalLen, u32 chunkLen, bytes`. */
    fun protobuf(id: Int, requestId: Int, bytes: ByteArray, offset: Long = 0, totalLength: Long = bytes.size.toLong()): ByteArray =
        frame(id, LeWriter().u16(requestId).u32(offset).u32(totalLength).u32(bytes.size.toLong()).bytes(bytes))

    fun protobufRequest(requestId: Int, bytes: ByteArray): ByteArray = protobuf(GfdiId.PROTOBUF_REQUEST, requestId, bytes)
    fun protobufResponse(requestId: Int, bytes: ByteArray): ByteArray = protobuf(GfdiId.PROTOBUF_RESPONSE, requestId, bytes)

    /** RESPONSE for a protobuf message: `u8 status, u16 requestId, u32 offset, u8 chunkStatus, u8 code`. */
    fun protobufStatus(originalId: Int, requestId: Int, offset: Long, chunkStatus: Int, code: Int): ByteArray =
        frame(GfdiId.RESPONSE, LeWriter().u16(originalId).u8(GfdiStatus.ACK).u16(requestId).u32(offset).u8(chunkStatus).u8(code))

    /** RESPONSE(5052): `u8 ACK, u32 ref, u32 garminTime, i32 tzOffsetSeconds, u32 nextDstEnd, u32 nextDstStart`. */
    fun currentTimeReply(referenceId: Long, t: GarminTime.CurrentTime): ByteArray =
        frame(
            GfdiId.RESPONSE,
            LeWriter().u16(GfdiId.CURRENT_TIME_REQUEST).u8(GfdiStatus.ACK).u32(referenceId)
                .u32(t.garminTime).i32(t.tzOffsetSeconds).u32(t.nextTransitionEnd).u32(t.nextTransitionStart)
        )

    /** RESPONSE(5101): `u8 ACK, u8 GUESS_OK (0), u8 unknown, u32 flags` echoing the watch's values. */
    fun authNegotiationReply(unknown: Int, flags: Long): ByteArray =
        frame(GfdiId.RESPONSE, LeWriter().u16(GfdiId.AUTH_NEGOTIATION).u8(GfdiStatus.ACK).u8(0).u8(unknown).u32(flags))
}
