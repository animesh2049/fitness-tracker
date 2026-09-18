package com.animesh.fitnesstracker.ui.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animesh.fitnesstracker.data.model.ExerciseType
import com.animesh.fitnesstracker.data.model.SessionExerciseWithSets
import com.animesh.fitnesstracker.data.model.SessionSet
import com.animesh.fitnesstracker.data.model.SessionWithExercises
import com.animesh.fitnesstracker.data.model.Settings
import com.animesh.fitnesstracker.data.model.WeightUnit
import com.animesh.fitnesstracker.di.AppContainer
import com.animesh.fitnesstracker.domain.LoggedSet
import com.animesh.fitnesstracker.domain.cycle.CycleEngine
import com.animesh.fitnesstracker.domain.cycle.CycleState
import com.animesh.fitnesstracker.domain.planner.SessionCursor
import com.animesh.fitnesstracker.domain.planner.SessionFlow
import com.animesh.fitnesstracker.domain.planner.SessionPlanner
import com.animesh.fitnesstracker.domain.planner.Step
import com.animesh.fitnesstracker.domain.progression.CurrentTargets
import com.animesh.fitnesstracker.domain.progression.ProgressionConfig
import com.animesh.fitnesstracker.domain.progression.ProgressionEngine
import com.animesh.fitnesstracker.domain.records.RecordKind
import com.animesh.fitnesstracker.domain.records.Records
import com.animesh.fitnesstracker.domain.timer.Countdown
import com.animesh.fitnesstracker.domain.timer.TimerKind
import com.animesh.fitnesstracker.util.Dates
import com.animesh.fitnesstracker.util.Weights
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Local, not yet saved, edits to the current set's actual values. */
data class SetEdit(val setId: Long, val reps: Int?, val weightKg: Double?, val seconds: Int?)

data class SetRow(val set: SessionSet, val text: String, val state: RowState, val badge: String)
enum class RowState { DONE_HIT, DONE_MISSED, CURRENT, UPCOMING }

/** One row of "Today's list" and one progress segment: where each exercise of the session stands. */
data class ExerciseState(
    val id: Long,
    val name: String,
    val done: Int,
    val total: Int,
    val skipped: Boolean,
    val isCurrent: Boolean,
    /** Index of the first unlogged set, null when the exercise is complete or skipped. */
    val nextSetIndex: Int?,
    /** Target of the next set, or of the last set when the exercise is complete. */
    val target: String
) {
    val complete: Boolean get() = !skipped && total > 0 && done >= total
    val started: Boolean get() = done > 0
    /** "3 of 3 sets done", "Skipped" or "3 sets · not started". */
    val stateText: String
        get() = when {
            skipped -> "Skipped"
            started -> "$done of $total sets done"
            else -> "$total sets · not started"
        }
}

data class SummaryRow(val name: String, val detail: String, val hit: Boolean)
/** Numbers from the watch activity linked to the finished session, when one was already synced. */
data class WatchNumbers(val activityId: Long, val name: String, val avgHr: Int?, val maxHr: Int?, val calories: Int?, val duration: String)
data class SessionSummary(
    val groupName: String,
    val dateLine: String,
    val duration: String,
    val sets: Int,
    val volume: String,
    val records: Int,
    val rows: List<SummaryRow>,
    val nextTime: List<String>,
    val watch: WatchNumbers? = null
)

