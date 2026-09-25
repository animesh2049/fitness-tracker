package com.animesh.fitnesstracker.garmin.fitimport

import com.animesh.fitnesstracker.data.model.Activity
import com.animesh.fitnesstracker.data.model.ActivityKind
import com.animesh.fitnesstracker.data.model.ActivityLap
import com.animesh.fitnesstracker.data.model.ActivityPoint
import com.animesh.fitnesstracker.data.model.BodyBatteryEvent
import com.animesh.fitnesstracker.data.model.BodyBatteryKind
import com.animesh.fitnesstracker.data.model.DailyMetric
import com.animesh.fitnesstracker.data.model.HealthMinute
import com.animesh.fitnesstracker.data.model.HrvSummary
import com.animesh.fitnesstracker.data.model.HrvValue
import com.animesh.fitnesstracker.data.model.IntensityMinute
import com.animesh.fitnesstracker.data.model.MetricType
import com.animesh.fitnesstracker.data.model.RespirationSample
import com.animesh.fitnesstracker.data.model.RestingHrDaily
import com.animesh.fitnesstracker.data.model.SleepNight
import com.animesh.fitnesstracker.data.model.SleepStage
import com.animesh.fitnesstracker.data.model.Spo2Sample
import com.animesh.fitnesstracker.data.model.StressSample
import com.animesh.fitnesstracker.domain.health.SleepNights
import com.animesh.fitnesstracker.garmin.fit.DecodedFit
import com.animesh.fitnesstracker.garmin.fit.EventRec
import com.animesh.fitnesstracker.util.Dates
import java.time.ZoneId
import kotlin.math.roundToInt

/** Steps, distance and active calories already stored for a day before a given minute. */
data class DayCounters(val steps: Int, val distanceM: Double, val activeKcal: Int) {
    companion object {
        val ZERO = DayCounters(0, 0.0, 0)
    }
}

/** A sleep window from FIT event 74 (type 0 start, type 1 end). */
data class SleepWindow(val start: Long, val end: Long)

/** Rows derived from one monitoring file. */
data class MonitoringRows(
    val minutes: List<HealthMinute> = emptyList(),
    val stress: List<StressSample> = emptyList(),
    val restingHr: List<RestingHrDaily> = emptyList(),
    val spo2: List<Spo2Sample> = emptyList(),
    val respiration: List<RespirationSample> = emptyList(),
    val intensity: List<IntensityMinute> = emptyList(),
    val metrics: List<DailyMetric> = emptyList(),
    val sleepWindows: List<SleepWindow> = emptyList(),
    val bodyBatteryEvents: List<BodyBatteryEvent> = emptyList()
)

/** Where a night's bounds came from. */
data class SleepBounds(val start: Long, val end: Long, val fromEvent: Boolean) {
    val source: String get() = if (fromEvent) SleepNight.SOURCE_EVENT else SleepNight.SOURCE_STAGES
}

data class HrvRows(val summaries: List<HrvSummary>, val values: List<HrvValue>)

data class ActivityRows(val activity: Activity, val laps: List<ActivityLap>, val points: List<ActivityPoint>)

/**
 * Turns a [DecodedFit] into Room rows. Pure Kotlin so the rules are unit tested without a
 * database; [FitImporter] does the writing. The rules follow research-data.md sections 3.3 and 4.
 */
class FitRows(private val zone: ZoneId = ZoneId.systemDefault()) {
    fun epochDay(timestamp: Long): Long = Dates.epochDayOfSeconds(timestamp, zone)

    // Monitoring

    /**
     * Timestamp of the first minute row a monitoring file will produce (the first record minus the
     * 60 second shift), so the importer can look up what the day already holds before it.
     */
    fun firstMinuteTimestamp(fit: DecodedFit): Long? = fit.monitoring.minOfOrNull { it.timestamp }?.let { it - MINUTE }

