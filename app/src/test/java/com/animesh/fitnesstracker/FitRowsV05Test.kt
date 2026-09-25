package com.animesh.fitnesstracker

import com.animesh.fitnesstracker.data.model.BodyBatteryKind
import com.animesh.fitnesstracker.data.model.MetricType
import com.animesh.fitnesstracker.data.model.SleepNight
import com.animesh.fitnesstracker.garmin.fit.BodyBatteryEventRec
import com.animesh.fitnesstracker.garmin.fit.DailySleepRec
import com.animesh.fitnesstracker.garmin.fit.DecodedFit
import com.animesh.fitnesstracker.garmin.fit.EventRec
import com.animesh.fitnesstracker.garmin.fit.FileIdRec
import com.animesh.fitnesstracker.garmin.fit.MaxMetRec
import com.animesh.fitnesstracker.garmin.fit.MonitoringRec
import com.animesh.fitnesstracker.garmin.fit.PhysiologicalMetricsRec
import com.animesh.fitnesstracker.garmin.fit.SessionRec
import com.animesh.fitnesstracker.garmin.fit.SkinTempRec
import com.animesh.fitnesstracker.garmin.fit.SleepDemandRec
import com.animesh.fitnesstracker.garmin.fit.SleepStageRec
import com.animesh.fitnesstracker.garmin.fit.SleepStatsRec
import com.animesh.fitnesstracker.garmin.fitimport.FitRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Version 0.5 rows: floors, Body Battery events, the sleep score breakdown, Sleep Coach and activity benefit. */
class FitRowsV05Test {
    private val f = HealthFixtures
    private val rows = FitRows(f.ZONE)

    private fun fileId(type: Int, created: Long = f.at("21:22")) = FileIdRec(typeNum = type, timeCreated = created)

    private fun mon(ts: Long, steps: Long? = null, ascent: Double? = null, descent: Double? = null) =
        MonitoringRec(ts, null, steps, null, null, if (steps != null) 6 else null, null, null, null, ascentM = ascent, descentM = descent)

    @Test
    fun ascentRecordsAddUpPerMinuteAndDoNotAccumulateAcrossMinutes() {
        val fit = DecodedFit(
            fileId(32),
            monitoring = listOf(
                mon(f.at("07:00"), steps = 100),
                mon(f.at("07:00"), ascent = 3.783, descent = 0.0),
                mon(f.at("07:01"), steps = 130),
                mon(f.at("07:02"), ascent = 2.312, descent = 1.5),
                mon(f.at("07:02"), ascent = 1.0, descent = 0.0)
            )
        )
        val m = rows.monitoring(fit).minutes
        assertEquals(listOf(3.783, 0.0, 3.312), m.map { it.ascentM })
        assertEquals(listOf(0.0, 0.0, 1.5), m.map { it.descentM })
        assertEquals("the climb is on the minute row of its record, the row before it", f.at("06:59"), m[0].timestamp)
    }

    @Test
    fun bodyBatteryEventsBecomeRowsKeyedByStartAndKind() {
        val start = f.at(f.WED.minusDays(1), "23:41")
        val end = f.at("06:53")
        val fit = DecodedFit(
            fileId(32),
            bodyBatteryEvents = listOf(
                BodyBatteryEventRec(start, 4, 432, 51, end),
                BodyBatteryEventRec(f.at("07:05"), 0, 58, -10, null),
                BodyBatteryEventRec(f.at("13:00"), 3, 32, 0, f.at("13:32")),
                BodyBatteryEventRec(f.at("15:00"), 9, null, -3, null),
                BodyBatteryEventRec(start, 4, 432, 51, end)
            )
        )
        val events = rows.monitoring(fit).bodyBatteryEvents
        assertEquals(3, events.size)
        val night = events[0]
        assertEquals(BodyBatteryKind.SLEEP, night.kind)
        assertEquals(4, night.kindRaw)
        assertEquals(51, night.delta)
        assertEquals(432, night.minutes)
        assertEquals(end, night.endTimestamp)
        assertEquals("sleep belongs to the morning it ended on, like the night itself", f.WED_DAY, night.epochDay)
        assertTrue(night.charged)
        val walk = events[1]
        assertEquals("other events belong to the day they started on", f.WED_DAY, walk.epochDay)
        assertEquals(BodyBatteryKind.ACTIVITY, walk.kind)
        assertEquals("no end field: start plus duration", f.at("07:05") + 58 * 60, walk.endTimestamp)
        assertEquals(BodyBatteryKind.UNMEASURED, events[2].kind)
        assertTrue(events.none { it.kindRaw == 9 })
    }

