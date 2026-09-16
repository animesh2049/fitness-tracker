package com.animesh.fitnesstracker

import com.animesh.fitnesstracker.data.model.ExerciseType
import com.animesh.fitnesstracker.domain.LoggedSet
import com.animesh.fitnesstracker.ui.progress.ProgressMath
import com.animesh.fitnesstracker.ui.progress.SeriesMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressMathTest {
    private fun weightSet(day: Long, session: Long, kg: Double, reps: Int, warmup: Boolean = false, completed: Boolean = true) =
        LoggedSet(epochDay = day, sessionId = session, targetReps = reps, targetWeightKg = kg, actualReps = reps, actualWeightKg = kg, isWarmup = warmup, completed = completed)

    private fun timedSet(day: Long, session: Long, secs: Int) =
        LoggedSet(epochDay = day, sessionId = session, targetSeconds = secs, actualSeconds = secs)

    @Test
    fun seriesTakesHeaviestSetPerSessionInChronologicalOrder() {
        // Newest first, as the DAO returns them.
        val history = listOf(
            weightSet(20, 2, 60.0, 8), weightSet(20, 2, 57.5, 10), weightSet(20, 2, 40.0, 5, warmup = true),
            weightSet(10, 1, 55.0, 8), weightSet(10, 1, 55.0, 9)
        )
        val s = ProgressMath.series(history, ExerciseType.WEIGHT)
        assertEquals(SeriesMetric.WEIGHT_KG, s.metric)
        assertEquals(listOf(10L, 20L), s.points.map { it.epochDay })
        assertEquals(listOf(55.0, 60.0), s.points.map { it.value })
        assertEquals(9, s.points[0].reps)
        assertEquals(8, s.points[1].reps)
        assertEquals(60.0 * 8 + 57.5 * 10, s.points[1].volumeKg, 1e-9)
    }

    @Test
    fun seriesFallsBackToRepsWhenNothingIsWeighted() {
        val history = listOf(weightSet(20, 2, 0.0, 12), weightSet(10, 1, 0.0, 10), weightSet(10, 1, 0.0, 11))
        val s = ProgressMath.series(history, ExerciseType.BODYWEIGHT)
        assertEquals(SeriesMetric.REPS, s.metric)
        assertEquals(listOf(11.0, 12.0), s.points.map { it.value })
    }

    @Test
    fun seriesIgnoresIncompleteSetsAndCapsAtTwelveSessions() {
        val history = (20L downTo 1L).flatMap { d -> listOf(weightSet(d, d, d * 2.0, 5), weightSet(d, d, 999.0, 5, completed = false)) }
        val s = ProgressMath.series(history, ExerciseType.WEIGHT)
        assertEquals(12, s.points.size)
        assertEquals(9L, s.points.first().epochDay)
        assertEquals(20L, s.points.last().epochDay)
        assertTrue(s.points.none { it.value == 999.0 })
    }

    @Test
    fun timedSeriesUsesLongestHoldAndSumsTotals() {
        val history = listOf(timedSet(5, 2, 45), timedSet(5, 2, 40), timedSet(1, 1, 30))
        val s = ProgressMath.series(history, ExerciseType.TIMED)
        assertEquals(SeriesMetric.SECONDS, s.metric)
        assertEquals(listOf(30.0, 45.0), s.points.map { it.value })
        assertEquals(85, s.points[1].totalSeconds)
    }

    @Test
    fun axisBoundsAreNiceAndSymmetricAroundTheMidline() {
        val b = ProgressMath.axisBounds(listOf(52.5, 55.0, 57.5, 60.0))
        assertEquals(50.0, b.min, 1e-9)
        assertEquals(60.0, b.max, 1e-9)
        assertEquals(55.0, b.mid, 1e-9)

        val secs = ProgressMath.axisBounds(listOf(30.0, 35.0, 45.0))
        assertEquals(30.0, secs.min, 1e-9)
        assertEquals(50.0, secs.max, 1e-9)

        val odd = ProgressMath.axisBounds(listOf(55.0, 72.5))
        val intervals = Math.round((odd.max - odd.min) / ProgressMath.niceStep((72.5 - 55.0) / 2))
        assertEquals(0L, intervals % 2)
    }

    @Test
    fun flatSeriesGetsHeadroomOnBothSides() {
        val b = ProgressMath.axisBounds(listOf(100.0, 100.0))
        assertEquals(90.0, b.min, 1e-9)
        assertEquals(110.0, b.max, 1e-9)
        assertEquals(0.5f, b.fraction(100.0), 1e-6f)
        val zero = ProgressMath.axisBounds(listOf(0.0))
        assertEquals(0.0, zero.min, 1e-9)
        assertTrue(zero.max > 0)
    }

    @Test
    fun niceStepRoundsUpToRoundNumbers() {
        assertEquals(5.0, ProgressMath.niceStep(3.75), 1e-9)
        assertEquals(10.0, ProgressMath.niceStep(7.5), 1e-9)
        assertEquals(2.5, ProgressMath.niceStep(2.2), 1e-9)
        assertEquals(0.5, ProgressMath.niceStep(0.5), 1e-9)
        assertEquals(1.0, ProgressMath.niceStep(0.0), 1e-9)
    }

    @Test
    fun labelsAreNotThinnedUpToSevenPoints() {
        assertEquals((0 until 7).toSet(), ProgressMath.labelIndices(7, 3))
        assertEquals(emptySet<Int>(), ProgressMath.labelIndices(0, 0))
    }

    @Test
    fun labelsAreThinnedAnchoredAtTheLastPointAndKeepTheSelection() {
        val twelve = ProgressMath.labelIndices(12, selected = 11)
        assertEquals(setOf(1, 3, 5, 7, 9, 11), twelve)

        val withSel = ProgressMath.labelIndices(12, selected = 4)
        assertTrue(4 in withSel)
        assertTrue(3 !in withSel && 5 !in withSel)
        assertTrue(11 in withSel)
        assertTrue(withSel.size <= 7)
    }

    @Test
    fun nearestIndexRespectsHitRadius() {
        val xs = listOf(0f, 100f, 200f)
        assertEquals(1, ProgressMath.nearestIndex(xs, 110f, 24f))
        assertEquals(2, ProgressMath.nearestIndex(xs, 190f, 24f))
        assertNull(ProgressMath.nearestIndex(xs, 150f, 24f))
        assertNull(ProgressMath.nearestIndex(emptyList(), 10f, 24f))
    }

    @Test
    fun bubbleStaysInsideThePlot() {
        assertEquals(40f, ProgressMath.clampBubbleLeft(45f, 60f, 40f, 326f), 1e-6f)
        assertEquals(266f, ProgressMath.clampBubbleLeft(320f, 60f, 40f, 326f), 1e-6f)
        assertEquals(150f, ProgressMath.clampBubbleLeft(180f, 60f, 40f, 326f), 1e-6f)
    }

    @Test
    fun xPositionsAreEvenlySpacedWithASinglePointCentred() {
        assertEquals(listOf(183f), ProgressMath.xPositions(1, 40f, 326f))
        val three = ProgressMath.xPositions(3, 40f, 326f)
        assertEquals(40f, three[0], 1e-6f)
        assertEquals(183f, three[1], 1e-6f)
        assertEquals(326f, three[2], 1e-6f)
    }
}
