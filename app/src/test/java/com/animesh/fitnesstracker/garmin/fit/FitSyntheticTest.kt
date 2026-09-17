package com.animesh.fitnesstracker.garmin.fit

import com.animesh.fitnesstracker.garmin.fit.FitFileBuilder.Companion.BYTE
import com.animesh.fitnesstracker.garmin.fit.FitFileBuilder.Companion.SINT16
import com.animesh.fitnesstracker.garmin.fit.FitFileBuilder.Companion.SINT32
import com.animesh.fitnesstracker.garmin.fit.FitFileBuilder.Companion.SINT8
import com.animesh.fitnesstracker.garmin.fit.FitFileBuilder.Companion.STRING
import com.animesh.fitnesstracker.garmin.fit.FitFileBuilder.Companion.UINT16
import com.animesh.fitnesstracker.garmin.fit.FitFileBuilder.Companion.UINT32
import com.animesh.fitnesstracker.garmin.fit.FitFileBuilder.Companion.UINT8
import com.animesh.fitnesstracker.garmin.fit.FitFileBuilder.Companion.UINT8Z
import com.animesh.fitnesstracker.garmin.fit.FitFileBuilder.Companion.ascii
import com.animesh.fitnesstracker.garmin.fit.FitFileBuilder.Companion.be16
import com.animesh.fitnesstracker.garmin.fit.FitFileBuilder.Companion.be32
import com.animesh.fitnesstracker.garmin.fit.FitFileBuilder.Companion.le16
import com.animesh.fitnesstracker.garmin.fit.FitFileBuilder.Companion.le32
import com.animesh.fitnesstracker.garmin.fit.FitFileBuilder.Companion.u8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Hand-built FIT files exercising the protocol layer: headers, both byte orders, compressed timestamps, sentinels, arrays, developer fields and error paths. */
class FitSyntheticTest {
    private val garminTs = 1_000_000_000L
    private val unixTs = garminTs + GARMIN_EPOCH_UNIX_SECONDS

    private fun fileIdDefinition(b: FitFileBuilder, localType: Int = 15) =
        b.definition(localType, Mesg.FILE_ID, listOf(Slot(0, 1, FitFileBuilder.ENUM), Slot(4, 4, UINT32)))
            .data(localType, u8(32), le32(garminTs))

    private fun recordDefinition(b: FitFileBuilder, localType: Int = 0) =
        b.definition(localType, Mesg.RECORD, listOf(Slot(253, 4, UINT32), Slot(3, 1, UINT8), Slot(5, 4, UINT32), Slot(0, 4, SINT32), Slot(6, 2, UINT16)))

    private fun decodeError(bytes: ByteArray): FitDecodeException {
        try {
            FitDecoder.decode(bytes)
        } catch (e: FitDecodeException) {
            return e
        }
        fail("expected FitDecodeException")
        throw IllegalStateException()
    }

    @Test
    fun littleEndianRecordDecodesScaledValuesAndTimestamp() {
        val b = fileIdDefinition(FitFileBuilder())
        recordDefinition(b).data(0, le32(garminTs), u8(150), le32(123456), le32(0x20000000L), le16(2500))
        val d = FitDecoder.decode(b.build())
        assertEquals(32, d.fileId.typeNum)
        assertEquals(FitFileType.MONITORING_B, d.fileId.type)
        assertEquals(unixTs, d.fileId.timeCreated)
        val r = d.records.single()
        assertEquals(unixTs, r.timestamp)
        assertEquals(150, r.heartRate)
        assertEquals(1234.56, r.distance!!, 1e-9)
        assertEquals(45.0, r.latitude!!, 1e-9)
        assertEquals(2.5, r.speed!!, 1e-9)
        assertEquals(0, d.unknownMessageCount)
        assertEquals(0, d.unknownFieldCount)
    }

    @Test
    fun bigEndianDefinitionIsHonoured() {
        val b = fileIdDefinition(FitFileBuilder())
        b.definition(2, Mesg.RECORD, listOf(Slot(253, 4, UINT32), Slot(6, 2, UINT16), Slot(0, 4, SINT32)), bigEndian = true)
            .data(2, be32(garminTs), be16(2500), be32(-0x20000000L))
        val r = FitDecoder.decode(b.build()).records.single()
        assertEquals(unixTs, r.timestamp)
        assertEquals(2.5, r.speed!!, 1e-9)
        assertEquals(-45.0, r.latitude!!, 1e-9)
    }

