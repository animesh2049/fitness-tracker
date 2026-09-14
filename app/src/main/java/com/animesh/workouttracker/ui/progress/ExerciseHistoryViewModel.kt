package com.animesh.workouttracker.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animesh.workouttracker.data.dao.DatedSet
import com.animesh.workouttracker.data.model.ExerciseType
import com.animesh.workouttracker.data.model.WeightUnit
import com.animesh.workouttracker.di.AppContainer
import com.animesh.workouttracker.domain.LoggedSet
import com.animesh.workouttracker.util.Dates
import com.animesh.workouttracker.util.Weights
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

enum class SetOutcome { HIT, MISSED, SKIPPED }

data class SetRow(val number: Int, val value: String, val warmup: Boolean, val outcome: SetOutcome)

data class SessionGroup(val sessionId: Long, val dateLabel: String, val sets: List<SetRow>)

data class ExerciseHistoryState(
    val loading: Boolean = true,
    val name: String = "",
    val sessionCount: Int = 0,
    val sessions: List<SessionGroup> = emptyList()
)

/** FR29: every logged set for one exercise, grouped by session, newest first. */
class ExerciseHistoryViewModel(c: AppContainer, exerciseId: Long) : ViewModel() {
    val state: StateFlow<ExerciseHistoryState> = combine(
        c.exercises.observe(exerciseId), c.sessions.observeSetsForExercise(exerciseId), c.settings.observe()
    ) { exercise, sets, settings ->
        val type = exercise?.type ?: ExerciseType.WEIGHT
        val groups = sets.groupBy { it.sessionId }.map { (sessionId, sessionSets) ->
            SessionGroup(
                sessionId, Dates.shortDay(sessionSets.first().epochDay),
                sessionSets.sortedBy { it.position }.map { row(it, type, settings.unit) }
            )
        }
        ExerciseHistoryState(false, exercise?.name ?: "", groups.size, groups)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExerciseHistoryState())

    private fun row(d: DatedSet, type: ExerciseType, unit: WeightUnit): SetRow {
        val logged = LoggedSet.from(d)
        val value = when {
            type == ExerciseType.TIMED -> "${d.actualSeconds ?: 0} s"
            (d.actualWeightKg ?: 0.0) > 0 -> "${d.actualReps ?: 0} × ${Weights.formatWithUnit(d.actualWeightKg!!, unit)}"
            else -> "${d.actualReps ?: 0} reps"
        }
        val outcome = when {
            !d.completed -> SetOutcome.SKIPPED
            logged.hitTarget -> SetOutcome.HIT
            else -> SetOutcome.MISSED
        }
        return SetRow(d.position + 1, value, d.isWarmup, outcome)
    }
}