data class SessionUi(
    val session: SessionWithExercises? = null,
    val steps: List<Step> = emptyList(),
    /** The first unlogged set of the exercise on screen, null when it is complete or skipped. */
    val current: Step? = null,
    /** The exercise the cursor points at. */
    val exercise: SessionExerciseWithSets? = null,
    val exerciseIndex: Int = 0,
    val exerciseCount: Int = 0,
    val canGoPrevious: Boolean = false,
    val canGoNext: Boolean = false,
    val cursorSkipped: Boolean = false,
    /** True when the exercise on screen has no unlogged set left (and is not skipped). */
    val cursorComplete: Boolean = false,
    val exerciseStates: List<ExerciseState> = emptyList(),
    val lastTime: String = "",
    val rows: List<SetRow> = emptyList(),
    val edit: SetEdit? = null,
    val targetText: String = "",
    /** True when every set of the session is logged or skipped. */
    val allDone: Boolean = false,
    val countdown: Countdown? = null,
    val now: Long = 0,
    val unit: WeightUnit = WeightUnit.KG,
    val settings: Settings = Settings(),
    val summary: SessionSummary? = null,
    /** Live text for what follows the current step, computed from state rather than frozen in the countdown. */
    val upNext: String = "",
    /** Session exercise id the "Next: ..." button jumps to; null means "Finish session". */
    val upNextExerciseId: Long? = null,
    /** While resting: index of the exercise the overlay card is peeking at, null when it shows the cursor. */
    val peekIndex: Int? = null
) {
    val resting: Boolean get() = countdown?.kind == TimerKind.REST
    val remaining: Int get() = countdown?.remainingSeconds(now) ?: 0
    val isTimed: Boolean get() = exercise?.exercise?.exerciseType == ExerciseType.TIMED
    val currentState: ExerciseState? get() = exerciseStates.getOrNull(exerciseIndex)
}

class SessionViewModel(private val c: AppContainer, private val sessionId: Long) : ViewModel() {
    private val edit = MutableStateFlow<SetEdit?>(null)
    private val summary = MutableStateFlow<SessionSummary?>(null)
    /** Session exercise id (not an index) of the exercise on screen; null resolves to the first unlogged set. */
    private val cursor = MutableStateFlow<Long?>(null)
    /** Session exercise id the rest overlay is peeking at; null shows the cursor exercise. */
    private val peek = MutableStateFlow<Long?>(null)

    private data class Local(val edit: SetEdit?, val summary: SessionSummary?, val cursor: Long?, val peek: Long?)

    private val _records = MutableSharedFlow<List<RecordKind>>(extraBufferCapacity = 4)
    /** Records beaten by the set that was just logged (for the celebration). */
    val records: SharedFlow<List<RecordKind>> = _records

    private val _closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emitted when the session ended (finished or abandoned) and the screen should close. */
    val closed: SharedFlow<Unit> = _closed

    private var timedSetId: Long? = null

    val state: StateFlow<SessionUi> = combine(
        c.sessions.observeSession(sessionId), c.settings.observe(), c.timer.countdown, c.timer.now,
        combine(edit, summary, cursor, peek) { e, s, cur, p -> Local(e, s, cur, p) }
    ) { session, settings, countdown, now, local ->
        build(session, settings, countdown, now, local)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionUi())

    init {
        viewModelScope.launch {
            c.timer.finished.collect { onTimerFinished(it) }
        }
    }

