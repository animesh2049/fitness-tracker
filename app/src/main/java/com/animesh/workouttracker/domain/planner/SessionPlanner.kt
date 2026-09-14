package com.animesh.workouttracker.domain.planner

import com.animesh.workouttracker.data.model.ExerciseType
import com.animesh.workouttracker.data.model.GroupExerciseWithSets
import com.animesh.workouttracker.data.model.GroupWithExercises
import com.animesh.workouttracker.data.model.Settings
import com.animesh.workouttracker.domain.progression.CurrentTargets
import com.animesh.workouttracker.domain.progression.Suggestion
import com.animesh.workouttracker.repository.PlannedExercise
import com.animesh.workouttracker.repository.PlannedSet

object SessionPlanner {

    /** Working-set targets of a group exercise, for feeding the progression engine. */
    fun currentTargets(ge: GroupExerciseWithSets): CurrentTargets {
        val working = ge.sortedSets.filter { !it.isWarmup }
        val sets = working.ifEmpty { ge.sortedSets }
        return CurrentTargets(
            reps = sets.mapNotNull { it.targetReps }.maxOrNull(),
            weightKg = sets.mapNotNull { it.targetWeightKg }.maxOrNull(),
            seconds = sets.mapNotNull { it.targetSeconds }.maxOrNull(),
            workingSets = working.size
        )
    }

    /**
     * Builds the session plan: each group exercise's sets with accepted suggestions applied to
     * the working sets (warm-ups are never changed).
     */
    fun plan(group: GroupWithExercises, accepted: Map<Long, Suggestion>, settings: Settings): List<PlannedExercise> =
        group.sortedExercises.map { ge ->
            val s = accepted[ge.groupExercise.id]
            val sets = ge.sortedSets.map { p ->
                if (p.isWarmup || s == null || !s.isActionable) {
                    PlannedSet(p.targetReps, p.targetWeightKg, p.targetSeconds, p.isWarmup)
                } else {
                    PlannedSet(
                        targetReps = s.newReps ?: p.targetReps,
                        targetWeightKg = if (ge.exercise.type == ExerciseType.TIMED) p.targetWeightKg else (s.newWeightKg ?: p.targetWeightKg),
                        targetSeconds = s.newSeconds ?: p.targetSeconds,
                        isWarmup = false
                    )
                }
            }
            PlannedExercise(
                exercise = ge.exercise,
                groupExerciseId = ge.groupExercise.id,
                supersetWithNext = ge.groupExercise.supersetWithNext,
                restSeconds = ge.sortedSets.firstOrNull { it.restSecondsOverride != null }?.restSecondsOverride
                    ?: ge.exercise.defaultRestSeconds ?: settings.defaultRestSeconds,
                sets = sets
            )
        }
}
