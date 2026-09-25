package com.animesh.fitnesstracker.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/*
 * Version 3 (Garmin health), extended in version 4 (floors, sleep score breakdown, Sleep Coach need,
 * Body Battery at sleep start and end, Body Battery events, activity performance condition and
 * benefit). Every timestamp is Unix seconds unless the name says otherwise;
 * every epochDay is the local calendar day. The watch computes all of these numbers, the app
 * only converts cumulative counters to per-minute deltas and assembles nights from stages.
 */

/** A raw FIT file the watch gave us (or a zip or USB copy), and how its import went. */
@Serializable
@Entity(tableName = "garmin_files", indices = [Index("path", unique = true), Index("watchTimestamp")])
data class SyncedFile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Watch directory index at download time, 0 for zip and USB imports. */
    val watchIndex: Int,
    /** FIT file_id.type: 4 activity, 15/28/32 monitoring, 44 metrics, 49 sleep, 68 HRV. */
    val fitType: Int,
    val watchTimestamp: Long? = null,
    /** Path relative to the raw file store root. */
    val path: String,
    val sizeBytes: Long,
    /** Unix millis of the last successful import, null when never imported. */
    val importedAt: Long? = null,
    val importError: String? = null,
    val minuteSamples: Int = 0,
    val activities: Int = 0
)

/** One minute the watch was worn. Steps, distance and calories are the delta for that minute. */
@Serializable
@Entity(tableName = "health_minutes", indices = [Index("epochDay")])
data class HealthMinute(
    /** Minute aligned. */
    @PrimaryKey val timestamp: Long,
    val epochDay: Long,
    val steps: Int = 0,
    val distanceM: Double = 0.0,
    val activeKcal: Int = 0,
    val heartRate: Int? = null,
    /** FIT activity_type of the record (0 generic, 1 running, 2 cycling, 6 walking, 8 sedentary...). */
    val activityKind: Int = 0,
    /** FIT intensity 0..7 from current_activity_type_intensity. */
    val intensity: Int = 0,
    /** False for gap filler rows longer than ten minutes: the watch was off the wrist. */
    val worn: Boolean = true,
    /** Metres climbed and descended in the minute, from the barometric altimeter (version 4). */
    val ascentM: Double = 0.0,
    val descentM: Double = 0.0
)

/**
 * One Body Battery event as the watch lists them (charged by sleep, drained by a workout...),
 * from FIT message 407 in the monitoring file. Keyed by start and raw kind. Version 4.
 */
@Serializable
@Entity(tableName = "health_body_battery_events", primaryKeys = ["startTimestamp", "kindRaw"], indices = [Index("epochDay")])
data class BodyBatteryEvent(
    val startTimestamp: Long,
    /** The watch's kind code; [kind] is the app's reading of it. */
    val kindRaw: Int,
    val endTimestamp: Long,
    /** Local day of the start, or of the end for sleep (the morning the night is keyed by). */
    val epochDay: Long,
    val minutes: Int,
    /** Signed Body Battery change over the event. */
    val delta: Int,
    val kind: BodyBatteryKind
) {
    val charged: Boolean get() = delta > 0
}

/** Stress and Body Battery, every three minutes. */
@Serializable
@Entity(tableName = "health_stress")
data class StressSample(
    @PrimaryKey val timestamp: Long,
    /** 0..100, null when the watch could not measure (it reported -1 or -2). */
    val stress: Int? = null,
    /** 0..100. */
    val bodyBattery: Int? = null
)

@Serializable
@Entity(tableName = "health_spo2")
data class Spo2Sample(
    @PrimaryKey val timestamp: Long,
    val percent: Int,
    /** FIT mode: 1 manual, 3 automatic. */
    val mode: Int? = null
)

@Serializable
@Entity(tableName = "health_respiration")
data class RespirationSample(
    @PrimaryKey val timestamp: Long,
    val breathsPerMinute: Double
)

/** Five-minute overnight HRV value. */
@Serializable
@Entity(tableName = "health_hrv_values")
data class HrvValue(
    @PrimaryKey val timestamp: Long,
    val valueMs: Double
)

@Serializable
@Entity(tableName = "health_resting_hr")
data class RestingHrDaily(
    @PrimaryKey val epochDay: Long,
    val bpm: Int
)

