package com.animesh.fitnesstracker

import com.animesh.fitnesstracker.data.model.Activity
import com.animesh.fitnesstracker.data.model.ActivityKind
import com.animesh.fitnesstracker.data.model.ActivityPoint
import com.animesh.fitnesstracker.data.model.BodyBatteryEvent
import com.animesh.fitnesstracker.data.model.BodyBatteryKind
import com.animesh.fitnesstracker.data.model.HealthMinute
import com.animesh.fitnesstracker.data.model.IntensityMinute
import com.animesh.fitnesstracker.data.model.Session
import com.animesh.fitnesstracker.data.model.SessionStatus
import com.animesh.fitnesstracker.data.model.Settings
import com.animesh.fitnesstracker.data.model.SleepNight
import com.animesh.fitnesstracker.data.model.SleepStage
import com.animesh.fitnesstracker.data.model.StressSample
import com.animesh.fitnesstracker.domain.health.BodyBatteryEvents
import com.animesh.fitnesstracker.domain.health.DaySummary
import com.animesh.fitnesstracker.domain.health.Floors
import com.animesh.fitnesstracker.domain.health.HrZones
import com.animesh.fitnesstracker.domain.health.PrimaryBenefit
import com.animesh.fitnesstracker.domain.health.SleepNeeds
import com.animesh.fitnesstracker.domain.health.SleepScoreBreakdown
import com.animesh.fitnesstracker.domain.health.PaceFormat
import com.animesh.fitnesstracker.domain.health.SessionMatcher
import com.animesh.fitnesstracker.domain.health.SleepNights
import com.animesh.fitnesstracker.domain.health.StressBands
import com.animesh.fitnesstracker.domain.health.TrendMetric
import com.animesh.fitnesstracker.domain.health.TrendPeriod
import com.animesh.fitnesstracker.domain.health.Trends
import com.animesh.fitnesstracker.util.Dates
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared fixtures: the design's example week, Wednesday 16 September 2026 in India. */
object HealthFixtures {
    val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    val WED: LocalDate = LocalDate.of(2026, 9, 16)
    val WED_DAY: Long = WED.toEpochDay()

    fun at(date: LocalDate, time: String): Long = LocalDateTime.of(date, java.time.LocalTime.parse(time)).atZone(ZONE).toEpochSecond()
    fun at(time: String): Long = at(WED, time)
    fun day(ts: Long): Long = Dates.epochDayOfSeconds(ts, ZONE)

    fun minute(ts: Long, steps: Int = 0, hr: Int? = null, distanceM: Double = 0.0, kcal: Int = 0, worn: Boolean = true) =
        HealthMinute(timestamp = ts, epochDay = day(ts), steps = steps, distanceM = distanceM, activeKcal = kcal, heartRate = hr, worn = worn)

    /** Design night to Wed 16 Sep: 23:41 to 06:53, codes 0 awake, 1 REM, 2 light, 3 deep as in Sleep.dc.html. */
    val DESIGN_SEGMENTS = listOf(
        0 to 4, 2 to 22, 3 to 38, 2 to 36, 1 to 20, 2 to 30, 3 to 27, 0 to 5, 2 to 42, 1 to 28, 2 to 40, 3 to 20, 2 to 38, 1 to 42, 0 to 10, 2 to 30
    )
    val FIT_STAGE = mapOf(0 to SleepStage.AWAKE, 1 to SleepStage.REM, 2 to SleepStage.LIGHT, 3 to SleepStage.DEEP)
    val NIGHT_START: Long = at(WED.minusDays(1), "23:41")

    fun designStages(): List<SleepStage> {
        var t = NIGHT_START
        return DESIGN_SEGMENTS.map { (code, minutes) ->
            val end = t + minutes * 60L
            SleepStage(endTimestamp = end, startTimestamp = t, stage = FIT_STAGE.getValue(code), nightEpochDay = WED_DAY).also { t = end }
        }
    }
}

class DaySummaryTest {
    private val f = HealthFixtures

    private fun minutes() = listOf(
        f.minute(f.at("04:10"), hr = 54),
        f.minute(f.at("07:00"), steps = 2000, hr = 71, distanceM = 1500.0, kcal = 90),
        f.minute(f.at("08:30"), steps = 3000, hr = 95, distanceM = 2200.0, kcal = 150),
        f.minute(f.at("12:00"), steps = 1412, hr = 80, distanceM = 1000.0, kcal = 60),
        f.minute(f.at("18:00"), steps = 2000, hr = 120, distanceM = 1400.0, kcal = 112),
        f.minute(f.at("18:05"), hr = 158),
        f.minute(f.at("20:00"), steps = 500, worn = false)
    )