    /**
     * @param prior what the database already holds for the day of the first minute, before that
     *   minute. The first record of a file carries the day's running totals, so the delta of the
     *   first minute is the totals minus what earlier files already accounted for.
     */
    fun monitoring(fit: DecodedFit, prior: DayCounters = DayCounters.ZERO): MonitoringRows {
        val minutes = ArrayList<HealthMinute>()
        val groups = fit.monitoring.groupBy { it.timestamp }.toSortedMap()
        val stepsPerType = HashMap<Int, Long>()
        val distancePerType = HashMap<Int, Double>()
        val kcalPerType = HashMap<Int, Int>()
        var prevRowTs: Long? = null
        var prevKind = 0
        var first = true

        for ((ts, records) in groups) {
            val rowTs = ts - MINUTE
            val day = epochDay(rowTs)
            val prev = prevRowTs
            if (prev != null && rowTs - prev > MINUTE) {
                val gap = rowTs - prev
                val worn = gap <= NOT_WORN_GAP
                if (gap <= MAX_FILL_GAP) {
                    var t = prev + MINUTE
                    while (t < rowTs) {
                        minutes += HealthMinute(timestamp = t, epochDay = epochDay(t), activityKind = if (worn) prevKind else 0, worn = worn)
                        t += MINUTE
                    }
                }
            }
            if (prev != null && epochDay(prev) != day) {
                // The counters restart at local midnight.
                stepsPerType.clear(); distancePerType.clear(); kcalPerType.clear()
            }

            var steps = 0L
            var distance = 0.0
            var kcal = 0
            var ascent = 0.0
            var descent = 0.0
            var hr: Int? = null
            var kind: Int? = null
            var intensity = 0
            for (r in records) {
                val type = r.activityType ?: 0
                r.cumulativeSteps?.let { v ->
                    val last = stepsPerType[type]
                    steps += if (last == null || v < last) v else v - last
                    stepsPerType[type] = v
                }
                r.cumulativeDistanceM?.let { v ->
                    val last = distancePerType[type]
                    distance += if (last == null || v < last) v else v - last
                    distancePerType[type] = v
                }
                r.cumulativeActiveKcal?.let { v ->
                    val last = kcalPerType[type]
                    kcal += if (last == null || v < last) v else v - last
                    kcalPerType[type] = v
                }
                r.heartRate?.let { if (it > 0) hr = it }
                r.activityType?.let { kind = it }
                r.intensity?.let { if (it > intensity) intensity = it }
                // Ascent and descent records carry the climb since the previous one, not a running total.
                r.ascentM?.let { if (it > 0) ascent += it }
                r.descentM?.let { if (it > 0) descent += it }
            }
            if (first) {
                // Subtract what earlier files of the same day already contributed.
                steps = (steps - prior.steps).coerceAtLeast(0)
                distance = (distance - prior.distanceM).coerceAtLeast(0.0)
                kcal = (kcal - prior.activeKcal).coerceAtLeast(0)
                first = false
            }
            val row = HealthMinute(
                timestamp = rowTs, epochDay = day, steps = steps.toInt(), distanceM = distance, activeKcal = kcal,
                heartRate = hr, activityKind = kind ?: prevKind, intensity = intensity, worn = true, ascentM = ascent, descentM = descent
            )
            minutes += row
            prevRowTs = rowTs
            prevKind = row.activityKind
        }

        val intensity = fit.monitoring.mapNotNull { r ->
            val moderate = r.moderateActivityMinutes ?: 0
            val vigorous = r.vigorousActivityMinutes ?: 0
            if (moderate == 0 && vigorous == 0) null else IntensityMinute(r.timestamp, moderate, vigorous)
        }.groupBy { it.timestamp }.map { (ts, rows) -> IntensityMinute(ts, rows.sumOf { it.moderate }, rows.sumOf { it.vigorous }) }

        val stress = fit.stress.mapNotNull { s ->
            val value = s.stress?.takeIf { it >= 0 }
            // The watch writes 127 for Body Battery in the first minutes off the wrist; only 0..100 is a reading.
            val battery = s.bodyBattery?.takeIf { it in 0..100 }
            if (value == null && battery == null) null else StressSample(s.timestamp, value, battery)
        }.distinctBy { it.timestamp }

        val fileTime = fit.fileId.timeCreated
        // One row per day: the last record with a current_day value, else the last with resting_heart_rate.
        val restingHr = fit.restingHr.mapNotNull { r ->
            val ts = r.timestamp ?: fileTime ?: return@mapNotNull null
            val current = r.currentDayRestingHeartRate?.takeIf { it > 0 }
            val bpm = current ?: r.restingHeartRate?.takeIf { it > 0 } ?: return@mapNotNull null
            Triple(epochDay(ts), bpm, current != null)
        }.groupBy { it.first }.map { (day, records) ->
            val chosen = records.lastOrNull { it.third } ?: records.last()
            RestingHrDaily(day, chosen.second)
        }

        val spo2 = fit.spo2.filter { it.spo2Percent in 1..100 }.map { Spo2Sample(it.timestamp, it.spo2Percent, it.mode) }.distinctBy { it.timestamp }
        val respiration = fit.respiration.filter { it.breathsPerMinute > 0 }.map { RespirationSample(it.timestamp, it.breathsPerMinute) }.distinctBy { it.timestamp }

        val metrics = fit.monitoringInfo.mapNotNull { info ->
            val rmr = info.restingMetabolicRate?.takeIf { it > 0 } ?: return@mapNotNull null
            DailyMetric(epochDay(info.timestamp), MetricType.RMR, rmr.toDouble(), null, info.timestamp)
        }.let(::latestPerDay)

        return MonitoringRows(minutes, stress, restingHr, spo2, respiration, intensity, metrics, sleepWindows(fit.events), bodyBatteryEvents(fit))
    }

