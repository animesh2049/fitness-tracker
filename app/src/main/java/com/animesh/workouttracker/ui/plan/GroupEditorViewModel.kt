package com.animesh.workouttracker.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animesh.workouttracker.data.model.Exercise
import com.animesh.workouttracker.data.model.ExerciseType
import com.animesh.workouttracker.data.model.GroupExerciseWithSets
import com.animesh.workouttracker.data.model.GroupWithExercises
import com.animesh.workouttracker.data.model.SetPrescription
import com.animesh.workouttracker.data.model.Settings
import com.animesh.workouttracker.data.model.WeightUnit
import com.animesh.workouttracker.di.AppContainer
import com.animesh.workouttracker.util.Weights
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SetRow(val set: SetPrescription, val text: String)

data class ExerciseRow(
    val ge: GroupExerciseWithSets,
    val typeLabel: String,
    val sets: List<SetRow>,
    /** Name of the following exercise, or null on the last one (no superset possible). */
    val nextName: String?
) {
    val id: Long get() = ge.groupExercise.id
    val superset: Boolean get() = ge.groupExercise.supersetWithNext && nextName != null
}

/** Local, not-yet-saved text of the name and notes fields. */
data class GroupTextDraft(val name: String = "", val notes: String = "", val initialised: Boolean = false)

data class PickerState(val open: Boolean = false, val search: String = "", val muscle: String? = null)

/** The set currently being edited in the dialog, with its exercise for type and increments. */
data class SetEdit(val set: SetPrescription, val exercise: Exercise)

data class GroupEditorState(
    val loaded: Boolean = false,
    val missing: Boolean = false,
    val group: GroupWithExercises? = null,
    val name: String = "",
    val notes: String = "",
    val meta: String = "",
    val rows: List<ExerciseRow> = emptyList(),
    val unit: WeightUnit = WeightUnit.KG,
    val picker: PickerState = PickerState(),
    val pickerExercises: List<Exercise> = emptyList(),
    val editing: SetEdit? = null
)

class GroupEditorViewModel(private val c: AppContainer, private val groupId: Long) : ViewModel() {
    private val text = MutableStateFlow(GroupTextDraft())
    private val picker = MutableStateFlow(PickerState())
    private val editing = MutableStateFlow<SetEdit?>(null)

    val state: StateFlow<GroupEditorState> = combine(
        c.groups.observeGroup(groupId), c.settings.observe(), c.exercises.observeActive(), text, combine(picker, editing) { p, e -> p to e }
    ) { group, settings, exercises, text, (picker, editing) ->
        build(group, settings, exercises, text, picker, editing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupEditorState())

    init {
        // Seed the editable name and notes once from the database; afterwards the draft is the source of truth.
        viewModelScope.launch {
            val g = c.groups.getGroup(groupId)?.group ?: return@launch
            if (!text.value.initialised) text.value = GroupTextDraft(g.name, g.notes, initialised = true)
        }
    }

    private fun build(group: GroupWithExercises?, settings: Settings, exercises: List<Exercise>, text: GroupTextDraft, picker: PickerState, editing: SetEdit?): GroupEditorState {
        if (group == null) return GroupEditorState(loaded = true, missing = true)
        val sorted = group.sortedExercises
        val rows = sorted.mapIndexed { i, ge ->
            ExerciseRow(
                ge = ge,
                typeLabel = typeLabel(ge.exercise.type),
                sets = ge.sortedSets.map { SetRow(it, setText(it, ge.exercise.type, settings.unit)) },
                nextName = sorted.getOrNull(i + 1)?.exercise?.name
            )
        }
        val pickerExercises = exercises
            .filter { picker.search.isBlank() || it.name.contains(picker.search.trim(), ignoreCase = true) }
            .filter { picker.muscle == null || picker.muscle in it.muscleList }
        return GroupEditorState(
            loaded = true, group = group,
            name = if (text.initialised) text.name else group.group.name,
            notes = if (text.initialised) text.notes else group.group.notes,
            meta = "${plural(sorted.size, "exercise")} · ${plural(group.workingSetCount, "working set")}",
            rows = rows, unit = settings.unit, picker = picker, pickerExercises = pickerExercises, editing = editing
        )
    }

    private fun setText(s: SetPrescription, type: ExerciseType, unit: WeightUnit): String {
        val base = when (type) {
            ExerciseType.TIMED -> "${s.targetSeconds ?: 0} s"
            ExerciseType.WEIGHT -> "${s.targetReps ?: 0} reps · ${Weights.formatWithUnit(s.targetWeightKg ?: 0.0, unit)}"
            ExerciseType.BODYWEIGHT -> {
                val w = s.targetWeightKg ?: 0.0
                if (w > 0) "${s.targetReps ?: 0} reps · +${Weights.formatWithUnit(w, unit)}" else "${s.targetReps ?: 0} reps"
            }
        }
        return if (s.restSecondsOverride != null) "$base · rest ${s.restSecondsOverride} s" else base
    }

    // Name and notes: kept locally, written on Done and when the editor goes away.
    fun setName(name: String) = text.update { it.copy(name = name) }
    fun setNotes(notes: String) = text.update { it.copy(notes = notes) }

    /** Persists name and notes. Runs on the app scope so it survives the ViewModel being cleared. */
    fun flushText() {
        val t = text.value
        if (!t.initialised) return
        val current = state.value.group?.group ?: return
        val name = t.name.trim().ifEmpty { current.name }
        if (name == current.name && t.notes == current.notes) return
        c.appScope.launch {
            val fresh = c.groups.getGroup(groupId)?.group ?: return@launch
            c.groups.updateGroup(fresh.copy(name = name, notes = t.notes))
        }
    }

    override fun onCleared() {
        flushText()
        super.onCleared()
    }

    // Everything below writes straight to Room.
    fun toggleWarmup(set: SetPrescription) = viewModelScope.launch { c.groups.updateSet(set.copy(isWarmup = !set.isWarmup)) }
    fun removeSet(set: SetPrescription) = viewModelScope.launch { c.groups.removeSet(set) }
    fun addSet(row: ExerciseRow) = viewModelScope.launch { c.groups.addSet(row.id, row.ge.exercise) }
    fun toggleSuperset(row: ExerciseRow) = viewModelScope.launch { c.groups.setSuperset(row.ge.groupExercise, !row.ge.groupExercise.supersetWithNext) }
    fun moveExercise(from: Int, to: Int) = viewModelScope.launch { c.groups.moveExercise(groupId, from, to) }
    fun removeExercise(row: ExerciseRow) = viewModelScope.launch { c.groups.removeExercise(row.id, groupId) }

    fun editSet(row: ExerciseRow, set: SetPrescription) { editing.value = SetEdit(set, row.ge.exercise) }
    fun cancelEdit() { editing.value = null }
    fun saveSet(set: SetPrescription) {
        editing.value = null
        viewModelScope.launch { c.groups.updateSet(set) }
    }

    fun openPicker() = picker.update { it.copy(open = true, search = "", muscle = null) }
    fun closePicker() = picker.update { it.copy(open = false) }
    fun setPickerSearch(q: String) = picker.update { it.copy(search = q) }
    fun togglePickerMuscle(m: String) = picker.update { it.copy(muscle = if (it.muscle == m) null else m) }
    fun pick(exercise: Exercise) {
        picker.update { it.copy(open = false) }
        viewModelScope.launch { c.groups.addExercise(groupId, exercise) }
    }
}
