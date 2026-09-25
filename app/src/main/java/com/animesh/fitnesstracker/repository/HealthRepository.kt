package com.animesh.fitnesstracker.repository

import com.animesh.fitnesstracker.data.AppDatabase
import com.animesh.fitnesstracker.data.dao.DayHeartRate
import com.animesh.fitnesstracker.data.dao.DayIntensity
import com.animesh.fitnesstracker.data.dao.DayStress
import com.animesh.fitnesstracker.data.dao.DayTotals
import com.animesh.fitnesstracker.data.model.BodyBatteryEvent
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
import com.animesh.fitnesstracker.domain.health.DaySummary
import com.animesh.fitnesstracker.domain.health.Floors
import com.animesh.fitnesstracker.domain.health.TrendMetric
import com.animesh.fitnesstracker.domain.health.TrendPeriod
import com.animesh.fitnesstracker.domain.health.TrendResult
import com.animesh.fitnesstracker.domain.health.Trends
import com.animesh.fitnesstracker.util.Dates
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** Everything the Health tab needs for one day, as one observable bundle. */
data class DayInputs(
    val epochDay: Long,
    val minutes: List<HealthMinute>,
    val stress: List<StressSample>,
    val restingHr: RestingHrDaily?,
    /** Intensity rows from the Monday of the day's week to the end of the day. */
    val intensityWeek: List<IntensityMinute>,
    /** The night that ended on the day, when synced. */
    val night: SleepNight?,
    val hrv: HrvSummary?,
    val metrics: List<DailyMetric>,
    /** Body Battery events that started on the day (version 0.5). */
    val bodyBatteryEvents: List<BodyBatteryEvent> = emptyList(),
    /** The latest resting metabolic rate on or before the day, for total calories (version 0.5). */
    val restingMetabolicRate: DailyMetric? = null
)

/** Everything the sleep screen needs for one night. */
data class NightInputs(
    val epochDay: Long,
    val night: SleepNight?,
    val stages: List<SleepStage>,
    val hrv: HrvSummary?,
    val hrvValues: List<HrvValue>
)