    private suspend fun build(session: SessionWithExercises?, settings: Settings, countdown: Countdown?, now: Long, local: Local): SessionUi {
        val (edit, summary, cursorId, peekId) = local
        if (session == null) return SessionUi(settings = settings, unit = settings.unit, summary = summary)
        val exercises = session.sortedExercises
        val steps = SessionFlow.steps(exercises)
        val exerciseIndex = SessionCursor.resolve(exercises, steps, cursorId)
        val exercise = exercises.getOrNull(exerciseIndex)
        val current = SessionCursor.currentStep(steps, exerciseIndex)
        val unit = settings.unit
        val effectiveEdit = if (current != null && edit?.setId == current.set.id) edit else current?.let {
            SetEdit(it.set.id, it.set.actualReps ?: it.set.targetReps, it.set.actualWeightKg ?: it.set.targetWeightKg, it.set.actualSeconds ?: it.set.targetSeconds)
        }
        val rows = exercise?.sortedSets?.map { s ->
            val state = when {
                s.completed && s.hitTarget -> RowState.DONE_HIT
                s.completed -> RowState.DONE_MISSED
                current != null && s.id == current.set.id -> RowState.CURRENT
                else -> RowState.UPCOMING
            }
            val text = setText(s, unit, actual = s.completed)
            val badge = when (state) { RowState.DONE_HIT -> "Hit"; RowState.DONE_MISSED -> "Missed"; RowState.CURRENT -> "Now"; RowState.UPCOMING -> "" }
            SetRow(s, if (s.isWarmup) "$text · warm-up" else text, state, badge)
        } ?: emptyList()
        val lastTime = exercise?.let { lastTimeText(it, unit) } ?: ""
        val exerciseStates = exercises.mapIndexed { i, e ->
            val next = SessionCursor.currentStep(steps, i)
            val targetSet = next?.set ?: e.sortedSets.lastOrNull()
            ExerciseState(
                id = e.exercise.id, name = e.exercise.exerciseName,
                done = e.sets.count { it.completed }, total = e.sets.size, skipped = e.exercise.skipped,
                isCurrent = i == exerciseIndex, nextSetIndex = next?.setIndex,
                target = targetSet?.let { setText(it, unit, actual = false) } ?: ""
            )
        }
        val upNext = SessionCursor.upNext(exercises, steps, exerciseIndex)
        val resting = countdown?.kind == TimerKind.REST
        val peekIndex = if (!resting || peekId == null) null
        else exercises.indexOfFirst { it.exercise.id == peekId }.takeIf { it >= 0 && it != exerciseIndex }
        return SessionUi(
            session = session, steps = steps, current = current, exercise = exercise,
            exerciseIndex = exerciseIndex, exerciseCount = exercises.size,
            canGoPrevious = exerciseIndex > 0, canGoNext = exerciseIndex >= 0 && exerciseIndex < exercises.size - 1,
            cursorSkipped = exercise?.exercise?.skipped == true,
            cursorComplete = exercise != null && !exercise.exercise.skipped && current == null,
            exerciseStates = exerciseStates,
            lastTime = lastTime, rows = rows, edit = effectiveEdit,
            targetText = current?.let { setText(it.set, unit, actual = false) } ?: "",
            allDone = SessionFlow.current(steps) == null, countdown = countdown, now = now, unit = unit, settings = settings, summary = summary,
            upNext = SessionCursor.upNextText(upNext, exercises) { setText(it, unit, actual = false) },
            upNextExerciseId = upNext.exerciseIndexOrNull?.let { exercises.getOrNull(it)?.exercise?.id },
            peekIndex = peekIndex
        )
    }

    private fun setText(s: SessionSet, unit: WeightUnit, actual: Boolean): String {
        val reps = if (actual) s.actualReps else s.targetReps
        val w = if (actual) s.actualWeightKg else s.targetWeightKg
        val secs = if (actual) s.actualSeconds else s.targetSeconds
        return when {
            secs != null -> "$secs s"
            w != null && w > 0 -> "${reps ?: 0} × ${Weights.formatWithUnit(w, unit)}"
            else -> "${reps ?: 0} reps"
        }
    }

    private suspend fun lastTimeText(ex: SessionExerciseWithSets, unit: WeightUnit): String {
        val history = c.sessions.setsForExercise(ex.exercise.exerciseId).filter { it.sessionId != sessionId && !it.isWarmup && it.completed }
        val last = history.firstOrNull() ?: return "No history yet"
        val sets = history.filter { it.sessionId == last.sessionId }
        return if (last.targetSeconds != null) sets.joinToString(" · ") { "${it.actualSeconds ?: 0}" } + " s"
        else {
            val w = sets.mapNotNull { it.actualWeightKg }.maxOrNull()
            sets.joinToString(" · ") { "${it.actualReps ?: 0}" } + (if (w != null && w > 0) " at ${Weights.formatWithUnit(w, unit)}" else "")
        }
    }