    private fun stress(): List<StressSample> {
        val rows = ArrayList<StressSample>()
        var t = f.at("09:00")
        fun add(stress: Int?, battery: Int? = null) { rows += StressSample(t, stress, battery); t += 180 }
        repeat(10) { add(20) }
        repeat(5) { add(40) }
        repeat(2) { add(60) }
        add(80)
        add(null, 60)
        return rows + listOf(StressSample(f.at("06:10"), 12, 88), StressSample(f.at("22:30"), 35, 45), StressSample(f.at("23:00"), 30, 47))
    }

    private fun intensityWeek() = listOf(
        IntensityMinute(f.at(f.WED.minusDays(3), "17:00"), moderate = 100),
        IntensityMinute(f.at(f.WED.minusDays(2), "17:00"), moderate = 30),
        IntensityMinute(f.at(f.WED.minusDays(1), "07:00"), vigorous = 20),
        IntensityMinute(f.at("07:30"), moderate = 24),
        IntensityMinute(f.at(f.WED.plusDays(1), "07:30"), moderate = 50)
    )

    private fun summary() = DaySummary.compute(f.WED_DAY, minutes(), stress(), restingHr = 54, intensityWeek = intensityWeek(), stepGoal = 10000, zone = f.ZONE)

    @Test
    fun stepsDistanceAndCaloriesAreSummedIncludingNotWornRows() {
        val s = summary()
        assertEquals(8_912, s.steps)
        assertEquals(6_100.0, s.distanceM, 0.001)
        assertEquals(412, s.activeKcal)
        assertEquals(0.8912f, s.stepProgress, 0.0001f)
        assertEquals(6, s.wornMinutes)
    }

    @Test
    fun designStepsOf8412ReachEightyFourPercentOfGoal() {
        val s = DaySummary.compute(f.WED_DAY, minutes().dropLast(1), emptyList(), null, emptyList(), 10_000, f.ZONE)
        assertEquals(8_412, s.steps)
        assertEquals(0.8412f, s.stepProgress, 0.0001f)
    }

    @Test
    fun heartRateRangeUsesWornMinutesWithAReading() {
        val s = summary()
        assertEquals(54, s.hrMin)
        assertEquals(158, s.hrMax!!.value)
        assertEquals("18:05", Dates.hhmm(s.hrMax!!.timestamp, f.ZONE))
        assertEquals(96, s.hrAvg)
        assertEquals(54, s.restingHr)
    }

    @Test
    fun bodyBatteryHighLowAndCurrentWithTimes() {
        val s = summary()
        assertEquals(88, s.bodyBatteryHigh!!.value)
        assertEquals("06:10", Dates.hhmm(s.bodyBatteryHigh!!.timestamp, f.ZONE))
        assertEquals(45, s.bodyBatteryLow!!.value)
        assertEquals("22:30", Dates.hhmm(s.bodyBatteryLow!!.timestamp, f.ZONE))
        assertEquals(47, s.bodyBatteryCurrent!!.value)
    }

    @Test
    fun stressAverageAndBandMinutes() {
        val s = summary()
        // 18 measured three-minute samples between 09:00 and 09:54 plus the three loose ones.
        assertEquals(StressBands(restMinutes = 33, lowMinutes = 21, mediumMinutes = 6, highMinutes = 3), s.stressBands)
        assertEquals(63, s.stressBands.totalMinutes)
        assertEquals(listOf(52, 33, 10, 5), s.stressBands.percent)
        assertEquals(32, s.stressAvg)
    }

    @Test
    fun stressBandsFollowTheWatchThresholds() {
        assertNull(DaySummary.stressBand(0))
        assertEquals(0, DaySummary.stressBand(1))
        assertEquals(0, DaySummary.stressBand(25))
        assertEquals(1, DaySummary.stressBand(26))
        assertEquals(1, DaySummary.stressBand(50))
        assertEquals(2, DaySummary.stressBand(51))
        assertEquals(2, DaySummary.stressBand(75))
        assertEquals(3, DaySummary.stressBand(76))
        assertEquals(3, DaySummary.stressBand(100))
        assertNull(DaySummary.stressBand(101))
    }

    @Test
    fun intensityMinutesCountVigorousDoubleAndResetOnMonday() {
        val s = summary()
        assertEquals(94, s.intensityMinutes)
        assertEquals(54, s.intensityModerate)
        assertEquals(20, s.intensityVigorous)
        assertEquals(150, s.intensityTarget)
        assertEquals(94f / 150f, s.intensityProgress, 0.0001f)
    }

    @Test
    fun emptyDayHasNoData() {
        val s = DaySummary.compute(f.WED_DAY, emptyList(), emptyList(), null, emptyList(), 10_000, f.ZONE)
        assertFalse(s.hasData)
        assertNull(s.hrAvg)
        assertNull(s.stressAvg)
        assertEquals(StressBands.EMPTY, s.stressBands)
        assertEquals(listOf(0, 0, 0, 0), s.stressBands.percent)
        assertEquals(0, s.intensityMinutes)
    }

