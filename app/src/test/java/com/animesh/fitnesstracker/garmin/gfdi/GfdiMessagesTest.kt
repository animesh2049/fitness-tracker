package com.animesh.fitnesstracker.garmin.gfdi

import com.animesh.fitnesstracker.garmin.ble.Crc16
import com.animesh.fitnesstracker.garmin.ble.GfdiFrame
import java.time.ZoneId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Golden byte arrays derived from the protocol reference (section 3) for every outgoing frame. */
class GfdiMessagesTest {
    private fun hex(s: String): ByteArray = s.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    /** Asserts the frame is `expectedPrefix + crc` with a valid CRC (CRC itself is tested against real files). */
    private fun assertFrame(expectedPrefix: String, frame: ByteArray) {
        val prefix = hex(expectedPrefix)
        assertArrayEquals(prefix, frame.copyOf(frame.size - 2))
        assertEquals(prefix.size + 2, frame.size)
        assertEquals(prefix.size + 2, (frame[0].toInt() and 0xFF) or ((frame[1].toInt() and 0xFF) shl 8))
        val crc = Crc16.compute(prefix)
        assertEquals(crc and 0xFF, frame[frame.size - 2].toInt() and 0xFF)
        assertEquals(crc shr 8, frame[frame.size - 1].toInt() and 0xFF)
    }

    @Test
    fun `generic status frames`() {
        assertFrame("09 00 88 13 BA 13 00", GfdiOut.ack(5050))
        assertFrame("09 00 88 13 8F 13 02", GfdiOut.unsupported(5007))
    }

    @Test
    fun `download request`() {
        // index 0, offset 0, NEW, crcSeed 0, size 0, flags 0
        assertFrame("14 00 8A 13 00 00 00 00 00 00 01 00 00 00 00 00 00 00", GfdiOut.downloadRequest(0))
        assertFrame("14 00 8A 13 39 30 00 00 00 00 01 00 00 00 00 00 00 00", GfdiOut.downloadRequest(0x3039))
    }

    @Test
    fun `file transfer data status`() {
        // RESPONSE(5004): ACK, OK, nextOffset 0x12345
        assertFrame("0E 00 88 13 8C 13 00 00 45 23 01 00", GfdiOut.fileTransferDataStatus(TransferStatus.OK, 0x12345))
        assertFrame("0E 00 88 13 8C 13 00 03 00 01 00 00", GfdiOut.fileTransferDataStatus(TransferStatus.CRC_MISMATCH, 256))
    }

    @Test
    fun `filter and set file flag`() {
        assertFrame("07 00 8F 13 03", GfdiOut.filterOnlyNew())
        assertFrame("09 00 90 13 2A 00 10", GfdiOut.setFileFlag(42, FileFlag.ARCHIVE))
    }

    @Test
    fun `fit definition and data are acked as APPLIED`() {
        assertFrame("0A 00 88 13 93 13 00 00", GfdiOut.fitStatusApplied(GfdiId.FIT_DEFINITION))
        assertFrame("0A 00 88 13 94 13 00 00", GfdiOut.fitStatusApplied(GfdiId.FIT_DATA))
    }

    @Test
    fun `device information reply`() {
        val phone = PhoneInfo("Pixel", "Google", "cheetah")
        // 5024 ACK | proto 150 | product -1 | unit -1 | sw 7791 (0x1E6F) | maxPacket -1 | strings | flags 1
        assertFrame(
            "2B 00 88 13 A0 13 00 96 00 FF FF FF FF FF FF 6F 1E FF FF 05 50 69 78 65 6C 06 47 6F 6F 67 6C 65 07 63 68 65 65 74 61 68 01",
            GfdiOut.deviceInformationReply(150, phone)
        )
        val flags0 = GfdiOut.deviceInformationReply(200, phone)
        assertEquals(0, flags0[flags0.size - 3].toInt())
    }

