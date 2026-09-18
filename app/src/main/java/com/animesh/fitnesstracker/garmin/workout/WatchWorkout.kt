package com.animesh.fitnesstracker.garmin.workout

/**
 * The day's workout as the watch should receive it. Built from the active session plan (group,
 * prescriptions, accepted suggestions) by the UI layer and turned into a Garmin workout FIT file by
 * [WorkoutEncoder]. Pure data; no Room or Android types.
 */
data class WatchWorkoutPlan(
    /** Shown on the watch's workout list; keep it under about 30 characters. */
    val name: String,
    /** Unique per push. The watch identifies a workout file by (serial, time created) and drops duplicates. */
    val serial: Long,
    val createdAtEpochSeconds: Long,
    val exercises: List<WatchExercise>
)

enum class WatchExerciseKind { WEIGHT, BODYWEIGHT, TIMED }

data class WatchExercise(
    /** The app's exercise id; used as the custom exercise name number when the catalogue has no match. */
    val appExerciseId: Long,
    val name: String,
    val kind: WatchExerciseKind,
    val sets: List<WatchSet>,
    /** Rest after each working set, in seconds. */
    val restSeconds: Int,
    /** True when this exercise alternates with the next one (superset). */
    val supersetWithNext: Boolean = false
)

data class WatchSet(
    val reps: Int? = null,
    val weightKg: Double? = null,
    val seconds: Int? = null,
    val warmup: Boolean = false
)

/** How one app exercise was mapped onto Garmin's exercise catalogue. */
data class ExerciseMapping(
    val appExerciseId: Long,
    val appName: String,
    /** FIT exercise_category; 65534 means custom (unknown to the watch). */
    val category: Int,
    /** FIT exercise_name within the category, or the app exercise id for custom ones. */
    val exerciseName: Int,
    /** Garmin's label for the matched catalogue entry, or null for a custom exercise. */
    val catalogueLabel: String?
) {
    val isCustom: Boolean get() = category == CUSTOM_CATEGORY

    companion object {
        const val CUSTOM_CATEGORY = 65534
    }
}

/** The encoded file plus what went into it, for the preview sheet and the sync log. */
data class EncodedWorkout(
    val bytes: ByteArray,
    val stepCount: Int,
    val mappings: List<ExerciseMapping>,
    /** File name to use for the USB fallback, for example "upper-a-2026-09-17.fit". */
    val suggestedFileName: String
)

/**
 * Encodes a [WatchWorkoutPlan] as a Garmin workout FIT file (messages file_id, workout,
 * workout_step, exercise_title). Implemented in Milestone 29; this entry point is the contract.
 */
object WorkoutEncoder {
    fun encode(plan: WatchWorkoutPlan): EncodedWorkout = WorkoutFitWriter.encode(plan)

    /** The catalogue mapping alone, for the preview sheet before encoding. */
    fun mappings(plan: WatchWorkoutPlan): List<ExerciseMapping> = plan.exercises.map { GarminExerciseCatalog.map(it.appExerciseId, it.name) }
}

/** Placeholder until Milestone 29 lands. */
internal object WorkoutFitWriter {
    fun encode(plan: WatchWorkoutPlan): EncodedWorkout = throw UnsupportedOperationException("Workout encoder not implemented yet (Milestone 29)")
}

/** Placeholder until Milestone 29 lands. */
internal object GarminExerciseCatalog {
    fun map(appExerciseId: Long, name: String): ExerciseMapping =
        ExerciseMapping(appExerciseId, name, ExerciseMapping.CUSTOM_CATEGORY, appExerciseId.toInt(), null)
}