    // ---- navigation (FR56, FR57, FR59) -------------------------------------------------------

    private fun moveCursorTo(index: Int) {
        val exercises = state.value.session?.sortedExercises ?: return
        exercises.getOrNull(index)?.let { cursor.value = it.exercise.id }
    }

    fun previousExercise() {
        val ui = state.value
        moveCursorTo(SessionCursor.neighbour(ui.exerciseCount, ui.exerciseIndex, -1))
    }

    fun nextExercise() {
        val ui = state.value
        moveCursorTo(SessionCursor.neighbour(ui.exerciseCount, ui.exerciseIndex, +1))
    }

    fun jumpToExercise(sessionExerciseId: Long) {
        cursor.value = sessionExerciseId
    }

    /** Undoes a skip so the exercise's sets are steps again (and visible to history). */
    fun unskipExercise() {
        val ex = state.value.exercise ?: return
        if (!ex.exercise.skipped) return
        viewModelScope.launch { c.sessions.setExerciseSkipped(ex.exercise, false) }
    }

    // ---- rest overlay peeking (FR58) ---------------------------------------------------------

    /** Index the rest overlay card shows: the peeked exercise, else the cursor. */
    private fun viewedIndex(ui: SessionUi): Int = ui.peekIndex ?: ui.exerciseIndex

    fun peekExercise(sessionExerciseId: Long) {
        peek.value = sessionExerciseId
    }

    fun peekPrevious() {
        val ui = state.value
        val i = SessionCursor.neighbour(ui.exerciseCount, viewedIndex(ui), -1)
        ui.session?.sortedExercises?.getOrNull(i)?.let { peek.value = it.exercise.id }
    }

    fun peekNext() {
        val ui = state.value
        val i = SessionCursor.neighbour(ui.exerciseCount, viewedIndex(ui), +1)
        ui.session?.sortedExercises?.getOrNull(i)?.let { peek.value = it.exercise.id }
    }

    /** Moves the cursor to the peeked exercise; the rest keeps running. */
    fun continueWithPeeked() {
        val id = peek.value ?: return
        cursor.value = id
        peek.value = null
    }

    // ---- set editing -------------------------------------------------------------------------

    private fun currentEdit(): SetEdit? = state.value.edit

    fun adjustReps(delta: Int) {
        val e = currentEdit() ?: return
        edit.value = e.copy(reps = ((e.reps ?: 0) + delta).coerceAtLeast(0))
    }

    fun adjustWeight(delta: Double) {
        val e = currentEdit() ?: return
        val inc = kotlin.math.abs(delta)
        edit.value = e.copy(weightKg = Weights.roundTo(((e.weightKg ?: 0.0) + delta).coerceAtLeast(0.0), inc))
    }

    fun weightStep(): Double {
        val ex = state.value.exercise ?: return 2.5
        return stepCache[ex.exercise.exerciseId] ?: 2.5
    }

    private val stepCache = mutableMapOf<Long, Double>()

    init {
        viewModelScope.launch {
            val s = c.sessions.observeSession(sessionId).first() ?: return@launch
            s.exercises.forEach { e -> c.exercises.get(e.exercise.exerciseId)?.let { stepCache[it.id] = it.weightIncrementKg } }
        }
    }

    /** Logs the current set with the edited values, moves the cursor along the flow and starts the rest timer when due. */
    fun doneSet() {
        val ui = state.value
        val step = ui.current ?: return
        val e = ui.edit ?: return
        viewModelScope.launch {
            c.sessions.completeSet(step.set, e.reps, e.weightKg, e.seconds)
            edit.value = null
            afterSetLogged(ui, step, e.reps, e.weightKg, e.seconds)
        }
    }