    @Test
    fun compressedTimestampHeaderAddsOffsetToLastTimestamp() {
        val b = fileIdDefinition(FitFileBuilder())
        recordDefinition(b).data(0, le32(garminTs), u8(100), le32(0), le32(0), le16(0))
        b.definition(1, Mesg.RECORD, listOf(Slot(3, 1, UINT8)))
            .compressed(1, 5, u8(101))
        val d = FitDecoder.decode(b.build())
        assertEquals(listOf(unixTs, unixTs + 5), d.records.map { it.timestamp })
        assertEquals(101, d.records[1].heartRate)
    }

    @Test
    fun compressedTimestampRollsOverWhenOffsetGoesBackwards() {
        val b = fileIdDefinition(FitFileBuilder())
        recordDefinition(b).data(0, le32(garminTs + 5), u8(100), le32(0), le32(0), le16(0))
        b.definition(1, Mesg.RECORD, listOf(Slot(3, 1, UINT8)))
            .compressed(1, 3, u8(102))
            .compressed(1, 3, u8(103))
            .compressed(1, 4, u8(104))
        val stamps = FitDecoder.decode(b.build()).records.map { it.timestamp }
        assertEquals(listOf(unixTs + 5, unixTs + 35, unixTs + 35, unixTs + 36), stamps)
    }

    @Test
    fun monitoringTimestamp16RollsOverAgainstLastFullTimestamp() {
        val base = garminTs - (garminTs and 0xFFFF) + 65530
        val b = fileIdDefinition(FitFileBuilder())
        b.definition(3, Mesg.MONITORING, listOf(Slot(253, 4, UINT32), Slot(3, 4, UINT32)))
            .data(3, le32(base), le32(100))
        b.definition(4, Mesg.MONITORING, listOf(Slot(26, 2, UINT16), Slot(27, 1, UINT8)))
            .data(4, le16(65533), u8(70))
            .data(4, le16(4), u8(71))
            .data(4, le16(4), u8(0))
        val m = FitDecoder.decode(b.build()).monitoring
        assertEquals(4, m.size)
        val unixBase = base + GARMIN_EPOCH_UNIX_SECONDS
        assertEquals(listOf(unixBase, unixBase + 3, unixBase + 10, unixBase + 10), m.map { it.timestamp })
        assertEquals(100L, m[0].cumulativeSteps)
        assertEquals(70, m[1].heartRate)
        assertNull("heart rate 0 means not measured", m[3].heartRate)
    }

    @Test
    fun monitoringWithoutAnyTimestampBaseStaysRawOnly() {
        val b = fileIdDefinition(FitFileBuilder())
        b.definition(4, Mesg.MONITORING, listOf(Slot(26, 2, UINT16), Slot(27, 1, UINT8))).data(4, le16(4), u8(70))
        val d = FitDecoder.decode(b.build())
        assertTrue(d.monitoring.isEmpty())
        assertEquals(1, d.raw.count { it.globalMessageNumber == Mesg.MONITORING })
    }

    @Test
    fun monitoringPackedActivityTypeAndIntensity() {
        val b = fileIdDefinition(FitFileBuilder())
        b.definition(3, Mesg.MONITORING, listOf(Slot(253, 4, UINT32), Slot(24, 1, BYTE), Slot(2, 4, UINT32), Slot(19, 2, UINT16), Slot(37, 2, UINT16)))
            .data(3, le32(garminTs), u8(0x46), le32(599921), le16(210), le16(12))
        val m = FitDecoder.decode(b.build()).monitoring.single()
        assertEquals(6, m.activityType)
        assertEquals(2, m.intensity)
        assertEquals(5999.21, m.cumulativeDistanceM!!, 1e-9)
        assertEquals(210, m.cumulativeActiveKcal)
        assertEquals(12, m.moderateActivityMinutes)
    }

