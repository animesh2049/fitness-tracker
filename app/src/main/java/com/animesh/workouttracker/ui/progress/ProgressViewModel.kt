package com.animesh.workouttracker.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animesh.workouttracker.data.model.Exercise
import com.animesh.workouttracker.data.model.ExerciseType
import com.animesh.workouttracker.data.model.Settings
import com.animesh.workouttracker.data.model.WeightUnit
import com.animesh.workouttracker.di.AppContainer
import com.animesh.workouttracker.domain.LoggedSet
import com.animesh.workouttracker.domain.records.RecordKind
import com.animesh.workouttracker.domain.records.Records
import com.animesh.workouttracker.util.Dates
import com.animesh.workouttracker.util.Weights
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlin.math.roundToInt

data class ExerciseChip(val id: Long, val name: String, val selected: Boolean)

/** One plotted session, already converted to display units. */
data class ChartPoint(
    val epochDay: Long,
    val dateLabel: String,
    val value: Double,
    /** Value with unit, shown in the bubble above the selected point. */
    val valueLabel: String
)

data class ChartData(
    val title: String,
    val rangeText: String,
    val points: List<ChartPoint>,
    val axis: AxisBounds,
    /** Grid labels for [AxisBounds.gridValues], bottom to top. */
    val gridLabels: List<String>,
    val selectedIndex: Int
)

data class StatData(val label: String, val value: String, val sub: String)

data class RecordRow(val label: String, val date: String, val value: String)

