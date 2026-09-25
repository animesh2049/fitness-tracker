package com.animesh.fitnesstracker.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animesh.fitnesstracker.di.AppContainer
import com.animesh.fitnesstracker.domain.health.TrendMetric
import com.animesh.fitnesstracker.domain.health.TrendPeriod
import com.animesh.fitnesstracker.domain.health.TrendResult
import com.animesh.fitnesstracker.util.Dates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Copy and formatting rules for one metric, lifted from the Trends design. */
data class MetricInfo(val unitWord: String, val chartTitle: String, val noteTitle: String, val noteBody: String)

data class TrendsState(
    val loading: Boolean = true,
    val metric: TrendMetric = TrendMetric.RESTING_HR,
    val period: TrendPeriod = TrendPeriod.DAY7,
    val metricName: String = TrendMetric.RESTING_HR.label,
    val periodLabel: String = "",
    val chartTitle: String = "",
    val chartRange: String = "",
    val hasData: Boolean = false,
    val values: List<Double?> = emptyList(),
    val labels: List<String> = emptyList(),
    val bars: Boolean = false,
    /** Version 0.5: the stacked part (calories) or the marker (sleep need) per bucket. */
    val secondary: List<Double?> = emptyList(),
    val secondaryMode: SecondaryMode = SecondaryMode.NONE,
    val selected: Int? = null,
    val min: Double = 0.0,
    val max: Double = 1.0,
    val gridLabels: List<String> = emptyList(),
    val bubble: String? = null,
    val average: String = "n/a",
    val unitWord: String = "",
    val bestWord: String = "Best",
    val best: String = "n/a",
    val bestWhen: String = "",
    val change: String = "n/a",
    /** True better, false worse, null neutral or unknown. */
    val changeGood: Boolean? = null,
    val changeSub: String = "",
    val noteTitle: String = "",
    val noteBody: String = ""
)

@OptIn(ExperimentalCoroutinesApi::class)
class TrendsViewModel(private val c: AppContainer) : ViewModel() {
    private val metric = MutableStateFlow(TrendMetric.RESTING_HR)
    private val period = MutableStateFlow(TrendPeriod.DAY7)
    private val picked = MutableStateFlow<Int?>(null)

    private val result = combine(metric, period) { m, p -> m to p }
        .flatMapLatest { (m, p) -> c.health.observeTrend(m, p, Dates.todayEpochDay()) }

    val state: StateFlow<TrendsState> = combine(result, picked) { r, sel -> build(r, sel) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrendsState())

    fun pickMetric(m: TrendMetric) {
        metric.value = m
        picked.value = null
    }

    fun pickPeriod(p: TrendPeriod) {
        period.value = p
        picked.value = null
    }

    fun select(index: Int) {
        picked.value = index
    }

