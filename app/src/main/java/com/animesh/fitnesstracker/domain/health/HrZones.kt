package com.animesh.fitnesstracker.domain.health

import com.animesh.fitnesstracker.data.model.Activity
import com.animesh.fitnesstracker.data.model.ActivityKind
import com.animesh.fitnesstracker.data.model.ActivityPoint
import java.util.Locale

/**
 * Heart rate zones for an activity. The watch's own time in zone wins when the file had it;
 * otherwise the zones are derived from the track points with boundaries at 50/60/70/80/90
 * percent of the max heart rate (zone 1 starts at 50 percent, zone 5 at 90 percent).
 */
object HrZones {
    const val ZONE_COUNT = 5
    val FRACTIONS = listOf(0.5, 0.6, 0.7, 0.8, 0.9)
    val NAMES = listOf("Warm up", "Easy", "Aerobic", "Threshold", "Maximum")

    /** Gaps between track points longer than this are treated as paused time and not counted. */
    private const val MAX_POINT_GAP_SECONDS = 60L

    /** Lower bpm boundary of zones 1 to 5. */
    fun bounds(maxHr: Int): List<Int> = FRACTIONS.map { Math.round(it * maxHr).toInt() }

    /** 0 below zone 1, else 1 to 5. */
    fun zoneFor(hr: Int, maxHr: Int): Int {
        val b = bounds(maxHr)
        var zone = 0
        for (i in b.indices) if (hr >= b[i]) zone = i + 1
        return zone
    }

    /** Seconds in zones 1 to 5, always five entries. */
    fun forActivity(activity: Activity, points: List<ActivityPoint>, maxHr: Int): List<Int> {
        val stored = activity.zoneSecondsList
        if (stored.size == ZONE_COUNT && stored.sum() > 0) return stored
        return fromPoints(points, maxHr)
    }

    /** Seconds in zones 1 to 5 from the track; each point's heart rate covers the time until the next point. */
    fun fromPoints(points: List<ActivityPoint>, maxHr: Int): List<Int> {
        val seconds = IntArray(ZONE_COUNT)
        val sorted = points.sortedBy { it.timestamp }
        for (i in 0 until sorted.size - 1) {
            val hr = sorted[i].heartRate ?: continue
            if (hr <= 0) continue
            val dt = (sorted[i + 1].timestamp - sorted[i].timestamp).coerceIn(0, MAX_POINT_GAP_SECONDS)
            val zone = zoneFor(hr, maxHr)
            if (zone in 1..ZONE_COUNT) seconds[zone - 1] += dt.toInt()
        }
        return seconds.toList()
    }

    /** Share of each zone in the total zone time, 0..1, all zeros when there is no zone time. */
    fun fractions(zoneSeconds: List<Int>): List<Double> {
        val total = zoneSeconds.sum()
        return if (total <= 0) zoneSeconds.map { 0.0 } else zoneSeconds.map { it.toDouble() / total }
    }
}

/** Pace and speed strings from metres per second. */
object PaceFormat {
    /** Whole seconds per kilometre, null when standing still. */
    fun secondsPerKm(speedMps: Double?): Int? {
        if (speedMps == null || speedMps <= 0.05) return null
        return Math.round(1000.0 / speedMps).toInt()
    }

    /** "5:32 /km", or "--" when there is no pace. */
    fun minPerKm(speedMps: Double?): String {
        val s = secondsPerKm(speedMps) ?: return "--"
        return String.format(Locale.US, "%d:%02d /km", s / 60, s % 60)
    }

    /** "24.1 km/h". */
    fun kmh(speedMps: Double?): String {
        if (speedMps == null || speedMps < 0) return "--"
        return String.format(Locale.US, "%.1f km/h", speedMps * 3.6)
    }

    /** Pace for foot sports, speed for the rest. */
    fun forKind(kind: ActivityKind, speedMps: Double?): String = if (kind.usesPace) minPerKm(speedMps) else kmh(speedMps)

    /** Average speed of a distance over a time, m/s, null when either is missing. */
    fun speed(distanceM: Double?, seconds: Int): Double? =
        if (distanceM == null || seconds <= 0) null else distanceM / seconds
}