    @Test
    fun invalidSentinelsBecomeNull() {
        val b = fileIdDefinition(FitFileBuilder())
        b.definition(5, Mesg.RECORD, listOf(Slot(253, 4, UINT32), Slot(3, 1, UINT8), Slot(6, 2, UINT16), Slot(9, 2, SINT16), Slot(5, 4, UINT32), Slot(13, 1, SINT8)))
            .data(5, le32(garminTs), u8(0xFF), le16(0xFFFF), le16(0x7FFF), le32(0xFFFFFFFFL), u8(0x7F))
        b.definition(6, Mesg.CAPABILITIES, listOf(Slot(0, 1, UINT8Z), Slot(23, 4, UINT32)))
            .data(6, u8(0), le32(0))
        val d = FitDecoder.decode(b.build())
        val r = d.records.single()
        assertNull(r.heartRate)
        assertNull(r.speed)
        assertNull(r.distance)
        assertNull(r.temperature)
        val raw = d.raw.first { it.globalMessageNumber == Mesg.RECORD }
        assertEquals(setOf(253), raw.fields.keys)
        val cap = d.raw.first { it.globalMessageNumber == Mesg.CAPABILITIES }
        assertEquals(mapOf(23 to 0L), cap.fields)
        assertEquals(0L, d.capabilities.single().connectivitySupported)
        assertNull(d.capabilities.single().sportsSupported)
    }

    @Test
    fun arraysDecodeElementWise() {
        val b = fileIdDefinition(FitFileBuilder())
        b.definition(6, Mesg.TIME_IN_ZONE, listOf(Slot(0, 2, UINT16), Slot(2, 12, UINT32), Slot(6, 3, UINT8)))
            .data(6, le16(18), le32(1000), le32(2500), le32(0), u8(100), u8(120), u8(140))
        val z = FitDecoder.decode(b.build()).timeInZone.single()
        assertEquals(18, z.referenceMesg)
        assertEquals(listOf(1.0, 2.5, 0.0), z.timeInHrZone)
        assertEquals(listOf(100, 120, 140), z.hrZoneHighBoundary)
    }

    @Test
    fun stringsStopAtTheFirstNul() {
        val b = fileIdDefinition(FitFileBuilder())
        b.definition(7, Mesg.SPORT, listOf(Slot(0, 1, FitFileBuilder.ENUM), Slot(1, 1, FitFileBuilder.ENUM), Slot(3, 8, STRING)))
            .data(7, u8(1), u8(0), ascii("Run", 8))
            .data(7, u8(2), u8(0), ascii("", 8))
        val sports = FitDecoder.decode(b.build()).sports
        assertEquals("Run", sports[0].name)
        assertEquals(1, sports[0].sport)
        assertNull(sports[1].name)
    }

    @Test
    fun unknownMessageIsCountedAndKeptRaw() {
        val b = fileIdDefinition(FitFileBuilder())
        b.definition(7, 9999, listOf(Slot(0, 2, UINT16), Slot(1, 1, UINT8), Slot(253, 4, UINT32)))
            .data(7, le16(42), u8(7), le32(garminTs))
            .data(7, le16(43), u8(0xFF), le32(garminTs + 1))
        val d = FitDecoder.decode(b.build())
        assertEquals(2, d.unknownMessageCount)
        assertEquals(0, d.unknownFieldCount)
        val raw = d.raw.filter { it.globalMessageNumber == 9999 }
        assertEquals(mapOf(0 to 42L, 1 to 7L, 253 to unixTs), raw[0].fields)
        assertEquals(mapOf(0 to 43L, 253 to unixTs + 1), raw[1].fields)
    }

    @Test
    fun unknownFieldInKnownMessageIsCountedAndKeptRaw() {
        val b = fileIdDefinition(FitFileBuilder())
        b.definition(3, Mesg.MONITORING, listOf(Slot(253, 4, UINT32), Slot(200, 1, UINT8)))
            .data(3, le32(garminTs), u8(9))
            .data(3, le32(garminTs + 60), u8(10))
        val d = FitDecoder.decode(b.build())
        assertEquals(2, d.unknownFieldCount)
        assertEquals(9L, d.raw.first { it.globalMessageNumber == Mesg.MONITORING }.fields[200])
        assertEquals(2, d.monitoring.size)
    }

    @Test
    fun developerFieldsAreDecodedByDescribedName() {
        val b = fileIdDefinition(FitFileBuilder())
        b.definition(8, Mesg.FIELD_DESCRIPTION, listOf(Slot(0, 1, UINT8), Slot(1, 1, UINT8), Slot(2, 1, UINT8), Slot(3, 16, STRING), Slot(8, 8, STRING)))
            .data(8, u8(0), u8(0), u8(UINT16), ascii("HR Dev", 16), ascii("bpm", 8))
        b.definition(9, Mesg.RECORD, listOf(Slot(253, 4, UINT32)), devFields = listOf(Slot(0, 2, 0), Slot(1, 1, 0)))
            .data(9, le32(garminTs), le16(150), u8(7))
        val d = FitDecoder.decode(b.build())
        val raw = d.raw.first { it.globalMessageNumber == Mesg.RECORD }
        assertEquals(mapOf("HR Dev" to 150L, "dev_0_1" to 7L), raw.developerFields)
        assertEquals(unixTs, d.records.single().timestamp)
        assertEquals(0, d.unknownMessageCount)
    }

