package com.animesh.fitnesstracker

import com.animesh.fitnesstracker.data.model.ActivityKind
import com.animesh.fitnesstracker.data.model.MetricType
import com.animesh.fitnesstracker.data.model.SleepNight
import com.animesh.fitnesstracker.data.model.SleepStage
import com.animesh.fitnesstracker.garmin.fit.DecodedFit
import com.animesh.fitnesstracker.garmin.fit.EnduranceScoreRec
import com.animesh.fitnesstracker.garmin.fit.EventRec
import com.animesh.fitnesstracker.garmin.fit.FileIdRec
import com.animesh.fitnesstracker.garmin.fit.FunctionalMetricsRec
import com.animesh.fitnesstracker.garmin.fit.HillScoreRec
import com.animesh.fitnesstracker.garmin.fit.HrvSummaryRec
import com.animesh.fitnesstracker.garmin.fit.HrvValueRec
import com.animesh.fitnesstracker.garmin.fit.LapRec
import com.animesh.fitnesstracker.garmin.fit.MaxMetRec
import com.animesh.fitnesstracker.garmin.fit.MonitoringInfoRec
import com.animesh.fitnesstracker.garmin.fit.MonitoringRec
import com.animesh.fitnesstracker.garmin.fit.NapRec
import com.animesh.fitnesstracker.garmin.fit.PhysiologicalMetricsRec
import com.animesh.fitnesstracker.garmin.fit.RacePredictionRec
import com.animesh.fitnesstracker.garmin.fit.RecordRec
import com.animesh.fitnesstracker.garmin.fit.RecoveryRec
import com.animesh.fitnesstracker.garmin.fit.RestingHrRec
import com.animesh.fitnesstracker.garmin.fit.RestlessMomentsRec
import com.animesh.fitnesstracker.garmin.fit.SessionRec
import com.animesh.fitnesstracker.garmin.fit.SleepStageRec
import com.animesh.fitnesstracker.garmin.fit.SleepStatsRec
import com.animesh.fitnesstracker.garmin.fit.StressRec
import com.animesh.fitnesstracker.garmin.fit.TimeInZoneRec
import com.animesh.fitnesstracker.garmin.fit.TrainingLoadRec
import com.animesh.fitnesstracker.garmin.fit.TrainingReadinessRec
import com.animesh.fitnesstracker.garmin.fit.WorkoutRec
import com.animesh.fitnesstracker.garmin.fitimport.DayCounters
import com.animesh.fitnesstracker.garmin.fitimport.FitRows
import com.animesh.fitnesstracker.garmin.fitimport.SleepBounds
import com.animesh.fitnesstracker.garmin.fitimport.SportMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FitRowsTest {
    private val f = HealthFixtures
    private val rows = FitRows(f.ZONE)

    private fun fileId(type: Int, created: Long = f.at("21:22")) = FileIdRec(typeNum = type, timeCreated = created)

    private fun mon(
        ts: Long, hr: Int? = null, steps: Long? = null, dist: Double? = null, kcal: Int? = null, type: Int? = null,
        intensity: Int? = null, moderate: Int? = null, vigorous: Int? = null
    ) = MonitoringRec(ts, hr, steps, dist, kcal, type, intensity, moderate, vigorous)

    private fun monitoring(vararg recs: MonitoringRec) = DecodedFit(fileId = fileId(32), monitoring = recs.toList())

    // Monitoring

    @Test
    fun cumulativeCountersBecomePerMinuteDeltasShiftedBackOneMinute() {
        val fit = monitoring(
            mon(f.at("07:00"), steps = 100, dist = 80.0, kcal = 5, type = 6, hr = 70),
            mon(f.at("07:01"), steps = 130, dist = 104.0, kcal = 6, type = 6),
            mon(f.at("07:02"), hr = 72),
            mon(f.at("07:03"), steps = 180, dist = 144.0, kcal = 9, type = 6, intensity = 3)
        )
        val m = rows.monitoring(fit).minutes
        assertEquals(listOf(f.at("06:59"), f.at("07:00"), f.at("07:01"), f.at("07:02")), m.map { it.timestamp })
        assertEquals(listOf(100, 30, 0, 50), m.map { it.steps })
        assertEquals(listOf(80.0, 24.0, 0.0, 40.0), m.map { it.distanceM })
        assertEquals(listOf(5, 1, 0, 3), m.map { it.activeKcal })
        assertEquals(listOf(70, null, 72, null), m.map { it.heartRate })
        assertEquals(listOf(6, 6, 6, 6), m.map { it.activityKind })
        assertEquals(3, m.last().intensity)
        assertTrue(m.all { it.worn && it.epochDay == f.WED_DAY })
        assertEquals(f.at("06:59"), rows.firstMinuteTimestamp(fit))
        assertNull(rows.firstMinuteTimestamp(DecodedFit(fileId(32))))
    }

    @Test
    fun whatEarlierFilesStoredForTheDayIsSubtractedFromTheFirstMinute() {
        val fit = monitoring(mon(f.at("07:00"), steps = 100, dist = 80.0, kcal = 5, type = 6), mon(f.at("07:01"), steps = 130, dist = 104.0, kcal = 6, type = 6))
        val m = rows.monitoring(fit, DayCounters(60, 50.0, 2)).minutes
        assertEquals(40, m[0].steps)
        assertEquals(30.0, m[0].distanceM, 0.0)
        assertEquals(3, m[0].activeKcal)
        assertEquals(30, m[1].steps)
        val clamped = rows.monitoring(fit, DayCounters(500, 500.0, 50)).minutes
        assertEquals(0, clamped[0].steps)
        assertEquals(0.0, clamped[0].distanceM, 0.0)
        assertEquals(0, clamped[0].activeKcal)
    }

    @Test
    fun activityTypesKeepSeparateCountersAndShortGapsAreFilledWorn() {
        val fit = monitoring(
            mon(f.at("07:00"), steps = 100, type = 6),
            mon(f.at("07:05"), steps = 50, type = 1),
            mon(f.at("07:06"), steps = 120, type = 6)
        )
        val m = rows.monitoring(fit).minutes
        assertEquals(7, m.size)
        assertEquals(170, m.sumOf { it.steps })
        assertEquals(100, m[0].steps)
        assertEquals(listOf(f.at("07:00"), f.at("07:01"), f.at("07:02"), f.at("07:03")), m.subList(1, 5).map { it.timestamp })
        assertTrue(m.subList(1, 5).all { it.steps == 0 && it.worn && it.activityKind == 6 })
        assertEquals(50, m[5].steps)
        assertEquals(1, m[5].activityKind)
        assertEquals(20, m[6].steps)
    }

    @Test
    fun countersRestartAtLocalMidnight() {
        val thu = f.WED.plusDays(1)
        val fit = monitoring(
            mon(f.at("23:59"), steps = 5000, type = 6),
            mon(f.at(thu, "00:00"), steps = 5000, type = 6),
            mon(f.at(thu, "00:01"), steps = 5, type = 6),
            mon(f.at(thu, "00:02"), steps = 12, type = 6)
        )
        val m = rows.monitoring(fit).minutes
        assertEquals(listOf(5000, 0, 5, 7), m.map { it.steps })
        assertEquals(listOf(f.WED_DAY, f.WED_DAY, f.WED_DAY + 1, f.WED_DAY + 1), m.map { it.epochDay })
        assertEquals(f.at("23:58"), m[0].timestamp)
        assertEquals(f.at(thu, "00:00"), m[2].timestamp)
    }

    @Test
    fun gapsLongerThanTenMinutesAreNotWornAndGapsLongerThanADayAreNotFilled() {
        val fit = monitoring(
            mon(f.at("07:00"), steps = 10, type = 6),
            mon(f.at("07:05"), steps = 20, type = 6),
            mon(f.at("07:30"), steps = 30, type = 6),
            mon(f.at(f.WED.plusDays(2), "07:30"), steps = 30, type = 6)
        )
        val m = rows.monitoring(fit).minutes
        assertEquals(32, m.size)
        assertEquals(4, m.subList(1, 5).count { it.worn })
        val long = m.subList(6, 30)
        assertEquals(24, long.size)
        assertTrue(long.all { !it.worn && it.steps == 0 && it.activityKind == 0 })
        assertEquals(f.at("07:05"), long.first().timestamp)
        assertEquals(f.at("07:28"), long.last().timestamp)
        assertTrue(m[30].worn)
        assertEquals(f.at("07:29"), m[30].timestamp)
        assertEquals(f.at(f.WED.plusDays(2), "07:29"), m[31].timestamp)
        assertEquals(30, m[31].steps)
    }

    @Test
    fun stressDropsUnmeasurableValuesButKeepsBodyBattery() {
        val t = f.at("10:00")
        val fit = DecodedFit(fileId(32), stress = listOf(StressRec(t, -1, 60), StressRec(t + 180, -2, null), StressRec(t + 360, 30, 70), StressRec(t + 360, 31, 71)))
        val s = rows.monitoring(fit).stress
        assertEquals(2, s.size)
        assertNull(s[0].stress)
        assertEquals(60, s[0].bodyBattery)
        assertEquals(30, s[1].stress)
        assertEquals(70, s[1].bodyBattery)
    }

    @Test
    fun restingHeartRatePrefersTheCurrentDayValueOncePerDay() {
        val t = f.at("06:00")
        val fit = DecodedFit(
            fileId(32),
            restingHr = listOf(RestingHrRec(t, 56, 54), RestingHrRec(t + 3600, 57, null), RestingHrRec(f.at(f.WED.plusDays(1), "06:00"), 58, null), RestingHrRec(null, 0, 0))
        )
        val r = rows.monitoring(fit).restingHr.sortedBy { it.epochDay }
        assertEquals(2, r.size)
        assertEquals(54, r[0].bpm)
        assertEquals(f.WED_DAY, r[0].epochDay)
        assertEquals(58, r[1].bpm)
    }

    @Test
    fun intensityMinutesRmrSpo2AndRespirationPassThrough() {
        val t = f.at("17:00")
        val fit = DecodedFit(
            fileId(32),
            monitoring = listOf(mon(t, moderate = 1), mon(t, vigorous = 1), mon(t + 60, moderate = 0, vigorous = 0)),
            monitoringInfo = listOf(MonitoringInfoRec(f.at("00:00"), 1650)),
            spo2 = listOf(com.animesh.fitnesstracker.garmin.fit.Spo2Rec(t, 96, 3, 2), com.animesh.fitnesstracker.garmin.fit.Spo2Rec(t + 60, 0, 3, null)),
            respiration = listOf(com.animesh.fitnesstracker.garmin.fit.RespirationRec(t, 14.2), com.animesh.fitnesstracker.garmin.fit.RespirationRec(t + 60, -1.0))
        )
        val m = rows.monitoring(fit)
        assertEquals(1, m.intensity.size)
        assertEquals(1, m.intensity[0].moderate)
        assertEquals(1, m.intensity[0].vigorous)
        assertEquals(3, m.intensity[0].weighted)
        assertEquals(listOf(MetricType.RMR), m.metrics.map { it.type })
        assertEquals(1650.0, m.metrics[0].value, 0.0)
        assertEquals(f.WED_DAY, m.metrics[0].epochDay)
        assertEquals(1, m.spo2.size)
        assertEquals(96, m.spo2[0].percent)
        assertEquals(1, m.respiration.size)
        assertEquals(14.2, m.respiration[0].breathsPerMinute, 0.0)
    }

    @Test
    fun sleepWindowsPairEvent74StartsWithEnds() {
        val start = f.at(f.WED.minusDays(1), "23:41")
        val end = f.at("06:53")
        val events = listOf(
            EventRec(start - 7200, 0, 0, null), EventRec(start - 3600, 74, 0, null), EventRec(start, 74, 0, null),
            EventRec(end, 74, 1, null), EventRec(end + 3600, 74, 1, null)
        )
        val w = rows.sleepWindows(events)
        assertEquals(1, w.size)
        assertEquals(start, w[0].start)
        assertEquals(end, w[0].end)
        assertEquals(w, rows.monitoring(DecodedFit(fileId(32), events = events)).sleepWindows)
    }

    // Sleep

    private fun designRecords(): List<SleepStageRec> = f.designStages().map { SleepStageRec(it.endTimestamp, it.stage) }
    private val nightEnd = f.designStages().last().endTimestamp

    private fun sleepFit(withEvents: Boolean = true, extraStages: List<SleepStageRec> = emptyList(), naps: List<NapRec> = emptyList()) = DecodedFit(
        fileId(49, created = nightEnd),
        events = if (withEvents) listOf(EventRec(f.NIGHT_START, 74, 0, null), EventRec(nightEnd, 74, 1, null)) else emptyList(),
        sleepStages = designRecords() + extraStages,
        sleepStats = listOf(SleepStatsRec(nightEnd, 81)),
        restlessMoments = listOf(RestlessMomentsRec(nightEnd, 12)),
        naps = naps
    )

    @Test
    fun sleepStagesUseUpperBoundTimestampsAndTheEventStart() {
        val fit = sleepFit()
        val bounds = rows.sleepBounds(fit)!!
        assertTrue(bounds.fromEvent)
        assertEquals(f.NIGHT_START, bounds.start)
        assertEquals(nightEnd, bounds.end)
        val stages = rows.sleepStages(fit, bounds)
        assertEquals(f.designStages(), stages)
        assertEquals(f.NIGHT_START, stages.first().startTimestamp)
        assertEquals(4 * 60, stages.first().seconds)
        assertEquals(SleepStage.AWAKE, stages.first().stage)
        assertTrue(stages.all { it.nightEpochDay == f.WED_DAY })

        val night = rows.sleepNight(fit, bounds, stages, prior = null, hrv = null)
        assertEquals(f.WED_DAY, night.epochDay)
        assertEquals(7 * 3600 + 12 * 60, night.totalSeconds)
        assertEquals(85 * 60, night.deepSeconds)
        assertEquals(81, night.score)
        assertEquals(12, night.restlessMoments)
        assertEquals(SleepNight.SOURCE_EVENT, night.source)
        assertNull(night.avgHrvMs)
    }

    @Test
    fun withoutEventsTheNightRunsFromTheFirstToTheLastStage() {
        val fit = sleepFit(withEvents = false)
        val bounds = rows.sleepBounds(fit)!!
        assertFalse(bounds.fromEvent)
        assertEquals(f.NIGHT_START + 4 * 60, bounds.start)
        assertEquals(nightEnd, bounds.end)
        val stages = rows.sleepStages(fit, bounds)
        assertEquals(16, stages.size)
        assertEquals(0, stages.first().seconds)
        val night = rows.sleepNight(fit, bounds, stages, null, null)
        assertEquals(SleepNight.SOURCE_STAGES, night.source)
        assertEquals(15 * 60, night.awakeSeconds)
        assertEquals(7 * 3600 + 8 * 60, night.totalSeconds)
    }

    @Test
    fun napStagesStayOutOfTheNightAndLongGapsStartAFreshRun() {
        val napStart = f.at("14:00")
        val napEnd = f.at("14:30")
        val fit = sleepFit(
            withEvents = false,
            extraStages = listOf(
                SleepStageRec(f.at("14:10"), SleepStage.LIGHT), SleepStageRec(napEnd, SleepStage.AWAKE),
                SleepStageRec(f.at("20:00"), SleepStage.LIGHT), SleepStageRec(f.at("20:30"), SleepStage.DEEP)
            ),
            naps = listOf(NapRec(napStart, napEnd))
        )
        val bounds = rows.sleepBounds(fit)!!
        val stages = rows.sleepStages(fit, bounds)
        // 16 night stages, the two nap stages dropped, the 20:00 record only opens a new run (zero length, dropped), 20:30 kept.
        assertEquals(17, stages.size)
        assertTrue(stages.none { it.endTimestamp in (napStart + 1)..napEnd })
        assertTrue(stages.none { it.endTimestamp == f.at("20:00") })
        val evening = stages.last()
        assertEquals(f.at("20:00"), evening.startTimestamp)
        assertEquals(f.at("20:30"), evening.endTimestamp)
        assertEquals(SleepStage.DEEP, evening.stage)
    }

    @Test
    fun aNightAlreadyBoundedByEventsKeepsThoseBoundsAndOvernightAverages() {
        val prior = SleepNight(
            epochDay = f.WED_DAY, startTimestamp = f.NIGHT_START, endTimestamp = nightEnd, source = SleepNight.SOURCE_EVENT,
            avgRespiration = 14.2, avgSpo2 = 96.0, lowestHr = 49, score = 70
        )
        val fit = sleepFit(withEvents = false)
        val bounds = rows.sleepBounds(fit)!!
        val stages = rows.sleepStages(fit, bounds)
        val hrv = rows.hrv(DecodedFit(fileId(68), hrvSummary = listOf(HrvSummaryRec(nightEnd + 120, 60.0, 58.0, 75.0, 52.0, 52.0, 66.0, 4)))).summaries.single()
        val night = rows.sleepNight(fit, bounds, stages, prior, hrv)
        assertEquals(f.NIGHT_START, night.startTimestamp)
        assertEquals(SleepNight.SOURCE_EVENT, night.source)
        assertEquals(14.2, night.avgRespiration!!, 0.0)
        assertEquals(96.0, night.avgSpo2!!, 0.0)
        assertEquals(49, night.lowestHr)
        assertEquals(81, night.score)
        assertEquals(58.0, night.avgHrvMs!!, 0.0)
        assertEquals(4, night.hrvStatus)
        assertEquals(85 * 60, night.deepSeconds)
    }

    @Test
    fun noBoundsWhenAFileHasNoSleep() {
        assertNull(rows.sleepBounds(DecodedFit(fileId(49))))
        assertNull(rows.sleepBounds(DecodedFit(fileId(49), sleepStages = listOf(SleepStageRec(f.at("03:00"), SleepStage.UNMEASURABLE)))))
        val eventOnly = rows.sleepNight(DecodedFit(fileId(32)), SleepBounds(f.NIGHT_START, nightEnd, true), emptyList(), null, null)
        assertEquals(0, eventOnly.totalSeconds)
        assertEquals(f.WED_DAY, eventOnly.epochDay)
    }

    // HRV and metrics

    @Test
    fun hrvSummaryIsKeyedByTheMorningItWasWritten() {
        val morning = f.at("06:55")
        val fit = DecodedFit(
            fileId(68),
            hrvSummary = listOf(HrvSummaryRec(morning - 86400, 59.0, 49.0, 70.0, 52.0, 52.0, 66.0, 2), HrvSummaryRec(morning, 60.0, 58.0, 75.0, 52.0, 52.0, 66.0, 4)),
            hrvValues = listOf(HrvValueRec(morning - 3600, 61.5), HrvValueRec(morning - 3300, 0.0), HrvValueRec(morning - 3000, 57.0))
        )
        val h = rows.hrv(fit)
        assertEquals(2, h.summaries.size)
        val today = h.summaries.first { it.epochDay == f.WED_DAY }
        assertEquals(58.0, today.lastNightAvg!!, 0.0)
        assertEquals(4, today.status)
        assertEquals("Balanced", today.statusLabel)
        assertEquals(52.0, today.baselineBalancedLower!!, 0.0)
        assertEquals(66.0, today.baselineBalancedUpper!!, 0.0)
        assertEquals(listOf(61.5, 57.0), h.values.map { it.valueMs })
    }

    @Test
    fun dailyMetricsKeepTheLatestValuePerDayAndZeroRecovery() {
        val t = f.at("08:00")
        val fit = DecodedFit(
            fileId(44),
            trainingLoad = listOf(TrainingLoadRec(t, 120, 100, 1.2)),
            racePredictions = listOf(RacePredictionRec(t, 1500, 3200, 7200, 15000)),
            hillScores = listOf(HillScoreRec(t, 55, 50, 60, 3)),
            enduranceScores = listOf(EnduranceScoreRec(t, 6100, 4)),
            trainingReadiness = listOf(TrainingReadinessRec(t, 72, 3)),
            functionalMetrics = listOf(FunctionalMetricsRec(t, 210, 165, null, 172)),
            recovery = listOf(RecoveryRec(t, 0)),
            maxMet = listOf(MaxMetRec(t, 48.2, 3, 1, 0), MaxMetRec(t + 3600, 48.5, 3, 1, 0), MaxMetRec(t - 86400, 47.9, 2, 1, 0))
        )
        val m = rows.metrics(fit)
        fun value(type: MetricType) = m.single { it.type == type && it.epochDay == f.WED_DAY }
        assertEquals(120.0, value(MetricType.TRAINING_LOAD_ACUTE).value, 0.0)
        assertEquals(100.0, value(MetricType.TRAINING_LOAD_CHRONIC).value, 0.0)
        assertEquals(1500.0, value(MetricType.RACE_5K).value, 0.0)
        assertEquals(15000.0, value(MetricType.RACE_FULL).value, 0.0)
        assertEquals(3L, value(MetricType.HILL).extra)
        assertEquals(6100.0, value(MetricType.ENDURANCE).value, 0.0)
        assertEquals(72.0, value(MetricType.READINESS).value, 0.0)
        assertEquals(210.0, value(MetricType.FTP).value, 0.0)
        assertEquals(165L, value(MetricType.FTP).extra)
        assertEquals(172.0, value(MetricType.LTHR).value, 0.0)
        assertEquals(0.0, value(MetricType.RECOVERY_MIN).value, 0.0)
        assertEquals(48.5, value(MetricType.VO2MAX).value, 0.0)
        assertEquals(47.9, m.single { it.type == MetricType.VO2MAX && it.epochDay == f.WED_DAY - 1 }.value, 0.0)
        assertEquals(14, m.size)
    }

    // Activities

    private fun strengthFit(start: Long = f.at("18:10")): DecodedFit = DecodedFit(
        fileId(4, created = start - 5),
        sessions = listOf(
            SessionRec(
                timestamp = start + 3700, startTime = start, sport = 10, subSport = 20, sportProfileName = "Strength", totalElapsedTime = 3700.0,
                totalTimerTime = 3400.0, totalDistance = null, totalCalories = 320, avgHeartRate = 118, maxHeartRate = 158, minHeartRate = 62,
                avgCadence = null, totalAscent = 0, totalDescent = 0, totalTrainingEffect = 2.4, beginningBodyBattery = 70, endingBodyBattery = 55,
                trainingLoadPeak = 45.0
            )
        ),
        timeInZone = listOf(TimeInZoneRec(start + 3700, 18, 0, listOf(10.0, 300.0, 600.0, 900.0, 200.0, 0.0), listOf(95, 114, 133, 152, 171, 190))),
        physiologicalMetrics = listOf(PhysiologicalMetricsRec(start + 3700, 2.1, 0.5, 13.77, 720, 165, 117)),
        laps = listOf(
            LapRec(start + 1800, start, 1800.0, 1700.0, null, 110, 140, null, null, 0, 150, 1),
            LapRec(start + 3700, start + 1800, 1900.0, 1700.0, null, 125, 158, null, null, 0, 170, 1)
        ),
        records = listOf(
            RecordRec(start, 12.97, 77.59, 920.0, 0.0, 0.0, 80, null, null, 28),
            RecordRec(start + 600, null, null, null, null, null, 120, null, null, 28),
            RecordRec(start + 600, null, null, null, null, null, 121, null, null, 28),
            RecordRec(start + 3600, null, null, null, null, null, 0, null, null, 27)
        )
    )

    @Test
    fun activityFromSessionMapsSportZonesEffectsAndTrack() {
        val start = f.at("18:10")
        val a = rows.activity(strengthFit(start), "activity/2026/a.fit")!!
        val act = a.activity
        assertEquals(ActivityKind.STRENGTH, act.kind)
        assertEquals("Strength", act.name)
        assertEquals(start, act.startTimestamp)
        assertEquals(start - 5, act.fitTimeCreated)
        assertEquals(start + 3700, act.endTimestamp)
        assertEquals(3400, act.timerSeconds)
        assertEquals(3700, act.elapsedSeconds)
        assertEquals(320, act.calories)
        assertEquals(118, act.avgHr)
        assertEquals(158, act.maxHr)
        assertEquals(62, act.minHr)
        assertEquals("300,600,900,200,0", act.hrZoneSeconds)
        assertEquals(listOf(300, 600, 900, 200, 0), act.zoneSecondsList)
        assertEquals("95,114,133,152,171,190", act.hrZoneBounds)
        assertEquals(48.2, act.vo2max!!, 0.0)
        assertEquals(720, act.recoveryMinutes)
        assertEquals(2.4, act.aerobicEffect!!, 0.0)
        assertEquals(0.5, act.anaerobicEffect!!, 0.0)
        assertEquals(70, act.bodyBatteryStart)
        assertEquals(55, act.bodyBatteryEnd)
        assertEquals(45.0, act.trainingLoad!!, 0.0)
        assertEquals("activity/2026/a.fit", act.filePath)
        assertNull(act.linkedSessionId)
        assertEquals(listOf(0, 1), a.laps.map { it.index })
        assertEquals(1700, a.laps[0].timerSeconds)
        assertEquals(start + 1800, a.laps[1].startTimestamp)
        assertEquals(3, a.points.size)
        assertEquals(12.97, a.points[0].lat!!, 0.0)
        // Two records at the same second: the first wins, matching the composite key on (activity, timestamp).
        assertEquals(120, a.points[1].heartRate)
        assertNull(a.points[2].heartRate)
    }

    @Test
    fun activityNameFallsBackThroughWorkoutProfileAndKindLabel() {
        val start = f.at("07:00")
        val run = SessionRec(start + 1800, start, 1, 0, null, 1800.0, 1750.0, 5000.0, 300, avgHeartRate = 150, maxHeartRate = 170, avgSpeed = 2.78, maxSpeed = 3.5, avgCadence = 85)
        val plain = rows.activity(DecodedFit(fileId(4, start), sessions = listOf(run)), "r.fit")!!.activity
        assertEquals("Run", plain.name)
        assertEquals(ActivityKind.RUN, plain.kind)
        assertEquals(5000.0, plain.distanceM!!, 0.0)
        assertEquals(2.78, plain.avgSpeedMps!!, 0.0)
        assertEquals("", plain.hrZoneSeconds)
        assertTrue(plain.zoneSecondsList.isEmpty())
        val profile = rows.activity(DecodedFit(fileId(4, start), sessions = listOf(run.copy(sportProfileName = "Run Outdoor"))), "r.fit")!!.activity
        assertEquals("Run Outdoor", profile.name)
        val workout = rows.activity(DecodedFit(fileId(4, start), sessions = listOf(run.copy(sportProfileName = "Run Outdoor")), workouts = listOf(WorkoutRec("Tempo", 1))), "r.fit")!!.activity
        assertEquals("Tempo", workout.name)
    }

    @Test
    fun activityWithoutASessionComesFromTheTrackAndAnEmptyFileIsSkipped() {
        val start = f.at("07:00")
        val recs = listOf(RecordRec(start, null, null, null, 0.0, null, 100, null, null, null), RecordRec(start + 900, null, null, null, 2500.0, null, 130, null, null, null))
        val a = rows.activity(DecodedFit(fileId(4, start), records = recs), "t.fit")!!
        assertEquals(start, a.activity.startTimestamp)
        assertEquals(start + 900, a.activity.endTimestamp)
        assertEquals(900, a.activity.elapsedSeconds)
        assertEquals(2500.0, a.activity.distanceM!!, 0.0)
        assertEquals(ActivityKind.OTHER, a.activity.kind)
        assertEquals("Activity", a.activity.name)
        assertNull(rows.activity(DecodedFit(fileId(4, start)), "e.fit"))
    }

    @Test
    fun sportMappingFollowsTheFitProfileNumbers() {
        assertEquals(ActivityKind.RUN, SportMapping.kind(1, 0))
        assertEquals(ActivityKind.CYCLE, SportMapping.kind(2, 5))
        assertEquals(ActivityKind.SWIM, SportMapping.kind(5, 18))
        assertEquals(ActivityKind.WALK, SportMapping.kind(11, 2))
        assertEquals(ActivityKind.HIKE, SportMapping.kind(17, 0))
        assertEquals(ActivityKind.STRENGTH, SportMapping.kind(10, 20))
        assertEquals(ActivityKind.CARDIO, SportMapping.kind(10, 26))
        assertEquals(ActivityKind.CARDIO, SportMapping.kind(10, 62))
        assertEquals(ActivityKind.CARDIO, SportMapping.kind(62, 0))
        assertEquals(ActivityKind.YOGA, SportMapping.kind(10, 43))
        assertEquals(ActivityKind.YOGA, SportMapping.kind(10, 44))
        assertEquals(ActivityKind.CARDIO, SportMapping.kind(4, 0))
        assertEquals(ActivityKind.STRENGTH, SportMapping.kind(4, 20))
        assertEquals(ActivityKind.OTHER, SportMapping.kind(10, 0))
        assertEquals(ActivityKind.OTHER, SportMapping.kind(22, 0))
        assertEquals(ActivityKind.OTHER, SportMapping.kind(null, null))
    }

    @Test
    fun zoneSecondsDropTheBelowZoneEntryAndPadShortLists() {
        assertEquals(listOf(300, 600, 900, 200, 0), rows.zoneSecondsOf(listOf(10.0, 300.0, 600.0, 900.0, 200.0, 0.0)))
        assertEquals(listOf(1, 2, 3, 4, 5), rows.zoneSecondsOf(listOf(1.0, 2.0, 3.0, 4.0, 5.0)))
        assertEquals(listOf(1, 2, 3, 0, 0), rows.zoneSecondsOf(listOf(1.4, 2.0, 3.0)))
        assertTrue(rows.zoneSecondsOf(emptyList()).isEmpty())
        assertNotNull(rows.zoneSecondsOf(listOf(0.0)))
    }
}
