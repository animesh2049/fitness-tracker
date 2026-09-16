package com.animesh.fitnesstracker.ui.progress

import com.animesh.fitnesstracker.data.model.ExerciseType
import com.animesh.fitnesstracker.domain.LoggedSet
import com.animesh.fitnesstracker.domain.bySession
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** What the single chart series measures. */
enum class SeriesMetric { WEIGHT_KG, REPS, SECONDS }

/** One session on the chart: its top value plus the reps of the top set when the metric is a weight. */
data class SeriesPoint(
    val epochDay: Long,
    val sessionId: Long,
    val value: Double,
    val reps: Int? = null,
    /** Sum of reps x weight over completed working sets, kg. */
    val volumeKg: Double = 0.0,
    /** Sum of actual seconds over completed working sets. */
    val totalSeconds: Int = 0
)

data class Series(val metric: SeriesMetric, val points: List<SeriesPoint>) {
    val isEmpty: Boolean get() = points.isEmpty()
}

/** Y axis bounds with three grid lines at [min], [mid] and [max]. */
data class AxisBounds(val min: Double, val max: Double) {
    val mid: Double get() = (min + max) / 2
    val gridValues: List<Double> get() = listOf(min, mid, max)
    /** 0 at [min], 1 at [max]. Values outside are clamped. */
    fun fraction(value: Double): Float {
        val span = max - min
        if (span <= 0) return 0.5f
        return ((value - min) / span).coerceIn(0.0, 1.0).toFloat()
    }
}

/** Pure chart maths for the Progress tab, kept free of Compose so it can be unit tested. */
object ProgressMath {
    const val MAX_POINTS = 12
    const val MAX_DATE_LABELS = 7

    /**
     * Builds the chart series from a flat, newest-first history. Warm-ups and incomplete sets are
     * ignored; sessions without a completed working set are dropped. The result is chronological
     * and holds at most [maxPoints] of the most recent sessions.
     *
     * WEIGHT and BODYWEIGHT chart the heaviest set per session. When no set in the whole history
     * carries a weight (pure bodyweight work) the series falls back to the most reps per session.
     * TIMED charts the longest actual hold per session.
     */
    fun series(history: List<LoggedSet>, type: ExerciseType, maxPoints: Int = MAX_POINTS): Series {
        val sessions = history.bySession().map { sets -> sets.filter { it.completed } }.filter { it.isNotEmpty() }
        val metric = when (type) {
            ExerciseType.TIMED -> SeriesMetric.SECONDS
            else -> if (sessions.any { s -> s.any { (it.actualWeightKg ?: 0.0) > 0 } }) SeriesMetric.WEIGHT_KG else SeriesMetric.REPS
        }
        val points = sessions.mapNotNull { sets ->
            val first = sets.first()
            val volume = sets.sumOf { (it.actualReps ?: 0) * (it.actualWeightKg ?: 0.0) }
            val seconds = sets.sumOf { it.actualSeconds ?: 0 }
            when (metric) {
                SeriesMetric.SECONDS -> {
                    val best = sets.maxOfOrNull { it.actualSeconds ?: 0 } ?: 0
                    if (best <= 0) null else SeriesPoint(first.epochDay, first.sessionId, best.toDouble(), volumeKg = volume, totalSeconds = seconds)
                }
                SeriesMetric.WEIGHT_KG -> {
                    val top = sets.filter { (it.actualWeightKg ?: 0.0) > 0 }
                        .sortedWith(compareByDescending<LoggedSet> { it.actualWeightKg!! }.thenByDescending { it.actualReps ?: 0 })
                        .firstOrNull() ?: return@mapNotNull null
                    SeriesPoint(first.epochDay, first.sessionId, top.actualWeightKg!!, top.actualReps, volume, seconds)
                }
                SeriesMetric.REPS -> {
                    val top = sets.maxByOrNull { it.actualReps ?: 0 } ?: return@mapNotNull null
                    val reps = top.actualReps ?: 0
                    if (reps <= 0) null else SeriesPoint(first.epochDay, first.sessionId, reps.toDouble(), reps, volume, seconds)
                }
            }
        }
        return Series(metric, points.take(maxPoints).asReversed())
    }