    @Test
    fun effectiveMaxHeartRateFallsBackTo220MinusAgeThen190() {
        assertEquals(190, Settings().effectiveMaxHeartRate(2026))
        assertEquals(184, Settings(birthYear = 1990).effectiveMaxHeartRate(2026))
        assertEquals(178, Settings(maxHeartRate = 178, birthYear = 1990).effectiveMaxHeartRate(2026))
    }
}

class SleepNightsTest {
    private val f = HealthFixtures

    private fun night(): SleepNight {
        val totals = SleepNights.totalsFromStages(f.designStages())
        return SleepNight(
            epochDay = f.WED_DAY, startTimestamp = f.NIGHT_START, endTimestamp = f.designStages().last().endTimestamp, score = 81,
            deepSeconds = totals.deepSeconds, lightSeconds = totals.lightSeconds, remSeconds = totals.remSeconds, awakeSeconds = totals.awakeSeconds,
            restlessMoments = 12, avgHrvMs = 58.0, hrvStatus = 4
        )
    }

    @Test
    fun designNightLastsSevenHoursTwelveWithAnHourTwentyFiveDeep() {
        val n = night()
        assertEquals(7 * 3600 + 12 * 60, n.totalSeconds)
        assertEquals(85 * 60, n.deepSeconds)
        assertEquals(238 * 60, n.lightSeconds)
        assertEquals(90 * 60, n.remSeconds)
        assertEquals(19 * 60, n.awakeSeconds)
        assertEquals("06:53", Dates.hhmm(n.endTimestamp, f.ZONE))
        assertEquals("7 h 12 min", Dates.hoursMinutes(n.totalSeconds))
        assertEquals("1 h 25 min", Dates.hoursMinutes(n.deepSeconds))
        assertEquals("Balanced", com.animesh.fitnesstracker.data.model.HrvStatus.label(n.hrvStatus))
        assertEquals(58.0, n.avgHrvMs!!, 0.0)
    }

    @Test
    fun percentagesAreOfTheWholeNight() {
        val t = SleepNights.totals(night())
        assertEquals(20, t.percent(SleepStage.DEEP))
        assertEquals(55, t.percent(SleepStage.LIGHT))
        assertEquals(21, t.percent(SleepStage.REM))
        assertEquals(4, t.percent(SleepStage.AWAKE))
        assertTrue(t.hasSleep)
        assertEquals("Good", SleepNights.scoreWord(81))
        assertEquals("Fair", SleepNights.scoreWord(72))
    }

    @Test
    fun nightForPrefersTheNightEndingOnTheDayThenTheDayBefore() {
        val n = night()
        val older = n.copy(epochDay = f.WED_DAY - 3, startTimestamp = n.startTimestamp - 3 * 86400, endTimestamp = n.endTimestamp - 3 * 86400)
        assertEquals(n, SleepNights.nightFor(f.WED_DAY, listOf(older, n)))
        assertEquals(n, SleepNights.nightFor(f.WED_DAY + 1, listOf(older, n)))
        assertNull(SleepNights.nightFor(f.WED_DAY + 2, listOf(older, n)))
        assertEquals(older, SleepNights.nightFor(f.WED_DAY - 3, listOf(older, n)))
    }

    @Test
    fun hypnogramMergesNeighboursDropsUnmeasurableAndClipsToBounds() {
        val stages = f.designStages()
        val segments = SleepNights.hypnogram(stages)
        assertEquals(16, segments.size)
        assertEquals(SleepStage.AWAKE, segments.first().stage)
        assertEquals(4 * 60, segments.first().seconds)
        assertEquals(f.NIGHT_START, segments.first().startTimestamp)
        assertEquals(stages.last().endTimestamp, segments.last().endTimestamp)

        val withNoise = stages + SleepStage(stages.last().endTimestamp + 600, stages.last().endTimestamp, SleepStage.UNMEASURABLE, f.WED_DAY) +
            SleepStage(stages.last().endTimestamp + 1200, stages.last().endTimestamp + 600, SleepStage.LIGHT, f.WED_DAY)
        val merged = SleepNights.hypnogram(withNoise)
        assertEquals(17, merged.size)
        val clipped = SleepNights.hypnogram(withNoise, start = f.NIGHT_START + 120, end = stages.last().endTimestamp)
        assertEquals(16, clipped.size)
        assertEquals(2 * 60, clipped.first().seconds)

        val split = listOf(
            SleepStage(100, 0, SleepStage.LIGHT, 1), SleepStage(200, 100, SleepStage.LIGHT, 1), SleepStage(300, 200, SleepStage.DEEP, 1)
        )
        val m = SleepNights.hypnogram(split)
        assertEquals(2, m.size)
        assertEquals(200, m[0].seconds)
    }
}

