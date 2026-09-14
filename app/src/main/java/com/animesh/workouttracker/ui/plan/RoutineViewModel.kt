package com.animesh.workouttracker.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animesh.workouttracker.data.model.DayLogKind
import com.animesh.workouttracker.data.model.GroupWithExercises
import com.animesh.workouttracker.data.model.RoutineWithSlots
import com.animesh.workouttracker.di.AppContainer
import com.animesh.workouttracker.domain.cycle.CycleEngine
import com.animesh.workouttracker.domain.cycle.CycleState
import com.animesh.workouttracker.domain.cycle.TodaySlot
import com.animesh.workouttracker.util.Dates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

data class SlotRow(
    val index: Int,
    val groupId: Long?,
    val name: String,
    val meta: String,
    val isRest: Boolean,
    val isToday: Boolean,
    val isNext: Boolean
)

data class UpcomingDay(val day: String, val name: String, val isRest: Boolean, val isToday: Boolean)

data class RoutineState(
    val loading: Boolean = true,
    val routine: RoutineWithSlots? = null,
    val slots: List<SlotRow> = emptyList(),
    val upcoming: List<UpcomingDay> = emptyList(),
    val log: List<String> = emptyList()
) {
    val subtitle: String get() = "Active · ${slots.size} day cycle · advances when you finish or skip"
}

class RoutineViewModel(private val c: AppContainer) : ViewModel() {
    private val log = MutableStateFlow<List<String>>(emptyList())

    val state: StateFlow<RoutineState> = combine(
        c.routines.observeActive(), c.groups.observeGroups(), log
    ) { routine, groups, log -> build(routine, groups, log) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoutineState())

    private fun build(routine: RoutineWithSlots?, groups: List<GroupWithExercises>, log: List<String>): RoutineState {
        if (routine == null) return RoutineState(loading = false, log = log)
        val today = Dates.todayEpochDay()
        val ids = routine.sortedSlots.map { it.slot.groupId }
        val cycle = CycleState.of(routine.routine)
        val slot = CycleEngine.today(ids, cycle, today)
        val position = when (slot) {
            is TodaySlot.Workout -> slot.position
            is TodaySlot.Rest -> slot.position
            TodaySlot.Empty -> 0
        }
        val swappedGroup = (slot as? TodaySlot.Workout)?.takeIf { it.swapped }?.groupId
        // When a group was swapped in, highlight its slot; the cycle itself still continues after the scheduled one.
        val todayIndex = swappedGroup?.let { g -> ids.indexOf(g).takeIf { it >= 0 } } ?: position
        val nextIndex = if (ids.isEmpty()) -1 else (position + 1) % ids.size
        val byId = groups.associateBy { it.group.id }

        val rows = ids.mapIndexed { i, id ->
            val g = id?.let { byId[it] }
            SlotRow(
                index = i, groupId = id,
                name = g?.group?.name ?: if (id == null) "Rest" else "Missing group",
                meta = if (g != null) groupMeta(g) else "No workout scheduled",
                isRest = id == null,
                isToday = i == todayIndex,
                isNext = i == nextIndex && i != todayIndex
            )
        }
        val upcomingIds = CycleEngine.upcoming(ids, cycle, today, 7)
        val start = LocalDate.now()
        val upcoming = upcomingIds.mapIndexed { d, id ->
            val g = if (d == 0 && swappedGroup != null) byId[swappedGroup] else id?.let { byId[it] }
            UpcomingDay(
                day = start.plusDays(d.toLong()).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()),
                name = g?.group?.name?.removeSuffix(" day")?.removeSuffix(" Day") ?: "Rest",
                isRest = g == null,
                isToday = d == 0
            )
        }
        return RoutineState(loading = false, routine = routine, slots = rows, upcoming = upcoming, log = log)
    }

    private fun addLog(text: String) { log.value = (listOf(text) + log.value).take(3) }

    fun pickSlot(row: SlotRow) {
        if (row.isToday) return
        if (row.groupId == null) { restToday(); return }
        mutateCycle { ids, st, today, _ ->
            addLog("Swapped in ${row.name} for today.")
            CycleEngine.swap(ids, st, row.groupId, today)
        }
    }

    fun skipToday() = mutateCycle { ids, st, today, routine ->
        val name = todayName(ids, st, today, routine)
        c.sessions.logDay(today, DayLogKind.SKIPPED, name)
        addLog(if (name.isEmpty()) "Skipped today. Cycle moved on." else "Skipped $name. Cycle moved on.")
        CycleEngine.skip(ids, st, today)
    }

    fun restToday() = mutateCycle { ids, st, today, routine ->
        val name = todayName(ids, st, today, routine)
        c.sessions.logDay(today, DayLogKind.REST, name)
        addLog(if (name.isEmpty()) "Rest day logged." else "Rest day logged. $name stays scheduled for tomorrow.")
        CycleEngine.restToday(ids, st, today)
    }

    private fun todayName(ids: List<Long?>, st: CycleState, today: Long, routine: RoutineWithSlots): String =
        (CycleEngine.today(ids, st, today) as? TodaySlot.Workout)?.groupId
            ?.let { id -> routine.sortedSlots.firstOrNull { it.slot.groupId == id }?.group?.name } ?: ""

    private fun mutateCycle(block: suspend (ids: List<Long?>, state: CycleState, today: Long, routine: RoutineWithSlots) -> CycleState) {
        viewModelScope.launch {
            val routine = c.routines.getActive() ?: return@launch
            val ids = routine.sortedSlots.map { it.slot.groupId }
            val today = Dates.todayEpochDay()
            val next = block(ids, CycleState.of(routine.routine), today, routine)
            c.routines.update(next.applyTo(routine.routine))
        }
    }
}