    /**
     * Body Battery events with a duration and a delta; the end falls back to start plus duration.
     * An event belongs to the day it started on, except sleep, which belongs to the morning it
     * ended on, the same day the app keys the night by, so the night's charge shows with the night.
     */
    fun bodyBatteryEvents(fit: DecodedFit): List<BodyBatteryEvent> = fit.bodyBatteryEvents.mapNotNull { e ->
        val minutes = e.durationMinutes ?: return@mapNotNull null
        val delta = e.delta ?: return@mapNotNull null
        val kindRaw = e.kind ?: return@mapNotNull null
        val kind = BodyBatteryKind.fromRaw(kindRaw)
        val end = e.endTimestamp ?: (e.timestamp + minutes * MINUTE)
        BodyBatteryEvent(
            startTimestamp = e.timestamp, kindRaw = kindRaw, endTimestamp = end,
            epochDay = epochDay(if (kind == BodyBatteryKind.SLEEP) end else e.timestamp), minutes = minutes, delta = delta, kind = kind
        )
    }.distinctBy { it.startTimestamp to it.kindRaw }

    /** Start and end pairs of FIT event 74. An unmatched start is dropped. */
    fun sleepWindows(events: List<EventRec>): List<SleepWindow> {
        val result = ArrayList<SleepWindow>()
        var start: Long? = null
        for (e in events.filter { it.event == SLEEP_EVENT }.sortedBy { it.timestamp }) {
            when (e.eventType) {
                0 -> start = e.timestamp
                1 -> {
                    val s = start
                    if (s != null && e.timestamp > s) result += SleepWindow(s, e.timestamp)
                    start = null
                }
            }
        }
        return result
    }

    // Sleep

    /** The night's bounds: the file's event 74 window when present, else first to last stage. */
    fun sleepBounds(fit: DecodedFit): SleepBounds? {
        val window = sleepWindows(fit.events).lastOrNull()
        if (window != null) return SleepBounds(window.start, window.end, fromEvent = true)
        val stages = fit.sleepStages.filter { it.stage != SleepStage.UNMEASURABLE && !inNap(fit, it.timestamp) }
        if (stages.isEmpty()) return null
        return SleepBounds(stages.minOf { it.timestamp }, stages.maxOf { it.timestamp }, fromEvent = false)
    }