    @Test
    fun undescribedDeveloperFieldsNeverCrash() {
        val b = fileIdDefinition(FitFileBuilder())
        b.definition(9, Mesg.RECORD, listOf(Slot(253, 4, UINT32)), devFields = listOf(Slot(5, 3, 2)))
            .data(9, le32(garminTs), u8(1), u8(2), u8(3))
        val raw = FitDecoder.decode(b.build()).raw.first { it.globalMessageNumber == Mesg.RECORD }
        assertEquals(listOf(1L, 2L, 3L), raw.developerFields["dev_2_5"])
    }

    @Test
    fun unknownBaseTypeIsReadAsBytes() {
        val b = fileIdDefinition(FitFileBuilder())
        b.definition(3, Mesg.MONITORING, listOf(Slot(253, 4, UINT32), Slot(27, 2, 0x1E)))
            .data(3, le32(garminTs), u8(1), u8(2))
        val raw = FitDecoder.decode(b.build()).raw.first { it.globalMessageNumber == Mesg.MONITORING }
        assertEquals(listOf(1L, 2L), raw.fields[27])
    }

    @Test
    fun twelveByteHeaderAndZeroDataSizeAreAccepted() {
        val b = fileIdDefinition(FitFileBuilder())
        assertEquals(32, FitDecoder.decode(b.build(headerSize = 12)).fileId.typeNum)
        assertEquals(32, FitDecoder.decode(b.build(declaredDataSize = 0)).fileId.typeNum)
        assertEquals(32, FitDecoder.decode(b.build(withHeaderCrc = false)).fileId.typeNum)
    }

    @Test
    fun missingFileIdYieldsNullFileId() {
        val b = FitFileBuilder()
        recordDefinition(b).data(0, le32(garminTs), u8(150), le32(0), le32(0), le16(0))
        val d = FitDecoder.decode(b.build())
        assertNull(d.fileId.typeNum)
        assertNull(d.fileId.type)
        assertEquals(1, d.records.size)
    }

    @Test
    fun badMagicThrows() {
        val bytes = fileIdDefinition(FitFileBuilder()).build()
        bytes[9] = 'X'.code.toByte()
        assertTrue(decodeError(bytes).message!!.contains("magic"))
    }

    @Test
    fun unsupportedHeaderSizeThrows() {
        val bytes = fileIdDefinition(FitFileBuilder()).build()
        bytes[0] = 13
        assertTrue(decodeError(bytes).message!!.contains("header size"))
    }

    @Test
    fun truncatedFileThrows() {
        val bytes = fileIdDefinition(FitFileBuilder()).build()
        val cut = bytes.copyOf(bytes.size - 4)
        assertTrue(decodeError(cut).message!!.contains("Truncated"))
        assertTrue(decodeError(bytes.copyOf(6)).message!!.contains("Not a FIT file"))
    }

    @Test
    fun truncatedRecordInsideDeclaredDataThrows() {
        val bytes = fileIdDefinition(FitFileBuilder()).rawBytes(0x0F, 0x20).build()
        assertTrue(decodeError(bytes).message!!.contains("Truncated"))
    }

    @Test
    fun fileCrcMismatchThrows() {
        val bytes = fileIdDefinition(FitFileBuilder()).build(corruptFileCrc = true)
        assertTrue(decodeError(bytes).message!!.contains("CRC"))
    }

    @Test
    fun headerCrcMismatchThrows() {
        val bytes = fileIdDefinition(FitFileBuilder()).build()
        bytes[12] = (bytes[12].toInt() xor 0x11).toByte()
        assertTrue(decodeError(bytes).message!!.contains("Header CRC"))
    }

    @Test
    fun dataRecordBeforeDefinitionThrows() {
        val bytes = FitFileBuilder().rawBytes(0x05, 0x01).build()
        assertTrue(decodeError(bytes).message!!.contains("before any definition"))
    }

