package com.animesh.fitnesstracker

import com.animesh.fitnesstracker.data.model.MetricType
import com.animesh.fitnesstracker.garmin.fit.FitDecoder
import com.animesh.fitnesstracker.garmin.fit.SyntheticFixtures
import com.animesh.fitnesstracker.garmin.fitimport.FitRows
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the decoder output from the synthetic Forerunner 570 style fixtures through the row
 * conversion, so the decoder (Milestone 18) and the importer's pure part (Milestone 19) are proven
 * to agree on whole files with the shapes the watch writes. See [SyntheticFixtures] for the story
 * behind the numbers.
 */
class FixturePipelineTest {
    private val zone = ZoneOffset.ofHours(-7) // the synthetic watch lives at UTC-7 (daily_sleep tz offsets)
    private val rows = FitRows(zone)

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fit/$name")) { "missing fixture $name" }.use { it.readBytes() }

    @Test
    fun bigMonitoringFileBecomesPlausibleMinuteRows() {
        val fit = FitDecoder.decode(fixture("MONITOR_M9GL2255.fit"))
        val out = rows.monitoring(fit)
        val steps = out.minutes.sumOf { it.steps }
        val withHr = out.minutes.count { it.heartRate != null }
        println("minutes=${out.minutes.size} steps=$steps withHr=$withHr stress=${out.stress.size} resp=${out.respiration.size} spo2=${out.spo2.size} restingHr=${out.restingHr}")
        assertTrue("expected hundreds of minute rows, got ${out.minutes.size}", out.minutes.size in 200..2000)
        assertTrue("steps should match the watch's ${SyntheticFixtures.WALKING_STEPS} walking steps plus a few generic ones, got $steps", steps in 7_300..8_000)
        assertTrue("most minutes should carry a heart rate", withHr > out.minutes.size / 2)
        // Off the wrist 06:40 to 07:20 and 13:00 to 14:00; the 20 minute cumulative records and the 13:30 intensity record split
        // those gaps into stretches of 19, 19, 19, 9, 9 and 19 minutes, and only stretches longer than 10 minutes count as not worn.
        assertEquals("the two off-wrist gaps become unworn filler minutes", 76, out.minutes.count { !it.worn })
        assertTrue(out.minutes.all { it.steps >= 0 })
        assertTrue(out.minutes.all { (it.heartRate ?: 60) in 30..220 })
        assertTrue(out.stress.size > 900)
        assertTrue("the watch's 127 Body Battery marker must never reach the rows", out.stress.all { (it.bodyBattery ?: 50) in 0..100 })
        assertTrue("off-wrist records (stress unmeasured and Body Battery 127) are dropped rather than stored as 127", out.stress.size < fit.stress.size)
        assertTrue(out.stress.all { (it.stress ?: 0) in 0..100 })
        assertTrue(out.respiration.all { it.breathsPerMinute > 0 })
        assertEquals(listOf(SyntheticFixtures.SPO2_PERCENT), out.spo2.map { it.percent })
        assertEquals(1, out.restingHr.size)
        assertEquals(SyntheticFixtures.RESTING_HR, out.restingHr.single().bpm)
        assertEquals(listOf(MetricType.RMR to SyntheticFixtures.RESTING_METABOLIC_RATE.toDouble()), out.metrics.map { it.type to it.value })
    }

    @Test
    fun sleepFileBecomesOneNightWithStages() {
        val fit = FitDecoder.decode(fixture("SLEEP_G9G80120.fit"))
        val bounds = rows.sleepBounds(fit)
        assertNotNull("sleep file should yield bounds", bounds)
        assertTrue(bounds!!.fromEvent)
        val stages = rows.sleepStages(fit, bounds)
        val hrv = rows.hrv(FitDecoder.decode(fixture("HRV_G9G80118.fit"))).summaries.firstOrNull()
        val night = rows.sleepNight(fit, bounds, stages, prior = null, hrv = hrv)
        println("night=$night stages=${stages.size}")
        assertEquals(25, stages.size)
        assertEquals(SyntheticFixtures.SLEEP_START, night.startTimestamp)
        assertEquals(SyntheticFixtures.SLEEP_END, night.endTimestamp)
        assertTrue("night should last about 7.5 hours", night.durationSeconds in 7 * 3600..8 * 3600)
        assertEquals(SyntheticFixtures.SLEEP_SCORE, night.score)
        assertEquals(50, night.restlessMoments)
        assertEquals(50.0, night.avgHrvMs!!, 1e-9)
        assertTrue(night.deepSeconds > 0 && night.lightSeconds > 0 && night.remSeconds > 0)
        assertEquals(night.durationSeconds.toLong(), (night.deepSeconds + night.lightSeconds + night.remSeconds + night.awakeSeconds).toLong())
    }

    @Test
    fun metricsFilesBecomeDailyMetrics() {
        val all = listOf("METRICS_F85H0122.fit", "METRICS_G9FM3212.fit", "METRICS_G9FM3213.fit", "METRICS_G9G80119.fit", "METRICS_G9GL2318.fit")
            .flatMap { rows.metrics(FitDecoder.decode(fixture(it))) }
        println("metrics=${all.map { it.type.name + "=" + it.value }}")
        assertTrue("at least the recovery metric is expected", all.isNotEmpty())
        assertEquals(listOf(MetricType.RECOVERY_MIN to 1.0), all.map { it.type to it.value })
    }
}