    @Test
    fun sleepNightCarriesTheScoreBreakdownAndKeepsItWhenAFileOmitsIt() {
        val start = f.NIGHT_START
        val end = f.at("06:53")
        val stats = SleepStatsRec(
            timestamp = end, overallSleepScore = 81, combinedAwakeScore = 72, awakeTimeScore = 70, awakeningsCountScore = 74, deepSleepScore = 74,
            lightSleepScore = 78, remSleepScore = 86, sleepDurationScore = 89, sleepQualityScore = 84, sleepRecoveryScore = 100,
            sleepRestlessnessScore = 71, awakeningsCount = 2, interruptionsScore = 72, averageStressDuringSleep = 8.67
        )
        val fit = DecodedFit(
            fileId(49, end), sleepStages = listOf(SleepStageRec(end, 2)), sleepStats = listOf(stats),
            events = listOf(EventRec(start, 74, 0, null), EventRec(end, 74, 1, null))
        )
        val bounds = rows.sleepBounds(fit)!!
        val night = rows.sleepNight(fit, bounds, rows.sleepStages(fit, bounds), prior = null, hrv = null)
        assertEquals(81, night.score)
        assertEquals(70, night.awakeScore)
        assertEquals(74, night.awakeningsScore)
        assertEquals(74, night.deepScore)
        assertEquals(78, night.lightScore)
        assertEquals(86, night.remScore)
        assertEquals(89, night.durationScore)
        assertEquals(84, night.qualityScore)
        assertEquals(100, night.recoveryScore)
        assertEquals(71, night.restlessnessScore)
        assertEquals(72, night.interruptionsScore)
        assertEquals(2, night.awakeningsCount)
        assertEquals(8.67, night.avgStressDuringSleep!!, 1e-9)
        assertTrue(night.hasScoreBreakdown)
        assertNull("the metrics file fills these later", night.bodyBatteryStart)
        assertNull(night.sleepNeedMin)

        // A monitoring file that only carries the sleep end event keeps the prior night's breakdown and metrics values.
        val prior = night.copy(bodyBatteryStart = 41, bodyBatteryEnd = 92, sleepNeedMin = 520, sleepBaselineMin = 480)
        val monitor = DecodedFit(fileId(32), events = listOf(EventRec(start, 74, 0, null), EventRec(end, 74, 1, null)))
        val again = rows.sleepNight(monitor, bounds, rows.sleepStages(fit, bounds), prior, null)
        assertEquals(prior.deepScore, again.deepScore)
        assertEquals(8.67, again.avgStressDuringSleep!!, 1e-9)
        assertEquals(41, again.bodyBatteryStart)
        assertEquals(51, again.bodyBatteryGain)
        assertEquals(520, again.sleepNeedMin)
        assertEquals(480, again.sleepBaselineMin)
    }

