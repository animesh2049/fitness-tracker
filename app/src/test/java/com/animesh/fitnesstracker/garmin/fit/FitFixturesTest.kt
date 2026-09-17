package com.animesh.fitnesstracker.garmin.fit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Instant

/** Decodes the real Forerunner 570 files and checks the values the app will rely on. Summaries go to stdout for the milestone report. */
class FitFixturesTest {
    private fun printSummary(name: String, decoded: DecodedFit) {
        println("=== $name")
        println(FitDump.summary(decoded))
    }

    private fun unix(iso: String) = Instant.parse(iso).epochSecond

    @Test
    fun everyFixtureDecodesWithValidCrc() {
        for (name in FitFixtures.all) {
            val bytes = FitFixtures.bytes(name)
            val decoded = FitDecoder.decode(bytes)
            assertNotNull("$name has a file_id type", decoded.fileId.typeNum)
            assertTrue("$name has records", decoded.raw.isNotEmpty())
            val dataEnd = bytes.size - 2
            val stored = (bytes[dataEnd].toInt() and 0xFF) or ((bytes[dataEnd + 1].toInt() and 0xFF) shl 8)
            assertEquals("$name CRC", stored, FitCrc.compute(bytes, 0, dataEnd))
            assertEquals("$name whole-file CRC folds to zero", 0, FitCrc.compute(bytes))
        }
    }