    @Test
    fun `configuration bitmap mirrors Gadgetbridge`() {
        val ours = Capabilities.OURS
        assertEquals(15, ours.size)
        for (i in 0 until 13) assertEquals(0xFF, ours[i].toInt() and 0xFF)
        assertEquals(0x00, ours[13].toInt() and 0xFF)
        assertEquals(0x03, ours[14].toInt() and 0xFF)
        assertTrue(Capabilities.has(ours, Capabilities.SYNC))
        assertTrue(Capabilities.has(ours, Capabilities.CURRENT_TIME_REQUEST_SUPPORT))
        assertFrame("16 00 BA 13 0F FF FF FF FF FF FF FF FF FF FF FF FF FF 00 03", GfdiOut.configuration())
    }

    @Test
    fun `device settings and system events`() {
        // count 3: (6, len 1, true) (7, len 1, false) (8, len 1, false)
        assertFrame("10 00 A2 13 03 06 01 01 07 01 00 08 01 00", GfdiOut.deviceSettings())
        assertFrame("08 00 A6 13 10 00", GfdiOut.systemEvent(SystemEvent.TIME_UPDATED))
        assertFrame("08 00 A6 13 08 00", GfdiOut.systemEvent(SystemEvent.SYNC_READY))
        assertFrame("08 00 A6 13 04 00", GfdiOut.systemEvent(SystemEvent.PAIR_COMPLETE))
        assertFrame("06 00 A7 13", GfdiOut.supportedFileTypesRequest())
    }

    @Test
    fun `notification subscription music and auth replies`() {
        assertFrame("0C 00 88 13 AC 13 00 01 01 00", GfdiOut.notificationSubscriptionReply(1, 0))
        assertFrame("0A 00 88 13 B2 13 00 00", GfdiOut.musicCapabilitiesReply())
        assertFrame("0F 00 88 13 ED 13 00 00 07 01 00 00 00", GfdiOut.authNegotiationReply(7, 1))
    }

    @Test
    fun `protobuf container and status`() {
        val body = hex("42 02 12 00")
        assertFrame("18 00 B3 13 01 00 00 00 00 00 04 00 00 00 04 00 00 00 42 02 12 00", GfdiOut.protobufRequest(1, body))
        assertFrame("18 00 B4 13 34 12 00 00 00 00 04 00 00 00 04 00 00 00 42 02 12 00", GfdiOut.protobufResponse(0x1234, body))
        assertFrame(
            "11 00 88 13 B3 13 00 34 12 00 00 00 00 01 64",
            GfdiOut.protobufStatus(GfdiId.PROTOBUF_REQUEST, 0x1234, 0, ProtobufChunkStatus.DISCARDED, ProtobufCode.UNKNOWN_REQUEST_ID)
        )
    }

    @Test
    fun `current time reply`() {
        val t = GarminTime.CurrentTime(garminTime = 0x01020304, tzOffsetSeconds = -3600, nextTransitionEnd = 0, nextTransitionStart = 5)
        assertFrame("1D 00 88 13 BC 13 00 07 00 00 00 04 03 02 01 F0 F1 FF FF 00 00 00 00 05 00 00 00", GfdiOut.currentTimeReply(7, t))
    }

    @Test
    fun `parses device information configuration and pushes`() {
        val payload = LeWriter().u16(150).u16(4567).u32(3999999999L).u16(1824).u16(375)
            .string("Forerunner 570").string("Forerunner 570").string("fr570").u8(0).toByteArray()
        val m = GfdiParser.parse(GfdiId.DEVICE_INFORMATION, payload) as GfdiMessage.DeviceInformation
        assertEquals(150, m.protocolVersion)
        assertEquals(3999999999L, m.unitNumber)
        assertEquals("18.24", m.softwareVersionString)
        assertEquals(375, m.maxPacketSize)
        assertEquals("fr570", m.deviceModel)

        val c = GfdiParser.parse(GfdiId.CONFIGURATION, hex("03 01 02 04")) as GfdiMessage.Configuration
        assertArrayEquals(hex("01 02 04"), c.bitmap)

        val s = GfdiParser.parse(GfdiId.SYNCHRONIZATION, hex("01 04 20 00 00 00")) as GfdiMessage.Synchronization
        assertEquals(1, s.type)
        assertEquals(0x20L, s.bitmask)
        val s8 = GfdiParser.parse(GfdiId.SYNCHRONIZATION, hex("00 08 00 00 00 00 01 00 00 00")) as GfdiMessage.Synchronization
        assertEquals(1L shl 32, s8.bitmask)

        val fa = GfdiParser.parse(GfdiId.FILE_AVAILABLE, hex("2A 00 80 20 05 00 00 00 10 27 00 00 10 00 00 40 00")) as GfdiMessage.FileAvailable
        assertEquals(42, fa.entry.index)
        assertEquals(32, fa.entry.subType)
        assertEquals(10000L, fa.entry.size)
        assertEquals(0x40000010L, fa.entry.garminTimestamp)
        assertEquals(0x40000010L + 631065600L, fa.entry.unixTimestamp)

        val a = GfdiParser.parse(GfdiId.AUTH_NEGOTIATION, hex("01 03 00 00 00")) as GfdiMessage.AuthNegotiation
        assertEquals(1, a.unknown)
        assertEquals(3L, a.flags)

        val t = GfdiParser.parse(GfdiId.CURRENT_TIME_REQUEST, hex("78 56 34 12")) as GfdiMessage.CurrentTimeRequest
        assertEquals(0x12345678L, t.referenceId)
    }

