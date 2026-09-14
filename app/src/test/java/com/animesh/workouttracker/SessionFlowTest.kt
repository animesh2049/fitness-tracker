package com.animesh.workouttracker

import com.animesh.workouttracker.data.model.ExerciseType
import com.animesh.workouttracker.data.model.SessionExercise
import com.animesh.workouttracker.data.model.SessionExerciseWithSets
import com.animesh.workouttracker.data.model.SessionSet
import com.animesh.workouttracker.domain.planner.SessionFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionFlowTest {
    private fun ex(pos: Int, sets: Int, superset: Boolean = false, skipped: Boolean = false, done: Int = 0) = SessionExerciseWithSets(
        SessionExercise(id = pos.toLong(), sessionId = 1, exerciseId = pos.toLong(), exerciseName = "E$pos", exerciseType = ExerciseType.WEIGHT, position = pos, supersetWithNext = superset, restSeconds = 60, skipped = skipped),
        List(sets) { SessionSet(id = pos * 10L + it, sessionExerciseId = pos.toLong(), position = it, targetReps = 8, completed = it < done) }
    )

    @Test
    fun plainExercisesGoSetBySet() {
        val steps = SessionFlow.steps(listOf(ex(0, 2), ex(1, 1)))
        assertEquals(listOf(0 to 0, 0 to 1, 1 to 0), steps.map { it.exerciseIndex to it.setIndex })
        assertEquals(listOf(true, true, true), steps.map { it.restAfter })
    }

    @Test
    fun supersetAlternatesAndRestsAfterRound() {
        val steps = SessionFlow.steps(listOf(ex(0, 2, superset = true), ex(1, 2), ex(2, 1)))
        assertEquals(listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1, 2 to 0), steps.map { it.exerciseIndex to it.setIndex })
        assertEquals(listOf(false, true, false, true, true), steps.map { it.restAfter })
    }

    @Test
    fun skippedExercisesAreLeftOutAndCurrentFindsFirstIncomplete() {
        val steps = SessionFlow.steps(listOf(ex(0, 2, done = 2), ex(1, 1, skipped = true), ex(2, 2, done = 1)))
        assertEquals(listOf(0 to 0, 0 to 1, 2 to 0, 2 to 1), steps.map { it.exerciseIndex to it.setIndex })
        val cur = SessionFlow.current(steps)!!
        assertEquals(2 to 1, cur.exerciseIndex to cur.setIndex)
        assertNull(SessionFlow.next(steps, cur))
    }
}