    /** True when the stage ending at [timestamp] belongs to a nap (FIT message 412); naps stay out of the night. */
    private fun inNap(fit: DecodedFit, timestamp: Long): Boolean =
        fit.naps.any { nap -> timestamp > nap.startTimestamp && timestamp <= nap.endTimestamp }

    /**
     * Stage rows for the night. A record's timestamp is the upper bound of its stage and the
     * previous record's timestamp its lower bound; the first stage starts at the sleep start
     * event when there is one. A record more than [MAX_STAGE_SECONDS] after the previous one
     * starts a new run (zero length first stage), and stages inside a nap are left out.
     */
    fun sleepStages(fit: DecodedFit, bounds: SleepBounds): List<SleepStage> {
        val nightDay = epochDay(bounds.end)
        val sorted = fit.sleepStages.sortedBy { it.timestamp }.distinctBy { it.timestamp }
        val rows = ArrayList<SleepStage>()
        var prevEnd: Long? = null
        for (rec in sorted) {
            val prev = prevEnd
            val start = when {
                prev == null -> if (bounds.fromEvent && bounds.start < rec.timestamp) bounds.start else rec.timestamp
                rec.timestamp - prev > MAX_STAGE_SECONDS -> rec.timestamp
                else -> prev
            }
            prevEnd = rec.timestamp
            if (inNap(fit, rec.timestamp)) continue
            if (rec.timestamp <= start && rows.isNotEmpty()) continue
            rows += SleepStage(endTimestamp = rec.timestamp, startTimestamp = start, stage = rec.stage, nightEpochDay = nightDay)
        }
        return rows
    }

    /**
     * Assembles the night row from every stage row of the night (this file's and earlier ones'),
     * the file's stats, and the row already stored, whose event bounds and overnight averages are kept.
     */
    fun sleepNight(fit: DecodedFit, bounds: SleepBounds, stages: List<SleepStage>, prior: SleepNight?, hrv: HrvSummary?): SleepNight {
        val totals = SleepNights.totalsFromStages(stages)
        val useBounds = if (!bounds.fromEvent && prior != null && prior.source == SleepNight.SOURCE_EVENT) {
            SleepBounds(prior.startTimestamp, prior.endTimestamp, fromEvent = true)
        } else bounds
        val score = fit.sleepStats.lastOrNull { it.overallSleepScore != null }?.overallSleepScore ?: prior?.score
        val stats = fit.sleepStats.lastOrNull { it.overallSleepScore != null } ?: fit.sleepStats.lastOrNull()
        val restless = fit.restlessMoments.lastOrNull()?.count ?: prior?.restlessMoments
        return SleepNight(
            epochDay = epochDay(useBounds.end),
            startTimestamp = useBounds.start,
            endTimestamp = useBounds.end,
            score = score,
            deepSeconds = totals.deepSeconds,
            lightSeconds = totals.lightSeconds,
            remSeconds = totals.remSeconds,
            awakeSeconds = totals.awakeSeconds,
            restlessMoments = restless,
            avgHrvMs = hrv?.lastNightAvg ?: prior?.avgHrvMs,
            hrvStatus = hrv?.status ?: prior?.hrvStatus,
            avgRespiration = prior?.avgRespiration,
            avgSpo2 = prior?.avgSpo2,
            lowestHr = prior?.lowestHr,
            source = useBounds.source,
            awakeScore = stats?.awakeTimeScore ?: prior?.awakeScore,
            awakeningsScore = stats?.awakeningsCountScore ?: prior?.awakeningsScore,
            deepScore = stats?.deepSleepScore ?: prior?.deepScore,
            lightScore = stats?.lightSleepScore ?: prior?.lightScore,
            remScore = stats?.remSleepScore ?: prior?.remScore,
            durationScore = stats?.sleepDurationScore ?: prior?.durationScore,
            qualityScore = stats?.sleepQualityScore ?: prior?.qualityScore,
            recoveryScore = stats?.sleepRecoveryScore ?: prior?.recoveryScore,
            restlessnessScore = stats?.sleepRestlessnessScore ?: prior?.restlessnessScore,
            interruptionsScore = stats?.interruptionsScore ?: prior?.interruptionsScore,
            awakeningsCount = stats?.awakeningsCount ?: prior?.awakeningsCount,
            avgStressDuringSleep = stats?.averageStressDuringSleep?.takeIf { it >= 0 } ?: prior?.avgStressDuringSleep,
            bodyBatteryStart = prior?.bodyBatteryStart,
            bodyBatteryEnd = prior?.bodyBatteryEnd,
            sleepNeedMin = prior?.sleepNeedMin,
            sleepBaselineMin = prior?.sleepBaselineMin,
            skinTempDeviation = prior?.skinTempDeviation
        )
    }

