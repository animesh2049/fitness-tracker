package com.animesh.workouttracker.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "exercises", indices = [Index("name", unique = true)])
data class Exercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: ExerciseType,
    /** Comma separated muscle tags from [Muscles.ALL]. */
    val muscles: String = "",
    /** Null means use the global default from [Settings]. */
    val defaultRestSeconds: Int? = null,
    val weightIncrementKg: Double = 2.5,
    val progressionRule: ProgressionRule = defaultRuleFor(type),
    val repRangeMin: Int = 8,
    val repRangeMax: Int = 12,
    val timeStepSeconds: Int = 10,
    val timeMaxSeconds: Int? = null,
    val repStep: Int = 1,
    val notes: String = "",
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    val muscleList: List<String> get() = muscles.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        fun defaultRuleFor(type: ExerciseType): ProgressionRule = when (type) {
            ExerciseType.WEIGHT -> ProgressionRule.LINEAR_WEIGHT
            ExerciseType.BODYWEIGHT -> ProgressionRule.LINEAR_REPS
            ExerciseType.TIMED -> ProgressionRule.LINEAR_TIME
        }
    }
}

@Entity(tableName = "workout_groups")
data class WorkoutGroup(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val notes: String = "",
    val isTemplate: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "group_exercises",
    foreignKeys = [
        ForeignKey(entity = WorkoutGroup::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Exercise::class, parentColumns = ["id"], childColumns = ["exerciseId"], onDelete = ForeignKey.RESTRICT)
    ],
    indices = [Index("groupId"), Index("exerciseId")]
)
data class GroupExercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long,
    val exerciseId: Long,
    val position: Int,
    val supersetWithNext: Boolean = false
)

@Entity(
    tableName = "set_prescriptions",
    foreignKeys = [
        ForeignKey(entity = GroupExercise::class, parentColumns = ["id"], childColumns = ["groupExerciseId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("groupExerciseId")]
)
data class SetPrescription(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupExerciseId: Long,
    val position: Int,
    val targetReps: Int? = null,
    val targetWeightKg: Double? = null,
    val targetSeconds: Int? = null,
    val isWarmup: Boolean = false,
    val restSecondsOverride: Int? = null
)

@Entity(tableName = "routines")
data class Routine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isActive: Boolean = false,
    val isTemplate: Boolean = false,
    /** Index into the routine's slots that is "today". */
    val position: Int = 0,
    /** Epoch day the position last changed; used to auto-advance rest slots. */
    val lastAdvancedEpochDay: Long? = null,
    /** A group swapped in for today only (epoch day it applies to). */
    val overrideGroupId: Long? = null,
    val overrideEpochDay: Long? = null,
    /** Epoch day on which "Rest today" was logged without advancing. */
    val restLoggedEpochDay: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "routine_slots",
    foreignKeys = [
        ForeignKey(entity = Routine::class, parentColumns = ["id"], childColumns = ["routineId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = WorkoutGroup::class, parentColumns = ["id"], childColumns = ["groupId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index("routineId"), Index("groupId")]
)
data class RoutineSlot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routineId: Long,
    val position: Int,
    /** Null means a rest slot. */
    val groupId: Long?
)

@Entity(tableName = "sessions", indices = [Index("epochDay"), Index("status")])
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: Long?,
    /** Snapshot in case the group is renamed or deleted later. */
    val groupName: String,
    val routineId: Long?,
    val epochDay: Long,
    val startedAt: Long,
    val endedAt: Long? = null,
    val status: SessionStatus = SessionStatus.IN_PROGRESS,
    val notes: String = ""
)

@Entity(
    tableName = "session_exercises",
    foreignKeys = [
        ForeignKey(entity = Session::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("sessionId"), Index("exerciseId")]
)
data class SessionExercise(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val exerciseId: Long,
    val exerciseName: String,
    val exerciseType: ExerciseType,
    /** The prescription row this came from, so accepted suggestions can be written back. Null for ad hoc exercises. */
    val groupExerciseId: Long? = null,
    val position: Int,
    val supersetWithNext: Boolean = false,
    val restSeconds: Int,
    val notes: String = "",
    val skipped: Boolean = false
)

@Entity(
    tableName = "session_sets",
    foreignKeys = [
        ForeignKey(entity = SessionExercise::class, parentColumns = ["id"], childColumns = ["sessionExerciseId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("sessionExerciseId")]
)
data class SessionSet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionExerciseId: Long,
    val position: Int,
    val targetReps: Int? = null,
    val targetWeightKg: Double? = null,
    val targetSeconds: Int? = null,
    val actualReps: Int? = null,
    val actualWeightKg: Double? = null,
    val actualSeconds: Int? = null,
    val isWarmup: Boolean = false,
    val completed: Boolean = false,
    val rpe: Int? = null,
    val completedAt: Long? = null
) {
    /** Did this set reach its target? Incomplete sets never do. */
    val hitTarget: Boolean
        get() = completed && when {
            targetSeconds != null -> (actualSeconds ?: 0) >= targetSeconds
            targetReps != null -> (actualReps ?: 0) >= targetReps &&
                (targetWeightKg == null || (actualWeightKg ?: 0.0) >= targetWeightKg - 1e-9)
            else -> true
        }
}

@Entity(tableName = "day_logs", indices = [Index("epochDay")])
data class DayLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val kind: DayLogKind,
    val groupName: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "settings")
data class Settings(
    @PrimaryKey val id: Int = 1,
    val unit: WeightUnit = WeightUnit.KG,
    val defaultRestSeconds: Int = 90,
    val defaultExerciseRestSeconds: Int = 120,
    val countdownSeconds: Int = 5,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val keepScreenAwake: Boolean = true,
    val autoStartRest: Boolean = true,
    val deloadAfterFailures: Int = 3,
    val deloadPercent: Int = 10,
    @ColumnInfo(defaultValue = "0") val seeded: Boolean = false
)