    /** Shared tail of every way a set gets logged: records, cursor move (superset alternation), rest. */
    private suspend fun afterSetLogged(ui: SessionUi, step: Step, reps: Int?, weightKg: Double?, seconds: Int?) {
        // Move first, so the re-emitted rows never show the finished exercise's "complete" card for a frame.
        moveCursorTo(SessionCursor.afterDoneSet(ui.steps, step))
        checkRecords(ui, step, reps, weightKg, seconds)
        startRestIfDue(ui, step)
    }

    private suspend fun checkRecords(ui: SessionUi, step: Step, reps: Int?, weightKg: Double?, seconds: Int?) {
        val ex = ui.session?.sortedExercises?.getOrNull(step.exerciseIndex) ?: return
        if (step.set.isWarmup) return
        val history = c.sessions.setsForExercise(ex.exercise.exerciseId).filter { it.sessionId != sessionId }.map { LoggedSet.from(it) }
        val candidate = LoggedSet(epochDay = Dates.todayEpochDay(), sessionId = sessionId, targetReps = step.set.targetReps, targetWeightKg = step.set.targetWeightKg, targetSeconds = step.set.targetSeconds, actualReps = reps, actualWeightKg = weightKg, actualSeconds = seconds)
        val beaten = Records.beats(history, candidate)
        if (beaten.isNotEmpty()) _records.tryEmit(beaten)
    }

    private fun startRestIfDue(ui: SessionUi, step: Step) {
        if (!step.restAfter || !ui.settings.autoStartRest) return
        val next = SessionFlow.next(ui.steps, step) ?: return
        val ex = ui.session?.sortedExercises?.getOrNull(step.exerciseIndex) ?: return
        val rest = if (next.exerciseIndex != step.exerciseIndex) ui.settings.defaultExerciseRestSeconds.coerceAtLeast(ex.exercise.restSeconds) else ex.exercise.restSeconds
        // The label only feeds the notification; the overlay computes its own text live from state.
        val label = if (next.exerciseIndex == step.exerciseIndex) "Set ${next.setIndex + 1} · ${setText(next.set, ui.unit, actual = false)}"
        else "${ui.session.sortedExercises[next.exerciseIndex].exercise.exerciseName} · Set ${next.setIndex + 1}"
        peek.value = null
        c.timer.start(TimerKind.REST, rest, label, tag = next.set.id)
    }

    fun addRest30() = c.timer.add(30)

    fun skipRest() {
        peek.value = null
        c.timer.skip()
    }

    /** Appends a set to the exercise on screen; on a skipped exercise this also undoes the skip. */
    fun addSet() {
        val ex = state.value.exercise ?: return
        viewModelScope.launch {
            if (ex.exercise.skipped) c.sessions.setExerciseSkipped(ex.exercise, false)
            c.sessions.addSet(ex.exercise.id, ex.sortedSets.lastOrNull(), ex.exercise.exerciseType)
        }
    }

    /** Marks the exercise on screen skipped and moves to its neighbour. A running rest timer is left alone. */
    fun skipExercise() {
        val ui = state.value
        val ex = ui.exercise ?: return
        // Only a timed set of this very exercise is cancelled; a rest is a break, not a statement about this exercise.
        if (timedSetId != null && ex.sets.any { it.id == timedSetId }) cancelTimedSet()
        val count = ui.exerciseCount
        val target = if (ui.exerciseIndex < count - 1) ui.exerciseIndex + 1 else ui.exerciseIndex - 1
        viewModelScope.launch {
            c.sessions.setExerciseSkipped(ex.exercise, true)
            if (target in 0 until count) moveCursorTo(target)
        }
    }

    // ---- timed sets --------------------------------------------------------------------------

    fun startTimedSet() {
        val ui = state.value
        val step = ui.current ?: return
        timedSetId = step.set.id
        c.timer.start(TimerKind.GET_READY, ui.settings.countdownSeconds, "Get into position", tag = step.set.id)
    }

