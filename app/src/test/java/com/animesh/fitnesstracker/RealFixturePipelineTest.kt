package com.animesh.fitnesstracker

import com.animesh.fitnesstracker.garmin.fit.FitDecoder
import com.animesh.fitnesstracker.garmin.fitimport.FitRows
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the real decoder output from the Forerunner 570 fixtures through the row conversion, so the
 * decoder (Milestone 18) and the importer's pure part (Milestone 19) are proven to agree on real data.
 */
class RealFixturePipelineTest {
    private val zone = ZoneOffset.ofHours(-7) // the fixtures were recorded at UTC-7 (daily_sleep tz offsets)
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
        assertTrue("steps should match the watch's ~7,300 walking steps, got $steps", steps in 5_000..12_000)
        assertTrue("most minutes should carry a heart rate", withHr > out.minutes.size / 2)
        assertTrue(out.minutes.all { it.steps >= 0 })
        assertTrue(out.minutes.all { (it.heartRate ?: 60) in 30..220 })
        assertTrue(out.stress.size > 1000)
        assertTrue(out.stress.all { (it.bodyBattery ?: 50) in 0..100 })
        assertTrue(out.stress.all { (it.stress ?: 0) in 0..100 })
        assertEquals(1, out.restingHr.size)
    }

    @Test
    fun sleepFileBecomesOneNightWithStages() {
        val fit = FitDecoder.decode(fixture("SLEEP_G9G80120.fit"))
        val bounds = rows.sleepBounds(fit)
        assertNotNull("sleep file should yield bounds", bounds)
        val stages = rows.sleepStages(fit, bounds!!)
        val hrv = rows.hrv(FitDecoder.decode(fixture("HRV_G9G80118.fit"))).summaries.firstOrNull()
        val night = rows.sleepNight(fit, bounds, stages, prior = null, hrv = hrv)
        println("night=$night stages=${stages.size}")
        assertTrue(stages.size in 10..60)
        assertTrue("night should last 4 to 12 hours", night.durationSeconds in 4 * 3600..12 * 3600)
        assertTrue((night.score ?: 50) in 0..100)
        assertTrue(night.deepSeconds + night.lightSeconds + night.remSeconds > 0)
    }

    @Test
    fun metricsFilesBecomeDailyMetrics() {
        val all = listOf("METRICS_F85H0122.fit", "METRICS_G9FM3212.fit", "METRICS_G9FM3213.fit", "METRICS_G9G80119.fit", "METRICS_G9GL2318.fit")
            .flatMap { rows.metrics(FitDecoder.decode(fixture(it))) }
        println("metrics=${all.map { it.type.name + "=" + it.value }}")
        assertTrue("at least the recovery metric is expected", all.isNotEmpty())
    }
}
