package com.animesh.fitnesstracker.ui.today

import android.net.Uri
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
import com.animesh.fitnesstracker.garmin.sync.SyncState
import com.animesh.fitnesstracker.garmin.sync.WatchInfo
import com.animesh.fitnesstracker.garmin.workout.EncodedWorkout
import com.animesh.fitnesstracker.garmin.workout.ExerciseMapping
import com.animesh.fitnesstracker.garmin.workout.PlannerExercise
import com.animesh.fitnesstracker.garmin.workout.WatchExercise
import com.animesh.fitnesstracker.garmin.workout.WatchExerciseKind
import com.animesh.fitnesstracker.garmin.workout.WatchSet
import com.animesh.fitnesstracker.garmin.workout.WatchWorkoutPlanner
import com.animesh.fitnesstracker.garmin.workout.WorkoutEncoder
import com.animesh.fitnesstracker.util.Dates
import com.animesh.fitnesstracker.util.Weights
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val unit: WeightUnit = WeightUnit.KG,
    /** The paired Garmin watch, null when none; gates the "Send to watch" button. */
    val watch: WatchInfo? = null
) {
    val canSendToWatch: Boolean get() = watch != null && ui is TodayUi.Workout
}

/** One exercise line of the "Send to watch" preview: name, how it maps onto Garmin's catalogue, and the sets. */
data class SendRow(val name: String, val mappingLabel: String, val setsLabel: String)

sealed interface SendPhase {
    data object Preview : SendPhase
    data class Sending(val text: String) : SendPhase
    data class Sent(val text: String) : SendPhase
    data class Failed(val text: String) : SendPhase
}