    private fun build(r: TrendResult, pickedIndex: Int?): TrendsState {
        val m = r.metric
        val p = r.period
        val info = INFO.getValue(m)
        val values = r.buckets.map { it.value }
        val present = values.filterNotNull()
        val hasData = present.isNotEmpty()
        val lo = present.minOrNull() ?: 0.0
        val hi = present.maxOrNull() ?: 1.0
        val pad = ((hi - lo) * 0.25).takeIf { it > 0 } ?: (hi * 0.1).takeIf { it > 0 } ?: 1.0
        val min = if (m.bars) 0.0 else floor(lo - pad).coerceAtLeast(0.0)
        val max = ceil(hi + pad).let { if (it <= min) min + 1 else it }
        val selected = pickedIndex?.takeIf { it in values.indices && values[it] != null }
            ?: values.indexOfLast { it != null }.takeIf { it >= 0 }
        val first = r.buckets.firstOrNull()?.startDay ?: Dates.todayEpochDay()
        val last = r.buckets.lastOrNull()?.endDay ?: Dates.todayEpochDay()
        val change = r.change

        return TrendsState(
            loading = false,
            metric = m,
            period = p,
            metricName = m.label,
            periodLabel = periodLabel(p, first, last),
            chartTitle = info.chartTitle,
            chartRange = if (p == TrendPeriod.DAY7 || p == TrendPeriod.WEEK4) HealthFormat.dayRange(first, last) else HealthFormat.monthRange(first, last),
            hasData = hasData,
            values = values,
            labels = r.buckets.map { it.label },
            bars = r.bars,
            secondary = if (secondaryMode(m) == SecondaryMode.NONE) emptyList() else r.buckets.map { it.secondary },
            secondaryMode = secondaryMode(m),
            selected = selected,
            min = min,
            max = max,
            gridLabels = listOf(min, (min + max) / 2, max).map { gridLabel(m, it) },
            bubble = selected?.let { values[it] }?.let { format(m, it) },
            average = r.average?.let { format(m, it) } ?: "n/a",
            unitWord = info.unitWord,
            bestWord = if (m.higherIsBetter) "Best" else "Lowest",
            best = r.best?.let { format(m, it) } ?: "n/a",
            bestWhen = r.bestIndex?.let { r.buckets.getOrNull(it)?.label } ?: "",
            change = change?.let { HealthFormat.signed(it) { v -> format(m, v) } } ?: "n/a",
            changeGood = change?.let { if (abs(it) < 0.05) null else if (m.higherIsBetter) it >= 0 else it <= 0 },
            changeSub = if (change != null) "vs previous ${periodShort(p)}" else "no earlier data",
            noteTitle = info.noteTitle,
            noteBody = info.noteBody
        )
    }

    private fun periodLabel(p: TrendPeriod, first: Long, last: Long): String = when (p) {
        TrendPeriod.DAY7 -> {
            val a = LocalDate.ofEpochDay(first)
            val b = LocalDate.ofEpochDay(last)
            val range = if (a.month == b.month && a.year == b.year) "${a.dayOfMonth} to ${HealthFormat.dayMonth(last)}" else HealthFormat.dayRange(first, last)
            "Last 7 days · $range"
        }
        TrendPeriod.WEEK4 -> "Last 4 weeks · weekly averages"
        TrendPeriod.MONTH6 -> "Last 6 months · monthly averages"
        TrendPeriod.YEAR12 -> "Last 12 months · monthly averages"
    }

