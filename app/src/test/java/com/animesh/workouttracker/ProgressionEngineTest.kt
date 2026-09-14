package com.animesh.workouttracker

import com.animesh.workouttracker.data.model.Exercise
import com.animesh.workouttracker.data.model.ExerciseType
import com.animesh.workouttracker.data.model.ProgressionRule
import com.animesh.workouttracker.domain.LoggedSet
import com.animesh.workouttracker.domain.progression.CurrentTargets
import com.animesh.workouttracker.domain.progression.ProgressionConfig
import com.animesh.workouttracker.domain.progression.ProgressionEngine
import com.animesh.workouttracker.domain.progression.SuggestionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressionEngineTest {

    private fun weightSession(day: Long, sessionId: Long, targetReps: Int, weight: Double, actual: List<Int>, warmup: Boolean = false) =
        actual.map { reps ->
            LoggedSet(epochDay = day, sessionId = sessionId, targetReps = targetReps, targetWeightKg = weight, actualReps = reps, actualWeightKg = weight, isWarmup = warmup)
        }

    private fun timedSession(day: Long, sessionId: Long, target: Int, actual: List<Int>) =
        actual.map { LoggedSet(epochDay = day, sessionId = sessionId, targetSeconds = target, actualSeconds = it) }

    private val bench = Exercise(id = 1, name = "Bench press", type = ExerciseType.WEIGHT, weightIncrementKg = 2.5)
    private val plank = Exercise(id = 2, name = "Plank", type = ExerciseType.TIMED, timeStepSeconds = 10, timeMaxSeconds = 180)

    @Test
    fun linearWeightAddsIncrementWhenAllSetsHit() {
        val history = weightSession(100, 1, 8, 60.0, listOf(8, 8, 8))
        val s = ProgressionEngine.suggest(bench, CurrentTargets(8, 60.0, null, 3), history)
        assertNotNull(s)
        assertEquals(SuggestionKind.WEIGHT_UP, s!!.kind)
        assertEquals(62.5, s.newWeightKg!!, 1e-9)
        assertEquals(8, s.newReps)
        assertTrue(s.reason, s.reason.contains("3 × 8 at 60 kg"))
    }

    @Test
    fun linearWeightHoldsWhenASetWasMissed() {
        val history = weightSession(100, 1, 8, 60.0, listOf(8, 8, 7))
        val s = ProgressionEngine.suggest(bench, CurrentTargets(8, 60.0, null, 3), history)
        assertEquals(SuggestionKind.HOLD, s!!.kind)
        assertTrue(s.reason.contains("2 of 3"))
    }

    @Test
    fun warmupsAndIncompleteSetsAreHandled() {
        val warm = weightSession(100, 1, 8, 40.0, listOf(3), warmup = true) // a "missed" warm-up must not matter
        val incomplete = LoggedSet(epochDay = 100, sessionId = 1, targetReps = 8, targetWeightKg = 60.0, actualReps = 8, actualWeightKg = 60.0, completed = false)
        val history = warm + weightSession(100, 1, 8, 60.0, listOf(8, 8)) + listOf(incomplete)
        val s = ProgressionEngine.suggest(bench, CurrentTargets(8, 60.0, null, 3), history)
        assertEquals(SuggestionKind.HOLD, s!!.kind) // incomplete set counts as a miss
        val clean = warm + weightSession(100, 1, 8, 60.0, listOf(8, 8, 8))
        assertEquals(SuggestionKind.WEIGHT_UP, ProgressionEngine.suggest(bench, CurrentTargets(8, 60.0, null, 3), clean)!!.kind)
    }

    @Test
    fun deloadAfterThreeConsecutiveFailures() {
        val ohp = Exercise(id = 3, name = "Overhead press", type = ExerciseType.WEIGHT, weightIncrementKg = 2.5)
        val history = weightSession(104, 3, 10, 30.0, listOf(10, 8, 7)) +
            weightSession(102, 2, 10, 30.0, listOf(9, 8, 8)) +
            weightSession(100, 1, 10, 30.0, listOf(10, 9, 7))
        val s = ProgressionEngine.suggest(ohp, CurrentTargets(10, 30.0, null, 3), history, ProgressionConfig(3, 10))
        assertEquals(SuggestionKind.DELOAD, s!!.kind)
        assertEquals(27.5, s.newWeightKg!!, 1e-9) // 27 rounded to the 2.5 increment
        assertTrue(s.reason.contains("3 sessions in a row"))
        // Two failures only: hold, not deload.
        val two = history.filter { it.sessionId != 1L }
        assertEquals(SuggestionKind.HOLD, ProgressionEngine.suggest(ohp, CurrentTargets(10, 30.0, null, 3), two)!!.kind)
    }

    @Test
    fun doubleProgressionAddsRepsThenWeight() {
        val incline = Exercise(id = 4, name = "Incline", type = ExerciseType.WEIGHT, progressionRule = ProgressionRule.DOUBLE_PROGRESSION, repRangeMin = 8, repRangeMax = 12, weightIncrementKg = 2.5)
        val atTen = weightSession(100, 1, 10, 22.5, listOf(10, 10, 10))
        val s1 = ProgressionEngine.suggest(incline, CurrentTargets(10, 22.5, null, 3), atTen)!!
        assertEquals(SuggestionKind.REPS_UP, s1.kind)
        assertEquals(11, s1.newReps)
        assertEquals(22.5, s1.newWeightKg!!, 1e-9)

        val atTwelve = weightSession(100, 1, 12, 22.5, listOf(12, 12, 12))
        val s2 = ProgressionEngine.suggest(incline, CurrentTargets(12, 22.5, null, 3), atTwelve)!!
        assertEquals(SuggestionKind.WEIGHT_UP, s2.kind)
        assertEquals(25.0, s2.newWeightKg!!, 1e-9)
        assertEquals(8, s2.newReps)
    }

    @Test
    fun linearTimeAddsStepAndRespectsCap() {
        val history = timedSession(100, 1, 45, listOf(45, 45, 45))
        val s = ProgressionEngine.suggest(plank, CurrentTargets(null, null, 45, 3), history)!!
        assertEquals(SuggestionKind.TIME_UP, s.kind)
        assertEquals(55, s.newSeconds)
        val capped = ProgressionEngine.suggest(plank, CurrentTargets(null, null, 180, 3), timedSession(100, 1, 180, listOf(180, 180, 180)))!!
        assertEquals(SuggestionKind.HOLD, capped.kind)
    }

    @Test
    fun linearRepsForBodyweight() {
        val pullup = Exercise(id = 5, name = "Pull-up", type = ExerciseType.BODYWEIGHT)
        val history = listOf(8, 8, 8).map { LoggedSet(epochDay = 100, sessionId = 1, targetReps = 8, actualReps = it) }
        val s = ProgressionEngine.suggest(pullup, CurrentTargets(8, null, null, 3), history)!!
        assertEquals(SuggestionKind.REPS_UP, s.kind)
        assertEquals(9, s.newReps)
    }

    @Test
    fun noHistoryOrRuleNoneGivesNothing() {
        assertNull(ProgressionEngine.suggest(bench, CurrentTargets(8, 60.0, null, 3), emptyList()))
        val none = bench.copy(progressionRule = ProgressionRule.NONE)
        assertNull(ProgressionEngine.suggest(none, CurrentTargets(8, 60.0, null, 3), weightSession(100, 1, 8, 60.0, listOf(8, 8, 8))))
    }

    @Test
    fun usesMostRecentSessionOnly() {
        val history = weightSession(104, 2, 8, 60.0, listOf(8, 8, 8)) + weightSession(100, 1, 8, 60.0, listOf(5, 5, 5))
        assertEquals(SuggestionKind.WEIGHT_UP, ProgressionEngine.suggest(bench, CurrentTargets(8, 60.0, null, 3), history)!!.kind)
    }

    @Test
    fun manuallyRaisedPrescriptionIsNotBumpedAgain() {
        val history = weightSession(100, 1, 8, 60.0, listOf(8, 8, 8))
        assertNull(ProgressionEngine.suggest(bench, CurrentTargets(8, 65.0, null, 3), history))
    }
}
