package com.animesh.fitnesstracker.garmin.workout

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The seeded "Push day" (Seed.kt) turned into a watch plan for Thursday 17 September 2026. */
class WatchWorkoutPlannerTest {
    private val day = LocalDate.of(2026, 9, 17).toEpochDay()
    private val now = 1_789_646_400_000L // 2026-09-17T12:00:00Z

    private fun reps(n: Int, reps: Int, kg: Double) = List(n) { WatchSet(reps = reps, weightKg = kg) }
    private fun secs(n: Int, s: Int) = List(n) { WatchSet(seconds = s) }

    /** Ids follow the seed order: Bench press 1, Incline dumbbell press 2, Overhead press 3, Triceps pushdown 5, Plank 32. */
    private val pushDay = listOf(
        PlannerExercise(1, "Bench press", WatchExerciseKind.WEIGHT, listOf(WatchSet(8, 40.0, warmup = true)) + reps(3, 8, 60.0), exerciseDefaultRestSeconds = 120),
        PlannerExercise(3, "Overhead press", WatchExerciseKind.WEIGHT, reps(3, 10, 30.0), exerciseDefaultRestSeconds = 120),
        PlannerExercise(2, "Incline dumbbell press", WatchExerciseKind.WEIGHT, reps(3, 10, 22.5), supersetWithNext = true),
        PlannerExercise(5, "Triceps pushdown", WatchExerciseKind.WEIGHT, reps(3, 12, 25.0)),
        PlannerExercise(32, "Plank", WatchExerciseKind.TIMED, secs(3, 45), exerciseDefaultRestSeconds = 45)
    )

    @Test
    fun `push day becomes a named, serialised plan with resolved rest`() {
        val plan = WatchWorkoutPlanner.plan("Push day", 1, day, pushDay, settingsDefaultRestSeconds = 90, nowMillis = now)

        assertEquals("Push day · Thu 17 Sep", plan.name)
        assertEquals(day * 100_000 + 1, plan.serial)
        assertEquals(1_789_646_400L, plan.createdAtEpochSeconds)
        assertEquals(listOf("Bench press", "Overhead press", "Incline dumbbell press", "Triceps pushdown", "Plank"), plan.exercises.map { it.name })
        assertEquals(listOf(1L, 3L, 2L, 5L, 32L), plan.exercises.map { it.appExerciseId })

        // Rest: exercise default when set, else the settings default.
        assertEquals(listOf(120, 120, 90, 90, 45), plan.exercises.map { it.restSeconds })
        assertEquals(listOf(false, false, true, false, false), plan.exercises.map { it.supersetWithNext })
        assertEquals(listOf(WatchExerciseKind.WEIGHT, WatchExerciseKind.WEIGHT, WatchExerciseKind.WEIGHT, WatchExerciseKind.WEIGHT, WatchExerciseKind.TIMED), plan.exercises.map { it.kind })

        val bench = plan.exercises[0]
        assertEquals(4, bench.sets.size)
        assertTrue(bench.sets[0].warmup)
        assertEquals(40.0, bench.sets[0].weightKg!!, 0.0)
        assertEquals(8, bench.sets[0].reps)
        assertFalse(bench.sets[1].warmup)
        assertEquals(60.0, bench.sets[3].weightKg!!, 0.0)
        val plank = plan.exercises[4]
        assertEquals(listOf(45, 45, 45), plank.sets.map { it.seconds })
        assertTrue(plank.sets.all { it.reps == null && it.weightKg == null })
    }

    @Test
    fun `prescription rest override beats the exercise default`() {
        val exercises = listOf(pushDay[0].copy(restOverrideSeconds = 60), pushDay[2].copy(restOverrideSeconds = 30))
        val plan = WatchWorkoutPlanner.plan("Push day", 1, day, exercises, 90, now)
        assertEquals(listOf(60, 30), plan.exercises.map { it.restSeconds })
    }

    @Test
    fun `long group names are cut to 30 characters`() {
        val name = WatchWorkoutPlanner.name("Upper body hypertrophy block A", day)
        assertTrue(name.length <= 30)
        assertEquals("Upper body hypertroph · 17 Sep", name)
        assertEquals("Upper A (strength) · 17 Sep", WatchWorkoutPlanner.name("Upper A (strength)", day))
        val medium = WatchWorkoutPlanner.name("Upper body A", day)
        assertEquals("Upper body A · Thu 17 Sep", medium)
        assertTrue(WatchWorkoutPlanner.name("Push / pull / legs marathon", day).length <= 30)
    }

    @Test
    fun `serial is per day and group and never zero`() {
        assertEquals(20713L * 100_000 + 7, WatchWorkoutPlanner.serial(20713, 7))
        assertEquals(1L, WatchWorkoutPlanner.serial(0, 0))
        assertTrue(WatchWorkoutPlanner.serial(day, 3) != WatchWorkoutPlanner.serial(day, 4))
        assertTrue(WatchWorkoutPlanner.serial(day, 3) != WatchWorkoutPlanner.serial(day + 1, 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty day is refused`() {
        WatchWorkoutPlanner.plan("Empty", 1, day, emptyList(), 90, now)
    }
}