class TrendsTest {
    private val f = HealthFixtures
    private val today = f.WED_DAY

    @Test
    fun sevenDayStepsAreBarsWithAverageBestAndChange() {
        val values = HashMap<Long, Double>()
        val current = listOf(8_000.0, 9_500.0, 6_200.0, 11_000.0, 7_300.0, 8_412.0, 9_100.0)
        current.forEachIndexed { i, v -> values[today - 6 + i] = v }
        (1..7).forEach { values[today - 6 - it] = 7_000.0 }
        val r = Trends.bucket(TrendMetric.STEPS, values, TrendPeriod.DAY7, today)
        assertTrue(r.bars)
        assertEquals(7, r.buckets.size)
        assertEquals(today - 6, r.buckets.first().startDay)
        assertEquals(today, r.buckets.last().endDay)
        assertEquals(current.average(), r.average!!, 0.001)
        assertEquals(3, r.bestIndex)
        assertEquals(11_000.0, r.best!!, 0.0)
        assertEquals(7_000.0, r.previousAverage!!, 0.0)
        assertEquals(current.average() - 7_000.0, r.change!!, 0.001)
        assertEquals((current.average() - 7_000.0) * 100 / 7_000.0, r.changePercent!!, 0.001)
    }

    @Test
    fun lowerIsBetterForRestingHeartRateAndLinesForTheRest() {
        val values = mapOf(today to 56.0, today - 1 to 54.0, today - 2 to 58.0)
        val r = Trends.bucket(TrendMetric.RESTING_HR, values, TrendPeriod.DAY7, today)
        assertFalse(r.bars)
        assertEquals(54.0, r.best!!, 0.0)
        assertEquals(5, r.bestIndex)
        assertNull(r.previousAverage)
        assertNull(r.change)
        assertEquals(4, r.buckets.count { it.value == null })
        assertTrue(TrendMetric.SLEEP_DURATION.bars)
        assertTrue(TrendMetric.INTENSITY_MINUTES.bars)
        assertTrue(TrendMetric.TRAINING_LOAD.bars)
        assertFalse(TrendMetric.HRV.bars)
        assertFalse(TrendMetric.STRESS.higherIsBetter)
    }

    @Test
    fun fourWeekBucketsAlignToMondayAndAverageTheirDays() {
        val bounds = Trends.bucketBounds(TrendPeriod.WEEK4, today)
        assertEquals(4, bounds.size)
        assertEquals(LocalDate.of(2026, 8, 24).toEpochDay(), bounds[0].first)
        assertEquals(LocalDate.of(2026, 8, 30).toEpochDay(), bounds[0].second)
        assertEquals(LocalDate.of(2026, 9, 14).toEpochDay(), bounds[3].first)
        assertEquals(today, bounds[3].second)
        val monday = LocalDate.of(2026, 9, 14).toEpochDay()
        val r = Trends.bucket(TrendMetric.STEPS, mapOf(monday to 8_000.0, monday + 1 to 10_000.0), TrendPeriod.WEEK4, today)
        assertEquals(9_000.0, r.buckets[3].value!!, 0.0)
        assertNull(r.buckets[0].value)
        assertEquals("24 Aug", r.buckets[0].label)
        val range = Trends.dayRange(TrendPeriod.WEEK4, today)
        assertEquals(LocalDate.of(2026, 7, 27).toEpochDay(), range.first)
        assertEquals(today, range.last)
    }

    @Test
    fun monthBucketsAreCalendarMonths() {
        val six = Trends.bucketBounds(TrendPeriod.MONTH6, today)
        assertEquals(LocalDate.of(2026, 4, 1).toEpochDay(), six[0].first)
        assertEquals(LocalDate.of(2026, 4, 30).toEpochDay(), six[0].second)
        assertEquals(LocalDate.of(2026, 9, 1).toEpochDay(), six[5].first)
        assertEquals(today, six[5].second)
        assertEquals(LocalDate.of(2025, 10, 1).toEpochDay(), Trends.dayRange(TrendPeriod.MONTH6, today).first)
        val twelve = Trends.bucketBounds(TrendPeriod.YEAR12, today)
        assertEquals(12, twelve.size)
        assertEquals(LocalDate.of(2025, 10, 1).toEpochDay(), twelve[0].first)
        assertEquals(LocalDate.of(2024, 10, 1).toEpochDay(), Trends.dayRange(TrendPeriod.YEAR12, today).first)
    }
}

class HrZonesTest {
    private fun activity(zones: String = "") = Activity(
        startTimestamp = 1_000, fitTimeCreated = 1_000, endTimestamp = 4_600, sport = 1, subSport = 0, kind = ActivityKind.RUN, name = "Run",
        timerSeconds = 3_600, elapsedSeconds = 3_600, hrZoneSeconds = zones, filePath = "activity/x.fit"
    )

