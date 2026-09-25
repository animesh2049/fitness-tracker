package com.animesh.fitnesstracker.garmin.fit

import com.animesh.fitnesstracker.domain.health.HealthSnapshots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decodes a Health Snapshot file built in memory with the layouts Gadgetbridge's profile gives for
 * the HSA messages (invented numbers) and checks the typed samples and the summary. No real
 * snapshot file has been captured yet; the record timestamp is taken as the start of each array.
 */
class HealthSnapshotDecodeTest {
    private val t0 = 1_789_700_000L
    private fun ts(unix: Long) = FitField.u32(253, unix - GARMIN_EPOCH_UNIX_SECONDS)

    private fun snapshot(): ByteArray {
        val w = FitWriter()
        w.write(Mesg.FILE_ID, FitField.enum(0, 70), FitField.u32(4, t0 - GARMIN_EPOCH_UNIX_SECONDS))
        w.write(HsaMesg.EVENT, ts(t0), FitField.u8(0, 0))
        // Eight heart rates one second apart, then the second half of the recording as another record.
        w.write(HsaMesg.HEART_RATE, ts(t0), FitField.u16(0, 1), FitField.u8(1, 1), FitField.array(2, FitBaseType.UINT8, listOf(62L, 63L, 61L, 64L, 66L, 65L, 63L, 62L)))
        w.write(HsaMesg.HEART_RATE, ts(t0 + 60), FitField.u16(0, 1), FitField.u8(1, 1), FitField.array(2, FitBaseType.UINT8, listOf(60L, null, 58L, 59L)))
        // Stress every 30 s with the unmeasurable sentinel in the middle.
        w.write(HsaMesg.STRESS, ts(t0), FitField.u16(0, 30), FitField.array(1, FitBaseType.SINT8, listOf(22L, 25L, -1L, 30L)))
        // Respiration with scale 100.
        w.write(HsaMesg.RESPIRATION, ts(t0), FitField.u16(0, 60), FitField.array(1, FitBaseType.SINT16, listOf(1450L, 1510L)))
        w.write(HsaMesg.SPO2, ts(t0 + 30), FitField.u16(0, 60), FitField.array(1, FitBaseType.UINT8, listOf(96L, 97L)), FitField.array(2, FitBaseType.UINT8, listOf(3L, 3L)))
        w.write(HsaMesg.BODY_BATTERY, ts(t0), FitField.u16(0, 60), FitField.array(1, FitBaseType.SINT8, listOf(47L, 47L, 48L)), FitField.array(2, FitBaseType.SINT16, listOf(1L, 1L, 1L)), FitField.array(3, FitBaseType.SINT16, listOf(0L, 0L, 0L)))
        w.write(HsaMesg.STEP, ts(t0), FitField.u16(0, 60), FitField.array(1, FitBaseType.UINT32, listOf(0L, 0L)))
        w.write(HsaMesg.WRIST_TEMPERATURE, ts(t0), FitField.u16(0, 60), FitField.array(1, FitBaseType.UINT16, listOf(33_450L, 33_500L)))
        w.write(HsaMesg.EVENT, ts(t0 + 120), FitField.u8(0, 1))
        return w.toByteArray()
    }