    // HRV

    fun hrv(fit: DecodedFit): HrvRows {
        val summaries = fit.hrvSummary.map { s ->
            HrvSummary(
                epochDay = epochDay(s.timestamp), timestamp = s.timestamp, weeklyAvg = s.weeklyAverageMs, lastNightAvg = s.lastNightAverageMs,
                fiveMinHigh = s.lastNight5MinHighMs, baselineLowUpper = s.baselineLowUpperMs, baselineBalancedLower = s.baselineBalancedLowerMs,
                baselineBalancedUpper = s.baselineBalancedUpperMs, status = s.status
            )
        }.groupBy { it.epochDay }.map { (_, rows) -> rows.maxBy { it.timestamp } }
        val values = fit.hrvValues.filter { it.valueMs > 0 }.map { HrvValue(it.timestamp, it.valueMs) }.distinctBy { it.timestamp }
        return HrvRows(summaries, values)
    }

    // Metrics

    fun metrics(fit: DecodedFit): List<DailyMetric> {
        val rows = ArrayList<DailyMetric>()
        fun add(ts: Long, type: MetricType, value: Number?, extra: Number? = null) {
            if (value == null) return
            rows += DailyMetric(epochDay(ts), type, value.toDouble(), extra?.toLong(), ts)
        }
        for (r in fit.trainingLoad) {
            add(r.timestamp, MetricType.TRAINING_LOAD_ACUTE, r.acute)
            add(r.timestamp, MetricType.TRAINING_LOAD_CHRONIC, r.chronic)
        }
        for (r in fit.racePredictions) {
            add(r.timestamp, MetricType.RACE_5K, r.time5k)
            add(r.timestamp, MetricType.RACE_10K, r.time10k)
            add(r.timestamp, MetricType.RACE_HALF, r.timeHalf)
            add(r.timestamp, MetricType.RACE_FULL, r.timeFull)
        }
        for (r in fit.hillScores) add(r.timestamp, MetricType.HILL, r.score, r.level)
        for (r in fit.enduranceScores) add(r.timestamp, MetricType.ENDURANCE, r.score, r.level)
        for (r in fit.trainingReadiness) add(r.timestamp, MetricType.READINESS, r.readiness, r.level)
        for (r in fit.functionalMetrics) {
            add(r.timestamp, MetricType.FTP, r.functionalThresholdPower, r.cyclingLactateThresholdHr)
            add(r.timestamp, MetricType.LTHR, r.runningLactateThresholdHr)
        }
        // Recovery time 0 is a real value: fully recovered.
        for (r in fit.recovery) add(r.timestamp, MetricType.RECOVERY_MIN, r.recoveryMinutes)
        for (r in fit.maxMet) {
            add(r.timestamp, MetricType.VO2MAX, r.vo2Max?.takeIf { it > 0 }, r.category)
            add(r.timestamp, MetricType.FITNESS_AGE, r.fitnessAge?.takeIf { it > 0 })
        }
        for (r in fit.monitoringInfo) add(r.timestamp, MetricType.RMR, r.restingMetabolicRate?.takeIf { it > 0 })
        // Sleep Coach: the demanded minutes, keyed by the day the record was written (the night starts that evening).
        for (r in fit.sleepDemand) add(r.timestamp, MetricType.SLEEP_NEED, r.demandMinutes?.takeIf { it > 0 }, r.normalMinutes)
        // Body Battery at the end and start of the night that ended on the day.
        for (r in fit.dailySleep) {
            val end = r.bodyBatteryEnd?.takeIf { it in 0..100 } ?: continue
            add(r.endTimestamp ?: r.timestamp, MetricType.SLEEP_BODY_BATTERY, end, r.bodyBatteryStart?.takeIf { it in 0..100 })
        }
        return latestPerDay(rows)
    }