    companion object {
        val PERIOD_LABELS = listOf("7D", "4W", "6M", "1Y")

        fun periodShort(p: TrendPeriod): String = when (p) {
            TrendPeriod.DAY7 -> "week"
            TrendPeriod.WEEK4 -> "4 weeks"
            TrendPeriod.MONTH6 -> "6 months"
            TrendPeriod.YEAR12 -> "year"
        }

        /** How the chart draws a bucket's secondary value, if the metric has one. */
        fun secondaryMode(m: TrendMetric): SecondaryMode = when (m) {
            TrendMetric.CALORIES -> SecondaryMode.STACK
            TrendMetric.SLEEP_NEED -> SecondaryMode.MARKER
            else -> SecondaryMode.NONE
        }

        /** A value in the metric's display form: "8,412", "7 h 12 m", "58 ms", "54". */
        fun format(m: TrendMetric, v: Double): String = when (m) {
            TrendMetric.STEPS, TrendMetric.CALORIES -> HealthFormat.thousands(v.roundToInt())
            TrendMetric.SLEEP_DURATION, TrendMetric.SLEEP_NEED -> HealthFormat.durationMinutes(v.roundToInt())
            TrendMetric.HRV -> "${HealthFormat.oneDecimal(v)} ms"
            TrendMetric.FLOORS -> v.roundToInt().toString()
            else -> HealthFormat.oneDecimal(v)
        }

        /** Short grid label: "9k" for steps, hours for sleep, one decimal otherwise. */
        fun gridLabel(m: TrendMetric, v: Double): String = when (m) {
            TrendMetric.STEPS -> "${(v / 1000).roundToInt()}k"
            TrendMetric.CALORIES -> if (v >= 1000) "${HealthFormat.oneDecimal(v / 1000)}k" else v.roundToInt().toString()
            TrendMetric.SLEEP_DURATION, TrendMetric.SLEEP_NEED -> "${HealthFormat.oneDecimal(v / 60)} h"
            TrendMetric.FLOORS -> v.roundToInt().toString()
            else -> HealthFormat.oneDecimal(v)
        }

        val INFO: Map<TrendMetric, MetricInfo> = mapOf(
            TrendMetric.STEPS to MetricInfo(
                "steps / day", "Steps per day", "Cumulative on the watch",
                "The watch counts steps per activity type as running totals; the app turns them into per-minute deltas and sums them per day."
            ),
            TrendMetric.RESTING_HR to MetricInfo(
                "bpm", "Resting heart rate", "Daily value from the watch",
                "One resting value per day, taken from the monitoring file. Lower over months usually means better aerobic fitness."
            ),
            TrendMetric.BODY_BATTERY_LOW to MetricInfo(
                "lowest of the day", "Body Battery low", "Computed on the watch",
                "Body Battery arrives every three minutes in the stress records. The trend shows the lowest value each day; the day view shows the full curve."
            ),
            TrendMetric.STRESS to MetricInfo(
                "daily average", "Average stress", "0 to 100",
                "Rest 1 to 25, low 26 to 50, medium 51 to 75, high 76 to 100. Minutes the watch marks as unmeasurable or as activity are excluded."
            ),
            TrendMetric.SLEEP_SCORE to MetricInfo(
                "out of 100", "Sleep score", "Scored on the watch",
                "The overall score from the sleep file. Sub-scores exist on the watch and can be added later."
            ),
            TrendMetric.SLEEP_DURATION to MetricInfo(
                "hours asleep", "Time asleep", "Nights belong to the day they end",
                "Awake minutes inside the sleep window are excluded. Naps are not counted here."
            ),
            TrendMetric.HRV to MetricInfo(
                "overnight average", "Overnight HRV", "Status is the watch's call",
                "The Forerunner reports a nightly average and a personal baseline band. Balanced means the average sits inside the band."
            ),
            TrendMetric.VO2MAX to MetricInfo(
                "ml / kg / min", "VO2 max estimate", "From the metrics file",
                "Updated by the watch after outdoor runs and walks with GPS. Gym sessions do not change it."
            ),
            TrendMetric.TRAINING_LOAD to MetricInfo(
                "acute load", "Training load (7 day)", "Acute vs chronic",
                "Acute is the last seven days, chronic the last four weeks. The watch flags productive, maintaining or overreaching from their ratio."
            ),
            TrendMetric.READINESS to MetricInfo(
                "out of 100", "Training readiness", "Morning value",
                "Combines sleep, recovery time, HRV status and load on the watch. Read once per day after waking."
            ),
            TrendMetric.INTENSITY_MINUTES to MetricInfo(
                "minutes / day", "Intensity minutes", "Weekly, vigorous counts double",
                "Moderate plus two times vigorous minutes, reset every Monday. Target 150."
            ),
            TrendMetric.FLOORS to MetricInfo(
                "floors / day", "Floors climbed", "3 metres per floor",
                "The watch's barometric altimeter counts ascent; the app turns metres into floors at 3 m each, the watch's own rule."
            ),
            TrendMetric.CALORIES to MetricInfo(
                "kcal / day", "Calories", "Resting plus active",
                "Resting is the watch's resting metabolic rate, prorated through today; active comes from the monitoring file. The lighter part of each bar is active."
            ),
            TrendMetric.SLEEP_NEED to MetricInfo(
                "minutes asleep", "Sleep vs need", "Sleep Coach",
                "Bars are time asleep; the tick is the need the watch set for that night."
            )
        )
    }
}