class HealthRepository(
    private val db: AppDatabase,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Current Unix seconds, for prorating today's resting calories; tests pin it. */
    private val now: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    private val dao get() = db.healthDao()

    private fun dayStart(epochDay: Long) = Dates.dayStartSeconds(epochDay, zone)
    private fun dayEnd(epochDay: Long) = Dates.dayEndSeconds(epochDay, zone)

    // One day

    fun observeMinutes(epochDay: Long): Flow<List<HealthMinute>> = dao.observeMinutes(epochDay)
    fun observeStress(epochDay: Long): Flow<List<StressSample>> = dao.observeStress(dayStart(epochDay), dayEnd(epochDay))
    fun observeDayTotals(epochDay: Long): Flow<DayTotals?> = dao.observeDayTotals(epochDay)
    fun observeRestingHr(epochDay: Long): Flow<RestingHrDaily?> = dao.observeRestingHr(epochDay)
    fun observeSpo2(epochDay: Long): Flow<List<Spo2Sample>> = dao.observeSpo2(dayStart(epochDay), dayEnd(epochDay))
    fun observeRespiration(epochDay: Long): Flow<List<RespirationSample>> = dao.observeRespiration(dayStart(epochDay), dayEnd(epochDay))
    fun observeMetricsForDay(epochDay: Long): Flow<List<DailyMetric>> = dao.observeMetricsForDay(epochDay)
    fun observeLatestMetric(type: MetricType, epochDay: Long): Flow<DailyMetric?> = dao.observeLatestMetric(type, epochDay)

    /** Intensity rows for the week the day is in, Monday 00:00 to the end of the day. */
    fun observeIntensityWeek(epochDay: Long): Flow<List<IntensityMinute>> =
        dao.observeIntensity(dayStart(Dates.mondayOf(epochDay)), dayEnd(epochDay))

    fun observeDay(epochDay: Long): Flow<DayInputs> {
        val samples = combine(observeMinutes(epochDay), observeStress(epochDay), observeRestingHr(epochDay), observeIntensityWeek(epochDay)) { m, s, r, i ->
            DayInputs(epochDay, m, s, r, i, null, null, emptyList())
        }
        val withNight = combine(samples, dao.observeSleepNight(epochDay), dao.observeHrvSummary(epochDay), dao.observeMetricsForDay(epochDay)) { d, night, hrv, metrics ->
            d.copy(night = night, hrv = hrv, metrics = metrics)
        }
        return combine(withNight, dao.observeBodyBatteryEvents(epochDay), dao.observeLatestMetric(MetricType.RMR, epochDay)) { d, events, rmr ->
            d.copy(bodyBatteryEvents = events, restingMetabolicRate = rmr)
        }
    }

    fun observeBodyBatteryEvents(epochDay: Long): Flow<List<BodyBatteryEvent>> = dao.observeBodyBatteryEvents(epochDay)
    fun observeBodyBatteryEventsBetween(fromDay: Long, toDay: Long): Flow<List<BodyBatteryEvent>> = dao.observeBodyBatteryEventsBetween(fromDay, toDay)

    // Sleep

    fun observeSleepNight(epochDay: Long): Flow<SleepNight?> = dao.observeSleepNight(epochDay)
    fun observeSleepStages(epochDay: Long): Flow<List<SleepStage>> = dao.observeSleepStages(epochDay)
    fun observeSleepNightsBetween(fromDay: Long, toDay: Long): Flow<List<SleepNight>> = dao.observeSleepNightsBetween(fromDay, toDay)
    /** Days that have a night, oldest first, for previous and next on the sleep screen. */
    fun observeSleepNightDays(): Flow<List<Long>> = dao.observeSleepNightDays()

    fun observeNight(epochDay: Long): Flow<NightInputs> =
        combine(dao.observeSleepNight(epochDay), dao.observeSleepStages(epochDay), dao.observeHrvSummary(epochDay)) { night, stages, hrv ->
            Triple(night, stages, hrv)
        }.let { base ->
            // HRV values are keyed by the night's own bounds, so they are queried once the night is known.
            combine(base, dao.observeHrvValues(dayStart(epochDay) - 12 * 3600, dayEnd(epochDay))) { (night, stages, hrv), values ->
                val inNight = if (night == null) values else values.filter { it.timestamp >= night.startTimestamp && it.timestamp <= night.endTimestamp }
                NightInputs(epochDay, night, stages, hrv, inNight)
            }
        }

    // Ranges

    fun observeDayTotalsBetween(fromDay: Long, toDay: Long): Flow<List<DayTotals>> = dao.observeDayTotalsBetween(fromDay, toDay)
    fun observeDayHeartRateBetween(fromDay: Long, toDay: Long): Flow<List<DayHeartRate>> = dao.observeDayHeartRateBetween(fromDay, toDay)
    fun observeRestingHrBetween(fromDay: Long, toDay: Long): Flow<List<RestingHrDaily>> = dao.observeRestingHrBetween(fromDay, toDay)
    fun observeHrvSummaryBetween(fromDay: Long, toDay: Long): Flow<List<HrvSummary>> = dao.observeHrvSummaryBetween(fromDay, toDay)
    fun observeMetrics(type: MetricType, fromDay: Long, toDay: Long): Flow<List<DailyMetric>> = dao.observeMetrics(type, fromDay, toDay)

    fun observeDayStressBetween(fromDay: Long, toDay: Long): Flow<List<DayStress>> =
        dao.observeDayStressBetween(dayStart(fromDay), dayEnd(toDay), Dates.zoneOffsetSeconds(fromDay, zone))

    fun observeDayIntensityBetween(fromDay: Long, toDay: Long): Flow<List<DayIntensity>> =
        dao.observeDayIntensityBetween(dayStart(fromDay), dayEnd(toDay), Dates.zoneOffsetSeconds(fromDay, zone))

    // Trends

    /** One value per local day for a metric over an inclusive day range. */
    fun observeTrendValues(metric: TrendMetric, fromDay: Long, toDay: Long): Flow<Map<Long, Double>> = when (metric) {
        TrendMetric.STEPS -> observeDayTotalsBetween(fromDay, toDay).map { rows -> rows.associate { it.epochDay to it.steps.toDouble() } }
        TrendMetric.RESTING_HR -> observeRestingHrBetween(fromDay, toDay).map { rows -> rows.associate { it.epochDay to it.bpm.toDouble() } }
        TrendMetric.BODY_BATTERY_LOW -> observeDayStressBetween(fromDay, toDay).map { rows ->
            rows.mapNotNull { r -> r.bodyBatteryMin?.let { r.epochDay to it.toDouble() } }.toMap()
        }
        TrendMetric.STRESS -> observeDayStressBetween(fromDay, toDay).map { rows ->
            rows.mapNotNull { r -> r.stressAvg?.let { r.epochDay to it } }.toMap()
        }
        TrendMetric.SLEEP_SCORE -> observeSleepNightsBetween(fromDay, toDay).map { rows ->
            rows.mapNotNull { n -> n.score?.let { n.epochDay to it.toDouble() } }.toMap()
        }
        TrendMetric.SLEEP_DURATION -> observeSleepNightsBetween(fromDay, toDay).map { rows ->
            rows.associate { it.epochDay to it.asleepSeconds / 60.0 }
        }
        TrendMetric.HRV -> observeHrvSummaryBetween(fromDay, toDay).map { rows ->
            rows.mapNotNull { h -> h.lastNightAvg?.let { h.epochDay to it } }.toMap()
        }
        TrendMetric.VO2MAX -> metricValues(MetricType.VO2MAX, fromDay, toDay)
        TrendMetric.TRAINING_LOAD -> metricValues(MetricType.TRAINING_LOAD_ACUTE, fromDay, toDay)
        TrendMetric.READINESS -> metricValues(MetricType.READINESS, fromDay, toDay)
        TrendMetric.INTENSITY_MINUTES -> observeDayIntensityBetween(fromDay, toDay).map { rows ->
            rows.associate { it.epochDay to (it.moderate + 2 * it.vigorous).toDouble() }
        }
        TrendMetric.FLOORS -> observeDayTotalsBetween(fromDay, toDay).map { rows -> rows.associate { it.epochDay to Floors.of(it.ascentM).toDouble() } }
        TrendMetric.CALORIES -> observeTotalCalories(fromDay, toDay)
        TrendMetric.SLEEP_NEED -> observeSleepNightsBetween(fromDay, toDay).map { rows ->
            rows.associate { it.epochDay to (if (it.asleepSeconds > 0) it.asleepSeconds else it.durationSeconds) / 60.0 }
        }
    }

    /** The second series of a metric (active calories, the night's sleep need); empty for metrics without one. */
    fun observeTrendSecondary(metric: TrendMetric, fromDay: Long, toDay: Long): Flow<Map<Long, Double>> = when (metric) {
        TrendMetric.CALORIES -> observeDayTotalsBetween(fromDay, toDay).map { rows -> rows.associate { it.epochDay to it.activeKcal.toDouble() } }
        TrendMetric.SLEEP_NEED -> observeSleepNightsBetween(fromDay, toDay).map { rows ->
            rows.mapNotNull { n -> n.sleepNeedMin?.let { n.epochDay to it.toDouble() } }.toMap()
        }
        else -> flowOf(emptyMap())
    }

    /**
     * Resting plus active calories per day. The resting side is the watch's resting metabolic rate
     * for the day, or the latest earlier one (looked up to sixty days back), whole for finished
     * days and prorated for the day that is still running. Days after today are left out.
     */
    private fun observeTotalCalories(fromDay: Long, toDay: Long): Flow<Map<Long, Double>> =
        combine(observeDayTotalsBetween(fromDay, toDay), observeMetrics(MetricType.RMR, fromDay - RMR_LOOKBACK_DAYS, toDay)) { totals, rmrRows ->
            val rmrByDay = rmrRows.associate { it.epochDay to it.value.toInt() }
            val nowSeconds = now()
            val today = Dates.epochDayOfSeconds(nowSeconds, zone)
            var carried: Int? = rmrRows.filter { it.epochDay < fromDay }.maxByOrNull { it.epochDay }?.value?.toInt()
            val activeByDay = totals.associate { it.epochDay to it.activeKcal }
            val result = LinkedHashMap<Long, Double>()
            for (day in fromDay..toDay) {
                rmrByDay[day]?.let { carried = it }
                if (day > today) continue
                val active = activeByDay[day]
                val resting = DaySummary.restingCalories(carried, dayStart(day), dayEnd(day), nowSeconds)
                if (active == null && resting == null) continue
                result[day] = ((resting ?: 0) + (active ?: 0)).toDouble()
            }
            result
        }

    private fun metricValues(type: MetricType, fromDay: Long, toDay: Long): Flow<Map<Long, Double>> =
        observeMetrics(type, fromDay, toDay).map { rows -> rows.associate { it.epochDay to it.value } }

    /** Buckets ready for the Trends chart, for the period ending today. */
    fun observeTrend(metric: TrendMetric, period: TrendPeriod, today: Long = Dates.todayEpochDay()): Flow<TrendResult> {
        val range = Trends.dayRange(period, today)
        return combine(observeTrendValues(metric, range.first, range.last), observeTrendSecondary(metric, range.first, range.last)) { values, secondary ->
            Trends.bucket(metric, values, period, today, secondary)
        }
    }

    companion object {
        /** How far back the resting metabolic rate is looked up for the calories trend. */
        const val RMR_LOOKBACK_DAYS = 60L
    }
}