/** State of the "Send to watch" bottom sheet; null when the sheet is closed. */
data class SendSheetState(
    val workoutName: String,
    val rows: List<SendRow>,
    val encoded: EncodedWorkout?,
    /** Message when [WorkoutEncoder.encode] threw; Send is disabled but the file can still not be saved. */
    val encodeError: String?,
    val phase: SendPhase = SendPhase.Preview,
    /** Outcome of "Save file instead", shown under the buttons. */
    val saved: String? = null
) {
    val canSend: Boolean get() = encoded != null && phase !is SendPhase.Sending
    val fileName: String get() = encoded?.suggestedFileName ?: "workout.fit"
}

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(private val c: AppContainer) : ViewModel() {
    private val decisions = MutableStateFlow<Map<Long, Decision>>(emptyMap())
    private val showSwap = MutableStateFlow(false)
    private var decisionsKey: String? = null

    private val _sendSheet = MutableStateFlow<SendSheetState?>(null)
    /** The "Send to watch" sheet, null while closed. */
    val sendSheet: StateFlow<SendSheetState?> = _sendSheet
    private var sendJob: Job? = null

    private val _events = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    /** Emits the id of a session that was just started, for navigation. */
    val sessionStarted: SharedFlow<Long> = _events

    val state: StateFlow<TodayState> = combine(
        c.routines.observeActive(), c.groups.observeGroups(), c.settings.observe(), c.sessions.observeInProgress(),
        combine(decisions, showSwap, c.watch.watch) { d, s, w -> Triple(d, s, w) }
    ) { routine, groups, settings, inProgress, (decisions, showSwap, watch) ->
        Inputs(routine, groups, settings, inProgress, decisions, showSwap, watch)
    }.mapLatest { build(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState())

    private data class Inputs(
        val routine: RoutineWithSlots?, val groups: List<GroupWithExercises>, val settings: Settings,
        val inProgress: SessionWithExercises?, val decisions: Map<Long, Decision>, val showSwap: Boolean, val watch: WatchInfo?
    )

    private suspend fun build(i: Inputs): TodayState {
        val today = Dates.todayEpochDay()
        val dateLine = Dates.shortDay(today)
        val swapOptions = i.groups.map { SwapOption(it, "${it.exercises.size} exercises · ${it.workingSetCount} sets") }
        val routine = i.routine ?: return TodayState(TodayUi.NoRoutine, dateLine, i.inProgress, i.showSwap, swapOptions, i.settings.unit, i.watch)
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
        return TodayState(ui, dateLine, i.inProgress, i.showSwap, swapOptions, i.settings.unit, i.watch)
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

    // Send to watch

    /** Builds today's plan (accepted suggestions applied), encodes it and opens the preview sheet. */
    fun openSendSheet() {
        val s = state.value
        val ui = s.ui as? TodayUi.Workout ?: return
        if (_sendSheet.value != null) return
        viewModelScope.launch {
            val settings = c.settings.get()
            val accepted = ui.cards.filter { it.decision == Decision.ACCEPTED && it.suggestion != null }
                .associate { it.groupExerciseId to it.suggestion!! }
            val planned = SessionPlanner.plan(ui.group, accepted, settings)
            val exercises = ui.group.sortedExercises.zip(planned).map { (ge, pe) ->
                PlannerExercise(
                    appExerciseId = ge.exercise.id,
                    name = ge.exercise.name,
                    kind = when (ge.exercise.type) {
                        ExerciseType.WEIGHT -> WatchExerciseKind.WEIGHT
                        ExerciseType.BODYWEIGHT -> WatchExerciseKind.BODYWEIGHT
                        ExerciseType.TIMED -> WatchExerciseKind.TIMED
                    },
                    sets = pe.sets.map { WatchSet(it.targetReps, it.targetWeightKg?.takeIf { w -> w > 0 }, it.targetSeconds, it.isWarmup) },
                    restOverrideSeconds = ge.sortedSets.firstOrNull { it.restSecondsOverride != null }?.restSecondsOverride,
                    exerciseDefaultRestSeconds = ge.exercise.defaultRestSeconds,
                    supersetWithNext = ge.groupExercise.supersetWithNext
                )
            }
            val sheet = withContext(Dispatchers.Default) {
                try {
                    val plan = WatchWorkoutPlanner.plan(
                        ui.group.group.name, ui.group.group.id, Dates.todayEpochDay(), exercises, settings.defaultRestSeconds, System.currentTimeMillis()
                    )
                    val mappings = runCatching { WorkoutEncoder.mappings(plan) }.getOrDefault(emptyList())
                    val rows = plan.exercises.mapIndexed { i, e -> SendRow(e.name, mappingLabel(e.name, mappings.getOrNull(i)), setsLabel(e, settings.unit)) }
                    var error: String? = null
                    val encoded = try {
                        WorkoutEncoder.encode(plan)
                    } catch (e: Exception) {
                        error = e.message ?: e.javaClass.simpleName
                        null
                    }
                    SendSheetState(plan.name, rows, encoded, error)
                } catch (e: Exception) {
                    SendSheetState(ui.group.group.name, emptyList(), null, e.message ?: e.javaClass.simpleName)
                }
            }
            _sendSheet.value = sheet
        }
    }

    fun closeSendSheet() {
        sendJob?.cancel()
        sendJob = null
        _sendSheet.value = null
    }

    /** Hands the encoded file to the sync stack and mirrors its state into the sheet until it ends. */
    fun sendToWatch() {
        val sheet = _sendSheet.value ?: return
        val encoded = sheet.encoded ?: return
        if (sheet.phase is SendPhase.Sending) return
        val watchName = state.value.watch?.name ?: "the watch"
        val requestedAt = System.currentTimeMillis()
        if (!c.watch.requestWorkoutUpload(encoded, sheet.workoutName)) {
            _sendSheet.update { it?.copy(phase = SendPhase.Failed("Could not start: no watch paired, a sync is already running, or the Bluetooth permission is missing. See Settings, Watch.")) }
            return
        }
        _sendSheet.update { it?.copy(phase = SendPhase.Sending("Starting"), saved = null) }
        sendJob?.cancel()
        sendJob = viewModelScope.launch {
            var sawRunning = false
            c.watch.syncState.collect { s ->
                val phase: SendPhase? = when (s) {
                    SyncState.Idle -> if (sawRunning) SendPhase.Failed("Cancelled") else null
                    SyncState.Connecting -> SendPhase.Sending("Connecting to $watchName")
                    SyncState.Handshake -> SendPhase.Sending("Talking to $watchName")
                    SyncState.Listing, is SyncState.Downloading, SyncState.Importing -> SendPhase.Sending("Syncing")
                    is SyncState.Uploading -> SendPhase.Sending("Uploading ${kb(s.sentBytes)} of ${kb(s.totalBytes)} KB")
                    is SyncState.Done -> if (s.finishedAtMillis >= requestedAt) SendPhase.Sent("Sent to $watchName") else null
                    is SyncState.Failed -> if (s.failedAtMillis >= requestedAt) SendPhase.Failed(s.reason) else null
                }
                if (s.isRunning) sawRunning = true
                if (phase != null) _sendSheet.update { it?.copy(phase = phase) }
                if (phase is SendPhase.Sent || phase is SendPhase.Failed) {
                    sendJob = null
                    this.cancel()
                }
            }
        }
    }

    /** USB fallback: writes the encoded file to the document the user picked. */
    fun saveWorkoutFile(uri: Uri) {
        val encoded = _sendSheet.value?.encoded ?: return
        val name = _sendSheet.value?.fileName ?: "workout.fit"
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val out = c.appContext.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("Could not open the file for writing")
                    out.use { it.write(encoded.bytes) }
                }
                "Saved $name. Connect the watch over USB and copy it into GARMIN/NewFiles."
            } catch (e: Exception) {
                "Save failed: ${e.message ?: e.javaClass.simpleName}"
            }
            _sendSheet.update { it?.copy(saved = result) }
        }
    }

    private fun mappingLabel(appName: String, m: ExerciseMapping?): String = when {
        m == null -> "custom name on the watch"
        m.isCustom || m.catalogueLabel == null -> "custom name on the watch"
        else -> "$appName → ${m.catalogueLabel}"
    }

    /** "3 × 8 · 60 kg · rest 90 s", "3 × 45 s · rest 45 s", "3 × 8 · bodyweight · rest 60 s" plus the warm-up count. */
    private fun setsLabel(e: WatchExercise, unit: WeightUnit): String {
        val working = e.sets.filter { !it.warmup }.ifEmpty { e.sets }
        val warmups = e.sets.count { it.warmup }
        val first = working.firstOrNull()
        val n = working.size
        val body = when (e.kind) {
            WatchExerciseKind.TIMED -> "$n × ${first?.seconds ?: 0} s"
            WatchExerciseKind.BODYWEIGHT -> "$n × ${first?.reps ?: 0} · " + (first?.weightKg?.let { "+" + Weights.formatWithUnit(it, unit) } ?: "bodyweight")
            WatchExerciseKind.WEIGHT -> "$n × ${first?.reps ?: 0} · ${Weights.formatWithUnit(first?.weightKg ?: 0.0, unit)}"
        }
        val warm = if (warmups > 0) " · $warmups warm-up" else ""
        val superset = if (e.supersetWithNext) " · superset" else ""
        return "$body · rest ${e.restSeconds} s$warm$superset"
    }

    private fun kb(bytes: Int): String = String.format(Locale.US, "%.1f", bytes / 1024.0)

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
