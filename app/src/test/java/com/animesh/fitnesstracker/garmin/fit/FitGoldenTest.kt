package com.animesh.fitnesstracker.garmin.fit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Exact expectations for the 755-byte MONITOR_M9GL2445.fit so a decoder regression shows up as a
 * precise diff. The file is the hand-specified ten minutes in [SyntheticFixtures.monitorM9GL2445]:
 * 21:20 to 21:30 local (UTC-7) on 16 September 2026, Unix 1789618800 to 1789619400.
 */
class FitGoldenTest {
    private val decoded by lazy { FitFixtures.decode("MONITOR_M9GL2445.fit") }
    private val t0 = 1789618800L

    @Test
    fun recordCountPerMessageNumber() {
        val counts = decoded.raw.groupingBy { it.globalMessageNumber }.eachCount().toSortedMap()
        val expected = sortedMapOf(
            0 to 1, 21 to 1, 23 to 1, 24 to 2, 49 to 1, 55 to 14, 103 to 1, 188 to 2, 211 to 1,
            227 to 4, 233 to 2, 279 to 5, 297 to 5, 355 to 1, 407 to 1, 408 to 1, 484 to 1
        )
        assertEquals(expected, counts)
        assertEquals(44, decoded.raw.size)
        assertEquals(17, decoded.unknownMessageCount)
        assertEquals("stress_level field 4 in each of the four stress records", 4, decoded.unknownFieldCount)
    }

    @Test
    fun fileId() {
        assertEquals(SyntheticFixtures.GOLDEN_START, t0)
        assertEquals(FileIdRec(typeNum = 32, timeCreated = t0 + 45, manufacturer = 1, product = 4570, serialNumber = 1000000001L, number = 4), decoded.fileId)
    }

    @Test
    fun firstMonitoringRecordRawFields() {
        val first = decoded.raw.first { it.globalMessageNumber == Mesg.MONITORING }
        val expected = mapOf<Int, Any?>(253 to t0, 3 to 312L, 2 to 218.4, 4 to 1140.0, 19 to 14L, 29 to 1280L, 5 to 0L)
        assertEquals(expected, first.fields)
        assertEquals(emptyMap<String, Any?>(), first.developerFields)
    }

    @Test
    fun firstMonitoringRecordsTyped() {
        assertEquals(14, decoded.monitoring.size)
        val first = decoded.monitoring[0]
        assertEquals(
            MonitoringRec(
                timestamp = t0, heartRate = null, cumulativeSteps = 312L, cumulativeDistanceM = 218.4, cumulativeActiveKcal = 14,
                activityType = 0, intensity = null, moderateActivityMinutes = null, vigorousActivityMinutes = null
            ),
            first
        )
        val second = decoded.monitoring[1]
        assertEquals(7325L, second.cumulativeSteps)
        assertEquals(5713.5, second.cumulativeDistanceM!!, 1e-9)
        assertEquals(293, second.cumulativeActiveKcal)
        assertEquals(6, second.activityType)
        val third = decoded.monitoring[2]
        assertNull(third.cumulativeSteps)
        assertEquals(t0, third.timestamp)
        assertEquals(0.0, third.ascentM!!, 1e-9)
        assertEquals(mapOf<Int, Any?>(253 to t0, 31 to 0.0, 32 to 0.0, 35 to 21.336, 36 to 31.301), decoded.raw.filter { it.globalMessageNumber == Mesg.MONITORING }[2].fields)
        val fourth = decoded.monitoring[3]
        assertEquals(8, fourth.activityType)
        assertEquals(0, fourth.intensity)
        assertEquals(42, fourth.moderateActivityMinutes)
        assertEquals(3, fourth.vigorousActivityMinutes)
    }

    @Test
    fun timestamp16HeartRateRecords() {
        val perMinute = decoded.monitoring.drop(4)
        assertEquals((1..10).map { t0 + it * 60 }, perMinute.map { it.timestamp })
        assertEquals(listOf(62, 61, null, 63, 64, 66, 65, 63, 62, 61), perMinute.map { it.heartRate })
        val raw = decoded.raw.filter { it.globalMessageNumber == Mesg.MONITORING }[6]
        assertEquals("heart rate 0 stays 0 in the raw record and becomes null in the typed one", mapOf<Int, Any?>(26 to 7972L, 27 to 0L), raw.fields)
    }

    @Test
    fun typedWellnessRecords() {
        assertEquals(
            listOf(StressRec(t0 + 60, -1, 31, 41), StressRec(t0 + 240, 22, 31, 41), StressRec(t0 + 420, -2, 30, 41), StressRec(t0 + 600, 19, 30, 40)),
            decoded.stress
        )
        assertEquals(listOf(RestingHrRec(t0, 60, 56)), decoded.restingHr)
        assertEquals(
            listOf(RespirationRec(t0 + 60, -2.0), RespirationRec(t0 + 180, 15.2), RespirationRec(t0 + 300, 14.8), RespirationRec(t0 + 420, -1.0), RespirationRec(t0 + 540, 15.1)),
            decoded.respiration
        )
        assertEquals(listOf(EventRec(t0 + 45, 74, 0, null)), decoded.events)
        assertEquals(listOf(MonitoringInfoRec(t0, 2159)), decoded.monitoringInfo)
        val info = decoded.raw.first { it.globalMessageNumber == Mesg.MONITORING_INFO }
        assertEquals(listOf(6L, 1L), info.fields[1])
        assertEquals(listOf(1.638, 2.457), info.fields[3])
        assertEquals(listOf(5000.0, 5000.0), info.fields[7])
        assertEquals("local_timestamp is the UTC-7 wall clock", t0 - 7 * 3600, info.fields[0])
    }

    @Test
    fun unknownRecordsKeepRawValues() {
        val unknown23 = decoded.raw.first { it.globalMessageNumber == 23 }
        assertEquals(mapOf<Int, Any?>(253 to t0, 2 to 1L, 3 to 1000000001L, 4 to 4570L, 5 to 618L), unknown23.fields)
        assertEquals(mapOf<Int, Any?>(0 to 618L), decoded.raw.first { it.globalMessageNumber == 49 }.fields)
        val unknown233 = decoded.raw.first { it.globalMessageNumber == 233 }
        assertEquals(mapOf<Int, Any?>(2 to listOf(10L, 0L, 0L, 200L)), unknown233.fields)
        val unknown279 = decoded.raw.first { it.globalMessageNumber == 279 }
        assertEquals(mapOf<Int, Any?>(253 to t0 + 120, 0 to 2897L), unknown279.fields)
        val unknown24 = decoded.raw.first { it.globalMessageNumber == 24 }
        assertEquals(27, (unknown24.fields[2] as List<*>).size)
        assertEquals(mapOf<Int, Any?>(253 to t0, 0 to 3L), decoded.raw.first { it.globalMessageNumber == 188 }.fields)
        assertEquals(mapOf<Int, Any?>(253 to t0, 0 to 7L, 1 to 42L), decoded.raw.first { it.globalMessageNumber == 484 }.fields)
    }
}
