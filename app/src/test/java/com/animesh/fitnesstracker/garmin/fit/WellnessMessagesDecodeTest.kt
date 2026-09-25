package com.animesh.fitnesstracker.garmin.fit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Decodes the version 0.5 wellness messages from files built in memory with [FitWriter], with the
 * field layouts a Forerunner 570 writes (numbers invented). Covers what the fixtures cannot: an
 * activity's physiological metrics, and every field of the daily sleep and event records.
 */
class WellnessMessagesDecodeTest {
    private val t0 = 1_789_600_000L
    private fun ts(unix: Long) = FitField.u32(253, unix - GARMIN_EPOCH_UNIX_SECONDS)

    @Test
    fun physiologicalMetricsCarryPerformanceConditionAndBenefit() {
        val w = FitWriter()
        w.write(Mesg.FILE_ID, FitField.enum(0, 4), FitField.u32(4, t0 - GARMIN_EPOCH_UNIX_SECONDS))
        w.write(
            Mesg.PHYSIOLOGICAL_METRICS,
            ts(t0 + 3600), FitField.u8(4, 24), FitField.u8(20, 6), FitField.u16(9, 1140), FitField.sint8(17, -7), FitField.u8(41, 3), FitField.u8(63, 121)
        )
        val d = FitDecoder.decode(w.toByteArray())
        val p = d.physiologicalMetrics.single()
        assertEquals(2.4, p.aerobicEffect!!, 1e-9)
        assertEquals(0.6, p.anaerobicEffect!!, 1e-9)
        assertEquals(1140, p.recoveryTimeMinutes)
        assertEquals(-7, p.endingPerformanceCondition)
        assertEquals(3, p.primaryBenefit)
        assertEquals(121, p.averageHeartRate)
        assertEquals(0, d.unknownFieldCount)
    }

    @Test
    fun physiologicalMetricsWithoutTheNewFieldsDecodeToNull() {
        val w = FitWriter()
        w.write(Mesg.FILE_ID, FitField.enum(0, 4), FitField.u32(4, t0 - GARMIN_EPOCH_UNIX_SECONDS))
        w.write(Mesg.PHYSIOLOGICAL_METRICS, ts(t0), FitField.u8(4, 30), FitField.sint8(17, null), FitField.u8(41, null))
        val p = FitDecoder.decode(w.toByteArray()).physiologicalMetrics.single()
        assertNull(p.endingPerformanceCondition)
        assertNull(p.primaryBenefit)
    }

    @Test
    fun bodyBatteryEventsKeepStartEndAndSignedDelta() {
        val w = FitWriter()
        w.write(Mesg.FILE_ID, FitField.enum(0, 32), FitField.u32(4, t0 - GARMIN_EPOCH_UNIX_SECONDS))
        val start = t0 - 452 * 60
        w.write(Mesg.BODY_BATTERY_EVENT, ts(start), FitField.u8(0, 4), FitField.u16(1, 452), FitField.sint8(2, 44), FitField.u8(3, 0), FitField.u8(6, 0), FitField.u32(7, t0 - GARMIN_EPOCH_UNIX_SECONDS))
        w.write(Mesg.BODY_BATTERY_EVENT, ts(t0 + 600), FitField.u8(0, 0), FitField.u16(1, 47), FitField.sint8(2, -12), FitField.u8(3, 20), FitField.u8(6, 31), FitField.u32(7, t0 + 600 + 47 * 60 - GARMIN_EPOCH_UNIX_SECONDS))
        val events = FitDecoder.decode(w.toByteArray()).bodyBatteryEvents
        assertEquals(
            listOf(
                BodyBatteryEventRec(start, 4, 452, 44, t0, 0L, 0L),
                BodyBatteryEventRec(t0 + 600, 0, 47, -12, t0 + 600 + 47 * 60, 20L, 31L)
            ),
            events
        )
    }

    @Test
    fun dailySleepAndSleepDemandDecode() {
        val w = FitWriter()
        w.write(Mesg.FILE_ID, FitField.enum(0, 44), FitField.u32(4, t0 - GARMIN_EPOCH_UNIX_SECONDS))
        val nightStart = t0 - 8 * 3600
        w.write(
            Mesg.DAILY_SLEEP,
            ts(t0), FitField.u8(2, 83), FitField.u16(3, 960), FitField.u32(8, t0 - 7 * 3600 - GARMIN_EPOCH_UNIX_SECONDS), FitField.u32(9, nightStart - GARMIN_EPOCH_UNIX_SECONDS),
            FitField.sint16(10, -420), FitField.u32(11, t0 - GARMIN_EPOCH_UNIX_SECONDS), FitField.sint16(12, -420), FitField.u8(14, 41), FitField.u8(16, 100)
        )
        w.write(Mesg.SLEEP_DEMAND, ts(t0), FitField.u16(0, 480), FitField.u16(1, 520))
        val d = FitDecoder.decode(w.toByteArray())
        assertEquals(DailySleepRec(t0, 83, 960, nightStart, t0, -420, -420, 41, 100), d.dailySleep.single())
        assertEquals(SleepDemandRec(t0, 480, 520), d.sleepDemand.single())
        assertEquals("the local timestamp is converted to Unix seconds of the wall clock", t0 - 7 * 3600, d.raw.first { it.globalMessageNumber == Mesg.DAILY_SLEEP }.fields[8])
    }

    @Test
    fun monitoringAltitudeUsesTheEnhancedAltitudeScale() {
        val w = FitWriter()
        w.write(Mesg.FILE_ID, FitField.enum(0, 32), FitField.u32(4, t0 - GARMIN_EPOCH_UNIX_SECONDS))
        w.write(Mesg.MONITORING_ALTITUDE, ts(t0), FitField.u16(0, 2897))
        w.write(Mesg.MONITORING_ALTITUDE, ts(t0 + 120), FitField.u16(0, null))
        val d = FitDecoder.decode(w.toByteArray())
        assertEquals(1, d.altitude.size)
        assertEquals(79.4, d.altitude.single().altitudeM, 1e-9)
    }
}