    @Test
    fun samplesDecodeWithTheirArraysAndIntervals() {
        val d = FitDecoder.decode(snapshot())
        assertEquals(FitFileType.HSA, d.fileId.type)
        assertEquals(0, d.unknownFieldCount)
        assertEquals(0, d.unknownMessageCount)
        val heart = d.healthSnapshot.filter { it.kind == HsaKind.HEART_RATE }
        assertEquals(2, heart.size)
        assertEquals(listOf(62.0, 63.0, 61.0, 64.0, 66.0, 65.0, 63.0, 62.0), heart[0].values)
        assertEquals(1, heart[0].processingIntervalSeconds)
        assertEquals(listOf(1.0), heart[0].extraA)
        assertEquals("the invalid element is dropped, not kept as zero", listOf(60.0, 58.0, 59.0), heart[1].values)
        val stress = d.healthSnapshot.single { it.kind == HsaKind.STRESS }
        assertEquals("stress keeps its sentinel", listOf(22.0, 25.0, -1.0, 30.0), stress.values)
        assertEquals(30, stress.processingIntervalSeconds)
        assertEquals(listOf(14.5, 15.1), d.healthSnapshot.single { it.kind == HsaKind.RESPIRATION }.values)
        val spo2 = d.healthSnapshot.single { it.kind == HsaKind.SPO2 }
        assertEquals(listOf(96.0, 97.0), spo2.values)
        assertEquals(listOf(3.0, 3.0), spo2.extraA)
        val battery = d.healthSnapshot.single { it.kind == HsaKind.BODY_BATTERY }
        assertEquals(listOf(47.0, 47.0, 48.0), battery.values)
        assertEquals(listOf(1.0, 1.0, 1.0), battery.extraA)
        assertEquals(listOf(0.0, 0.0, 0.0), battery.extraB)
        assertEquals("zero steps are real", listOf(0.0, 0.0), d.healthSnapshot.single { it.kind == HsaKind.STEPS }.values)
        assertEquals(listOf(33.45, 33.5), d.healthSnapshot.single { it.kind == HsaKind.WRIST_TEMPERATURE }.values)
        assertEquals(listOf(HsaEventRec(t0, 0), HsaEventRec(t0 + 120, 1)), d.healthSnapshotEvents)
    }

    @Test
    fun summaryAveragesEachStreamAndSpansTheRecording() {
        val d = FitDecoder.decode(snapshot())
        val s = HealthSnapshots.summarise(d)!!
        assertEquals(t0, s.startTimestamp)
        assertEquals("the last Body Battery sample sits 120 s after its record", t0 + 120, s.endTimestamp)
        assertEquals(120, s.durationSeconds)
        assertEquals(62, s.avgHeartRate)
        assertEquals(58, s.minHeartRate)
        assertEquals(66, s.maxHeartRate)
        assertEquals(14.8, s.avgRespiration!!, 1e-9)
        assertEquals("the -1 sentinel is ignored", 26, s.avgStress)
        assertEquals(97, s.avgSpo2)
        assertEquals(47, s.bodyBatteryStart)
        assertEquals(48, s.bodyBatteryEnd)
        assertEquals(8 + 3 + 4 + 2 + 2 + 3 + 2 + 2, s.samples)
    }

    @Test
    fun expandPlacesArrayElementsByTheInterval() {
        val rec = HsaSampleRec(HsaKind.STRESS, t0, 30, listOf(10.0, 20.0, 30.0))
        assertEquals(listOf(t0, t0 + 30, t0 + 60), HealthSnapshots.expand(rec).map { it.timestamp })
        assertEquals(listOf(t0, t0), HealthSnapshots.expand(rec.copy(processingIntervalSeconds = null, values = listOf(1.0, 2.0))).map { it.timestamp })
    }

    @Test
    fun aFileWithoutSamplesHasNoSummary() {
        val w = FitWriter()
        w.write(Mesg.FILE_ID, FitField.enum(0, 70), FitField.u32(4, t0 - GARMIN_EPOCH_UNIX_SECONDS))
        w.write(HsaMesg.EVENT, ts(t0), FitField.u8(0, 0))
        val d = FitDecoder.decode(w.toByteArray())
        assertTrue(d.healthSnapshot.isEmpty())
        assertNull(HealthSnapshots.summarise(d))
    }

    @Test
    fun dumpNamesTheSnapshotMessages() {
        val summary = FitDump.summary(FitDecoder.decode(snapshot()))
        assertTrue(summary.contains("308 hsa_heart_rate_data"))
        assertTrue(summary.contains("306 hsa_stress_data"))
        assertTrue(summary.contains("2:heart_rate="))
        assertTrue(summary.contains("file_id: type=70 (HSA)"))
    }
}
