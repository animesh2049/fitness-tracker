package com.animesh.fitnesstracker.domain.health

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The metric chips of the Trends screen (FR50). Bars are drawn for totals, lines for the rest. */
enum class TrendMetric(val label: String, val unit: String, val higherIsBetter: Boolean, val bars: Boolean) {
    STEPS("Steps", "", true, true),
    RESTING_HR("Resting HR", "bpm", false, false),
    BODY_BATTERY_LOW("Body Battery low", "", true, false),
    STRESS("Stress", "", false, false),
    SLEEP_SCORE("Sleep score", "", true, false),
    SLEEP_DURATION("Sleep", "min", true, true),
    HRV("HRV", "ms", true, false),
    VO2MAX("VO2 max", "", true, false),
    TRAINING_LOAD("Training load", "", true, true),
    READINESS("Readiness", "", true, false),
    INTENSITY_MINUTES("Intensity minutes", "min", true, true),
    /** Floors climbed per day (version 0.5). */
    FLOORS("Floors", "floors", true, true),
    /** Total calories per day; the secondary value is the active part (version 0.5). */
    CALORIES("Calories", "kcal", true, true),
    /** Minutes asleep per night; the secondary value is Sleep Coach's need for that night (version 0.5). */
    SLEEP_NEED("Sleep need", "min", true, true)
}

enum class TrendPeriod(val label: String, val bucketCount: Int) {
    DAY7("7 days", 7), WEEK4("4 weeks", 4), MONTH6("6 months", 6), YEAR12("1 year", 12)
}

/**
 * One bar or point: the local days it covers (inclusive) and the average of the days with data.
 * [secondary] is the metric's second series (active calories, the sleep need) averaged the same way.
 */
data class TrendBucket(val startDay: Long, val endDay: Long, val value: Double?, val label: String, val secondary: Double? = null) {
    val hasValue: Boolean get() = value != null
}

data class TrendResult(
    val metric: TrendMetric,
    val period: TrendPeriod,
    val buckets: List<TrendBucket>,
    /** Average of the daily values in the period. */
    val average: Double?,
    /** Index of the best bucket (highest or lowest, per metric). */
    val bestIndex: Int?,
    val best: Double?,
    /** Average of the previous period of the same length, null without data. */
    val previousAverage: Double?,
    /** [average] minus [previousAverage]. */
    val change: Double?,
    val bars: Boolean
) {
    val changePercent: Double?
        get() {
            val prev = previousAverage ?: return null
            val c = change ?: return null
            return if (prev == 0.0) null else c * 100.0 / prev
        }
}

/**
 * Buckets daily values for the Trends screen. Values are one number per local day (steps of the
 * day, sleep score of the night ending that day, and so on). Multi-day buckets hold the average
 * of the days with data, so a partial current week compares fairly with full weeks.
 */
object Trends {
    private val dayLabel = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
    private val monthLabel = DateTimeFormatter.ofPattern("MMM", Locale.getDefault())
    private val weekLabel = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

    /** Inclusive day range a bucketing needs, including the previous period used for the change. */
    fun dayRange(period: TrendPeriod, today: Long): LongRange {
        val current = bucketBounds(period, today)
        val previous = previousBounds(period, current)
        return previous.first..current.last().second
    }

    /** Bucket start and end days (inclusive), oldest first, the last bucket ending today. */
    fun bucketBounds(period: TrendPeriod, today: Long): List<Pair<Long, Long>> {
        val date = LocalDate.ofEpochDay(today)
        return when (period) {
            TrendPeriod.DAY7 -> (6 downTo 0).map { val d = today - it; d to d }
            TrendPeriod.WEEK4 -> {
                val monday = date.with(java.time.DayOfWeek.MONDAY)
                (3 downTo 0).map { i ->
                    val start = monday.minusWeeks(i.toLong())
                    val end = if (i == 0) date else start.plusDays(6)
                    start.toEpochDay() to end.toEpochDay()
                }
            }
            TrendPeriod.MONTH6, TrendPeriod.YEAR12 -> {
                val first = date.withDayOfMonth(1)
                val n = period.bucketCount
                (n - 1 downTo 0).map { i ->
                    val start = first.minusMonths(i.toLong())
                    val end = if (i == 0) date else start.plusMonths(1).minusDays(1)
                    start.toEpochDay() to end.toEpochDay()
                }
            }
        }
    }

    /** The same span immediately before the current buckets. */
    private fun previousBounds(period: TrendPeriod, current: List<Pair<Long, Long>>): Pair<Long, Long> {
        val start = current.first().first
        val length = when (period) {
            TrendPeriod.DAY7 -> 7L
            TrendPeriod.WEEK4 -> 28L
            TrendPeriod.MONTH6, TrendPeriod.YEAR12 -> {
                val firstDate = LocalDate.ofEpochDay(start)
                start - firstDate.minusMonths(period.bucketCount.toLong()).toEpochDay()
            }
        }
        return (start - length) to (start - 1)
    }

    fun bucket(
        metric: TrendMetric,
        valuesByDay: Map<Long, Double>,
        period: TrendPeriod,
        today: Long,
        secondaryByDay: Map<Long, Double> = emptyMap()
    ): TrendResult {
        val bounds = bucketBounds(period, today)
        val buckets = bounds.map { (start, end) ->
            val values = (start..end).mapNotNull { valuesByDay[it] }
            val secondary = (start..end).mapNotNull { secondaryByDay[it] }
            TrendBucket(start, end, if (values.isEmpty()) null else values.average(), label(period, start), if (secondary.isEmpty()) null else secondary.average())
        }
        val currentValues = (bounds.first().first..bounds.last().second).mapNotNull { valuesByDay[it] }
        val (prevStart, prevEnd) = previousBounds(period, bounds)
        val previousValues = (prevStart..prevEnd).mapNotNull { valuesByDay[it] }
        val average = if (currentValues.isEmpty()) null else currentValues.average()
        val previousAverage = if (previousValues.isEmpty()) null else previousValues.average()
        val withValue = buckets.withIndex().filter { it.value.value != null }
        val bestEntry = if (metric.higherIsBetter) withValue.maxByOrNull { it.value.value!! } else withValue.minByOrNull { it.value.value!! }
        return TrendResult(
            metric = metric,
            period = period,
            buckets = buckets,
            average = average,
            bestIndex = bestEntry?.index,
            best = bestEntry?.value?.value,
            previousAverage = previousAverage,
            change = if (average != null && previousAverage != null) average - previousAverage else null,
            bars = metric.bars
        )
    }

    private fun label(period: TrendPeriod, startDay: Long): String {
        val date = LocalDate.ofEpochDay(startDay)
        return when (period) {
            TrendPeriod.DAY7 -> date.format(dayLabel)
            TrendPeriod.WEEK4 -> date.format(weekLabel)
            TrendPeriod.MONTH6, TrendPeriod.YEAR12 -> date.format(monthLabel)
        }
    }
}