data class ProgressState(
    val loading: Boolean = true,
    val chips: List<ExerciseChip> = emptyList(),
    val exercise: Exercise? = null,
    val subtitle: String = "",
    val chart: ChartData? = null,
    val stats: List<StatData> = emptyList(),
    val records: List<RecordRow> = emptyList(),
    val unit: WeightUnit = WeightUnit.KG
)

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressViewModel(private val c: AppContainer) : ViewModel() {
    private val selectedExercise = MutableStateFlow<Long?>(null)
    /** Selected chart point keyed by exercise id; null means the newest point. */
    private val selectedPoint = MutableStateFlow<Pair<Long, Int>?>(null)

    private data class Inputs(val chips: List<ExerciseChip>, val exercise: Exercise?, val settings: Settings)

    val state: StateFlow<ProgressState> = combine(
        c.sessions.observeExercisesWithHistory(), c.exercises.observeAll(), c.settings.observe(), selectedExercise
    ) { ids, exercises, settings, picked ->
        val withHistory = exercises.filter { it.id in ids.toSet() }.sortedBy { it.name.lowercase() }
        val current = withHistory.firstOrNull { it.id == picked } ?: withHistory.firstOrNull()
        Inputs(withHistory.map { ExerciseChip(it.id, it.name, it.id == current?.id) }, current, settings)
    }.flatMapLatest { inputs ->
        val ex = inputs.exercise
        if (ex == null) flowOf(ProgressState(loading = false, chips = inputs.chips, unit = inputs.settings.unit))
        else combine(c.sessions.observeSetsForExercise(ex.id), selectedPoint) { sets, sel ->
            build(inputs, ex, sets.map { LoggedSet.from(it) }, sel?.takeIf { it.first == ex.id }?.second)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgressState())

    fun selectExercise(id: Long) { selectedExercise.value = id }

    fun selectPoint(index: Int) {
        val ex = state.value.exercise ?: return
        selectedPoint.value = ex.id to index
    }

    private fun build(inputs: Inputs, ex: Exercise, history: List<LoggedSet>, pickedPoint: Int?): ProgressState {
        val unit = inputs.settings.unit
        val series = ProgressMath.series(history, ex.type)
        val timed = ex.type == ExerciseType.TIMED
        val n = series.points.size
        val metricName = if (timed) "Longest hold" else "Top set weight"
        val subtitle = "$metricName · last $n ${if (n == 1) "session" else "sessions"}"
        val fmtWeight: (Double) -> String = { Weights.formatWithUnit(it, unit) }

        val chart = if (series.isEmpty) null else {
            val displayValues = series.points.map { displayValue(it.value, series.metric, unit) }
            val axis = ProgressMath.axisBounds(displayValues)
            val points = series.points.mapIndexed { i, p ->
                ChartPoint(p.epochDay, Dates.dayMonth(p.epochDay), displayValues[i], valueLabel(displayValues[i], series.metric, unit))
            }
            val first = Dates.dayMonth(series.points.first().epochDay)
            val last = Dates.dayMonth(series.points.last().epochDay)
            ChartData(
                title = metricName,
                rangeText = if (n == 1) first else "$first to $last",
                points = points,
                axis = axis,
                gridLabels = axis.gridValues.map { valueLabel(it, series.metric, unit) },
                selectedIndex = (pickedPoint ?: (n - 1)).coerceIn(0, n - 1)
            )
        }

        val stats = if (timed) timedStats(history, series) else weightStats(history, series, unit)

        val records = Records.summary(history, fmtWeight).map { r ->
            val value = when (r.kind) {
                RecordKind.HEAVIEST_FOR_REPS, RecordKind.BEST_E1RM -> fmtWeight(r.value)
                RecordKind.MOST_REPS_AT_WEIGHT -> r.value.roundToInt().toString()
                RecordKind.LONGEST_HOLD -> "${r.value.roundToInt()} s"
            }
            RecordRow(r.label, Dates.full(r.epochDay), value)
        }

        return ProgressState(false, inputs.chips, ex, subtitle, chart, stats, records, unit)
    }

    private fun weightStats(history: List<LoggedSet>, series: Series, unit: WeightUnit): List<StatData> {
        val last = series.points.lastOrNull()
        val unitName = unit.name.lowercase()
        val topSet = when {
            last == null -> StatData("Top set", "0", "no sets yet")
            series.metric == SeriesMetric.REPS -> StatData("Top set", "${last.reps ?: 0}", "reps, bodyweight")
            else -> StatData("Top set", "${Weights.format(Weights.toDisplay(last.value, unit))} × ${last.reps ?: 0}", "$unitName × reps")
        }
        val e1 = Records.bestE1rm(history)
        val est = if (e1 == null) StatData("Est. 1RM", "0", "no weighted sets") else StatData(
            "Est. 1RM",
            Weights.formatWithUnit(Records.epley(e1.actualWeightKg!!, e1.actualReps!!).roundToInt().toDouble(), unit),
            "Epley, ${Dates.dayMonth(e1.epochDay)}"
        )
        val volume = StatData("Volume", Weights.formatVolume(last?.volumeKg ?: 0.0, unit), "last session")
        return listOf(topSet, est, volume)
    }

    private fun timedStats(history: List<LoggedSet>, series: Series): List<StatData> {
        val best = Records.longestHold(history)?.actualSeconds ?: 0
        val sessionsAtBest = series.points.count { it.value.roundToInt() == best }
        val bestHold = StatData("Best hold", "$best s", "$sessionsAtBest ${if (sessionsAtBest == 1) "session" else "sessions"} at best")
        val last = series.points.lastOrNull()
        val total = StatData("Total", "${last?.totalSeconds ?: 0} s", "last session")
        val delta = if (series.points.size >= 2) (series.points.last().value - series.points.first().value).roundToInt() else 0
        val trend = StatData("Trend", (if (delta > 0) "+$delta" else "$delta") + " s", "over the range")
        return listOf(bestHold, total, trend)
    }

    private fun displayValue(raw: Double, metric: SeriesMetric, unit: WeightUnit): Double =
        if (metric == SeriesMetric.WEIGHT_KG) Weights.toDisplay(raw, unit) else raw

    private fun valueLabel(display: Double, metric: SeriesMetric, unit: WeightUnit): String = when (metric) {
        SeriesMetric.WEIGHT_KG -> Weights.format(display) + " " + unit.name.lowercase()
        SeriesMetric.SECONDS -> "${Weights.format(display)} s"
        SeriesMetric.REPS -> Weights.format(display)
    }
}