    @Test
    fun boundariesAreFiftyToNinetyPercentOfMax() {
        assertEquals(listOf(95, 114, 133, 152, 171), HrZones.bounds(190))
        assertEquals(0, HrZones.zoneFor(90, 190))
        assertEquals(1, HrZones.zoneFor(95, 190))
        assertEquals(2, HrZones.zoneFor(120, 190))
        assertEquals(4, HrZones.zoneFor(160, 190))
        assertEquals(5, HrZones.zoneFor(171, 190))
        assertEquals(5, HrZones.zoneFor(200, 190))
    }

    @Test
    fun storedZonesWinOverPoints() {
        val points = listOf(ActivityPoint(1, 0, heartRate = 120), ActivityPoint(1, 10, heartRate = 120))
        assertEquals(listOf(300, 600, 900, 200, 0), HrZones.forActivity(activity("300,600,900,200,0"), points, 190))
        assertEquals(listOf(0, 10, 0, 0, 0), HrZones.forActivity(activity(""), points, 190))
        assertEquals(listOf(0, 10, 0, 0, 0), HrZones.forActivity(activity("0,0,0,0,0"), points, 190))
    }

    @Test
    fun pointsAttributeTimeToTheZoneOfTheStartingHeartRateAndCapGaps() {
        val points = listOf(
            ActivityPoint(1, 0, heartRate = 120), ActivityPoint(1, 10, heartRate = 120), ActivityPoint(1, 20, heartRate = 120),
            ActivityPoint(1, 30, heartRate = 120), ActivityPoint(1, 40, heartRate = 160), ActivityPoint(1, 50, heartRate = 160),
            ActivityPoint(1, 200, heartRate = 80), ActivityPoint(1, 210, heartRate = null), ActivityPoint(1, 220, heartRate = 175)
        )
        assertEquals(listOf(0, 40, 0, 70, 0), HrZones.fromPoints(points, 190))
        val fractions = HrZones.fractions(listOf(0, 40, 0, 70, 0))
        assertEquals(40.0 / 110, fractions[1], 0.0001)
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0, 0.0), HrZones.fractions(listOf(0, 0, 0, 0, 0)))
    }

    @Test
    fun paceAndSpeedFormatting() {
        assertEquals("5:33 /km", PaceFormat.minPerKm(3.0))
        assertEquals("6:00 /km", PaceFormat.minPerKm(1000.0 / 360))
        assertEquals("--", PaceFormat.minPerKm(0.0))
        assertEquals("--", PaceFormat.minPerKm(null))
        assertEquals("24.1 km/h", PaceFormat.kmh(6.7))
        assertEquals("5:33 /km", PaceFormat.forKind(ActivityKind.RUN, 3.0))
        assertEquals("10.8 km/h", PaceFormat.forKind(ActivityKind.CYCLE, 3.0))
        assertTrue(ActivityKind.WALK.usesPace)
        assertFalse(ActivityKind.STRENGTH.usesPace)
        assertEquals(2.5, PaceFormat.speed(5_000.0, 2_000)!!, 0.0)
        assertNull(PaceFormat.speed(null, 2_000))
    }
}

class SessionMatcherTest {
    private fun session(id: Long, start: String, end: String?, status: SessionStatus = SessionStatus.COMPLETED) = Session(
        id = id, groupId = 1, groupName = "Push", routineId = null, epochDay = HealthFixtures.WED_DAY,
        startedAt = HealthFixtures.at(start) * 1000, endedAt = end?.let { HealthFixtures.at(it) * 1000 }, status = status
    )

    private fun activity(id: Long, start: String, end: String) = Activity(
        id = id, startTimestamp = HealthFixtures.at(start), fitTimeCreated = HealthFixtures.at(start), endTimestamp = HealthFixtures.at(end),
        sport = 10, subSport = 20, kind = ActivityKind.STRENGTH, name = "Strength", timerSeconds = 3_000, elapsedSeconds = 3_600, filePath = "a.fit"
    )

    @Test
    fun matchesWhenOverlapCoversHalfTheSession() {
        val s = session(1, "18:00", "19:00")
        assertEquals(s, SessionMatcher.match(activity(1, "18:20", "19:30"), listOf(s)))
        assertEquals(s, SessionMatcher.match(activity(1, "18:30", "19:30"), listOf(s)))
        assertNull(SessionMatcher.match(activity(1, "18:31", "19:30"), listOf(s)))
        assertNull(SessionMatcher.match(activity(1, "19:10", "19:40"), listOf(s)))
        assertEquals(40 * 60L, SessionMatcher.overlapSeconds(activity(1, "18:20", "19:30"), s))
    }

