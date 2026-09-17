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

/** One watch computed number per day, keyed with the day in [DailyMetric]. Stored by name. */
enum class MetricType {
    VO2MAX, TRAINING_LOAD_ACUTE, TRAINING_LOAD_CHRONIC, READINESS, ENDURANCE, HILL,
    RACE_5K, RACE_10K, RACE_HALF, RACE_FULL, RECOVERY_MIN, RMR, FTP, LTHR
}
