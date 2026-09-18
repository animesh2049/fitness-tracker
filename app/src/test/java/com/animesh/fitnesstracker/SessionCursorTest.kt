package com.animesh.fitnesstracker

import com.animesh.fitnesstracker.data.model.ExerciseType
import com.animesh.fitnesstracker.data.model.SessionExercise
import com.animesh.fitnesstracker.data.model.SessionExerciseWithSets
import com.animesh.fitnesstracker.data.model.SessionSet
import com.animesh.fitnesstracker.domain.planner.SessionCursor
import com.animesh.fitnesstracker.domain.planner.SessionFlow
import com.animesh.fitnesstracker.domain.planner.UpNext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCursorTest {
    /** Session exercise ids are 100 + position so they never collide with an index. */
    private fun ex(pos: Int, sets: Int, superset: Boolean = false, skipped: Boolean = false, done: Int = 0) = SessionExerciseWithSets(
        SessionExercise(id = 100L + pos, sessionId = 1, exerciseId = pos.toLong(), exerciseName = "E$pos", exerciseType = ExerciseType.WEIGHT, position = pos, supersetWithNext = superset, restSeconds = 60, skipped = skipped),
        List(sets) { SessionSet(id = pos * 10L + it, sessionExerciseId = 100L + pos, position = it, targetReps = 8, targetWeightKg = 20.0, completed = it < done) }
    )

    private fun text(s: SessionSet) = "${s.targetReps} × ${s.targetWeightKg} kg"

    // Sample from the design: exercise 0 done, exercise 1 in progress, exercise 2 skipped, 3 and 4 untouched.
    private val sample = listOf(ex(0, 3, done = 3), ex(1, 3, done = 1), ex(2, 3, skipped = true), ex(3, 3), ex(4, 3))
    private val sampleSteps = SessionFlow.steps(sample)

    @Test
    fun resolveWithoutCursorFallsBackToFirstUnloggedStep() {
        assertEquals(1, SessionCursor.resolve(sample, sampleSteps, null))
    }

    @Test
    fun resolveUsesExplicitCursorEvenOnCompleteOrSkippedExercise() {
        assertEquals(0, SessionCursor.resolve(sample, sampleSteps, 100L))
        assertEquals(2, SessionCursor.resolve(sample, sampleSteps, 102L))
        assertEquals(4, SessionCursor.resolve(sample, sampleSteps, 104L))
    }

    @Test
    fun resolveIgnoresUnknownCursorId() {
        assertEquals(1, SessionCursor.resolve(sample, sampleSteps, 999L))
    }

    @Test
    fun resolveFallsBackToLastExerciseWhenEverythingIsLogged() {
        val all = listOf(ex(0, 2, done = 2), ex(1, 1, skipped = true), ex(2, 2, done = 2))
        assertEquals(2, SessionCursor.resolve(all, SessionFlow.steps(all), null))
        assertEquals(-1, SessionCursor.resolve(emptyList(), emptyList(), null))
    }

    @Test
    fun currentStepIsTheExercisesFirstUnloggedSet() {
        val step = SessionCursor.currentStep(sampleSteps, 1)!!
        assertEquals(1, step.exerciseIndex)
        assertEquals(1, step.setIndex)
        assertEquals(0, SessionCursor.currentStep(sampleSteps, 3)!!.setIndex)
    }

    @Test
    fun currentStepIsNullOnCompleteAndSkippedExercises() {
        assertNull(SessionCursor.currentStep(sampleSteps, 0))
        assertNull(SessionCursor.currentStep(sampleSteps, 2))
        assertNull(SessionCursor.currentStep(sampleSteps, 7))
    }

    @Test
    fun neighbourClampsAtTheEndsAndVisitsEveryExercise() {
        assertEquals(0, SessionCursor.neighbour(5, 0, -1))
        assertEquals(4, SessionCursor.neighbour(5, 4, +1))
        assertEquals(2, SessionCursor.neighbour(5, 1, +1)) // the skipped one is visited
        assertEquals(0, SessionCursor.neighbour(5, 1, -1)) // the complete one is visited
        assertEquals(3, SessionCursor.neighbour(5, 2, +1))
        assertEquals(-1, SessionCursor.neighbour(0, 0, +1))
    }

    @Test
    fun afterDoneSetStaysOnAPlainExerciseUntilItsLastSet() {
        val plain = listOf(ex(0, 2), ex(1, 1))
        val steps = SessionFlow.steps(plain)
        assertEquals(0, SessionCursor.afterDoneSet(steps, steps[0]))
        assertEquals(1, SessionCursor.afterDoneSet(steps, steps[1]))
        // Nothing follows the very last set: stay put.
        assertEquals(1, SessionCursor.afterDoneSet(steps, steps[2]))
    }

    @Test
    fun afterDoneSetFollowsTheSupersetPartnerAndReturns() {
        val superset = listOf(ex(0, 2, superset = true), ex(1, 2), ex(2, 1))
        val steps = SessionFlow.steps(superset) // A1 B1 A2 B2 C1
        assertEquals(1, SessionCursor.afterDoneSet(steps, steps[0])) // A1 -> B
        assertEquals(0, SessionCursor.afterDoneSet(steps, steps[1])) // B1 -> A
        assertEquals(1, SessionCursor.afterDoneSet(steps, steps[2])) // A2 -> B
        assertEquals(2, SessionCursor.afterDoneSet(steps, steps[3])) // B2 -> C
    }

    @Test
    fun afterDoneSetSkipsSetsAlreadyLoggedOutOfOrder() {
        // The user logged B1 before A1 (navigated ahead), then came back and logged A1.
        val a = ex(0, 2, superset = true)
        val b = ex(1, 2, done = 1)
        val steps = SessionFlow.steps(listOf(a, b))
        assertEquals(0, SessionCursor.afterDoneSet(steps, steps[0])) // B1 done already, so A2 is next
    }

    @Test
    fun upNextDescribesNextSetPartnerNextExerciseAndFinish() {
        val superset = listOf(ex(0, 2, superset = true), ex(1, 2), ex(2, 2, done = 1))
        val steps = SessionFlow.steps(superset)
        assertTrue(SessionCursor.upNext(superset, steps, 0) is UpNext.Partner)
        assertEquals("E1 · Set 1", SessionCursor.upNextText(SessionCursor.upNext(superset, steps, 0), superset, ::text))
        // C has one set left and nothing after it; the earlier unlogged A/B sets are still pending, so no finish.
        val fromC = SessionCursor.upNext(superset, steps, 2)
        assertTrue(fromC is UpNext.NextExercise)
        assertEquals("E0 · Set 1", SessionCursor.upNextText(fromC, superset, ::text))

        val plain = listOf(ex(0, 2), ex(1, 1))
        val plainSteps = SessionFlow.steps(plain)
        assertEquals("Set 2 · 8 × 20.0 kg", SessionCursor.upNextText(SessionCursor.upNext(plain, plainSteps, 0), plain, ::text))
        assertTrue(SessionCursor.upNext(plain, plainSteps, 0) is UpNext.NextSet)
        val singles = listOf(ex(0, 1), ex(1, 1))
        val singlesSteps = SessionFlow.steps(singles)
        assertTrue(SessionCursor.upNext(singles, singlesSteps, 0) is UpNext.NextExercise)
        assertEquals("E1 · Set 1", SessionCursor.upNextText(SessionCursor.upNext(singles, singlesSteps, 0), singles, ::text))

        val lastOnly = listOf(ex(0, 2, done = 2), ex(1, 1))
        assertEquals("Finish session", SessionCursor.upNextText(SessionCursor.upNext(lastOnly, SessionFlow.steps(lastOnly), 1), lastOnly, ::text))
    }

    @Test
    fun upNextFromACompleteOrSkippedExercisePointsAtTheFirstPendingStep() {
        val fromDone = SessionCursor.upNext(sample, sampleSteps, 0)
        assertTrue(fromDone is UpNext.NextExercise)
        assertEquals(1, (fromDone as UpNext.NextExercise).exerciseIndex)
        val fromSkipped = SessionCursor.upNext(sample, sampleSteps, 2)
        assertEquals(3, (fromSkipped as UpNext.NextExercise).exerciseIndex)
        // Last exercise complete, earlier one pending: wrap around instead of claiming finish.
        val wrap = listOf(ex(0, 1), ex(1, 1, done = 1))
        val fromLast = SessionCursor.upNext(wrap, SessionFlow.steps(wrap), 1)
        assertEquals(0, (fromLast as UpNext.NextExercise).exerciseIndex)
        val allDone = listOf(ex(0, 1, done = 1), ex(1, 1, skipped = true))
        assertTrue(SessionCursor.upNext(allDone, SessionFlow.steps(allDone), 1) is UpNext.Finish)
    }

    @Test
    fun stepForSetFindsTheSetByIdEvenAfterNavigatingAway() {
        val step = SessionCursor.stepForSet(sample, sampleSteps, 31L)!!
        assertEquals(3, step.exerciseIndex)
        assertEquals(1, step.setIndex)
        // A set on a skipped exercise is not in the flow but can still be resolved from the rows.
        val onSkipped = SessionCursor.stepForSet(sample, sampleSteps, 20L)!!
        assertEquals(2, onSkipped.exerciseIndex)
        assertEquals(0, onSkipped.setIndex)
        assertNull(SessionCursor.stepForSet(sample, sampleSteps, 999L))
    }
}
