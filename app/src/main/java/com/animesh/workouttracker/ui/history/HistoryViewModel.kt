package com.animesh.workouttracker.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animesh.workouttracker.data.model.DayLog
import com.animesh.workouttracker.data.model.DayLogKind
import com.animesh.workouttracker.data.model.ExerciseType
import com.animesh.workouttracker.data.model.SessionExerciseWithSets
import com.animesh.workouttracker.data.model.SessionStatus
import com.animesh.workouttracker.data.model.SessionWithExercises
import com.animesh.workouttracker.data.model.WeightUnit
import com.animesh.workouttracker.di.AppContainer
import com.animesh.workouttracker.util.Dates
import com.animesh.workouttracker.util.Weights
import java.time.YearMonth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class DotKind { TRAINED, REST, SKIPPED }

/** How an exercise's result line is coloured on the day card. */
enum class ResultTone { HIT, MISSED, SKIPPED, NONE }

data class DayCell(
    val epochDay: Long,
    val dayOfMonth: Int,
    val dot: DotKind?,
    val isToday: Boolean,
    val isFuture: Boolean
)

data class ExerciseRow(val name: String, val result: String, val tone: ResultTone)

data class SessionSummary(
    val id: Long,
    val title: String,
    val abandoned: Boolean,
    val meta: String,
    val rows: List<ExerciseRow>
)

sealed interface DayDetail {
    data class Sessions(val sessions: List<SessionSummary>) : DayDetail
    data object Rest : DayDetail
    data class Skipped(val groupName: String) : DayDetail
    data object TodayEmpty : DayDetail
    data class None(val future: Boolean) : DayDetail
}