/** Nightly HRV status as the watch computed it. Status 0 none, 1 poor, 2 low, 3 unbalanced, 4 balanced. */
@Serializable
@Entity(tableName = "health_hrv_summary")
data class HrvSummary(
    /** The morning the night ended on. */
    @PrimaryKey val epochDay: Long,
    val timestamp: Long,
    val weeklyAvg: Double? = null,
    val lastNightAvg: Double? = null,
    val fiveMinHigh: Double? = null,
    val baselineLowUpper: Double? = null,
    val baselineBalancedLower: Double? = null,
    val baselineBalancedUpper: Double? = null,
    val status: Int? = null
) {
    val statusLabel: String get() = HrvStatus.label(status)
}

object HrvStatus {
    const val NONE = 0
    const val POOR = 1
    const val LOW = 2
    const val UNBALANCED = 3
    const val BALANCED = 4

    fun label(status: Int?): String = when (status) {
        POOR -> "Poor"
        LOW -> "Low"
        UNBALANCED -> "Unbalanced"
        BALANCED -> "Balanced"
        else -> "No status"
    }
}

/** One sleep stage segment. Stage 0 unmeasurable, 1 awake, 2 light, 3 deep, 4 REM. */
@Serializable
@Entity(tableName = "health_sleep_stages", indices = [Index("nightEpochDay")])
data class SleepStage(
    /** The stage's upper bound, as the watch writes it. */
    @PrimaryKey val endTimestamp: Long,
    val startTimestamp: Long,
    val stage: Int,
    val nightEpochDay: Long
) {
    val seconds: Int get() = (endTimestamp - startTimestamp).coerceAtLeast(0).toInt()

    companion object {
        const val UNMEASURABLE = 0
        const val AWAKE = 1
        const val LIGHT = 2
        const val DEEP = 3
        const val REM = 4
    }
}

/** One night of sleep, keyed by the day it ends on. */
@Serializable
@Entity(tableName = "health_sleep_nights", indices = [Index("startTimestamp")])
data class SleepNight(
    @PrimaryKey val epochDay: Long,
    val startTimestamp: Long,
    val endTimestamp: Long,
    /** Overall sleep score 0..100 from the watch. */
    val score: Int? = null,
    val deepSeconds: Int = 0,
    val lightSeconds: Int = 0,
    val remSeconds: Int = 0,
    val awakeSeconds: Int = 0,
    val restlessMoments: Int? = null,
    val avgHrvMs: Double? = null,
    val hrvStatus: Int? = null,
    val avgRespiration: Double? = null,
    val avgSpo2: Double? = null,
    val lowestHr: Int? = null,
    /** "event" when the bounds came from FIT event 74, "stages" when from the first and last stage. */
    val source: String = SOURCE_STAGES,
    // Version 4: the watch's score breakdown (sleep_assessment), all 0..100.
    val awakeScore: Int? = null,
    val awakeningsScore: Int? = null,
    val deepScore: Int? = null,
    val lightScore: Int? = null,
    val remScore: Int? = null,
    val durationScore: Int? = null,
    val qualityScore: Int? = null,
    val recoveryScore: Int? = null,
    val restlessnessScore: Int? = null,
    val interruptionsScore: Int? = null,
    val awakeningsCount: Int? = null,
    val avgStressDuringSleep: Double? = null,
    // Version 4: from the metrics file (daily_sleep and sleep_demand), copied in by the importer.
    val bodyBatteryStart: Int? = null,
    val bodyBatteryEnd: Int? = null,
    /** Sleep Coach's demanded minutes for this night, announced the day before. */
    val sleepNeedMin: Int? = null,
    /** The usual need Sleep Coach adjusts from. */
    val sleepBaselineMin: Int? = null,
    /** Overnight skin temperature deviation, once the watch writes it (Milestone 37). */
    val skinTempDeviation: Double? = null
) {
    val bodyBatteryGain: Int? get() = if (bodyBatteryStart != null && bodyBatteryEnd != null) bodyBatteryEnd - bodyBatteryStart else null
    val hasScoreBreakdown: Boolean get() = durationScore != null || qualityScore != null || deepScore != null
    /** Time asleep: everything but awake. */
    val asleepSeconds: Int get() = deepSeconds + lightSeconds + remSeconds
    val totalSeconds: Int get() = asleepSeconds + awakeSeconds

    /** Length to show: the stage total, or the event window when the stages have not been synced yet. */
    val durationSeconds: Int get() = if (totalSeconds > 0) totalSeconds else (endTimestamp - startTimestamp).coerceAtLeast(0).toInt()

    companion object {
        const val SOURCE_STAGES = "stages"
        const val SOURCE_EVENT = "event"
    }
}

