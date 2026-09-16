package com.animesh.fitnesstracker.backup

import androidx.room.withTransaction
import com.animesh.fitnesstracker.data.AppDatabase
import com.animesh.fitnesstracker.data.model.DayLog
import com.animesh.fitnesstracker.data.model.DietPlan
import com.animesh.fitnesstracker.data.model.DietPlanCell
import com.animesh.fitnesstracker.data.model.DietSettings
import com.animesh.fitnesstracker.data.model.Exercise
import com.animesh.fitnesstracker.data.model.GroupExercise
import com.animesh.fitnesstracker.data.model.Ingredient
import com.animesh.fitnesstracker.data.model.Meal
import com.animesh.fitnesstracker.data.model.MealStep
import com.animesh.fitnesstracker.data.model.Routine
import com.animesh.fitnesstracker.data.model.RoutineSlot
import com.animesh.fitnesstracker.data.model.Session
import com.animesh.fitnesstracker.data.model.SessionExercise
import com.animesh.fitnesstracker.data.model.SessionSet
import com.animesh.fitnesstracker.data.model.SetPrescription
import com.animesh.fitnesstracker.data.model.Settings
import com.animesh.fitnesstracker.data.model.WorkoutGroup
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Everything in the database, in one file. Ids are kept so relations survive a round trip. */
@Serializable
data class BackupFile(
    val schemaVersion: Int = BackupCodec.SCHEMA_VERSION,
    val exportedAt: Long,
    val appVersion: String,
    val exercises: List<Exercise> = emptyList(),
    val groups: List<WorkoutGroup> = emptyList(),
    val groupExercises: List<GroupExercise> = emptyList(),
    val setPrescriptions: List<SetPrescription> = emptyList(),
    val routines: List<Routine> = emptyList(),
    val routineSlots: List<RoutineSlot> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val sessionExercises: List<SessionExercise> = emptyList(),
    val sessionSets: List<SessionSet> = emptyList(),
    val dayLogs: List<DayLog> = emptyList(),
    val settings: Settings? = null,
    // Schema version 2 (diet planner). Defaults keep version 1 files decodable.
    val meals: List<Meal> = emptyList(),
    val ingredients: List<Ingredient> = emptyList(),
    val mealSteps: List<MealStep> = emptyList(),
    val dietPlans: List<DietPlan> = emptyList(),
    val dietPlanCells: List<DietPlanCell> = emptyList(),
    val dietSettings: DietSettings? = null
) {
    /** Total number of rows across all tables (each settings row counts as one). */
    val rowCount: Int
        get() = exercises.size + groups.size + groupExercises.size + setPrescriptions.size +
            routines.size + routineSlots.size + sessions.size + sessionExercises.size +
            sessionSets.size + dayLogs.size + (if (settings != null) 1 else 0) +
            meals.size + ingredients.size + mealSteps.size + dietPlans.size + dietPlanCells.size +
            (if (dietSettings != null) 1 else 0)
}

enum class ImportMode { REPLACE, MERGE }

/** Rows written per table, keyed by table name. */
data class ImportResult(val mode: ImportMode, val counts: Map<String, Int>) {
    val total: Int get() = counts.values.sum()
}

class BackupFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

object BackupCodec {
    const val SCHEMA_VERSION = 2

    val json: Json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    fun encode(file: BackupFile): String = json.encodeToString(BackupFile.serializer(), file)

