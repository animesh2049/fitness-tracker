package com.animesh.workouttracker.ui.plan

import android.database.sqlite.SQLiteConstraintException
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animesh.workouttracker.data.model.Exercise
import com.animesh.workouttracker.data.model.ExerciseType
import com.animesh.workouttracker.data.model.Muscles
import com.animesh.workouttracker.data.model.ProgressionRule
import com.animesh.workouttracker.data.model.WeightUnit
import com.animesh.workouttracker.di.AppContainer
import com.animesh.workouttracker.util.Weights
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Editable copy of an exercise. Numbers are kept as text so the user can clear and retype them. */
data class ExerciseDraft(
    val name: String = "",
    val type: ExerciseType = ExerciseType.WEIGHT,
    val muscles: Set<String> = emptySet(),
    val restText: String = "",
    /** In the user's display unit. */
    val incrementText: String = "",
    val rule: ProgressionRule = ProgressionRule.LINEAR_WEIGHT,
    val repMinText: String = "8",
    val repMaxText: String = "12",
    val timeStepText: String = "10",
    val timeMaxText: String = "",
    val notes: String = ""
)

data class ExerciseEditorState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val draft: ExerciseDraft = ExerciseDraft(),
    val dirty: Boolean = false,
    val unit: WeightUnit = WeightUnit.KG,
    val globalRest: Int = 90,
    val error: String? = null,
    /** True when deleting would archive instead (history or group usage). */
    val referenced: Boolean = false,
    val archived: Boolean = false
) {
    val rules: List<ProgressionRule> get() = rulesFor(draft.type)

    companion object {
        fun rulesFor(type: ExerciseType): List<ProgressionRule> = when (type) {
            ExerciseType.TIMED -> listOf(ProgressionRule.LINEAR_TIME, ProgressionRule.NONE)
            else -> listOf(ProgressionRule.LINEAR_WEIGHT, ProgressionRule.DOUBLE_PROGRESSION, ProgressionRule.LINEAR_REPS, ProgressionRule.NONE)
        }
    }
}

class ExerciseEditorViewModel(private val c: AppContainer, private val exerciseId: Long) : ViewModel() {
    private val draft = MutableStateFlow(ExerciseDraft())
    private val saved = MutableStateFlow(ExerciseDraft())
    private val loaded = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val meta = MutableStateFlow(false to false) // referenced, archived
    private var original: Exercise? = null

    private val _closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits after a successful save or delete. */
    val closed: SharedFlow<Unit> = _closed

    val state: StateFlow<ExerciseEditorState> = combine(draft, saved, loaded, c.settings.observe(), combine(error, meta) { e, m -> e to m }) { d, s, l, settings, (err, m) ->
        ExerciseEditorState(
            loaded = l, isNew = exerciseId == 0L, draft = d, dirty = d != s, unit = settings.unit,
            globalRest = settings.defaultRestSeconds, error = err, referenced = m.first, archived = m.second
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExerciseEditorState())

    init {
        viewModelScope.launch {
            val unit = c.settings.get().unit
            val existing = if (exerciseId != 0L) c.exercises.get(exerciseId) else null
            original = existing
            val initial = if (existing == null) {
                ExerciseDraft(incrementText = Weights.format(Weights.toDisplay(2.5, unit)))
            } else {
                ExerciseDraft(
                    name = existing.name,
                    type = existing.type,
                    muscles = existing.muscleList.toSet(),
                    restText = existing.defaultRestSeconds?.toString() ?: "",
                    incrementText = Weights.format(Weights.toDisplay(existing.weightIncrementKg, unit)),
                    rule = existing.progressionRule,
                    repMinText = existing.repRangeMin.toString(),
                    repMaxText = existing.repRangeMax.toString(),
                    timeStepText = existing.timeStepSeconds.toString(),
                    timeMaxText = existing.timeMaxSeconds?.toString() ?: "",
                    notes = existing.notes
                )
            }
            if (existing != null) meta.value = c.exercises.isReferenced(existing.id) to existing.archived
            draft.value = initial
            saved.value = initial
            loaded.value = true
        }
    }

    fun setName(v: String) { draft.update { it.copy(name = v) }; error.value = null }
    fun setType(t: ExerciseType) = draft.update { d ->
        val rule = if (d.rule in ExerciseEditorState.rulesFor(t)) d.rule else Exercise.defaultRuleFor(t)
        d.copy(type = t, rule = rule)
    }
    fun toggleMuscle(m: String) = draft.update { it.copy(muscles = if (m in it.muscles) it.muscles - m else it.muscles + m) }
    fun setRest(v: String) = draft.update { it.copy(restText = v.filter(Char::isDigit)) }
    fun setIncrement(v: String) = draft.update { it.copy(incrementText = v) }
    fun setRule(r: ProgressionRule) = draft.update { it.copy(rule = r) }
    fun setRepMin(v: String) = draft.update { it.copy(repMinText = v.filter(Char::isDigit)) }
    fun setRepMax(v: String) = draft.update { it.copy(repMaxText = v.filter(Char::isDigit)) }
    fun setTimeStep(v: String) = draft.update { it.copy(timeStepText = v.filter(Char::isDigit)) }
    fun setTimeMax(v: String) = draft.update { it.copy(timeMaxText = v.filter(Char::isDigit)) }
    fun setNotes(v: String) = draft.update { it.copy(notes = v) }

    fun save() {
        val d = draft.value
        val name = d.name.trim()
        if (name.isEmpty()) { error.value = "Give the exercise a name."; return }
        val repMin = d.repMinText.toIntOrNull() ?: 8
        val repMax = d.repMaxText.toIntOrNull() ?: 12
        if (d.rule == ProgressionRule.DOUBLE_PROGRESSION && repMax < repMin) { error.value = "Rep range max must be at least the min."; return }
        viewModelScope.launch {
            val unit = c.settings.get().unit
            val increment = d.incrementText.replace(',', '.').toDoubleOrNull()?.let { Weights.toKg(it, unit) }?.takeIf { it > 0 } ?: 2.5
            val base = original ?: Exercise(name = name, type = d.type)
            val exercise = base.copy(
                name = name,
                type = d.type,
                muscles = d.muscles.filter { it in Muscles.ALL }.joinToString(","),
                defaultRestSeconds = d.restText.toIntOrNull()?.takeIf { it > 0 },
                weightIncrementKg = increment,
                progressionRule = d.rule,
                repRangeMin = repMin,
                repRangeMax = repMax,
                timeStepSeconds = d.timeStepText.toIntOrNull()?.takeIf { it > 0 } ?: 10,
                timeMaxSeconds = d.timeMaxText.toIntOrNull()?.takeIf { it > 0 },
                notes = d.notes.trim()
            )
            try {
                if (original == null) c.exercises.add(exercise) else c.exercises.update(exercise)
                saved.value = d
                _closed.tryEmit(Unit)
            } catch (e: SQLiteConstraintException) {
                error.value = "An exercise called \"$name\" already exists."
            }
        }
    }

    /** Archives when the exercise has history or is used in a group, otherwise deletes it. */
    fun delete() {
        if (exerciseId == 0L) return
        viewModelScope.launch {
            c.exercises.archiveOrDelete(exerciseId)
            _closed.tryEmit(Unit)
        }
    }

    fun unarchive() {
        if (exerciseId == 0L) return
        viewModelScope.launch {
            c.exercises.unarchive(exerciseId)
            meta.update { it.copy(second = false) }
        }
    }

    fun discard() { _closed.tryEmit(Unit) }
}
