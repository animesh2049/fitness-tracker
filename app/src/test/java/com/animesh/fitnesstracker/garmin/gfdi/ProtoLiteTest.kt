package com.animesh.fitnesstracker.garmin.gfdi

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtoLiteTest {
    private fun hex(s: String): ByteArray = s.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `varints and length delimited fields encode per the protobuf wire format`() {
        assertArrayEquals(hex("08 01"), ProtoWriter().varint(1, 1).toByteArray())
        assertArrayEquals(hex("10 96 01"), ProtoWriter().varint(2, 150).toByteArray())
        assertArrayEquals(hex("1A 03 61 62 63"), ProtoWriter().bytes(3, "abc".toByteArray()).toByteArray())
        assertArrayEquals(hex("42 02 12 00"), GarminProto.batteryRequest())
        assertArrayEquals(hex("6A 02 72 00"), GarminProto.connectedNotification())
    }

    @Test
    fun `reader walks fields and skips fixed width ones`() {
        val fields = ProtoReader(hex("08 96 01 12 02 AA BB 1D 01 02 03 04 21 01 02 03 04 05 06 07 08 28 07")).fields()
        assertEquals(listOf(1, 2, 3, 4, 5), fields.map { it.number })
        assertEquals(150L, fields[0].varint)
        assertArrayEquals(hex("AA BB"), fields[1].bytes)
        assertEquals(7L, fields[4].varint)
    }

    @Test
    fun `battery response is recognised and the level extracted`() {
        // Smart { device_status_service { remote_device_battery_status_response { status OK, level 77, battery_status 1 } } }
        val smart = ProtoWriter().message(8, ProtoWriter().message(3, ProtoWriter().varint(1, 1).varint(2, 77).varint(3, 1))).toByteArray()
        assertArrayEquals(hex("42 08 1A 06 08 01 10 4D 18 01"), smart)
        val incoming = GarminProto.classify(smart) as GarminProto.Incoming.BatteryResponse
        assertEquals(77, incoming.level)
    }

    @Test
    fun `core service pings and GPS requests are classified`() {
        assertEquals(GarminProto.Incoming.ConnectedNotification, GarminProto.classify(hex("6A 02 72 00")))
        val gps = ProtoWriter().message(13, ProtoWriter().message(3, ProtoWriter().varint(1, 0))).toByteArray()
        assertEquals(GarminProto.Incoming.GetLocationRequest, GarminProto.classify(gps))
        val updates = ProtoWriter().message(13, ProtoWriter().message(5, ProtoWriter().varint(1, 1))).toByteArray()
        assertEquals(GarminProto.Incoming.LocationUpdatesRequest, GarminProto.classify(updates))
        // Declines decode back to the expected status values.
        val declined = ProtoReader(ProtoReader(GarminProto.locationDeclined()).first(13)!!.bytes!!).first(4)!!.bytes!!
        assertEquals(4L, ProtoReader(declined).first(1)!!.varint)
    }

    @Test
    fun `anything else is Other with the top level field`() {
        val http = ProtoWriter().message(2, ProtoWriter().varint(1, 1)).toByteArray()
        assertEquals(GarminProto.Incoming.Other(2), GarminProto.classify(http))
        val sync = ProtoWriter().message(13, ProtoWriter().bytes(1, ByteArray(0))).toByteArray()
        assertEquals(GarminProto.Incoming.Other(13), GarminProto.classify(sync))
        assertTrue(GarminProto.classify(hex("FF FF FF")) is GarminProto.Incoming.Other)
        assertTrue(GarminProto.classify(ByteArray(0)) is GarminProto.Incoming.Other)
    }
}