    /** Parses a backup, turning any malformed input into a [BackupFormatException]. */
    fun decode(text: String): BackupFile {
        val file = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: SerializationException) {
            throw BackupFormatException("Not a workout backup file: ${e.message?.lineSequence()?.firstOrNull() ?: "invalid JSON"}", e)
        } catch (e: IllegalArgumentException) {
            throw BackupFormatException("Not a workout backup file: ${e.message ?: "invalid content"}", e)
        }
        if (file.schemaVersion > SCHEMA_VERSION) {
            throw BackupFormatException("Backup schema ${file.schemaVersion} is newer than this app understands ($SCHEMA_VERSION)")
        }
        return file
    }

    suspend fun snapshot(db: AppDatabase, appVersion: String): BackupFile = BackupFile(
        schemaVersion = SCHEMA_VERSION,
        exportedAt = System.currentTimeMillis(),
        appVersion = appVersion,
        exercises = db.exerciseDao().getAll(),
        groups = db.groupDao().getAllGroups(),
        groupExercises = db.groupDao().getAllGroupExercises(),
        setPrescriptions = db.groupDao().getAllPrescriptions(),
        routines = db.routineDao().getAllRoutines(),
        routineSlots = db.routineDao().getAllSlots(),
        sessions = db.sessionDao().getAllSessions(),
        sessionExercises = db.sessionDao().getAllSessionExercises(),
        sessionSets = db.sessionDao().getAllSessionSets(),
        dayLogs = db.sessionDao().getAllDayLogs(),
        settings = db.settingsDao().get(),
        meals = db.mealDao().getAllMeals(),
        ingredients = db.mealDao().getAllIngredients(),
        mealSteps = db.mealDao().getAllSteps(),
        dietPlans = db.dietPlanDao().getAllPlans(),
        dietPlanCells = db.dietPlanDao().getAllCells(),
        dietSettings = db.dietSettingsDao().get()
    )

    /** Pretty JSON of the whole database. */
    suspend fun export(db: AppDatabase, appVersion: String): String = encode(snapshot(db, appVersion))

    suspend fun import(db: AppDatabase, json: String, mode: ImportMode): ImportResult {
        val file = decode(json)
        return when (mode) {
            ImportMode.REPLACE -> replace(db, file)
            ImportMode.MERGE -> merge(db, file)
        }
    }

    /** Deletes every row (child tables first) and inserts the backup with its original ids. */
    private suspend fun replace(db: AppDatabase, file: BackupFile): ImportResult = db.withTransaction {
        val sessionDao = db.sessionDao()
        val routineDao = db.routineDao()
        val groupDao = db.groupDao()
        val exerciseDao = db.exerciseDao()
        val mealDao = db.mealDao()
        val planDao = db.dietPlanDao()

        // A version 1 backup predates the diet planner and says nothing about it, so the diet
        // tables are left untouched when restoring one; only version 2 files replace them.
        val replacesDiet = file.schemaVersion >= 2
        if (replacesDiet) {
            planDao.deleteAllCells()
            planDao.deleteAllPlans()
            mealDao.deleteAllSteps()
            mealDao.deleteAllIngredients()
            mealDao.deleteAllMeals()
        }
        sessionDao.deleteAllSets()
        sessionDao.deleteAllSessionExercises()
        sessionDao.deleteAllSessions()
        sessionDao.deleteAllDayLogs()
        routineDao.deleteAllSlots()
        routineDao.deleteAllRoutines()
        groupDao.deleteAllPrescriptions()
        groupDao.deleteAllGroupExercises()
        groupDao.deleteAllGroups()
        exerciseDao.deleteAll()

        val counts = linkedMapOf<String, Int>()
        counts["exercises"] = exerciseDao.insertAllReplace(file.exercises).size
        counts["groups"] = groupDao.insertGroupsReplace(file.groups).size
        counts["groupExercises"] = groupDao.insertGroupExercisesReplace(file.groupExercises).size
        counts["setPrescriptions"] = groupDao.insertPrescriptionsReplace(file.setPrescriptions).size
        counts["routines"] = routineDao.insertRoutinesReplace(file.routines).size
        counts["routineSlots"] = routineDao.insertSlotsReplace(file.routineSlots).size
        counts["sessions"] = sessionDao.insertSessionsReplace(file.sessions).size
        counts["sessionExercises"] = sessionDao.insertSessionExercisesReplace(file.sessionExercises).size
        counts["sessionSets"] = sessionDao.insertSetsReplace(file.sessionSets).size
        counts["dayLogs"] = sessionDao.insertDayLogsReplace(file.dayLogs).size
        val settings = file.settings
        if (settings != null) {
            db.settingsDao().upsert(settings.copy(id = 1, seeded = true))
            counts["settings"] = 1
        } else {
            counts["settings"] = 0
        }
        counts["meals"] = if (replacesDiet) mealDao.insertMealsReplace(file.meals).size else 0
        counts["ingredients"] = if (replacesDiet) mealDao.insertIngredientsReplace(file.ingredients).size else 0
        counts["mealSteps"] = if (replacesDiet) mealDao.insertStepsReplace(file.mealSteps).size else 0
        counts["dietPlans"] = if (replacesDiet) planDao.insertPlansReplace(file.dietPlans).size else 0
        counts["dietPlanCells"] = if (replacesDiet) planDao.insertCellsReplace(file.dietPlanCells).size else 0
        val dietSettings = file.dietSettings
        if (replacesDiet && dietSettings != null) {
            db.dietSettingsDao().upsert(dietSettings.copy(id = 1, seeded = true))
            counts["dietSettings"] = 1
        } else {
            counts["dietSettings"] = 0
        }
        ImportResult(ImportMode.REPLACE, counts)
    }

    /**
     * Inserts rows whose ids are not taken yet and never deletes. Exercises are matched by name
     * as well (names are unique), and references to a matched exercise are redirected to the
     * existing row so foreign keys keep holding.
     */
    private suspend fun merge(db: AppDatabase, file: BackupFile): ImportResult = db.withTransaction {
        val sessionDao = db.sessionDao()
        val routineDao = db.routineDao()
        val groupDao = db.groupDao()
        val exerciseDao = db.exerciseDao()
        val counts = linkedMapOf<String, Int>()

        val existing = exerciseDao.getAll()
        val byId = existing.associateBy { it.id }
        val byName = existing.associateBy { it.name.trim().lowercase() }
        val exerciseIdMap = HashMap<Long, Long>()
        val toInsert = ArrayList<Exercise>()
        for (e in file.exercises) {
            val sameName = byName[e.name.trim().lowercase()]
            when {
                byId.containsKey(e.id) -> exerciseIdMap[e.id] = e.id
                sameName != null -> exerciseIdMap[e.id] = sameName.id
                else -> { exerciseIdMap[e.id] = e.id; toInsert += e }
            }
        }
        counts["exercises"] = exerciseDao.insertAll(toInsert).count { it != -1L }
        fun mapExercise(id: Long): Long = exerciseIdMap[id] ?: id

        counts["groups"] = groupDao.insertGroupsIgnore(file.groups).count { it != -1L }
        counts["groupExercises"] = groupDao.insertGroupExercisesIgnore(
            file.groupExercises.map { it.copy(exerciseId = mapExercise(it.exerciseId)) }
        ).count { it != -1L }
        counts["setPrescriptions"] = groupDao.insertPrescriptionsIgnore(file.setPrescriptions).count { it != -1L }
        counts["routines"] = routineDao.insertRoutinesIgnore(file.routines).count { it != -1L }
        counts["routineSlots"] = routineDao.insertSlotsIgnore(file.routineSlots).count { it != -1L }
        counts["sessions"] = sessionDao.insertSessionsIgnore(file.sessions).count { it != -1L }
        counts["sessionExercises"] = sessionDao.insertSessionExercisesIgnore(
            file.sessionExercises.map { it.copy(exerciseId = mapExercise(it.exerciseId)) }
        ).count { it != -1L }
        counts["sessionSets"] = sessionDao.insertSetsIgnore(file.sessionSets).count { it != -1L }
        counts["dayLogs"] = sessionDao.insertDayLogsIgnore(file.dayLogs).count { it != -1L }
        val settings = file.settings
        counts["settings"] = if (settings != null && db.settingsDao().insertIgnore(settings.copy(id = 1, seeded = true)) != -1L) 1 else 0

        // Meals follow the exercise rule: match by id, then by name; redirect references to the match.
        // A matched meal keeps the recipe it already has, so its ingredients and steps in the file are skipped.
        val mealDao = db.mealDao()
        val planDao = db.dietPlanDao()
        val existingMeals = mealDao.getAllMeals()
        val mealById = existingMeals.associateBy { it.id }
        val mealByName = existingMeals.associateBy { it.name.trim().lowercase() }
        val mealIdMap = HashMap<Long, Long>()
        val mealsToInsert = ArrayList<Meal>()
        for (m in file.meals) {
            val sameName = mealByName[m.name.trim().lowercase()]
            when {
                mealById.containsKey(m.id) -> mealIdMap[m.id] = m.id
                sameName != null -> mealIdMap[m.id] = sameName.id
                else -> { mealIdMap[m.id] = m.id; mealsToInsert += m }
            }
        }
        val insertedMealIds = mealDao.insertMealsIgnore(mealsToInsert).filter { it != -1L }.toSet()
        counts["meals"] = insertedMealIds.size
        fun mapMeal(id: Long): Long = mealIdMap[id] ?: id
        counts["ingredients"] = mealDao.insertIngredientsIgnore(
            file.ingredients.filter { mapMeal(it.mealId) in insertedMealIds }.map { it.copy(mealId = mapMeal(it.mealId)) }
        ).count { it != -1L }
        counts["mealSteps"] = mealDao.insertStepsIgnore(
            file.mealSteps.filter { mapMeal(it.mealId) in insertedMealIds }.map { it.copy(mealId = mapMeal(it.mealId)) }
        ).count { it != -1L }
        // Keep a single active plan: when one is active already, imported plans come in inactive.
        val hasActivePlan = planDao.getActive() != null
        counts["dietPlans"] = planDao.insertPlansIgnore(
            if (hasActivePlan) file.dietPlans.map { it.copy(isActive = false) } else file.dietPlans
        ).count { it != -1L }
        counts["dietPlanCells"] = planDao.insertCellsIgnore(
            file.dietPlanCells.map { c -> c.copy(mealId = c.mealId?.let(::mapMeal)) }
        ).count { it != -1L }
        val dietSettings = file.dietSettings
        counts["dietSettings"] = if (dietSettings != null && db.dietSettingsDao().insertIgnore(dietSettings.copy(id = 1, seeded = true)) != -1L) 1 else 0
        ImportResult(ImportMode.MERGE, counts)
    }
}