    @Test
    fun `parses status frames by original id`() {
        val d = GfdiParser.parse(GfdiId.RESPONSE, hex("8A 13 00 00 10 27 00 00")) as GfdiMessage.DownloadRequestStatus
        assertTrue(d.ok)
        assertEquals(10000L, d.maxFileSize)
        val refused = GfdiParser.parse(GfdiId.RESPONSE, hex("8A 13 00 01 00 00 00 00")) as GfdiMessage.DownloadRequestStatus
        assertEquals(false, refused.ok)

        val f = GfdiParser.parse(GfdiId.RESPONSE, hex("8C 13 00 00 00 01 00 00")) as GfdiMessage.FileTransferDataStatus
        assertEquals(256L, f.nextOffset)

        val sf = GfdiParser.parse(GfdiId.RESPONSE, hex("90 13 00 00 2A 00 10")) as GfdiMessage.SetFileFlagStatus
        assertEquals(42, sf.index)
        assertEquals(0x10, sf.flags)

        val types = GfdiParser.parse(GfdiId.RESPONSE, hex("A7 13 00 02 80 04 03 46 49 54 80 20 01 4D")) as GfdiMessage.SupportedFileTypesStatus
        assertEquals(listOf(FileTypeInfo(128, 4, "FIT"), FileTypeInfo(128, 32, "M")), types.types)

        val p = GfdiParser.parse(GfdiId.RESPONSE, hex("B3 13 00 05 00 00 00 00 00 00 00")) as GfdiMessage.ProtobufStatus
        assertEquals(5, p.requestId)
        assertEquals(ProtobufChunkStatus.KEPT, p.chunkStatus)

        val g = GfdiParser.parse(GfdiId.RESPONSE, hex("8F 13 00 00")) as GfdiMessage.GenericStatus
        assertEquals(GfdiId.FILTER, g.originalId)
        assertEquals(GfdiStatus.ACK, g.status)
    }

    @Test
    fun `file transfer data and protobuf containers`() {
        val ft = GfdiParser.parse(GfdiId.FILE_TRANSFER_DATA, hex("00 CD AB 00 02 00 00 DE AD BE EF")) as GfdiMessage.FileTransferData
        assertEquals(0xABCD, ft.crc)
        assertEquals(512L, ft.offset)
        assertArrayEquals(hex("DE AD BE EF"), ft.data)

        val pb = GfdiParser.parse(GfdiId.PROTOBUF_REQUEST, hex("01 03 00 00 00 00 02 00 00 00 02 00 00 00 72 00")) as GfdiMessage.Protobuf
        assertEquals(0x0301, pb.requestId)
        assertTrue(pb.isRequest)
        assertTrue(pb.isComplete)
        assertArrayEquals(hex("72 00"), pb.bytes)
    }