    fun cancelTimedSet() {
        timedSetId = null
        c.timer.skip()
    }

    /** The step a countdown belongs to, resolved from its tag (the session set id), never from the on-screen set. */
    private fun taggedStep(ui: SessionUi, cd: Countdown): Step? {
        val exercises = ui.session?.sortedExercises ?: return null
        return SessionCursor.stepForSet(exercises, ui.steps, cd.tag)
    }

    fun stopTimedSetEarly() {
        val ui = state.value
        val cd = ui.countdown ?: return
        if (cd.kind != TimerKind.WORK) return
        val step = taggedStep(ui, cd) ?: return
        if (step.set.completed) return
        val held = cd.elapsedSeconds(System.currentTimeMillis())
        timedSetId = null
        c.timer.skip()
        viewModelScope.launch {
            c.sessions.completeSet(step.set, null, null, held)
            afterSetLogged(ui, step, null, null, held)
        }
    }

    private fun onTimerFinished(cd: Countdown) {
        val ui = state.value
        if (cd.kind == TimerKind.REST) {
            // The overlay closes on its own (no countdown); forget any peek so the next rest starts on the cursor.
            peek.value = null
            return
        }
        val step = taggedStep(ui, cd) ?: return
        if (timedSetId != step.set.id || step.set.completed) return
        when (cd.kind) {
            TimerKind.GET_READY -> c.timer.start(TimerKind.WORK, step.set.targetSeconds ?: 30, "Hold", tag = step.set.id)
            TimerKind.WORK -> {
                timedSetId = null
                viewModelScope.launch {
                    c.sessions.completeSet(step.set, null, null, step.set.targetSeconds)
                    afterSetLogged(ui, step, null, null, step.set.targetSeconds)
                }
            }
            TimerKind.REST -> Unit
        }
    }

    // ---- ending ------------------------------------------------------------------------------

    fun finish(notes: String = "") {
        viewModelScope.launch {
            c.timer.skip()
            val before = c.sessions.getSession(sessionId) ?: return@launch
            val settings = c.settings.get()
            // Records beaten in this session, judged against everything logged before it.
            var records = 0
            for (ex in before.exercises) {
                val history = c.sessions.setsForExercise(ex.exercise.exerciseId).filter { it.sessionId != sessionId }.map { LoggedSet.from(it) }.toMutableList()
                for (s in ex.sortedSets.filter { it.completed && !it.isWarmup }) {
                    val cand = LoggedSet.from(s, before.session.epochDay, sessionId)
                    if (Records.beats(history, cand).isNotEmpty()) records++
                    history += cand
                }
            }
            c.sessions.finish(sessionId, notes, writeBackTargets = true)
            advanceCycle(before)
            // A watch activity synced before the session was finished can now be matched to it (FR52).
            runCatching { c.activities.linkUnlinked() }
            summary.value = buildSummary(before, settings, records)
        }
    }

    private suspend fun advanceCycle(s: SessionWithExercises) {
        val routine = c.routines.getActive() ?: return
        if (s.session.routineId != null && s.session.routineId != routine.routine.id) return
        val slots = routine.sortedSlots.map { it.slot.groupId }
        val next = CycleEngine.advance(slots, CycleState.of(routine.routine), Dates.todayEpochDay())
        c.routines.update(next.applyTo(routine.routine))
    }