    /** Days whose night row should pick up new sleep need or Body Battery values from these metrics. */
    fun nightsTouchedByMetrics(metrics: List<DailyMetric>): Set<Long> = metrics.flatMapTo(LinkedHashSet()) { m ->
        when (m.type) {
            MetricType.SLEEP_BODY_BATTERY -> listOf(m.epochDay)
            // The need written on day D is for the night that ends on D + 1; D itself is the fallback the importer also checks.
            MetricType.SLEEP_NEED -> listOf(m.epochDay, m.epochDay + 1)
            else -> emptyList()
        }
    }

    private fun latestPerDay(rows: List<DailyMetric>): List<DailyMetric> =
        rows.groupBy { it.epochDay to it.type }.map { (_, group) -> group.maxBy { it.timestamp } }

    // Activities

    /** Null when the file has neither a session nor track points. */
    fun activity(fit: DecodedFit, filePath: String): ActivityRows? {
        val session = fit.sessions.firstOrNull()
        val records = fit.records.sortedBy { it.timestamp }.distinctBy { it.timestamp }
        val physio = fit.physiologicalMetrics.lastOrNull()
        val start = session?.startTime ?: records.firstOrNull()?.timestamp ?: fit.fileId.timeCreated ?: return null
        if (session == null && records.isEmpty()) return null
        val timeCreated = fit.fileId.timeCreated ?: start
        val lastRecord = records.lastOrNull()?.timestamp
        val elapsed = session?.totalElapsedTime?.roundToInt()
            ?: lastRecord?.let { (it - start).toInt() }
            ?: 0
        val end = if (elapsed > 0) start + elapsed else (session?.timestamp ?: start)
        val timer = session?.totalTimerTime?.roundToInt() ?: elapsed
        val sport = session?.sport ?: fit.sports.firstOrNull()?.sport ?: 0
        val subSport = session?.subSport ?: fit.sports.firstOrNull()?.subSport ?: 0
        val kind = SportMapping.kind(sport, subSport)
        val name = listOf(
            fit.activity.firstOrNull()?.name, fit.workouts.firstOrNull()?.name, session?.sportProfileName, fit.sports.firstOrNull()?.name
        ).firstOrNull { !it.isNullOrBlank() }?.trim() ?: kind.label

        val zones = fit.timeInZone.firstOrNull { it.referenceMesg == SESSION_MESSAGE }
            ?: fit.timeInZone.firstOrNull { it.referenceMesg == null }
        val zoneSeconds = zones?.let { zoneSecondsOf(it.timeInHrZone) } ?: emptyList()
        val zoneBounds = zones?.hrZoneHighBoundary ?: emptyList()

        val activity = Activity(
            startTimestamp = start,
            fitTimeCreated = timeCreated,
            endTimestamp = end,
            sport = sport,
            subSport = subSport,
            kind = kind,
            name = name,
            timerSeconds = timer,
            elapsedSeconds = elapsed,
            distanceM = session?.totalDistance?.takeIf { it > 0 } ?: records.lastOrNull()?.distance?.takeIf { it > 0 },
            calories = session?.totalCalories,
            avgHr = session?.avgHeartRate ?: physio?.averageHeartRate,
            maxHr = session?.maxHeartRate,
            minHr = session?.minHeartRate,
            avgCadence = session?.avgCadence,
            avgSpeedMps = session?.avgSpeed,
            maxSpeedMps = session?.maxSpeed,
            totalAscent = session?.totalAscent,
            totalDescent = session?.totalDescent,
            aerobicEffect = session?.totalTrainingEffect ?: physio?.aerobicEffect,
            anaerobicEffect = session?.totalAnaerobicTrainingEffect ?: physio?.anaerobicEffect,
            recoveryMinutes = physio?.recoveryTimeMinutes,
            bodyBatteryStart = session?.beginningBodyBattery,
            bodyBatteryEnd = session?.endingBodyBattery,
            trainingLoad = session?.trainingLoadPeak,
            vo2max = physio?.metMax?.takeIf { it > 0 }?.let { Math.round(it * 3.5 * 10) / 10.0 },
            hrZoneSeconds = Activity.csv(zoneSeconds),
            hrZoneBounds = Activity.csv(zoneBounds),
            filePath = filePath,
            performanceCondition = physio?.endingPerformanceCondition,
            primaryBenefit = physio?.primaryBenefit
        )
        val laps = fit.laps.mapIndexed { index, lap ->
            val lapTimer = lap.totalTimerTime?.roundToInt() ?: lap.totalElapsedTime?.roundToInt() ?: 0
            ActivityLap(
                activityId = 0, index = index, startTimestamp = lap.startTime ?: (lap.timestamp - lapTimer), timerSeconds = lapTimer,
                distanceM = lap.totalDistance, avgHr = lap.avgHeartRate, maxHr = lap.maxHeartRate, avgSpeedMps = lap.avgSpeed,
                avgCadence = lap.avgCadence, calories = lap.totalCalories
            )
        }
        val points = records.map { r ->
            ActivityPoint(
                activityId = 0, timestamp = r.timestamp, lat = r.latitude, lon = r.longitude, altitude = r.altitude, distanceM = r.distance,
                speedMps = r.speed, heartRate = r.heartRate?.takeIf { it > 0 }, cadence = r.cadence, power = r.power, temperature = r.temperature
            )
        }
        return ActivityRows(activity, laps, points)
    }