    /**
     * "Nice" axis bounds that enclose [values] with a round step so that min, mid and max all land
     * on tidy numbers. A flat series gets a symmetric band around its value.
     */
    fun axisBounds(values: List<Double>): AxisBounds {
        if (values.isEmpty()) return AxisBounds(0.0, 1.0)
        val lo = values.min()
        val hi = values.max()
        val range = hi - lo
        if (range <= 0) {
            // Flat series: one step of headroom either side so the line sits on the middle grid line.
            val step = niceStep(maxOf(abs(hi) * 0.2, 1.0) / 2)
            val base = floor(lo / step + 1e-9) * step
            val min = if (base - step < 0 && lo >= 0) 0.0 else base - step
            return AxisBounds(round2(min), round2(base + step))
        }
        val step = niceStep(range / 2)
        var min = floor(lo / step + 1e-9) * step
        var max = ceil(hi / step - 1e-9) * step
        if (min < 0 && lo >= 0) min = 0.0
        if (max - min < step - 1e-9) max = min + step
        val intervals = Math.round((max - min) / step).toInt()
        if (intervals % 2 == 1) max += step
        return AxisBounds(round2(min), round2(max))
    }

    /** Rounds [raw] up to 1, 2, 2.5 or 5 times a power of ten. */
    fun niceStep(raw: Double): Double {
        if (raw <= 0) return 1.0
        val mag = 10.0.pow(floor(log10(raw)))
        val frac = raw / mag
        val nice = when {
            frac <= 1.0 -> 1.0
            frac <= 2.0 -> 2.0
            frac <= 2.5 -> 2.5
            frac <= 5.0 -> 5.0
            else -> 10.0
        }
        return round2(nice * mag)
    }

    /**
     * Which point indices get a date label. Up to [maxLabels] points are all labelled; beyond that
     * labels are thinned with a fixed stride anchored at the last point, and the selected point is
     * always labelled (displacing any neighbour that would collide with it).
     */
    fun labelIndices(count: Int, selected: Int, maxLabels: Int = MAX_DATE_LABELS): Set<Int> {
        if (count <= 0) return emptySet()
        if (count <= maxLabels) return (0 until count).toSet()
        val stride = ceil((count - 1).toDouble() / (maxLabels - 1)).toInt().coerceAtLeast(2)
        val out = (0 until count).filter { (count - 1 - it) % stride == 0 }.toMutableSet()
        if (selected in 0 until count && selected !in out) {
            out.removeAll { abs(it - selected) < stride }
            out += selected
        }
        return out
    }

    /** Index of the point whose x is closest to [tapX], or null when none is within [hitRadius]. */
    fun nearestIndex(xs: List<Float>, tapX: Float, hitRadius: Float): Int? {
        var best: Int? = null
        var bestDist = Float.MAX_VALUE
        xs.forEachIndexed { i, x ->
            val d = abs(x - tapX)
            if (d < bestDist) { bestDist = d; best = i }
        }
        return if (best != null && bestDist <= hitRadius) best else null
    }

    /** Left edge of a bubble of [width] centred on [centerX], kept inside [left, right]. */
    fun clampBubbleLeft(centerX: Float, width: Float, left: Float, right: Float): Float {
        val maxLeft = right - width
        if (maxLeft <= left) return left
        return (centerX - width / 2).coerceIn(left, maxLeft)
    }

    /** X positions for [count] evenly spaced points between [left] and [right]; a single point sits in the middle. */
    fun xPositions(count: Int, left: Float, right: Float): List<Float> {
        if (count <= 0) return emptyList()
        if (count == 1) return listOf((left + right) / 2)
        val step = (right - left) / (count - 1)
        return List(count) { left + it * step }
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
