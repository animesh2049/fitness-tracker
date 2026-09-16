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