    @Test
    fun unfinishedAndAbandonedSessionsNeverMatch() {
        assertNull(SessionMatcher.match(activity(1, "18:00", "19:00"), listOf(session(1, "18:00", null, SessionStatus.IN_PROGRESS))))
        assertNull(SessionMatcher.match(activity(1, "18:00", "19:00"), listOf(session(1, "18:00", "19:00", SessionStatus.ABANDONED))))
        assertNull(SessionMatcher.match(activity(1, "18:00", "19:00"), listOf(session(1, "18:00", "18:00"))))
    }

    @Test
    fun linksEachSessionAndActivityOnceByLargestOverlap() {
        val morning = session(1, "07:00", "08:00")
        val evening = session(2, "18:00", "19:00")
        val run = activity(1, "07:05", "07:50")
        val lift = activity(2, "18:10", "19:05")
        val lateLift = activity(3, "18:25", "19:20")
        val pairs = SessionMatcher.linkActivities(listOf(lateLift, lift, run), listOf(morning, evening))
        assertEquals(2, pairs.size)
        assertEquals(setOf(1L to 1L, 2L to 2L), pairs.map { it.first.id to it.second.id }.toSet())
        assertTrue(SessionMatcher.linkActivities(listOf(lift.copy(linkedSessionId = 5)), listOf(evening)).isEmpty())
        assertNotNull(SessionMatcher.match(lateLift, listOf(evening)))
    }
}

class FloorsAndCaloriesTest {
    private val f = HealthFixtures

    private fun climb(ascent: Double, descent: Double = 0.0) = listOf(
        HealthMinute(timestamp = f.at("07:00"), epochDay = f.WED_DAY, steps = 10, activeKcal = 40, ascentM = ascent, descentM = descent),
        HealthMinute(timestamp = f.at("07:01"), epochDay = f.WED_DAY, steps = 10, activeKcal = 60)
    )

    private fun summary(minutes: List<HealthMinute>, rmr: Int? = null, now: Long? = null) =
        DaySummary.compute(f.WED_DAY, minutes, emptyList(), null, emptyList(), 10_000, f.ZONE, restingMetabolicRate = rmr, nowSeconds = now)

    @Test
    fun floorsAreThreeMetresEachRoundedDown() {
        assertEquals(2, Floors.of(7.4))
        assertEquals(0, Floors.of(2.9))
        assertEquals(1, Floors.of(3.0))
        assertEquals(0, Floors.of(-1.0))
        val s = summary(climb(7.4, 3.1))
        assertEquals(7.4, s.ascentM, 1e-9)
        assertEquals(3.1, s.descentM, 1e-9)
        assertEquals(2, s.floors)
        assertEquals(1, s.descentFloors)
        assertEquals(3.0, DaySummary.METRES_PER_FLOOR, 0.0)
    }

    @Test
    fun restingCaloriesAreProratedThroughTheCurrentDay() {
        val start = f.at("00:00")
        assertEquals(0, summary(climb(0.0), rmr = 2_160, now = start).restingKcal)
        assertEquals(1_080, summary(climb(0.0), rmr = 2_160, now = f.at("12:00")).restingKcal)
        assertEquals(2_160, summary(climb(0.0), rmr = 2_160, now = f.at(f.WED.plusDays(3), "09:00")).restingKcal)
        assertEquals("a day still to come has no resting calories yet", 0, summary(climb(0.0), rmr = 2_160, now = f.at(f.WED.minusDays(1), "09:00")).restingKcal)
        assertEquals("without a clock the day counts as finished", 2_160, summary(climb(0.0), rmr = 2_160).restingKcal)
        assertNull(summary(climb(0.0)).restingKcal)
        assertNull(summary(climb(0.0)).totalKcal)
    }

    @Test
    fun totalCaloriesAddActiveToResting() {
        val s = summary(climb(0.0), rmr = 2_160, now = f.at("12:00"))
        assertEquals(100, s.activeKcal)
        assertEquals(1_180, s.totalKcal)
        assertEquals(2_260, summary(climb(0.0), rmr = 2_160).totalKcal)
    }
}

class BodyBatteryDayTest {
    private val f = HealthFixtures

    private fun event(start: String, minutes: Int, delta: Int, kind: BodyBatteryKind, raw: Int = 0, startDate: java.time.LocalDate = f.WED) = BodyBatteryEvent(
        startTimestamp = f.at(startDate, start), kindRaw = raw, endTimestamp = f.at(startDate, start) + minutes * 60L, epochDay = f.WED_DAY,
        minutes = minutes, delta = delta, kind = kind
    )

    private fun activity(name: String, start: String, end: String) = Activity(
        startTimestamp = f.at(start), fitTimeCreated = f.at(start), endTimestamp = f.at(end), sport = 10, subSport = 20,
        kind = ActivityKind.STRENGTH, name = name, timerSeconds = 3_000, elapsedSeconds = 3_600, filePath = "a.fit"
    )

