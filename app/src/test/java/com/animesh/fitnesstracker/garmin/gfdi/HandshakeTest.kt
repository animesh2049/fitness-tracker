package com.animesh.fitnesstracker.garmin.gfdi

import com.animesh.fitnesstracker.garmin.ble.GfdiFrame
import java.time.ZoneId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandshakeTest {
    private val sent = ArrayList<GfdiFrame.Decoded>()
    private var now = 1_789_542_309_000L // 2026-09-16T07:05:09Z
    private val phone = PhoneInfo("Pixel 7 Pro", "Google", "cheetah")

    private fun handshake(firstConnect: Boolean = false) =
        Handshake({ sent.add(GfdiFrame.decode(it)!!) }, phone, firstConnect, { now }, ZoneId.of("Asia/Kolkata"))

    private fun ids() = sent.map { it.messageId }
    private fun statusOf(d: GfdiFrame.Decoded) = LeReader(d.payload).let { r -> r.u16() to r.u8() }
    private fun payload(d: GfdiFrame.Decoded) = d.payload

    private val deviceInfo = GfdiParser.parse(
        GfdiId.DEVICE_INFORMATION,
        LeWriter().u16(150).u16(4567).u32(12345).u16(1824).u16(375).string("FR570").string("Forerunner 570").string("fr570").toByteArray()
    )
    private val configuration = GfdiMessage.Configuration(Capabilities.bitmap(listOf(3, 4, 5, 71, 76)))

    @Test
    fun `device information is answered with ours and remembered`() {
        val hs = handshake()
        hs.onMessage(deviceInfo)
        assertEquals(listOf(GfdiId.RESPONSE), ids())
        assertEquals(GfdiId.DEVICE_INFORMATION to GfdiStatus.ACK, statusOf(sent[0]))
        assertArrayEquals(GfdiOut.deviceInformationReply(150, phone), GfdiFrame.encode(GfdiId.RESPONSE, sent[0].payload))
        assertEquals("18.24", hs.deviceInfo!!.softwareVersionString)
        assertFalse(hs.isReady())
    }

    @Test
    fun `configuration triggers ACK, our bitmap and the initialisation burst in order`() {
        val hs = handshake(firstConnect = false)
        hs.onMessage(configuration)
        assertEquals(
            listOf(GfdiId.RESPONSE, GfdiId.CONFIGURATION, GfdiId.SUPPORTED_FILE_TYPES_REQUEST, GfdiId.DEVICE_SETTINGS,
                GfdiId.SYSTEM_EVENT, GfdiId.SYSTEM_EVENT, GfdiId.PROTOBUF_REQUEST),
            ids()
        )
        assertEquals(GfdiId.CONFIGURATION to GfdiStatus.ACK, statusOf(sent[0]))
        assertArrayEquals(byteArrayOf(15) + Capabilities.OURS, payload(sent[1]))
        assertEquals(SystemEvent.TIME_UPDATED, payload(sent[4])[0].toInt())
        assertEquals(SystemEvent.SYNC_READY, payload(sent[5])[0].toInt())
        val pb = GfdiParser.parse(GfdiId.PROTOBUF_REQUEST, payload(sent[6])) as GfdiMessage.Protobuf
        assertArrayEquals(GarminProto.batteryRequest(), pb.bytes)
        assertEquals(1, pb.requestId)
        assertTrue(Capabilities.has(hs.watchCapabilities!!, Capabilities.SYNC))
        // A repeated CONFIGURATION is acknowledged but does not repeat the burst.
        sent.clear()
        hs.onMessage(configuration)
        assertEquals(listOf(GfdiId.RESPONSE, GfdiId.CONFIGURATION), ids())
    }

    @Test
    fun `first connect adds pairing system events`() {
        handshake(firstConnect = true).onMessage(configuration)
        val events = sent.filter { it.messageId == GfdiId.SYSTEM_EVENT }.map { it.payload[0].toInt() }
        assertEquals(listOf(SystemEvent.TIME_UPDATED, SystemEvent.SYNC_READY, SystemEvent.PAIR_COMPLETE, SystemEvent.SYNC_COMPLETE, SystemEvent.SETUP_WIZARD_COMPLETE), events)
    }

    @Test
    fun `ready on supported file types reply or after the 2 s grace`() {
        val hs = handshake()
        hs.onMessage(configuration)
        assertFalse(hs.isReady(now))
        assertEquals(2_000L, hs.graceRemainingMs(now))
        now += 1_999
        assertFalse(hs.isReady(now))
        now += 1
        assertTrue(hs.isReady(now))

        val hs2 = handshake()
        assertNull(hs2.graceRemainingMs(now))
        hs2.onMessage(configuration)
        hs2.onMessage(GfdiMessage.SupportedFileTypesStatus(0, listOf(FileTypeInfo(128, 32, "MONITOR"))))
        assertTrue(hs2.isReady(now))
        assertEquals(1, hs2.supportedTypes!!.size)
    }

    @Test
    fun `always-on replies`() {
        val hs = handshake()
        hs.onMessage(GfdiMessage.AuthNegotiation(1, 0x11))
        hs.onMessage(GfdiMessage.CurrentTimeRequest(0x99))
        hs.onMessage(GfdiMessage.NotificationSubscription(1, 0))
        hs.onMessage(GfdiMessage.MusicControlCapabilities(0))
        hs.onMessage(GfdiMessage.FitDefinition(byteArrayOf(0x40, 0)))
        hs.onMessage(GfdiMessage.FitData(byteArrayOf(0)))
        hs.onMessage(GfdiMessage.Unknown(5039, byteArrayOf(5)))
        assertEquals(List(7) { GfdiId.RESPONSE }, ids())
        assertArrayEquals(GfdiOut.authNegotiationReply(1, 0x11), GfdiFrame.encode(GfdiId.RESPONSE, sent[0].payload))
        val time = LeReader(sent[1].payload)
        assertEquals(GfdiId.CURRENT_TIME_REQUEST, time.u16()); assertEquals(0, time.u8()); assertEquals(0x99L, time.u32())
        assertEquals(GarminTime.fromUnix(1_789_542_309L), time.u32())
        assertEquals(19800, time.i32())
        assertArrayEquals(GfdiOut.notificationSubscriptionReply(1, 0), GfdiFrame.encode(GfdiId.RESPONSE, sent[2].payload))
        assertArrayEquals(GfdiOut.musicCapabilitiesReply(), GfdiFrame.encode(GfdiId.RESPONSE, sent[3].payload))
        assertArrayEquals(GfdiOut.fitStatusApplied(GfdiId.FIT_DEFINITION), GfdiFrame.encode(GfdiId.RESPONSE, sent[4].payload))
        assertArrayEquals(GfdiOut.fitStatusApplied(GfdiId.FIT_DATA), GfdiFrame.encode(GfdiId.RESPONSE, sent[5].payload))
        assertEquals(5039 to GfdiStatus.UNSUPPORTED, statusOf(sent[6]))
    }

    @Test
    fun `protobuf pings are echoed, battery stored, GPS declined, others discarded`() {
        val hs = handshake()
        val ping = GarminProto.connectedNotification()
        hs.onMessage(GfdiMessage.Protobuf(GfdiId.PROTOBUF_REQUEST, 0x301, 0, ping.size.toLong(), ping.size.toLong(), ping))
        assertEquals(listOf(GfdiId.RESPONSE, GfdiId.PROTOBUF_RESPONSE), ids())
        val st = GfdiParser.parse(GfdiId.RESPONSE, sent[0].payload) as GfdiMessage.ProtobufStatus
        assertEquals(0x301, st.requestId); assertEquals(ProtobufChunkStatus.KEPT, st.chunkStatus); assertEquals(ProtobufCode.NO_ERROR, st.code)
        val echo = GfdiParser.parse(GfdiId.PROTOBUF_RESPONSE, sent[1].payload) as GfdiMessage.Protobuf
        assertEquals(0x301, echo.requestId)
        assertArrayEquals(ping, echo.bytes)

        sent.clear()
        val battery = ProtoWriter().message(8, ProtoWriter().message(3, ProtoWriter().varint(1, 1).varint(2, 64))).toByteArray()
        hs.onMessage(GfdiMessage.Protobuf(GfdiId.PROTOBUF_RESPONSE, 1, 0, battery.size.toLong(), battery.size.toLong(), battery))
        assertEquals(64, hs.batteryPercent)
        assertEquals(listOf(GfdiId.RESPONSE), ids())
        assertEquals(GfdiId.PROTOBUF_RESPONSE, (GfdiParser.parse(GfdiId.RESPONSE, sent[0].payload) as GfdiMessage.ProtobufStatus).originalId)

        sent.clear()
        val gps = ProtoWriter().message(13, ProtoWriter().message(3, ProtoWriter().varint(1, 0))).toByteArray()
        hs.onMessage(GfdiMessage.Protobuf(GfdiId.PROTOBUF_REQUEST, 7, 0, gps.size.toLong(), gps.size.toLong(), gps))
        assertEquals(listOf(GfdiId.RESPONSE, GfdiId.PROTOBUF_RESPONSE), ids())
        assertArrayEquals(GarminProto.locationDeclined(), (GfdiParser.parse(GfdiId.PROTOBUF_RESPONSE, sent[1].payload) as GfdiMessage.Protobuf).bytes)

        sent.clear()
        val http = ProtoWriter().message(2, ProtoWriter().varint(1, 1)).toByteArray()
        hs.onMessage(GfdiMessage.Protobuf(GfdiId.PROTOBUF_REQUEST, 8, 0, http.size.toLong(), http.size.toLong(), http))
        assertEquals(listOf(GfdiId.RESPONSE), ids())
        val discarded = GfdiParser.parse(GfdiId.RESPONSE, sent[0].payload) as GfdiMessage.ProtobufStatus
        assertEquals(ProtobufChunkStatus.DISCARDED, discarded.chunkStatus)
        assertEquals(ProtobufCode.UNKNOWN_REQUEST_ID, discarded.code)
    }

    @Test
    fun `chunked protobuf is reassembled with KEPT statuses for partial chunks`() {
        val hs = handshake()
        val ping = GarminProto.connectedNotification()
        hs.onMessage(GfdiMessage.Protobuf(GfdiId.PROTOBUF_REQUEST, 9, 0, 4, 2, ping.copyOfRange(0, 2)))
        assertEquals(listOf(GfdiId.RESPONSE), ids())
        assertEquals(ProtobufChunkStatus.KEPT, (GfdiParser.parse(GfdiId.RESPONSE, sent[0].payload) as GfdiMessage.ProtobufStatus).chunkStatus)
        hs.onMessage(GfdiMessage.Protobuf(GfdiId.PROTOBUF_REQUEST, 9, 2, 4, 2, ping.copyOfRange(2, 4)))
        assertEquals(listOf(GfdiId.RESPONSE, GfdiId.RESPONSE, GfdiId.PROTOBUF_RESPONSE), ids())
    }

    @Test
    fun `pushes are acked and forwarded and unrequested chunks aborted`() {
        val hs = handshake()
        val pushes = ArrayList<GfdiMessage>()
        hs.onPush = { pushes.add(it) }
        hs.onMessage(GfdiMessage.Synchronization(1, 0x20))
        val entry = DirectoryEntry(5, 128, 32, 1, 0, 0, 100, 0)
        hs.onMessage(GfdiMessage.FileAvailable(entry))
        assertEquals(2, pushes.size)
        assertEquals(GfdiId.SYNCHRONIZATION to GfdiStatus.ACK, statusOf(sent[0]))
        assertEquals(GfdiId.FILE_AVAILABLE to GfdiStatus.ACK, statusOf(sent[1]))
        hs.onMessage(GfdiMessage.FileTransferData(0, 0, 0, byteArrayOf(1)))
        val st = GfdiParser.parse(GfdiId.RESPONSE, sent[2].payload) as GfdiMessage.FileTransferDataStatus
        assertEquals(TransferStatus.ABORT, st.transferStatus)
    }
}
