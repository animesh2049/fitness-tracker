package com.animesh.fitnesstracker.garmin.fit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sanity checks on the hand-maintained profile table, the CRC and the diagnostics dump. */
class ProfileAndDumpTest {
    private val contractMessages = listOf(
        0, 1, 3, 12, 18, 19, 20, 21, 26, 34, 55, 103, 104, 140, 211, 216, 227, 229, 269, 275, 284, 297, 339, 346, 356,
        369, 370, 371, 378, 382, 402, 403, 412
    )

    @Test
    fun everyContractMessageIsInTheProfile() {
        for (num in contractMessages) assertNotNull("message $num", Profile.message(num))
        assertNotNull(Profile.message(Mesg.FIELD_DESCRIPTION))
        assertNotNull(Profile.message(Mesg.DEVELOPER_DATA_ID))
    }

    @Test
    fun fieldNamesAreUniqueWithinAMessage() {
        for (m in Profile.messages.values) {
            val names = m.fields.values.map { it.name }
            assertEquals("duplicate names in ${m.name}: $names", names.size, names.toSet().size)
        }
    }

    @Test
    fun timestampFieldsAreResolvedForEveryMessage() {
        assertEquals(FieldKind.TIMESTAMP, Profile.field(9999, 253)!!.kind)
        assertEquals("message_index", Profile.field(9999, 254)!!.name)
        assertNull(Profile.field(9999, 0))
        assertEquals(FieldKind.TIMESTAMP, Profile.field(Mesg.STRESS_LEVEL, 1)!!.kind)
        assertEquals(128.0, Profile.field(Mesg.HRV_VALUE, 0)!!.scale, 0.0)
        assertEquals(500.0, Profile.field(Mesg.RECORD, 78)!!.offset, 0.0)
        assertTrue(Profile.isUnknownField(Mesg.MONITORING, 200))
        assertTrue(!Profile.isUnknownField(Mesg.MONITORING, 253))
        assertTrue(!Profile.isUnknownField(9999, 200))
    }

    @Test
    fun baseTypesResolveByLowFiveBits() {
        assertEquals(FitBaseType.UINT16, FitBaseType.fromId(0x84))
        assertEquals(FitBaseType.UINT16, FitBaseType.fromId(0x04))
        assertEquals(FitBaseType.ENUM, FitBaseType.fromId(0x00))
        assertNull(FitBaseType.fromId(0x1F))
        assertTrue(FitBaseType.SINT16.isInvalid(0x7FFF))
        assertTrue(!FitBaseType.SINT16.isInvalid(-1))
        assertTrue(FitBaseType.UINT32Z.isInvalid(0))
        assertTrue(FitBaseType.FLOAT32.isInvalid(0xFFFFFFFFL))
    }

    @Test
    fun crcMatchesKnownVector() {
        // CRC-16/ARC of "123456789" is 0xBB3D.
        assertEquals(0xBB3D, FitCrc.compute("123456789".toByteArray(Charsets.US_ASCII)))
        assertEquals(0, FitCrc.compute(ByteArray(0)))
    }

    @Test
    fun dumpListsHistogramAndCoverage() {
        val summary = FitDump.summary(FitFixtures.decode("MONITOR_M9GL2445.fit"))
        assertTrue(summary.contains("file_id: type=32 (MONITORING_B)"))
        assertTrue(summary.contains("55 monitoring"))
        assertTrue(summary.contains("27:heart_rate="))
        assertTrue(summary.contains("timestamps: first=1789618980"))
        assertTrue(summary.contains("unknown messages: 13 records"))
        assertTrue(summary.lines().any { it.trim().startsWith("484 ?") })
    }
}