    @Test
    fun metricsCarrySleepNeedBodyBatteryAndFitnessAge() {
        val morning = f.at("06:40")
        val nightEnd = f.at("06:32")
        val fit = DecodedFit(
            fileId(44, morning),
            sleepDemand = listOf(SleepDemandRec(morning, 480, 520)),
            dailySleep = listOf(DailySleepRec(morning, 83, 960, nightEnd - 7 * 3600, nightEnd, -420, -420, 41, 79)),
            maxMet = listOf(MaxMetRec(morning, 48.0, 3, 1, 0, fitnessAge = 29))
        )
        val m = rows.metrics(fit)
        fun value(type: MetricType) = m.single { it.type == type && it.epochDay == f.WED_DAY }
        assertEquals(520.0, value(MetricType.SLEEP_NEED).value, 0.0)
        assertEquals(480L, value(MetricType.SLEEP_NEED).extra)
        assertEquals(79.0, value(MetricType.SLEEP_BODY_BATTERY).value, 0.0)
        assertEquals(41L, value(MetricType.SLEEP_BODY_BATTERY).extra)
        assertEquals(29.0, value(MetricType.FITNESS_AGE).value, 0.0)
        assertEquals(48.0, value(MetricType.VO2MAX).value, 0.0)
        assertEquals(setOf(f.WED_DAY, f.WED_DAY + 1), rows.nightsTouchedByMetrics(m))

        val noValues = rows.metrics(DecodedFit(fileId(44, morning), sleepDemand = listOf(SleepDemandRec(morning, 480, null)), dailySleep = listOf(DailySleepRec(morning, null, null, null, null, null, null, null, 127))))
        assertTrue("no demand and an out of range Body Battery yield nothing", noValues.isEmpty())
    }

    @Test
    fun activityKeepsPerformanceConditionAndBenefit() {
        val start = f.at("18:10")
        val fit = DecodedFit(
            fileId(4, start - 5),
            sessions = listOf(SessionRec(start + 3600, start, 11, 0, null, 3600.0, 3500.0, 6100.0, 312, null, 96, 121)),
            physiologicalMetrics = listOf(PhysiologicalMetricsRec(start + 3600, 1.8, 0.0, null, 360, null, 96, endingPerformanceCondition = -2, primaryBenefit = 1))
        )
        val a = rows.activity(fit, "a.fit")!!.activity
        assertEquals(-2, a.performanceCondition)
        assertEquals(1, a.primaryBenefit)
        val bare = rows.activity(DecodedFit(fileId(4, start), sessions = listOf(SessionRec(start + 60, start, 10, 20, null, 60.0, 60.0, null, 5, null, 90, 100))), "b.fit")!!.activity
        assertNull(bare.performanceCondition)
        assertNull(bare.primaryBenefit)
    }

    @Test
    fun skinTemperatureBecomesADailyMetricOnlyWithADeviation() {
        val end = f.at("06:41")
        val local = end + 5 * 3600 + 30 * 60 // Asia/Kolkata wall clock as a local timestamp
        val fit = DecodedFit(
            fileId(73, end + 10),
            skinTemp = listOf(
                SkinTempRec(end, local, -0.35, 0.1, 21, -0.5),
                SkinTempRec(end + 60, local + 60, -0.3, 0.1, 21, -0.4),
                SkinTempRec(end + 86_400, null, null, null, 22, null)
            )
        )
        val m = rows.skinTemp(fit)
        assertEquals(1, m.size)
        val row = m.single()
        assertEquals(MetricType.SKIN_TEMP, row.type)
        assertEquals("the latest record of the day wins", -0.3, row.value, 0.0)
        assertEquals(21L, row.extra)
        assertEquals("day from the local timestamp", Math.floorDiv(local, 86_400L), row.epochDay)
        assertEquals(f.WED_DAY, row.epochDay)
        assertEquals(setOf(f.WED_DAY), rows.nightsTouchedByMetrics(m))
        assertTrue(rows.skinTemp(DecodedFit(fileId(73), skinTemp = listOf(SkinTempRec(end, local, null, null, 1, null)))).isEmpty())
        val byTimestamp = rows.skinTemp(DecodedFit(fileId(73), skinTemp = listOf(SkinTempRec(end, null, 0.2, null, 30, null)))).single()
        assertEquals("without a wall clock the record's own timestamp names the day", f.WED_DAY, byTimestamp.epochDay)
    }

    @Test
    fun sleepNightDefaultsAreNullForOlderRows() {
        val night = SleepNight(epochDay = f.WED_DAY, startTimestamp = f.NIGHT_START, endTimestamp = f.at("06:53"))
        assertNull(night.bodyBatteryGain)
        assertTrue(!night.hasScoreBreakdown)
    }
}