data class HistoryState(
    val monthTitle: String = "",
    val subtitle: String = "",
    /** Seven cells per row, Monday first; null is padding outside the month. */
    val cells: List<DayCell?> = emptyList(),
    val selectedDay: Long = 0,
    val selectedLabel: String = "",
    val detail: DayDetail = DayDetail.None(false),
    val canGoNext: Boolean = false,
    val loaded: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(private val c: AppContainer) : ViewModel() {
    private val month = MutableStateFlow(YearMonth.now())
    private val selected = MutableStateFlow(Dates.todayEpochDay())

    private data class MonthData(val month: YearMonth, val sessions: List<SessionWithExercises>, val logs: List<DayLog>)

    private val monthData = month.flatMapLatest { ym ->
        val first = ym.atDay(1).toEpochDay()
        val last = ym.atEndOfMonth().toEpochDay()
        combine(c.sessions.observeBetween(first, last), c.sessions.observeDayLogsBetween(first, last)) { s, l -> MonthData(ym, s, l) }
    }

    val state: StateFlow<HistoryState> = combine(monthData, selected, c.settings.observe()) { data, sel, settings ->
        build(data, sel, settings.unit)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryState(monthTitle = Dates.monthYear(YearMonth.now().atDay(1))))

    private fun build(data: MonthData, sel: Long, unit: WeightUnit): HistoryState {
        val ym = data.month
        val today = Dates.todayEpochDay()
        val first = ym.atDay(1)
        val length = ym.lengthOfMonth()
        val sessionsByDay = data.sessions.groupBy { it.session.epochDay }
        val logsByDay = data.logs.groupBy { it.epochDay }

        val cells = ArrayList<DayCell?>()
        repeat(first.dayOfWeek.value - 1) { cells += null }
        for (d in 1..length) {
            val epochDay = first.plusDays((d - 1).toLong()).toEpochDay()
            val kinds = logsByDay[epochDay].orEmpty().map { it.kind }
            val dot = when {
                sessionsByDay.containsKey(epochDay) -> DotKind.TRAINED
                DayLogKind.SKIPPED in kinds -> DotKind.SKIPPED
                DayLogKind.REST in kinds -> DotKind.REST
                else -> null
            }
            cells += DayCell(epochDay, d, dot, isToday = epochDay == today, isFuture = epochDay > today)
        }
        while (cells.size % 7 != 0) cells += null

        val sessionCount = data.sessions.size
        val skipped = data.logs.count { it.kind == DayLogKind.SKIPPED }
        val rest = data.logs.count { it.kind == DayLogKind.REST }
        val subtitle = "${plural(sessionCount, "session")} · $skipped skipped · ${plural(rest, "rest day")}"

        val daySessions = sessionsByDay[sel].orEmpty()
        val dayLogs = logsByDay[sel].orEmpty()
        val detail: DayDetail = when {
            daySessions.isNotEmpty() -> DayDetail.Sessions(daySessions.sortedBy { it.session.startedAt }.map { summary(it, unit) })
            dayLogs.any { it.kind == DayLogKind.SKIPPED } -> DayDetail.Skipped(dayLogs.first { it.kind == DayLogKind.SKIPPED }.groupName)
            dayLogs.any { it.kind == DayLogKind.REST } -> DayDetail.Rest
            sel == today -> DayDetail.TodayEmpty
            else -> DayDetail.None(future = sel > today)
        }

        return HistoryState(
            monthTitle = Dates.monthYear(first),
            subtitle = subtitle,
            cells = cells,
            selectedDay = sel,
            selectedLabel = Dates.shortDay(sel),
            detail = detail,
            canGoNext = ym < YearMonth.now(),
            loaded = true
        )
    }

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

    private fun summary(s: SessionWithExercises, unit: WeightUnit): SessionSummary {
        val parts = ArrayList<String>()
        val ended = s.session.endedAt
        if (ended != null) parts += Dates.formatDuration((ended - s.session.startedAt).coerceAtLeast(0))
        val sets = s.completedSets.count { !it.isWarmup }
        parts += if (sets == 1) "1 set" else "$sets sets"
        val volume = s.volumeKg
        if (volume > 0) parts += Weights.formatVolume(volume, unit)
        return SessionSummary(
            id = s.session.id,
            title = s.session.groupName,
            abandoned = s.session.status == SessionStatus.ABANDONED,
            meta = parts.joinToString(" · "),
            rows = s.sortedExercises.map { row(it, unit) }
        )
    }

    private fun row(se: SessionExerciseWithSets, unit: WeightUnit): ExerciseRow {
        val name = se.exercise.exerciseName
        if (se.exercise.skipped) return ExerciseRow(name, "skipped", ResultTone.SKIPPED)
        val working = se.sortedSets.filter { !it.isWarmup }.ifEmpty { se.sortedSets }
        val done = working.filter { it.completed }
        if (done.isEmpty()) return ExerciseRow(name, "no sets", ResultTone.NONE)
        val text = when (se.exercise.exerciseType) {
            ExerciseType.TIMED -> done.joinToString(" · ") { "${it.actualSeconds ?: 0}" } + " s"
            else -> {
                val reps = done.joinToString(" · ") { "${it.actualReps ?: 0}" }
                val weights = done.mapNotNull { it.actualWeightKg }.filter { it > 0 }.distinct()
                val plus = if (se.exercise.exerciseType == ExerciseType.BODYWEIGHT) "+" else ""
                val suffix = when (weights.size) {
                    0 -> ""
                    1 -> " at $plus" + Weights.formatWithUnit(weights[0], unit)
                    else -> " at $plus" + weights.joinToString("/") { Weights.format(Weights.toDisplay(it, unit)) } + " " + unit.name.lowercase()
                }
                reps + suffix
            }
        }
        val tone = if (working.all { it.hitTarget }) ResultTone.HIT else ResultTone.MISSED
        return ExerciseRow(name, text, tone)
    }

    fun previousMonth() = moveTo(month.value.minusMonths(1))

    fun nextMonth() {
        val next = month.value.plusMonths(1)
        if (next <= YearMonth.now()) moveTo(next)
    }

    private fun moveTo(ym: YearMonth) {
        month.value = ym
        val today = Dates.todayEpochDay()
        selected.value = if (ym == YearMonth.now()) today else ym.atDay(1).toEpochDay()
    }

    fun select(epochDay: Long) { selected.value = epochDay }

    fun deleteSession(id: Long) {
        viewModelScope.launch { c.sessions.delete(id) }
    }

    /** Deletes the rest or skipped log of the selected day. */
    fun deleteDayLog() {
        val day = selected.value
        viewModelScope.launch { c.sessions.clearDayLog(day) }
    }
}
