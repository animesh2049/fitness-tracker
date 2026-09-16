package com.animesh.fitnesstracker.data

import androidx.room.withTransaction
import com.animesh.fitnesstracker.data.model.Exercise
import com.animesh.fitnesstracker.data.model.ExerciseType
import com.animesh.fitnesstracker.data.model.GroupExercise
import com.animesh.fitnesstracker.data.model.ProgressionRule
import com.animesh.fitnesstracker.data.model.Routine
import com.animesh.fitnesstracker.data.model.RoutineSlot
import com.animesh.fitnesstracker.data.model.SetPrescription
import com.animesh.fitnesstracker.data.model.Settings
import com.animesh.fitnesstracker.data.model.WorkoutGroup

/**
 * First-launch content: a starter exercise library, default settings, and a
 * sample Push / Pull / Legs / Rest routine the user can edit or delete.
 */
object Seed {
    private fun w(name: String, muscles: String, inc: Double = 2.5, rest: Int? = null, rule: ProgressionRule = ProgressionRule.LINEAR_WEIGHT) =
        Exercise(name = name, type = ExerciseType.WEIGHT, muscles = muscles, weightIncrementKg = inc, defaultRestSeconds = rest, progressionRule = rule)

    private fun b(name: String, muscles: String) =
        Exercise(name = name, type = ExerciseType.BODYWEIGHT, muscles = muscles, progressionRule = ProgressionRule.LINEAR_REPS)

    private fun t(name: String, muscles: String, step: Int = 10, max: Int? = null) =
        Exercise(name = name, type = ExerciseType.TIMED, muscles = muscles, timeStepSeconds = step, timeMaxSeconds = max, defaultRestSeconds = 45)

    val exercises: List<Exercise> = listOf(
        w("Bench press", "chest,triceps,shoulders", rest = 120),
        w("Incline dumbbell press", "chest,shoulders"),
        w("Overhead press", "shoulders,triceps", rest = 120),
        w("Lateral raise", "shoulders", inc = 1.0),
        w("Triceps pushdown", "triceps"),
        w("Skull crusher", "triceps"),
        w("Chest fly", "chest"),
        w("Dip", "chest,triceps"),
        w("Deadlift", "back,legs,glutes", inc = 5.0, rest = 180),
        w("Barbell row", "back,biceps", rest = 120),
        w("Lat pulldown", "back,biceps"),
        w("Seated cable row", "back,biceps"),
        w("Face pull", "shoulders,back", inc = 1.0),
        w("Bicep curl", "biceps", inc = 1.0),
        w("Hammer curl", "biceps", inc = 1.0),
        w("Back squat", "legs,glutes", rest = 180),
        w("Front squat", "legs,core", rest = 150),
        w("Romanian deadlift", "legs,glutes,back", rest = 120),
        w("Leg press", "legs,glutes", inc = 5.0),
        w("Leg curl", "legs"),
        w("Leg extension", "legs"),
        w("Bulgarian split squat", "legs,glutes"),
        w("Hip thrust", "glutes", inc = 5.0),
        w("Calf raise", "legs", inc = 2.5),
        w("Cable crunch", "core"),
        b("Pull-up", "back,biceps"),
        b("Chin-up", "back,biceps"),
        b("Push-up", "chest,triceps"),
        b("Inverted row", "back"),
        b("Hanging leg raise", "core"),
        b("Lunge", "legs,glutes"),
        t("Plank", "core", step = 10, max = 180),
        t("Side plank", "core", step = 10, max = 120),
        t("Dead hang", "back,mobility", step = 10, max = 120),
        t("Wall sit", "legs", step = 10, max = 180),
        t("Hamstring stretch", "mobility", step = 15, max = 120),
        t("Couch stretch", "mobility", step = 15, max = 120),
        t("Hip flexor stretch", "mobility", step = 15, max = 120),
        t("Child's pose", "mobility", step = 15, max = 120)
    )

    suspend fun runIfNeeded(db: AppDatabase) {
        val settings = db.settingsDao().get()
        if (settings?.seeded == true) return
        db.withTransaction {
            if (db.exerciseDao().count() == 0) {
                db.exerciseDao().insertAll(exercises)
            }
            if (db.groupDao().count() == 0) {
                seedSampleRoutine(db)
            }
            db.settingsDao().upsert((settings ?: Settings()).copy(seeded = true))
        }
    }

    private suspend fun seedSampleRoutine(db: AppDatabase) {
        val byName = db.exerciseDao().getAll().associateBy { it.name }
        fun id(name: String) = byName.getValue(name).id

        suspend fun group(name: String, sortOrder: Int, entries: List<Triple<String, Boolean, List<SetPrescription>>>): Long {
            val gid = db.groupDao().insertGroup(WorkoutGroup(name = name, sortOrder = sortOrder))
            entries.forEachIndexed { index, (exName, superset, sets) ->
                val geId = db.groupDao().insertGroupExercise(
                    GroupExercise(groupId = gid, exerciseId = id(exName), position = index, supersetWithNext = superset)
                )
                db.groupDao().insertPrescriptions(sets.mapIndexed { i, s -> s.copy(groupExerciseId = geId, position = i) })
            }
            return gid
        }

        fun reps(n: Int, reps: Int, kg: Double) = List(n) { SetPrescription(groupExerciseId = 0, position = it, targetReps = reps, targetWeightKg = kg) }
        fun body(n: Int, reps: Int) = List(n) { SetPrescription(groupExerciseId = 0, position = it, targetReps = reps) }
        fun secs(n: Int, s: Int) = List(n) { SetPrescription(groupExerciseId = 0, position = it, targetSeconds = s) }

        val push = group(
            "Push day", 0, listOf(
                Triple("Bench press", false, listOf(SetPrescription(groupExerciseId = 0, position = 0, targetReps = 8, targetWeightKg = 40.0, isWarmup = true)) + reps(3, 8, 60.0)),
                Triple("Overhead press", false, reps(3, 10, 30.0)),
                Triple("Incline dumbbell press", true, reps(3, 10, 22.5)),
                Triple("Triceps pushdown", false, reps(3, 12, 25.0)),
                Triple("Plank", false, secs(3, 45))
            )
        )
        val pull = group(
            "Pull day", 1, listOf(
                Triple("Deadlift", false, reps(3, 5, 100.0)),
                Triple("Pull-up", false, body(3, 8)),
                Triple("Barbell row", false, reps(3, 10, 50.0)),
                Triple("Face pull", false, reps(3, 15, 15.0)),
                Triple("Hamstring stretch", false, secs(2, 60))
            )
        )
        val legs = group(
            "Legs day", 2, listOf(
                Triple("Back squat", false, reps(3, 5, 80.0)),
                Triple("Romanian deadlift", false, reps(3, 8, 70.0)),
                Triple("Leg press", false, reps(3, 12, 120.0)),
                Triple("Calf raise", false, reps(3, 15, 40.0)),
                Triple("Couch stretch", false, secs(2, 45))
            )
        )
        val routineId = db.routineDao().insertRoutine(Routine(name = "Push Pull Legs", isActive = true))
        db.routineDao().insertSlots(
            listOf(
                RoutineSlot(routineId = routineId, position = 0, groupId = push),
                RoutineSlot(routineId = routineId, position = 1, groupId = pull),
                RoutineSlot(routineId = routineId, position = 2, groupId = legs),
                RoutineSlot(routineId = routineId, position = 3, groupId = null)
            )
        )
    }
}
