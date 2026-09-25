package com.animesh.fitnesstracker.data.model

enum class ExerciseType { WEIGHT, BODYWEIGHT, TIMED }

enum class ProgressionRule { LINEAR_WEIGHT, DOUBLE_PROGRESSION, LINEAR_TIME, LINEAR_REPS, NONE }

enum class SessionStatus { IN_PROGRESS, COMPLETED, ABANDONED }

/** Non-workout day records shown on the History calendar. */
enum class DayLogKind { REST, SKIPPED }

enum class WeightUnit { KG, LB }

object Muscles {
    val ALL = listOf(
        "chest", "back", "shoulders", "biceps", "triceps",
        "legs", "glutes", "core", "mobility", "cardio", "other"
    )
}

/** The three meals a day the diet planner schedules. Stored by name. */
enum class MealSlot { BREAKFAST, LUNCH, DINNER }

/** What kind of workout a watch activity is, mapped from the FIT sport and sub sport. Stored by name. */
enum class ActivityKind(val label: String) {
    STRENGTH("Strength"), WALK("Walk"), RUN("Run"), CYCLE("Cycle"), HIKE("Hike"), YOGA("Yoga"),
    CARDIO("Cardio"), SWIM("Swim"), OTHER("Activity");

    /** Foot sports show pace (min/km); the rest show speed (km/h). */
    val usesPace: Boolean get() = this == RUN || this == WALK || this == HIKE
}

/**
 * One watch computed number per day, keyed with the day in [DailyMetric]. Stored by name.
 * Version 0.5 adds SLEEP_NEED (value: Sleep Coach's demanded minutes for the night that starts on
 * the day, extra: the usual need), SLEEP_BODY_BATTERY (value: Body Battery at the end of the night
 * that ended on the day, extra: at its start), FITNESS_AGE and SKIN_TEMP (overnight deviation, once
 * the watch writes it).
 */
enum class MetricType {
    VO2MAX, TRAINING_LOAD_ACUTE, TRAINING_LOAD_CHRONIC, READINESS, ENDURANCE, HILL,
    RACE_5K, RACE_10K, RACE_HALF, RACE_FULL, RECOVERY_MIN, RMR, FTP, LTHR,
    SLEEP_NEED, SLEEP_BODY_BATTERY, FITNESS_AGE, SKIN_TEMP
}

/**
 * What a Body Battery event was, from the watch's kind code (FIT message 407 field 0). The mapping
 * was read off a Forerunner 570's own events (4 the night's sleep, 0 a recorded activity, 3 a stretch
 * without measurements) and is confirmed on the watch before any other kind gets a name.
 */
enum class BodyBatteryKind {
    SLEEP, ACTIVITY, UNMEASURED, UNKNOWN;

    companion object {
        fun fromRaw(kind: Int?): BodyBatteryKind = when (kind) {
            4 -> SLEEP
            0 -> ACTIVITY
            3 -> UNMEASURED
            else -> UNKNOWN
        }
    }
}
