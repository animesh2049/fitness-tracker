package com.animesh.fitnesstracker.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animesh.fitnesstracker.data.model.DayLogKind
import com.animesh.fitnesstracker.data.model.ExerciseType
import com.animesh.fitnesstracker.data.model.GroupExerciseWithSets
import com.animesh.fitnesstracker.data.model.GroupWithExercises
import com.animesh.fitnesstracker.data.model.RoutineWithSlots
import com.animesh.fitnesstracker.data.model.SessionWithExercises
import com.animesh.fitnesstracker.data.model.Settings
import com.animesh.fitnesstracker.data.model.WeightUnit
import com.animesh.fitnesstracker.di.AppContainer
import com.animesh.fitnesstracker.domain.LoggedSet
import com.animesh.fitnesstracker.domain.cycle.CycleEngine
import com.animesh.fitnesstracker.domain.cycle.CycleState
import com.animesh.fitnesstracker.domain.cycle.TodaySlot
import com.animesh.fitnesstracker.domain.planner.SessionPlanner
import com.animesh.fitnesstracker.domain.progression.ProgressionConfig
import com.animesh.fitnesstracker.domain.progression.ProgressionEngine
import com.animesh.fitnesstracker.domain.progression.Suggestion
import com.animesh.fitnesstracker.domain.progression.SuggestionKind
import com.animesh.fitnesstracker.util.Dates
import com.animesh.fitnesstracker.util.Weights
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Decision { ACCEPTED, KEPT }

data class ExerciseCard(
    val ge: GroupExerciseWithSets,
    val summary: String,
    val typeLabel: String,
    val suggestion: Suggestion?,
    val decision: Decision?,
    /** Unit-aware label of the suggested target, for example "62.5 kg" or "3 × 11". */
    val suggestionLabel: String
) {
    val groupExerciseId: Long get() = ge.groupExercise.id
    val pending: Boolean get() = suggestion?.isActionable == true && decision == null
}

sealed interface TodayUi {
    data object Loading : TodayUi
    data object NoRoutine : TodayUi
    data class Rest(val title: String, val subtitle: String, val headline: String, val body: String, val showDoneResting: Boolean) : TodayUi
    data class Workout(
        val group: GroupWithExercises,
        val cards: List<ExerciseCard>,
        val title: String,
        val subtitle: String,
        val swapped: Boolean
    ) : TodayUi
}

data class SwapOption(val group: GroupWithExercises, val meta: String)