    @Test
    fun flippedByteFailsWithDecodeException() {
        val bytes = FitFixtures.bytes("MONITOR_M9GL2445.fit")
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x40).toByte()
        try {
            FitDecoder.decode(bytes)
            fail("expected FitDecodeException")
        } catch (e: FitDecodeException) {
            assertTrue(e.message!!.contains("CRC"))
        }
    }

    @Test
    fun truncatedFixtureFailsWithDecodeException() {
        val bytes = FitFixtures.bytes("SLEEP_G9G80120.fit")
        try {
            FitDecoder.decode(bytes.copyOf(bytes.size - 40))
            fail("expected FitDecodeException")
        } catch (e: FitDecodeException) {
            assertTrue(e.message!!.contains("Truncated"))
        }
    }

    @Test
    fun bigMonitorFileIdAndTimeCreated() {
        val d = FitFixtures.decode("MONITOR_M9GL2255.fit")
        assertEquals(32, d.fileId.typeNum)
        assertEquals(FitFileType.MONITORING_B, d.fileId.type)
        assertTrue(d.fileId.type!!.isMonitoring)
        val created = d.fileId.timeCreated!!
        assertTrue(created >= unix("2026-09-01T00:00:00Z"))
        assertTrue(created <= unix("2026-09-17T00:00:00Z"))
        assertEquals(1, d.fileId.manufacturer)
        assertEquals(4570, d.fileId.product)
    }

    @Test
    fun bigMonitorHeartRatesArePlausible() {
        val d = FitFixtures.decode("MONITOR_M9GL2255.fit")
        val hrs = d.monitoring.mapNotNull { it.heartRate }
        assertTrue("expected hundreds of HR samples, got ${hrs.size}", hrs.size > 500)
        assertTrue("HR out of range: ${hrs.filter { it !in 30..220 }}", hrs.all { it in 30..220 })
        assertEquals(1090, d.monitoring.size)
        assertTrue(d.monitoring.zipWithNext().all { (a, b) -> a.timestamp <= b.timestamp })
    }

    @Test
    fun bigMonitorCumulativeStepsNeverDecreasePerActivityType() {
        val d = FitFixtures.decode("MONITOR_M9GL2255.fit")
        val withSteps = d.monitoring.filter { it.cumulativeSteps != null }
        assertTrue(withSteps.size > 30)
        val byType = withSteps.groupBy { it.activityType }
        assertTrue(byType.containsKey(6))
        for ((type, recs) in byType) {
            val steps = recs.map { it.cumulativeSteps!! }
            assertTrue("steps decreased for activity type $type: $steps", steps.zipWithNext().all { (a, b) -> a <= b })
        }
        assertEquals(7325L, byType.getValue(6).last().cumulativeSteps)
    }

    @Test
    fun bigMonitorStressAndBodyBattery() {
        val d = FitFixtures.decode("MONITOR_M9GL2255.fit")
        assertEquals(1252, d.stress.size)
        val bb = d.stress.mapNotNull { it.bodyBattery }
        assertTrue(bb.size > 1000)
        assertTrue(bb.all { it in 0..100 })
        assertTrue(d.stress.mapNotNull { it.stress }.all { it in -2..100 })
        assertTrue(d.stress.zipWithNext().all { (a, b) -> a.timestamp <= b.timestamp })
        assertEquals(1, d.spo2.size)
        assertEquals(97, d.spo2.single().spo2Percent)
        assertEquals(1, d.restingHr.size)
        assertEquals(56, d.restingHr.single().currentDayRestingHeartRate)
        assertEquals(1281, d.respiration.size)
        assertEquals(1, d.monitoringInfo.size)
        assertEquals(2159, d.monitoringInfo.single().restingMetabolicRate)
        printSummary("MONITOR_M9GL2255.fit", d)
    }

    @Test
    fun otherMonitorFilesMapMonitoringAndStress() {
        for (name in listOf("MONITOR_M9FM1100.fit", "MONITOR_M9G00000.fit")) {
            val d = FitFixtures.decode(name)
            assertEquals(FitFileType.MONITORING_B, d.fileId.type)
            assertTrue("$name monitoring", d.monitoring.isNotEmpty())
            assertTrue("$name stress", d.stress.isNotEmpty())
            printSummary(name, d)
        }
    }

    @Test
    fun sleepFixtureStagesAndScore() {
        val d = FitFixtures.decode("SLEEP_G9G80120.fit")
        assertEquals(FitFileType.SLEEP, d.fileId.type)
        assertEquals(25, d.sleepStages.size)
        assertTrue(d.sleepStages.all { it.stage in 0..4 })
        assertTrue(d.sleepStages.any { it.stage in 2..4 })
        assertTrue(d.sleepStages.zipWithNext().all { (a, b) -> a.timestamp < b.timestamp })
        val stats = d.sleepStats.single()
        assertEquals(83, stats.overallSleepScore)
        assertTrue(stats.overallSleepScore!! in 0..100)
        assertEquals(74, stats.deepSleepScore)
        assertEquals(86, stats.remSleepScore)
        assertEquals(2, stats.awakeningsCount)
        assertEquals(8.67, stats.averageStressDuringSleep!!, 1e-9)
        assertNull(stats.timestamp)
        assertEquals(1, d.restlessMoments.size)
        assertEquals(listOf(74, 74), d.events.map { it.event })
        assertEquals(listOf(0, 1), d.events.map { it.eventType })
        assertTrue(d.events[0].timestamp < d.events[1].timestamp)
        assertTrue(d.sleepStages.first().timestamp > d.events[0].timestamp)
        printSummary("SLEEP_G9G80120.fit", d)
    }

    @Test
    fun hrvFixtureSummaryAndValues() {
        val d = FitFixtures.decode("HRV_G9G80118.fit")
        assertEquals(FitFileType.HRV_STATUS, d.fileId.type)
        val s = d.hrvSummary.single()
        assertTrue(s.status!! in 0..4)
        assertEquals(50.0, s.lastNightAverageMs!!, 1e-9)
        assertEquals(87.0, s.lastNight5MinHighMs!!, 1e-9)
        assertNull("weekly average is the invalid sentinel in this file", s.weeklyAverageMs)
        assertEquals(96, d.hrvValues.size)
        assertTrue(d.hrvValues.all { it.valueMs in 10.0..200.0 })
        assertTrue(d.hrvValues.zipWithNext().all { (a, b) -> b.timestamp - a.timestamp == 300L })
        printSummary("HRV_G9G80118.fit", d)
    }

    @Test
    fun metricsFixturesCarryRecoveryAndReadiness() {
        val names = listOf("METRICS_F85H0122.fit", "METRICS_G9FM3212.fit", "METRICS_G9FM3213.fit", "METRICS_G9G80119.fit", "METRICS_G9GL2318.fit")
        val decoded = names.associateWith { FitFixtures.decode(it) }
        decoded.forEach { (name, d) ->
            assertEquals(name, FitFileType.METRICS, d.fileId.type)
            assertTrue(name, d.functionalMetrics.isNotEmpty())
            printSummary(name, d)
        }
        val withRecovery = decoded.getValue("METRICS_G9G80119.fit")
        assertEquals(1, withRecovery.recovery.size)
        assertEquals(1, withRecovery.recovery.single().recoveryMinutes)
        val readiness = withRecovery.trainingReadiness.single()
        assertEquals(83, readiness.sleepScore)
        assertEquals(0, readiness.level)
        assertNull("readiness itself is the invalid sentinel", readiness.readiness)
        assertNull(decoded.getValue("METRICS_G9FM3213.fit").trainingReadiness.single().sleepScore)
        val anyMetric = decoded.values.any { it.maxMet.any { m -> m.vo2Max != null && m.vo2Max in 20.0..80.0 } || it.trainingReadiness.isNotEmpty() || it.trainingLoad.isNotEmpty() || it.recovery.isNotEmpty() }
        assertTrue(anyMetric)
    }

    @Test
    fun skinTemperatureFixtureIsUnknownButDecodes() {
        val d = FitFixtures.decode("SKINTEMP_G9G80127.fit")
        assertEquals(FitFileType.SKIN_TEMP, d.fileId.type)
        assertTrue(d.raw.any { it.globalMessageNumber == 398 })
        assertTrue(d.unknownMessageCount >= 1)
        printSummary("SKINTEMP_G9G80127.fit", d)
    }

    @Test
    fun deviceFixtureHasCapabilities() {
        val d = FitFixtures.decode("device.fit")
        assertEquals(FitFileType.DEVICE, d.fileId.type)
        val cap = d.capabilities.single()
        assertNotNull(cap.connectivitySupported)
        assertNotNull(cap.sportsSupported)
        printSummary("device.fit", d)
    }

    @Test
    fun settingsFixtureHasUserProfile() {
        val d = FitFixtures.decode("Settings.fit")
        assertEquals(FitFileType.SETTINGS, d.fileId.type)
        val profile = d.userProfile.single()
        assertNotNull(profile.weightKg)
        assertTrue(profile.weightKg!! in 30.0..200.0)
        assertTrue(d.unknownMessageCount > 0)
        printSummary("Settings.fit", d)
    }

    @Test
    fun recordsAndTotalsDecodeWithUnknownMessages() {
        val records = FitFixtures.decode("Records.fit")
        val totals = FitFixtures.decode("Totals.fit")
        assertTrue(records.unknownMessageCount > 0)
        assertTrue(totals.unknownMessageCount > 0)
        assertEquals(FitFileType.TOTALS, totals.fileId.type)
        assertFalse(records.raw.isEmpty())
        printSummary("Records.fit", records)
        printSummary("Totals.fit", totals)
    }
}