    @Test
    fun `truncated or unknown payloads become Unknown`() {
        assertTrue(GfdiParser.parse(GfdiId.DEVICE_INFORMATION, hex("96 00")) is GfdiMessage.Unknown)
        assertTrue(GfdiParser.parse(5039, hex("05")) is GfdiMessage.Unknown)
        // A truncated status still reports the original id and status.
        val g = GfdiParser.parse(GfdiId.RESPONSE, hex("8A 13 00 00")) as GfdiMessage.GenericStatus
        assertEquals(GfdiId.DOWNLOAD_REQUEST, g.originalId)
    }

    @Test
    fun `directory entries parse and skip empty slots`() {
        val e1 = hex("0A 00 80 20 01 00 00 00 E8 03 00 00 00 00 00 00")
        val e2 = hex("0B 00 80 31 02 00 00 00 10 00 00 00 80 00 00 40")
        val zero = ByteArray(16)
        val entries = DirectoryEntry.parseDirectory(e1 + zero + e2 + hex("01 02"))
        assertEquals(2, entries.size)
        assertEquals(10, entries[0].index)
        assertNull(entries[0].unixTimestamp)
        assertEquals(49, entries[1].subType)
        assertEquals(0x40000080L + GarminTime.EPOCH_OFFSET, entries[1].unixTimestamp)
    }

    @Test
    fun `garmin time and current time reply values`() {
        assertEquals(631065600L, GarminTime.toUnix(0))
        assertEquals(0L, GarminTime.fromUnix(631065600L))
        // 2026-09-16T12:00:00Z in Kolkata (+05:30, no DST) has no transitions.
        val now = 1789560000000L
        val t = GarminTime.currentTime(now, ZoneId.of("Asia/Kolkata"))
        assertEquals(1789560000L - 631065600L, t.garminTime)
        assertEquals(19800, t.tzOffsetSeconds)
        assertEquals(0L, t.nextTransitionStart)
        assertEquals(0L, t.nextTransitionEnd)
        // Berlin in summer: offset includes DST, next transition is the October fall-back.
        val b = GarminTime.currentTime(now, ZoneId.of("Europe/Berlin"))
        assertEquals(7200, b.tzOffsetSeconds)
        assertTrue(b.nextTransitionStart > t.garminTime)
        assertTrue(b.nextTransitionEnd > b.nextTransitionStart)
        // The reply for a 5052 round-trips through the frame codec.
        val frame = GfdiOut.currentTimeReply(99, b)
        val decoded = GfdiFrame.decode(frame)!!
        assertEquals(GfdiId.RESPONSE, decoded.messageId)
        val r = LeReader(decoded.payload)
        assertEquals(GfdiId.CURRENT_TIME_REQUEST, r.u16())
        assertEquals(0, r.u8())
        assertEquals(99L, r.u32())
        assertEquals(b.garminTime, r.u32())
        assertEquals(7200, r.i32())
    }

    @Test
    fun `create file frame`() {
        // 5005: size 1000, 128/5, index 0, reserved 0, subTypeMask 0, numberMask 0xFFFF, pathLength 0, id 0x1122334455667788
        assertFrame(
            "1C 00 8D 13 E8 03 00 00 80 05 00 00 00 00 FF FF 00 00 88 77 66 55 44 33 22 11",
            GfdiOut.createFile(1000, fileId = 0x1122334455667788L)
        )
        // Default type is a workout FIT file and the id is random but the layout is fixed.
        val random = GfdiOut.createFile(64)
        assertEquals(28, random.size)
        assertEquals(0x80, random[8].toInt() and 0xFF)
        assertEquals(5, random[9].toInt())
    }

    @Test
    fun `upload request frame`() {
        // 5003: index 77, size 1000, offset 0, crcSeed 0
        assertFrame("12 00 8B 13 4D 00 E8 03 00 00 00 00 00 00 00 00", GfdiOut.uploadRequest(77, 1000))
        assertFrame("12 00 8B 13 4D 00 E8 03 00 00 6A 01 00 00 EF BE", GfdiOut.uploadRequest(77, 1000, offset = 362, crcSeed = 0xBEEF))
    }