    /**
     * Zones 1 to 5 from FIT time_in_hr_zone. The watch writes six entries, the first being time
     * below zone 1, which is dropped; five entries are taken as they are.
     */
    fun zoneSecondsOf(timeInZone: List<Double>): List<Int> {
        val seconds = timeInZone.map { it.roundToInt() }
        return when {
            seconds.size >= 6 -> seconds.subList(1, 6)
            seconds.size == 5 -> seconds
            seconds.isEmpty() -> emptyList()
            else -> seconds + List(5 - seconds.size) { 0 }
        }
    }

    companion object {
        const val MINUTE = 60L
        /** Gaps longer than this are not worn time. */
        const val NOT_WORN_GAP = 10 * 60L
        /** Gaps longer than a day are not filled at all (the watch was off for a long time). */
        const val MAX_FILL_GAP = 24 * 3600L
        const val SLEEP_EVENT = 74
        const val SESSION_MESSAGE = 18
        /** No single sleep stage lasts longer than this; a bigger gap between stage records starts a new run. */
        const val MAX_STAGE_SECONDS = 4 * 3600L
    }
}

/** FIT sport and sub sport to [ActivityKind]; numbers from the FIT profile (GarminSport.java). */
object SportMapping {
    fun kind(sport: Int?, subSport: Int?): ActivityKind = when (sport) {
        1 -> ActivityKind.RUN
        2 -> ActivityKind.CYCLE
        5 -> ActivityKind.SWIM
        11 -> ActivityKind.WALK
        17 -> ActivityKind.HIKE
        62 -> ActivityKind.CARDIO
        10 -> when (subSport) {
            20 -> ActivityKind.STRENGTH
            26, 62 -> ActivityKind.CARDIO
            43, 44 -> ActivityKind.YOGA
            else -> ActivityKind.OTHER
        }
        4 -> when (subSport) {
            20 -> ActivityKind.STRENGTH
            43, 44 -> ActivityKind.YOGA
            else -> ActivityKind.CARDIO
        }
        else -> ActivityKind.OTHER
    }
}
