package com.animesh.workouttracker.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animesh.workouttracker.data.model.Exercise
import com.animesh.workouttracker.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ExerciseListRow(val exercise: Exercise, val typeLabel: String, val musclesLine: String) {
    val id: Long get() = exercise.id
}

data class ExercisesState(
    val loading: Boolean = true,
    val rows: List<ExerciseListRow> = emptyList(),
    val search: String = "",
    val muscle: String? = null,
    val showArchived: Boolean = false,
    val total: Int = 0
)

class ExercisesViewModel(private val c: AppContainer) : ViewModel() {
    private val search = MutableStateFlow("")
    private val muscle = MutableStateFlow<String?>(null)
    private val showArchived = MutableStateFlow(false)

    val state: StateFlow<ExercisesState> = combine(c.exercises.observeAll(), search, muscle, showArchived) { all, q, m, archived ->
        val visible = all.filter { archived || !it.archived }
        val rows = visible
            .filter { q.isBlank() || it.name.contains(q.trim(), ignoreCase = true) }
            .filter { m == null || m in it.muscleList }
            .map { e -> ExerciseListRow(e, typeLabel(e.type), e.muscleList.joinToString(", ").ifEmpty { "No muscle tags" }) }
        ExercisesState(false, rows, q, m, archived, visible.size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExercisesState())

    fun setSearch(q: String) { search.value = q }
    fun toggleMuscle(m: String) { muscle.value = if (muscle.value == m) null else m }
    fun setShowArchived(show: Boolean) { showArchived.value = show }
    fun unarchive(id: Long) = viewModelScope.launch { c.exercises.unarchive(id) }
}