    @Test
    fun dayTotalsSortingTitlesAndOvernight() {
        val walk = event("07:05", 58, -10, BodyBatteryKind.ACTIVITY)
        val night = event("23:41", 432, 51, BodyBatteryKind.SLEEP, raw = 4, startDate = f.WED.minusDays(1))
        val gap = event("13:00", 32, 0, BodyBatteryKind.UNMEASURED, raw = 3)
        val unknownUp = event("16:00", 20, 3, BodyBatteryKind.UNKNOWN, raw = 9)
        val unknownDown = event("18:00", 20, -4, BodyBatteryKind.UNKNOWN, raw = 9)
        val sleepNight = SleepNight(epochDay = f.WED_DAY, startTimestamp = f.NIGHT_START, endTimestamp = f.at("06:53"), bodyBatteryStart = 37, bodyBatteryEnd = 88)
        val day = BodyBatteryEvents.day(listOf(unknownDown, walk, gap, night, unknownUp), listOf(activity("Upper A", "07:00", "08:05")), sleepNight, f.ZONE)
        assertEquals(listOf("Sleep", "Workout · Upper A", "Not worn", "Charged", "Drained"), day.rows.map { it.title })
        assertEquals("23:41 to 06:53 · 7 h 12 m", day.rows[0].detail)
        assertEquals("07:05 to 08:03 · 58 min", day.rows[1].detail)
        assertEquals(54, day.chargedTotal)
        assertEquals(14, day.drainedTotal)
        assertEquals(37, day.overnightStart)
        assertEquals(88, day.overnightEnd)
        assertEquals(51, day.overnightGain)
        assertTrue(day.hasEvents)
    }

    @Test
    fun activityTitleNeedsHalfTheEventOverlapping() {
        val walk = event("07:05", 58, -10, BodyBatteryKind.ACTIVITY)
        assertEquals("Workout", BodyBatteryEvents.day(listOf(walk), listOf(activity("Late", "07:40", "08:30")), null, f.ZONE).rows.single().title)
        assertEquals("Workout · Upper A", BodyBatteryEvents.day(listOf(walk), listOf(activity("Upper A", "07:34", "08:30")), null, f.ZONE).rows.single().title)
        val both = BodyBatteryEvents.day(listOf(walk), listOf(activity("Short", "07:00", "07:40"), activity("Long", "07:10", "08:30")), null, f.ZONE)
        assertEquals("Workout · Long", both.rows.single().title)
        val sleep = event("23:41", 432, 51, BodyBatteryKind.SLEEP, raw = 4)
        assertEquals("Sleep", BodyBatteryEvents.day(listOf(sleep), listOf(activity("Night owl", "23:41", "06:53")), null, f.ZONE).rows.single().title)
    }

    @Test
    fun emptyDayAndUnknownLabels() {
        val empty = BodyBatteryEvents.day(emptyList(), emptyList(), null, f.ZONE)
        assertFalse(empty.hasEvents)
        assertEquals(0, empty.chargedTotal)
        assertNull(empty.overnightGain)
        assertEquals("Charged", BodyBatteryEvents.kindLabel(event("10:00", 5, 0, BodyBatteryKind.UNKNOWN)))
        assertEquals("Drained", BodyBatteryEvents.kindLabel(event("10:00", 5, -1, BodyBatteryKind.UNKNOWN)))
        assertEquals("Not worn", BodyBatteryEvents.kindLabel(event("10:00", 5, 0, BodyBatteryKind.UNMEASURED)))
        assertEquals("1 h 00 m", BodyBatteryEvents.duration(3_600))
        assertEquals("59 min", BodyBatteryEvents.duration(59 * 60 + 30))
    }
}

class SleepScoreTest {
    private val f = HealthFixtures

    private fun night() = SleepNight(
        epochDay = f.WED_DAY, startTimestamp = f.NIGHT_START, endTimestamp = f.at("06:53"), score = 81,
        deepSeconds = 85 * 60, lightSeconds = 238 * 60, remSeconds = 90 * 60, awakeSeconds = 19 * 60, restlessMoments = 12,
        awakeScore = 70, awakeningsScore = 74, deepScore = 74, lightScore = 78, remScore = 86, durationScore = 89, qualityScore = 84,
        recoveryScore = 100, restlessnessScore = 71, interruptionsScore = 72, awakeningsCount = 2, sleepNeedMin = 520, sleepBaselineMin = 480
    )