    @Test
    fun `outgoing file transfer data frame`() {
        // 5004: flags 0, crc 0xBEEF, offset 362, three bytes
        assertFrame("10 00 8C 13 00 EF BE 6A 01 00 00 01 02 03", GfdiOut.fileTransferData(362, 0xBEEF, byteArrayOf(1, 2, 3)))
        assertEquals(13, GfdiOut.FILE_TRANSFER_DATA_OVERHEAD)
    }

    @Test
    fun `set file flag delete`() {
        assertFrame("09 00 90 13 4D 00 20", GfdiOut.setFileFlag(77, FileFlag.DELETE))
    }

    @Test
    fun `create file status parses`() {
        val ok = GfdiParser.parse(GfdiId.RESPONSE, hex("8D 13 00 00 4D 00 80 05 05 00")) as GfdiMessage.CreateFileStatus
        assertEquals(GfdiMessage.CreateFileStatus(0, CreateStatus.OK, 77, 128, 5, 5), ok)
        assertTrue(ok.ok)
        val dup = GfdiParser.parse(GfdiId.RESPONSE, hex("8D 13 00 01 00 00 80 05 00 00")) as GfdiMessage.CreateFileStatus
        assertEquals(CreateStatus.DUPLICATE, dup.createStatus)
        assertTrue(!dup.ok)
        val full = GfdiParser.parse(GfdiId.RESPONSE, hex("8D 13 00 04 00 00 80 05 00 00")) as GfdiMessage.CreateFileStatus
        assertEquals(CreateStatus.NO_SLOTS, full.createStatus)
        // A bare NAK without the create fields falls back to the generic status.
        val nak = GfdiParser.parse(GfdiId.RESPONSE, hex("8D 13 01")) as GfdiMessage.GenericStatus
        assertEquals(GfdiId.CREATE_FILE, nak.originalId)
        assertEquals(GfdiStatus.NAK, nak.status)
    }

    @Test
    fun `upload request status parses`() {
        val ok = GfdiParser.parse(GfdiId.RESPONSE, hex("8B 13 00 00 00 00 00 00 E8 03 00 00 00 00")) as GfdiMessage.UploadRequestStatus
        assertEquals(GfdiMessage.UploadRequestStatus(0, UploadStatus.OK, 0, 1000, 0), ok)
        assertTrue(ok.ok)
        val busy = GfdiParser.parse(GfdiId.RESPONSE, hex("8B 13 00 05 6A 01 00 00 00 00 00 00 EF BE")) as GfdiMessage.UploadRequestStatus
        assertEquals(UploadStatus.NOT_READY, busy.uploadStatus)
        assertEquals(362L, busy.offset)
        assertEquals(0xBEEF, busy.crcSeed)
        assertTrue(!busy.ok)
    }

    @Test
    fun `transfer data status for our chunks parses`() {
        val ok = GfdiParser.parse(GfdiId.RESPONSE, hex("8C 13 00 00 6A 01 00 00")) as GfdiMessage.FileTransferDataStatus
        assertEquals(GfdiMessage.FileTransferDataStatus(0, TransferStatus.OK, 362), ok)
        val resend = GfdiParser.parse(GfdiId.RESPONSE, hex("8C 13 00 01 00 00 00 00")) as GfdiMessage.FileTransferDataStatus
        assertEquals(TransferStatus.RESEND, resend.transferStatus)
        val crc = GfdiParser.parse(GfdiId.RESPONSE, hex("8C 13 00 03 6A 01 00 00")) as GfdiMessage.FileTransferDataStatus
        assertEquals(TransferStatus.CRC_MISMATCH, crc.transferStatus)
        assertEquals(362L, crc.nextOffset)
    }

    @Test
    fun `workout capability bit and status names`() {
        assertEquals(18, Capabilities.WORKOUT_DOWNLOAD)
        assertTrue(Capabilities.has(byteArrayOf(0, 0, 0x04), Capabilities.WORKOUT_DOWNLOAD))
        assertTrue(!Capabilities.has(byteArrayOf(0x38, 0), Capabilities.WORKOUT_DOWNLOAD))
        assertEquals("NO_SPACE_FOR_TYPE", CreateStatus.name(5))
        assertEquals("CRC_INCORRECT", UploadStatus.name(6))
        assertEquals("SYNC_PAUSED", TransferStatus.name(5))
    }
}
