package com.animesh.fitnesstracker.garmin.workout

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One exercise of the day as the Today screen knows it, after accepted suggestions were applied to the
 * working sets. Rest is resolved by the planner: [restOverrideSeconds] (the prescription) wins over
 * [exerciseDefaultRestSeconds], which wins over the settings default.
 */
data class PlannerExercise(
    val appExerciseId: Long,
    val name: String,
    val kind: WatchExerciseKind,
    val sets: List<WatchSet>,
    val restOverrideSeconds: Int? = null,
    val exerciseDefaultRestSeconds: Int? = null,
    val supersetWithNext: Boolean = false
)

/**
 * Builds the [WatchWorkoutPlan] for "Send to watch" from the Today screen's data. Pure Kotlin so the
 * naming, serial and rest rules are unit tested without Room.
 */
object WatchWorkoutPlanner {
    const val MAX_NAME_LENGTH = 30
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
    private val SHORT_DAY = java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.ENGLISH)

    /**
     * @param groupName the workout group, for example "Push day"
     * @param groupId nonzero group id; together with [epochDay] it makes the file's serial unique per day and group
     * @param epochDay the day the workout is for
     * @param exercises ordered exercises with their sets after accepted suggestions
     * @param settingsDefaultRestSeconds the global rest default from Settings
     * @param nowMillis wall clock, stamped into the file as time_created
     */
    fun plan(
        groupName: String,
        groupId: Long,
        epochDay: Long,
        exercises: List<PlannerExercise>,
        settingsDefaultRestSeconds: Int,
        nowMillis: Long
    ): WatchWorkoutPlan {
        require(exercises.isNotEmpty()) { "A workout needs at least one exercise" }
        return WatchWorkoutPlan(
            name = name(groupName, epochDay),
            serial = serial(epochDay, groupId),
            createdAtEpochSeconds = nowMillis / 1000,
            exercises = exercises.map { e ->
                WatchExercise(
                    appExerciseId = e.appExerciseId,
                    name = e.name,
                    kind = e.kind,
                    sets = e.sets,
                    restSeconds = (e.restOverrideSeconds ?: e.exerciseDefaultRestSeconds ?: settingsDefaultRestSeconds).coerceAtLeast(0),
                    supersetWithNext = e.supersetWithNext
                )
            }
        )
    }

    /**
     * "<Group> · <EEE d MMM>" when it fits [MAX_NAME_LENGTH]; otherwise the weekday is dropped, and if the
     * group name alone is still too long it is cut so the date always survives ("Upper body hyper… · 17 Sep").
     */
    fun name(groupName: String, epochDay: Long): String {
        val date = LocalDate.ofEpochDay(epochDay)
        val group = groupName.trim()
        val full = "$group · ${date.format(DAY)}"
        if (full.length <= MAX_NAME_LENGTH) return full
        val shortDate = date.format(SHORT_DAY)
        val withShortDate = "$group · $shortDate"
        if (withShortDate.length <= MAX_NAME_LENGTH) return withShortDate
        val room = MAX_NAME_LENGTH - shortDate.length - 3
        return "${group.substring(0, room).trimEnd()} · $shortDate"
    }

    /** epochDay * 100 000 + groupId, never zero (the watch keys files by serial and creation time). */
    fun serial(epochDay: Long, groupId: Long): Long {
        val s = epochDay * 100_000L + groupId
        return if (s == 0L) 1L else s
    }
}