/** A watch computed daily number (VO2 max, training load, readiness, race predictions...). */
@Serializable
@Entity(tableName = "health_daily_metrics", primaryKeys = ["epochDay", "type"], indices = [Index("type", "epochDay")])
data class DailyMetric(
    val epochDay: Long,
    val type: MetricType,
    val value: Double,
    /** Level or category where the metric has one (hill and endurance level, VO2 max category, FTP's lactate HR). */
    val extra: Long? = null,
    val timestamp: Long
)

/** Intensity minutes credited in this minute. The week total counts vigorous double. */
@Serializable
@Entity(tableName = "health_intensity")
data class IntensityMinute(
    @PrimaryKey val timestamp: Long,
    val moderate: Int = 0,
    val vigorous: Int = 0
) {
    val weighted: Int get() = moderate + 2 * vigorous
}

/** A workout recorded on the watch. */
@Serializable
@Entity(
    tableName = "activities",
    foreignKeys = [
        ForeignKey(entity = Session::class, parentColumns = ["id"], childColumns = ["linkedSessionId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index("startTimestamp", "fitTimeCreated", unique = true), Index("linkedSessionId"), Index("kind")]
)
data class Activity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startTimestamp: Long,
    /** FIT file_id.time_created; together with the start it identifies the activity across re-imports. */
    val fitTimeCreated: Long,
    val endTimestamp: Long,
    val sport: Int,
    val subSport: Int,
    val kind: ActivityKind,
    val name: String,
    val timerSeconds: Int,
    val elapsedSeconds: Int,
    val distanceM: Double? = null,
    val calories: Int? = null,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val minHr: Int? = null,
    val avgCadence: Int? = null,
    val avgSpeedMps: Double? = null,
    val maxSpeedMps: Double? = null,
    val totalAscent: Int? = null,
    val totalDescent: Int? = null,
    val aerobicEffect: Double? = null,
    val anaerobicEffect: Double? = null,
    val recoveryMinutes: Int? = null,
    val bodyBatteryStart: Int? = null,
    val bodyBatteryEnd: Int? = null,
    val trainingLoad: Double? = null,
    val vo2max: Double? = null,
    /** Seconds in HR zones 1 to 5 as five comma separated ints, empty when the file had none. */
    val hrZoneSeconds: String = "",
    /** Upper bpm boundary of each zone as comma separated ints, empty when unknown. */
    val hrZoneBounds: String = "",
    /** Raw file path relative to the store root. */
    val filePath: String,
    val linkedSessionId: Long? = null,
    /** Version 4: signed deviation from the user's baseline at the end of the activity, when the watch computed one. */
    val performanceCondition: Int? = null,
    /** Version 4: the watch's training effect label code (see the domain's label table); null or 0 when it gave none. */
    val primaryBenefit: Int? = null
) {
    val zoneSecondsList: List<Int> get() = csvInts(hrZoneSeconds)
    val zoneBoundsList: List<Int> get() = csvInts(hrZoneBounds)

    companion object {
        fun csvInts(csv: String): List<Int> = csv.split(',').mapNotNull { it.trim().toIntOrNull() }
        fun csv(values: List<Int>): String = values.joinToString(",")
    }
}

@Serializable
@Entity(
    tableName = "activity_laps",
    foreignKeys = [ForeignKey(entity = Activity::class, parentColumns = ["id"], childColumns = ["activityId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("activityId")]
)
data class ActivityLap(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityId: Long,
    val index: Int,
    val startTimestamp: Long,
    val timerSeconds: Int,
    val distanceM: Double? = null,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val avgSpeedMps: Double? = null,
    val avgCadence: Int? = null,
    val calories: Int? = null
)

/** One track point (FIT record message). */
@Serializable
@Entity(
    tableName = "activity_points",
    primaryKeys = ["activityId", "timestamp"],
    foreignKeys = [ForeignKey(entity = Activity::class, parentColumns = ["id"], childColumns = ["activityId"], onDelete = ForeignKey.CASCADE)]
)
data class ActivityPoint(
    val activityId: Long,
    val timestamp: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val altitude: Double? = null,
    val distanceM: Double? = null,
    val speedMps: Double? = null,
    val heartRate: Int? = null,
    val cadence: Int? = null,
    val power: Int? = null,
    val temperature: Int? = null
)