    @Test
    fun redefinedLocalTypeUsesTheLatestLayout() {
        val b = fileIdDefinition(FitFileBuilder())
        b.definition(0, Mesg.HRV_VALUE, listOf(Slot(253, 4, UINT32), Slot(0, 2, UINT16))).data(0, le32(garminTs), le16(6400))
        b.definition(0, Mesg.SLEEP_LEVEL, listOf(Slot(253, 4, UINT32), Slot(0, 1, FitFileBuilder.ENUM))).data(0, le32(garminTs), u8(3))
        val d = FitDecoder.decode(b.build())
        assertEquals(50.0, d.hrvValues.single().valueMs, 1e-9)
        assertEquals(3, d.sleepStages.single().stage)
        assertFalse(d.raw.any { it.globalMessageNumber == Mesg.HRV_VALUE && it.fields[0] == 3L })
    }

    @Test
    fun stressUsesStressLevelTimeAndKeepsSentinelStress() {
        val b = fileIdDefinition(FitFileBuilder())
        b.definition(0, Mesg.STRESS_LEVEL, listOf(Slot(0, 2, SINT16), Slot(1, 4, UINT32), Slot(3, 1, SINT8)))
            .data(0, le16(0xFFFF), le32(garminTs + 60), u8(49))
            .data(0, le16(37), le32(garminTs + 120), u8(0x7F))
        val s = FitDecoder.decode(b.build()).stress
        assertEquals(listOf(unixTs + 60, unixTs + 120), s.map { it.timestamp })
        assertEquals(-1, s[0].stress)
        assertEquals(49, s[0].bodyBattery)
        assertEquals(37, s[1].stress)
        assertNull(s[1].bodyBattery)
    }

    @Test
    fun sessionPrefersEnhancedSpeedAndAppliesScales() {
        val b = fileIdDefinition(FitFileBuilder())
        b.definition(0, Mesg.SESSION, listOf(Slot(253, 4, UINT32), Slot(2, 4, UINT32), Slot(7, 4, UINT32), Slot(9, 4, UINT32), Slot(14, 2, UINT16), Slot(124, 4, UINT32), Slot(5, 1, FitFileBuilder.ENUM)))
            .data(0, le32(garminTs + 3600), le32(garminTs), le32(3_600_000), le32(1_000_000), le16(2000), le32(2778), u8(1))
        val s = FitDecoder.decode(b.build()).sessions.single()
        assertEquals(unixTs, s.startTime)
        assertEquals(3600.0, s.totalElapsedTime!!, 1e-9)
        assertEquals(10000.0, s.totalDistance!!, 1e-9)
        assertEquals(2.778, s.avgSpeed!!, 1e-9)
        assertEquals(1, s.sport)
    }

    @Test
    fun wellnessScalesAreApplied() {
        val b = fileIdDefinition(FitFileBuilder())
        b.definition(0, Mesg.HRV_STATUS_SUMMARY, listOf(Slot(253, 4, UINT32), Slot(1, 2, UINT16), Slot(6, 1, FitFileBuilder.ENUM)))
            .data(0, le32(garminTs), le16(6400), u8(4))
        b.definition(1, Mesg.MAX_MET_DATA, listOf(Slot(0, 4, UINT32), Slot(2, 2, UINT16), Slot(8, 1, FitFileBuilder.ENUM)))
            .data(1, le32(garminTs), le16(452), u8(3))
        b.definition(2, Mesg.RESPIRATION_RATE, listOf(Slot(253, 4, UINT32), Slot(0, 2, SINT16)))
            .data(2, le32(garminTs), le16(1450))
        b.definition(3, Mesg.NAP, listOf(Slot(0, 4, UINT32), Slot(2, 4, UINT32)))
            .data(3, le32(garminTs), le32(garminTs + 1200))
        b.definition(4, Mesg.SPO2_DATA, listOf(Slot(253, 4, UINT32), Slot(0, 1, UINT8), Slot(2, 1, FitFileBuilder.ENUM)))
            .data(4, le32(garminTs), u8(97), u8(3))
        val d = FitDecoder.decode(b.build())
        assertEquals(50.0, d.hrvSummary.single().lastNightAverageMs!!, 1e-9)
        assertEquals(4, d.hrvSummary.single().status)
        assertEquals(45.2, d.maxMet.single().vo2Max!!, 1e-9)
        assertEquals(unixTs, d.maxMet.single().timestamp)
        assertEquals(14.5, d.respiration.single().breathsPerMinute, 1e-9)
        assertEquals(NapRec(unixTs, unixTs + 1200), d.naps.single())
        assertEquals(Spo2Rec(unixTs, 97, 3, null), d.spo2.single())
    }
}