    private suspend fun buildSummary(s: SessionWithExercises, settings: Settings, records: Int): SessionSummary {
        val after = c.sessions.getSession(sessionId) ?: s
        val unit = settings.unit
        val rows = after.sortedExercises.filter { !it.exercise.skipped }.map { ex ->
            val done = ex.sortedSets.filter { it.completed && !it.isWarmup }
            val detail = when {
                done.isEmpty() -> "No sets logged"
                ex.exercise.exerciseType == ExerciseType.TIMED -> done.joinToString(" · ") { "${it.actualSeconds ?: 0}" } + " s"
                else -> done.joinToString(" · ") { "${it.actualReps ?: 0}" } + (done.mapNotNull { it.actualWeightKg }.maxOrNull()?.takeIf { it > 0 }?.let { " at ${Weights.formatWithUnit(it, unit)}" } ?: "")
            }
            SummaryRow(ex.exercise.exerciseName, detail, done.isNotEmpty() && done.all { it.hitTarget })
        }
        val nextTime = after.sortedExercises.filter { !it.exercise.skipped }.mapNotNull { ex ->
            val geId = ex.exercise.groupExerciseId ?: return@mapNotNull null
            val group = after.session.groupId?.let { c.groups.getGroup(it) } ?: return@mapNotNull null
            val ge = group.exercises.firstOrNull { it.groupExercise.id == geId } ?: return@mapNotNull null
            val history = c.sessions.setsForExercise(ex.exercise.exerciseId).map { LoggedSet.from(it) }
            val sug = ProgressionEngine.suggest(ge.exercise, SessionPlanner.currentTargets(ge), history, ProgressionConfig(settings.deloadAfterFailures, settings.deloadPercent)) { Weights.formatWithUnit(it, unit) }
                ?: return@mapNotNull null
            if (!sug.isActionable) return@mapNotNull null
            val target = when {
                sug.newSeconds != null -> "${sug.newSeconds} s"
                sug.newWeightKg != null -> Weights.formatWithUnit(sug.newWeightKg, unit) + (if (sug.newReps != null && sug.newReps != SessionPlanner.currentTargets(ge).reps) " × ${sug.newReps}" else "")
                sug.newReps != null -> "${sug.newReps} reps"
                else -> ""
            }
            "${ex.exercise.exerciseName}: try $target next time."
        }
        val completed = after.completedSets.filter { !it.isWarmup }
        val duration = after.session.endedAt?.let { Dates.formatDuration(it - after.session.startedAt) } ?: ""
        val watch = runCatching { c.activities.observeForSession(sessionId).first().firstOrNull() }.getOrNull()?.let {
            WatchNumbers(it.id, it.name, it.avgHr, it.maxHr, it.calories, Dates.hoursMinutes(it.timerSeconds))
        }
        return SessionSummary(
            groupName = after.session.groupName, dateLine = Dates.shortDay(after.session.epochDay), duration = duration,
            sets = completed.size, volume = Weights.formatVolume(after.volumeKg, unit), records = records, rows = rows, nextTime = nextTime,
            watch = watch
        )
    }

    fun abandon() {
        viewModelScope.launch {
            c.timer.skip()
            val s = c.sessions.getSession(sessionId)
            if (s != null && s.completedSets.isEmpty()) c.sessions.delete(sessionId) else c.sessions.abandon(sessionId)
            _closed.tryEmit(Unit)
        }
    }

    fun close() { _closed.tryEmit(Unit) }

    /** Hit target for the record celebration text. */
    fun recordText(kinds: List<RecordKind>): String = kinds.joinToString(", ") {
        when (it) {
            RecordKind.HEAVIEST_FOR_REPS -> "heaviest for these reps"
            RecordKind.MOST_REPS_AT_WEIGHT -> "most reps at this weight"
            RecordKind.BEST_E1RM -> "best estimated 1RM"
            RecordKind.LONGEST_HOLD -> "longest hold"
        }
    }.replaceFirstChar { it.uppercase() }

    companion object {
        fun currentTargets(ex: SessionExerciseWithSets): CurrentTargets {
            val working = ex.sortedSets.filter { !it.isWarmup }.ifEmpty { ex.sortedSets }
            return CurrentTargets(
                working.mapNotNull { it.targetReps }.maxOrNull(), working.mapNotNull { it.targetWeightKg }.maxOrNull(),
                working.mapNotNull { it.targetSeconds }.maxOrNull(), working.size
            )
        }
    }
}