data class TodayState(
    val ui: TodayUi = TodayUi.Loading,
    val dateLine: String = "",
    val inProgress: SessionWithExercises? = null,
    val showSwap: Boolean = false,
    val swapOptions: List<SwapOption> = emptyList(),
    val unit: WeightUnit = WeightUnit.KG
)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(private val c: AppContainer) : ViewModel() {
    private val decisions = MutableStateFlow<Map<Long, Decision>>(emptyMap())
    private val showSwap = MutableStateFlow(false)
    private var decisionsKey: String? = null

    private val _events = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    /** Emits the id of a session that was just started, for navigation. */
    val sessionStarted: SharedFlow<Long> = _events

    val state: StateFlow<TodayState> = combine(
        c.routines.observeActive(), c.groups.observeGroups(), c.settings.observe(), c.sessions.observeInProgress(),
        combine(decisions, showSwap) { d, s -> d to s }
    ) { routine, groups, settings, inProgress, (decisions, showSwap) ->
        Inputs(routine, groups, settings, inProgress, decisions, showSwap)
    }.mapLatest { build(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState())

    private data class Inputs(
        val routine: RoutineWithSlots?, val groups: List<GroupWithExercises>, val settings: Settings,
        val inProgress: SessionWithExercises?, val decisions: Map<Long, Decision>, val showSwap: Boolean
    )

    private suspend fun build(i: Inputs): TodayState {
        val today = Dates.todayEpochDay()
        val dateLine = Dates.shortDay(today)
        val swapOptions = i.groups.map { SwapOption(it, "${it.exercises.size} exercises · ${it.workingSetCount} sets") }
        val routine = i.routine ?: return TodayState(TodayUi.NoRoutine, dateLine, i.inProgress, i.showSwap, swapOptions, i.settings.unit)
        val slots = routine.sortedSlots.map { it.slot.groupId }
        val state = CycleState.of(routine.routine)
        val slot = CycleEngine.today(slots, state, today)
        val cycleLen = slots.size
        val nextName = CycleEngine.upcoming(slots, state, today, 2).getOrNull(1).let { id -> if (id == null) "Rest" else i.groups.firstOrNull { it.group.id == id }?.group?.name ?: "Rest" }

        val ui: TodayUi = when (slot) {
            TodaySlot.Empty -> TodayUi.NoRoutine
            is TodaySlot.Rest -> {
                val dayNum = slot.position + 1
                val scheduledName = slots[slot.position].let { id -> i.groups.firstOrNull { it.group.id == id }?.group?.name }
                if (slot.logged) TodayUi.Rest(
                    title = "Rest day", subtitle = "Logged · cycle stays on day $dayNum of $cycleLen",
                    headline = "Rest day logged", body = "${scheduledName ?: "Your next workout"} stays scheduled. It will show here tomorrow.",
                    showDoneResting = false
                ) else TodayUi.Rest(
                    title = "Rest day", subtitle = "Cycle day $dayNum of $cycleLen · Next: $nextName",
                    headline = "Scheduled rest", body = "Next workout is $nextName. Tap Done resting to move the cycle on, or train anyway.",
                    showDoneResting = true
                )
            }
            is TodaySlot.Workout -> {
                val group = i.groups.firstOrNull { it.group.id == slot.groupId } ?: c.groups.getGroup(slot.groupId)
                if (group == null) TodayUi.NoRoutine else {
                    val key = "${group.group.id}:$today"
                    val decisions = if (decisionsKey == key) i.decisions else { decisionsKey = key; this.decisions.value = emptyMap(); emptyMap() }
                    val cards = group.sortedExercises.map { ge -> card(ge, i.settings, decisions[ge.groupExercise.id]) }
                    val dayNum = slot.position + 1
                    TodayUi.Workout(
                        group, cards, title = group.group.name,
                        subtitle = if (slot.swapped) "Swapped in · cycle day $dayNum of $cycleLen" else "Cycle day $dayNum of $cycleLen · ${group.exercises.size} exercises",
                        swapped = slot.swapped
                    )
                }
            }
        }
        return TodayState(ui, dateLine, i.inProgress, i.showSwap, swapOptions, i.settings.unit)
    }

    private suspend fun card(ge: GroupExerciseWithSets, settings: Settings, decision: Decision?): ExerciseCard {
        val history = c.sessions.setsForExercise(ge.exercise.id).map { LoggedSet.from(it) }
        val current = SessionPlanner.currentTargets(ge)
        val suggestion = ProgressionEngine.suggest(
            ge.exercise, current, history, ProgressionConfig(settings.deloadAfterFailures, settings.deloadPercent)
        ) { Weights.formatWithUnit(it, settings.unit) }
        val accepted = decision == Decision.ACCEPTED && suggestion?.isActionable == true
        val working = ge.sortedSets.filter { !it.isWarmup }.ifEmpty { ge.sortedSets }
        val n = working.size
        val reps = if (accepted) suggestion!!.newReps ?: current.reps else current.reps
        val weight = if (accepted) suggestion!!.newWeightKg ?: current.weightKg else current.weightKg
        val secs = if (accepted) suggestion!!.newSeconds ?: current.seconds else current.seconds
        val summary = when (ge.exercise.type) {
            ExerciseType.TIMED -> "$n × ${secs ?: 0} s"
            ExerciseType.BODYWEIGHT -> if ((weight ?: 0.0) > 0) "$n × ${reps ?: 0} · +${Weights.formatWithUnit(weight!!, settings.unit)}" else "$n × ${reps ?: 0} · bodyweight"
            ExerciseType.WEIGHT -> "$n × ${reps ?: 0} · ${Weights.formatWithUnit(weight ?: 0.0, settings.unit)}"
        }
        val typeLabel = when (ge.exercise.type) { ExerciseType.WEIGHT -> "Weight"; ExerciseType.BODYWEIGHT -> "Body"; ExerciseType.TIMED -> "Timed" }
        val label = suggestion?.let { s ->
            when {
                s.newSeconds != null -> "${s.newSeconds} s"
                s.kind == SuggestionKind.REPS_UP -> "$n × ${s.newReps}"
                s.newWeightKg != null && s.newReps != null && s.newReps != current.reps -> "${Weights.formatWithUnit(s.newWeightKg, settings.unit)} × ${s.newReps}"
                s.newWeightKg != null -> Weights.formatWithUnit(s.newWeightKg, settings.unit)
                s.newReps != null -> "$n × ${s.newReps}"
                else -> ""
            }
        } ?: ""
        return ExerciseCard(ge, summary, typeLabel, suggestion, decision, label)
    }

    fun accept(groupExerciseId: Long) { decisions.value = decisions.value + (groupExerciseId to Decision.ACCEPTED) }
    fun keep(groupExerciseId: Long) { decisions.value = decisions.value + (groupExerciseId to Decision.KEPT) }
    fun openSwap() { showSwap.value = true }
    fun cancelSwap() { showSwap.value = false }

    fun startSession() {
        val s = state.value
        val ui = s.ui as? TodayUi.Workout ?: return
        viewModelScope.launch {
            val settings = c.settings.get()
            val accepted = ui.cards.filter { it.decision == Decision.ACCEPTED && it.suggestion != null }
                .associate { it.groupExerciseId to it.suggestion!! }
            val plan = SessionPlanner.plan(ui.group, accepted, settings)
            val routineId = c.routines.getActive()?.routine?.id
            val id = c.sessions.start(ui.group.group.id, ui.group.group.name, routineId, plan)
            _events.tryEmit(id)
        }
    }

    fun resumeSession() {
        state.value.inProgress?.let { _events.tryEmit(it.session.id) }
    }

    fun skipToday() = mutateCycle { slots, st, today, routine ->
        val name = CycleEngine.today(slots, st, today).let { s -> (s as? TodaySlot.Workout)?.groupId?.let { id -> routine.sortedSlots.firstOrNull { it.slot.groupId == id }?.group?.name } } ?: ""
        c.sessions.logDay(today, DayLogKind.SKIPPED, name)
        CycleEngine.skip(slots, st, today)
    }

    fun restToday() = mutateCycle { slots, st, today, routine ->
        val name = (CycleEngine.today(slots, st, today) as? TodaySlot.Workout)?.groupId?.let { id -> routine.sortedSlots.firstOrNull { it.slot.groupId == id }?.group?.name } ?: ""
        c.sessions.logDay(today, DayLogKind.REST, name)
        CycleEngine.restToday(slots, st, today)
    }

    fun doneResting() = mutateCycle { slots, st, today, _ ->
        c.sessions.logDay(today, DayLogKind.REST, "")
        CycleEngine.advance(slots, st, today)
    }

    fun pickSwap(groupId: Long) {
        showSwap.value = false
        mutateCycle { slots, st, today, _ -> CycleEngine.swap(slots, st, groupId, today) }
    }

    private fun mutateCycle(block: suspend (slots: List<Long?>, state: CycleState, today: Long, routine: RoutineWithSlots) -> CycleState) {
        viewModelScope.launch {
            val routine = c.routines.getActive() ?: return@launch
            val slots = routine.sortedSlots.map { it.slot.groupId }
            val today = Dates.todayEpochDay()
            val next = block(slots, CycleState.of(routine.routine), today, routine)
            c.routines.update(next.applyTo(routine.routine))
        }
    }
}