    @Test
    fun breakdownRowsFollowTheFixedOrderWithBandsAndDetails() {
        val rows = SleepScoreBreakdown.rows(night())
        assertEquals(listOf("Duration", "Quality", "Deep", "Light", "REM", "Restlessness", "Interruptions", "Awake time", "Awakenings", "Recovery"), rows.map { it.name })
        assertEquals(listOf(89, 84, 74, 78, 86, 71, 72, 70, 74, 100), rows.map { it.score })
        assertEquals(listOf("Good", "Good", "Fair", "Fair", "Good", "Fair", "Fair", "Fair", "Fair", "Excellent"), rows.map { it.band })
        assertEquals("12 moments", rows[5].detail)
        assertEquals("19 m", rows[7].detail)
        assertEquals("2 times", rows[8].detail)
        assertNull(rows[0].detail)
        assertEquals("1 time", SleepScoreBreakdown.rows(night().copy(awakeningsCount = 1))[8].detail)
        assertEquals("1 h 05 m", SleepScoreBreakdown.awakeDuration(65 * 60))
    }

    @Test
    fun breakdownSkipsNullScoresAndDetails() {
        val partial = SleepScoreBreakdown.rows(night().copy(qualityScore = null, recoveryScore = null, restlessMoments = null, awakeSeconds = 0, awakeningsCount = null))
        assertEquals(listOf("Duration", "Deep", "Light", "REM", "Restlessness", "Interruptions", "Awake time", "Awakenings"), partial.map { it.name })
        assertTrue(partial.all { it.detail == null })
        assertTrue(SleepScoreBreakdown.rows(SleepNight(epochDay = f.WED_DAY, startTimestamp = f.NIGHT_START, endTimestamp = f.at("06:53"))).isEmpty())
    }

    @Test
    fun sleepNeedComparesTheNightWithTheCoach() {
        val need = SleepNeeds.of(night())!!
        assertEquals(520, need.needMin)
        assertEquals(480, need.baselineMin)
        assertEquals(413, need.sleptMin)
        assertEquals(107, need.shortByMin)
        assertFalse(need.met)
        assertEquals(413f / 520f, need.fraction, 1e-6f)
        val met = SleepNeeds.of(night().copy(sleepNeedMin = 400))!!
        assertTrue(met.met)
        assertEquals(0, met.shortByMin)
        assertEquals(1f, met.fraction, 0f)
        assertNull(SleepNeeds.of(night().copy(sleepNeedMin = null)))
        val eventOnly = SleepNeeds.of(night().copy(deepSeconds = 0, lightSeconds = 0, remSeconds = 0, awakeSeconds = 0))!!
        assertEquals("falls back to the night's length", 432, eventOnly.sleptMin)
    }

    @Test
    fun primaryBenefitLabels() {
        assertEquals("Recovery", PrimaryBenefit.label(1))
        assertEquals("Base", PrimaryBenefit.label(2))
        assertEquals("Tempo", PrimaryBenefit.label(3))
        assertEquals("Threshold", PrimaryBenefit.label(4))
        assertEquals("VO2 max", PrimaryBenefit.label(5))
        assertEquals("Anaerobic capacity", PrimaryBenefit.label(6))
        assertEquals("Sprint", PrimaryBenefit.label(7))
        assertNull(PrimaryBenefit.label(0))
        assertNull(PrimaryBenefit.label(null))
        assertNull(PrimaryBenefit.label(42))
    }
}

class TrendSecondaryTest {
    private val today = HealthFixtures.WED_DAY

    @Test
    fun secondarySeriesIsAveragedPerBucketAndNullWithoutData() {
        val values = (0..6).associate { today - it to 420.0 + it }
        val need = mapOf(today to 520.0, today - 1 to 480.0)
        val r = Trends.bucket(TrendMetric.SLEEP_NEED, values, TrendPeriod.DAY7, today, need)
        assertEquals(7, r.buckets.size)
        assertEquals(520.0, r.buckets.last().secondary!!, 0.0)
        assertEquals(480.0, r.buckets[5].secondary!!, 0.0)
        assertNull(r.buckets[0].secondary)
        assertTrue(r.bars)
        val weekly = Trends.bucket(TrendMetric.CALORIES, values, TrendPeriod.WEEK4, today, mapOf(today to 400.0, today - 1 to 300.0))
        val lastWeek = weekly.buckets.last()
        assertEquals(350.0, lastWeek.secondary!!, 0.0)
        assertNull("no secondary given, no secondary out", Trends.bucket(TrendMetric.STEPS, values, TrendPeriod.DAY7, today).buckets.last().secondary)
    }

    @Test
    fun newMetricsAreBarsAndHigherIsBetter() {
        for (m in listOf(TrendMetric.FLOORS, TrendMetric.CALORIES, TrendMetric.SLEEP_NEED)) {
            assertTrue(m.name, m.bars)
            assertTrue(m.name, m.higherIsBetter)
        }
        assertEquals("floors", TrendMetric.FLOORS.unit)
        assertEquals("kcal", TrendMetric.CALORIES.unit)
        assertEquals("min", TrendMetric.SLEEP_NEED.unit)
    }
}
