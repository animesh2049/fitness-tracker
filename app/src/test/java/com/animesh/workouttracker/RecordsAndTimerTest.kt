package com.animesh.workouttracker

import com.animesh.workouttracker.domain.LoggedSet
import com.animesh.workouttracker.domain.records.RecordKind
import com.animesh.workouttracker.domain.records.Records
import com.animesh.workouttracker.domain.timer.Countdown
import com.animesh.workouttracker.domain.timer.TimerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordsAndTimerTest {
    private fun set(day: Long, reps: Int, kg: Double) = LoggedSet(epochDay = day, sessionId = day, targetReps = reps, targetWeightKg = kg, actualReps = reps, actualWeightKg = kg)

    @Test
    fun epley() {
        assertEquals(76.0, Records.epley(60.0, 8), 0.01)
        assertEquals(100.0, Records.epley(100.0, 1), 0.0)
    }

    @Test
    fun recordSummaryForWeightExercise() {
        val history = listOf(set(1, 8, 55.0), set(2, 8, 57.5), set(3, 9, 57.5), set(4, 8, 60.0), set(5, 8, 60.0))
        val recs = Records.summary(history) { "$it kg" }
        val heaviest = recs.first { it.kind == RecordKind.HEAVIEST_FOR_REPS }
        assertEquals(60.0, heaviest.value, 0.0)
        assertEquals(8, heaviest.reps)
        assertEquals(4L, heaviest.epochDay) // first time it was hit
        val e1 = recs.first { it.kind == RecordKind.BEST_E1RM }
        assertEquals(76.0, e1.value, 0.0)
    }

    @Test
    fun beatsDetectsNewRecords() {
        val history = listOf(set(1, 8, 55.0), set(2, 8, 57.5), set(3, 8, 60.0))
        val kinds = Records.beats(history, set(4, 8, 62.5))
        assertTrue(kinds.contains(RecordKind.HEAVIEST_FOR_REPS))
        assertTrue(kinds.contains(RecordKind.BEST_E1RM))
        assertTrue(Records.beats(history, set(4, 8, 60.0)).isEmpty())
        assertTrue(Records.beats(history, set(4, 9, 60.0)).contains(RecordKind.MOST_REPS_AT_WEIGHT))
        assertTrue(Records.beats(history, set(4, 8, 40.0).copy(isWarmup = true)).isEmpty())
    }

    @Test
    fun longestHold() {
        val history = listOf(30, 35, 45).mapIndexed { i, s -> LoggedSet(epochDay = i.toLong(), sessionId = i.toLong(), targetSeconds = s, actualSeconds = s) }
        val recs = Records.summary(history) { "$it" }
        assertEquals(RecordKind.LONGEST_HOLD, recs.single().kind)
        assertEquals(45.0, recs.single().value, 0.0)
        assertEquals(listOf(RecordKind.LONGEST_HOLD), Records.beats(history, LoggedSet(epochDay = 9, targetSeconds = 55, actualSeconds = 55)))
    }

    @Test
    fun countdownUsesWallClock() {
        val c = Countdown.start(TimerKind.REST, 90, nowMillis = 1_000_000, label = "Set 2")
        assertEquals(90, c.remainingSeconds(1_000_000))
        assertEquals(90, c.remainingSeconds(1_000_500)) // ceil: shows 90 until a full second has passed
        assertEquals(89, c.remainingSeconds(1_001_000))
        assertEquals(60, c.remainingSeconds(1_030_000))
        assertFalse(c.isFinished(1_089_999))
        assertTrue(c.isFinished(1_090_000))
        assertEquals(0, c.remainingSeconds(1_200_000))
        val extended = c.add(30)
        assertEquals(120, extended.totalSeconds)
        assertEquals(90, extended.remainingSeconds(1_030_000))
        assertEquals(0.5f, Countdown.start(TimerKind.WORK, 60, 0).fraction(30_000), 1e-6f)
    }
}
