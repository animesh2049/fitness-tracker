package com.animesh.fitnesstracker.garmin.fit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Exact expectations for the 804-byte MONITOR_M9GL2445.fit so a decoder regression shows up as a precise diff. */
class FitGoldenTest {
    private val decoded by lazy { FitFixtures.decode("MONITOR_M9GL2445.fit") }

    @Test
    fun recordCountPerMessageNumber() {
        val counts = decoded.raw.groupingBy { it.globalMessageNumber }.eachCount().toSortedMap()
        val expected = sortedMapOf(
            0 to 1, 21 to 1, 23 to 1, 24 to 4, 35 to 1, 55 to 11, 103 to 1, 188 to 2, 211 to 2,
            227 to 1, 233 to 2, 279 to 1, 297 to 1, 355 to 1, 484 to 1
        )
        assertEquals(expected, counts)
        assertEquals(31, decoded.raw.size)
        assertEquals(13, decoded.unknownMessageCount)
        assertEquals(2, decoded.unknownFieldCount)
    }

    @Test
    fun fileId() {
        assertEquals(FileIdRec(typeNum = 32, timeCreated = 1789619025L, manufacturer = 1, product = 4570, serialNumber = 3616528818L, number = 4), decoded.fileId)
    }

    @Test
    fun firstMonitoringRecordRawFields() {
        val first = decoded.raw.first { it.globalMessageNumber == Mesg.MONITORING }
        val expected = mapOf<Int, Any?>(253 to 1789618980L, 2 to 0.0, 3 to 0L, 4 to 110.0, 19 to 6L, 29 to 1283L, 5 to 0L)
        assertEquals(expected, first.fields)
        assertEquals(emptyMap<String, Any?>(), first.developerFields)
    }

    @Test
    fun firstMonitoringRecordsTyped() {
        assertEquals(11, decoded.monitoring.size)
        val first = decoded.monitoring[0]
        assertEquals(
            MonitoringRec(
                timestamp = 1789618980L, heartRate = null, cumulativeSteps = 0L, cumulativeDistanceM = 0.0, cumulativeActiveKcal = 6,
                activityType = 0, intensity = null, moderateActivityMinutes = null, vigorousActivityMinutes = null
            ),
            first
        )
        val second = decoded.monitoring[1]
        assertEquals(7325L, second.cumulativeSteps)
        assertEquals(5999.21, second.cumulativeDistanceM!!, 1e-9)
        assertEquals(210, second.cumulativeActiveKcal)
        assertEquals(6, second.activityType)
        val third = decoded.monitoring[2]
        assertNull(third.cumulativeSteps)
        assertEquals(1789618980L, third.timestamp)
        assertEquals(mapOf<Int, Any?>(253 to 1789618980L, 35 to 21.336, 36 to 31.301), decoded.raw.filter { it.globalMessageNumber == Mesg.MONITORING }[2].fields)
    }

    @Test
    fun typedWellnessRecords() {
        assertEquals(listOf(StressRec(1789619040L, -1, 49, 101)), decoded.stress)
        assertEquals(listOf(RestingHrRec(1789618980L, 60, 56), RestingHrRec(1789619040L, 60, 56)), decoded.restingHr)
        assertEquals(listOf(RespirationRec(1789619040L, -2.0)), decoded.respiration)
        assertEquals(listOf(EventRec(1789619025L, 78, 3, null)), decoded.events)
        assertEquals(listOf(MonitoringInfoRec(1789618980L, 2159)), decoded.monitoringInfo)
        val info = decoded.raw.first { it.globalMessageNumber == Mesg.MONITORING_INFO }
        assertEquals(listOf(6L, 1L), info.fields[1])
        assertEquals(listOf(1.638, 2.457), info.fields[3])
        assertEquals(listOf(5000.0, 5000.0), info.fields[7])
        assertEquals(1789593780L, info.fields[0])
    }

    @Test
    fun unknownRecordsKeepRawValues() {
        val unknown35 = decoded.raw.first { it.globalMessageNumber == 35 }
        assertEquals(mapOf<Int, Any?>(3 to 600L), unknown35.fields)
        val unknown233 = decoded.raw.first { it.globalMessageNumber == 233 }
        assertEquals(mapOf<Int, Any?>(2 to listOf(10L, 0L, 0L, 200L)), unknown233.fields)
        val unknown279 = decoded.raw.first { it.globalMessageNumber == 279 }
        assertEquals(mapOf<Int, Any?>(253 to 1789619040L, 0 to 2936L), unknown279.fields)
    }
}
