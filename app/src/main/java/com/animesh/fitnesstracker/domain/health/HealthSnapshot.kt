package com.animesh.fitnesstracker.domain.health

import com.animesh.fitnesstracker.garmin.fit.DecodedFit
import com.animesh.fitnesstracker.garmin.fit.HsaKind
import com.animesh.fitnesstracker.garmin.fit.HsaSampleRec
import kotlin.math.roundToInt

/**
 * The numbers a Health Snapshot detail shows: the two minute recording's heart rate range and
 * average, respiration, stress, SpO2 and Body Battery at its start and end. Everything comes from
 * the watch's own samples; nothing is re-derived.
 */
data class HealthSnapshotSummary(
    val startTimestamp: Long,
    val endTimestamp: Long,
    val avgHeartRate: Int?,
    val minHeartRate: Int?,
    val maxHeartRate: Int?,
    val avgRespiration: Double?,
    val avgStress: Int?,
    val avgSpo2: Int?,
    val bodyBatteryStart: Int?,
    val bodyBatteryEnd: Int?,
    /** Expanded sample count across every stream. */
    val samples: Int
) {
    val durationSeconds: Int get() = (endTimestamp - startTimestamp).coerceAtLeast(0).toInt()
}

/** Summarises a decoded Health Snapshot file (FIT type 70). Pure Kotlin, unit tested. */
object HealthSnapshots {
    /** A sample placed in time: the record's timestamp plus its index times the processing interval. */
    data class TimedSample(val timestamp: Long, val value: Double)

    /** Expands a record's array into timed samples; a missing interval stacks the values on the record's timestamp. */
    fun expand(rec: HsaSampleRec): List<TimedSample> {
        val step = (rec.processingIntervalSeconds ?: 0).coerceAtLeast(0)
        return rec.values.mapIndexed { i, v -> TimedSample(rec.timestamp + i.toLong() * step, v) }
    }

    /** Null when the file carries no samples at all. Stress ignores the watch's negative sentinels. */
    fun summarise(fit: DecodedFit): HealthSnapshotSummary? {
        val byKind = fit.healthSnapshot.groupBy { it.kind }.mapValues { (_, recs) -> recs.sortedBy { it.timestamp }.flatMap(::expand) }
        val all = byKind.values.flatten()
        if (all.isEmpty()) return null
        val heart = byKind[HsaKind.HEART_RATE].orEmpty().map { it.value }.filter { it > 0 }
        val respiration = byKind[HsaKind.RESPIRATION].orEmpty().map { it.value }.filter { it > 0 }
        val stress = byKind[HsaKind.STRESS].orEmpty().map { it.value }.filter { it >= 0 }
        val spo2 = byKind[HsaKind.SPO2].orEmpty().map { it.value }.filter { it > 0 }
        val battery = byKind[HsaKind.BODY_BATTERY].orEmpty().map { it.value }.filter { it in 0.0..100.0 }
        return HealthSnapshotSummary(
            startTimestamp = all.minOf { it.timestamp },
            endTimestamp = all.maxOf { it.timestamp },
            avgHeartRate = heart.averageOrNull()?.roundToInt(),
            minHeartRate = heart.minOrNull()?.roundToInt(),
            maxHeartRate = heart.maxOrNull()?.roundToInt(),
            avgRespiration = respiration.averageOrNull()?.let { (it * 10).roundToInt() / 10.0 },
            avgStress = stress.averageOrNull()?.roundToInt(),
            avgSpo2 = spo2.averageOrNull()?.roundToInt(),
            bodyBatteryStart = battery.firstOrNull()?.roundToInt(),
            bodyBatteryEnd = battery.lastOrNull()?.roundToInt(),
            samples = all.size
        )
    }

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
}
