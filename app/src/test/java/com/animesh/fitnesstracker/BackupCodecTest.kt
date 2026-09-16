package com.animesh.fitnesstracker

import com.animesh.fitnesstracker.backup.BackupCodec
import com.animesh.fitnesstracker.backup.BackupFile
import com.animesh.fitnesstracker.backup.BackupFormatException
import com.animesh.fitnesstracker.backup.CsvExport
import com.animesh.fitnesstracker.data.model.DayLog
import com.animesh.fitnesstracker.data.model.DayLogKind
import com.animesh.fitnesstracker.data.model.DietPlan
import com.animesh.fitnesstracker.data.model.DietPlanCell
import com.animesh.fitnesstracker.data.model.DietSettings
import com.animesh.fitnesstracker.data.model.Exercise
import com.animesh.fitnesstracker.data.model.ExerciseType
import com.animesh.fitnesstracker.data.model.GroupExercise
import com.animesh.fitnesstracker.data.model.Ingredient
import com.animesh.fitnesstracker.data.model.Meal
import com.animesh.fitnesstracker.data.model.MealSlot
import com.animesh.fitnesstracker.data.model.MealStep
import com.animesh.fitnesstracker.data.model.Routine
import com.animesh.fitnesstracker.data.model.RoutineSlot
import com.animesh.fitnesstracker.data.model.Session
import com.animesh.fitnesstracker.data.model.SessionExercise
import com.animesh.fitnesstracker.data.model.SessionExerciseWithSets
import com.animesh.fitnesstracker.data.model.SessionSet
import com.animesh.fitnesstracker.data.model.SessionStatus
import com.animesh.fitnesstracker.data.model.SessionWithExercises
import com.animesh.fitnesstracker.data.model.SetPrescription
import com.animesh.fitnesstracker.data.model.Settings
import com.animesh.fitnesstracker.data.model.WeightUnit
import com.animesh.fitnesstracker.data.model.WorkoutGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCodecTest {
    private val bench = Exercise(id = 1, name = "Bench press", type = ExerciseType.WEIGHT, muscles = "chest,triceps", createdAt = 1_000)
    private val plank = Exercise(id = 2, name = "Plank", type = ExerciseType.TIMED, timeStepSeconds = 5, createdAt = 1_000)

    private fun sample() = BackupFile(
        exportedAt = 1_757_800_000_000,
        appVersion = "0.1.0",
        exercises = listOf(bench, plank),
        groups = listOf(WorkoutGroup(id = 1, name = "Push, day \"A\"", createdAt = 1_000)),
        groupExercises = listOf(GroupExercise(id = 1, groupId = 1, exerciseId = 1, position = 0)),
        setPrescriptions = listOf(SetPrescription(id = 1, groupExerciseId = 1, position = 0, targetReps = 5, targetWeightKg = 80.0)),
        routines = listOf(Routine(id = 1, name = "PPL", isActive = true, lastAdvancedEpochDay = 20_700, createdAt = 1_000)),
        routineSlots = listOf(RoutineSlot(id = 1, routineId = 1, position = 0, groupId = 1), RoutineSlot(id = 2, routineId = 1, position = 1, groupId = null)),
        sessions = listOf(
            Session(
                id = 1, groupId = 1, groupName = "Push, day \"A\"", routineId = 1, epochDay = 20_710,
                startedAt = 5_000, endedAt = 9_000, status = SessionStatus.COMPLETED, notes = "felt good"
            )
        ),
        sessionExercises = listOf(
            SessionExercise(
                id = 1, sessionId = 1, exerciseId = 1, exerciseName = "Bench press", exerciseType = ExerciseType.WEIGHT,
                groupExerciseId = 1, position = 0, restSeconds = 90
            )
        ),
        sessionSets = listOf(
            SessionSet(id = 1, sessionExerciseId = 1, position = 0, targetReps = 5, targetWeightKg = 80.0, actualReps = 5, actualWeightKg = 80.0, completed = true, rpe = 8, completedAt = 6_000),
            SessionSet(id = 2, sessionExerciseId = 1, position = 1, targetReps = 5, targetWeightKg = 80.0, actualReps = 4, actualWeightKg = 82.5, completed = true)
        ),
        dayLogs = listOf(DayLog(id = 1, epochDay = 20_711, kind = DayLogKind.REST, createdAt = 1_000)),
        settings = Settings(unit = WeightUnit.LB, defaultRestSeconds = 75, seeded = true)
    )

    private fun dietSample() = sample().copy(
        schemaVersion = 2,
        meals = listOf(
            Meal(
                id = 1, name = "Rajma chawal", slots = "lunch", cookMinutes = 40, kcal = 560.0, proteinG = 20.0, carbsG = 92.0, fatG = 10.0,
                prepDayBefore = true, prepInstruction = "Soak 1/2 cup rajma overnight.", createdAt = 1_000
            )
        ),
        ingredients = listOf(
            Ingredient(id = 1, mealId = 1, position = 0, name = "Rajma (soaked)", amount = 0.5, unit = "cup"),
            Ingredient(id = 2, mealId = 1, position = 1, name = "Rice", amount = 0.75, unit = "cup")
        ),
        mealSteps = listOf(
            MealStep(id = 1, mealId = 1, position = 0, text = "Pressure cook the rajma for 5 whistles."),
            MealStep(id = 2, mealId = 1, position = 1, text = "Simmer in the masala and serve over rice.")
        ),
        dietPlans = listOf(DietPlan(id = 1, name = "Cutting week", isActive = true, createdAt = 1_000)),
        dietPlanCells = listOf(DietPlanCell(id = 1, planId = 1, dayOfWeek = 2, slot = MealSlot.LUNCH, mealId = 1, servings = 1.5)),
        dietSettings = DietSettings(prepReminderMinute = 20 * 60, mealReminderEnabled = false, seeded = true)
    )

    @Test
    fun roundTripIsLossless() {
        val original = sample()
        val text = BackupCodec.encode(original)
        assertTrue(text.contains("\"schemaVersion\": 2"))
        val decoded = BackupCodec.decode(text)
        assertEquals(original, decoded)
        assertEquals(14, original.rowCount)
    }

    @Test
    fun versionOneFileDecodesWithEmptyDietTables() {
        val text = """{"schemaVersion": 1, "exportedAt": 1, "appVersion": "0.1.0", "exercises": [{"id": 3, "name": "Row", "type": "WEIGHT"}], "settings": {"unit": "KG"}}"""
        val file = BackupCodec.decode(text)
        assertEquals(1, file.schemaVersion)
        assertEquals(1, file.exercises.size)
        assertEquals(emptyList<Meal>(), file.meals)
        assertEquals(emptyList<Ingredient>(), file.ingredients)
        assertEquals(emptyList<MealStep>(), file.mealSteps)
        assertEquals(emptyList<DietPlan>(), file.dietPlans)
        assertEquals(emptyList<DietPlanCell>(), file.dietPlanCells)
        assertEquals(null, file.dietSettings)
        assertEquals(2, file.rowCount)
    }

    @Test
    fun versionTwoRoundTripKeepsMealsAndPlan() {
        val original = dietSample()
        val text = BackupCodec.encode(original)
        assertTrue(text.contains("\"slot\": \"LUNCH\""))
        assertTrue(text.contains("\"prepInstruction\": \"Soak 1/2 cup rajma overnight.\""))
        val decoded = BackupCodec.decode(text)
        assertEquals(original, decoded)
        assertEquals(2, decoded.ingredients.size)
        assertEquals(2, decoded.mealSteps.size)
        assertEquals(1, decoded.dietPlanCells.size)
        assertEquals(1L, decoded.dietPlanCells[0].mealId)
        assertEquals(1.5, decoded.dietPlanCells[0].servings, 0.0)
        assertEquals(1200, decoded.dietSettings!!.prepReminderMinute)
        assertEquals(14 + 1 + 2 + 2 + 1 + 1 + 1, decoded.rowCount)
    }

    @Test
    fun decodeToleratesUnknownFieldsAndMissingDefaults() {
        val text = """{"exportedAt": 1, "appVersion": "9.9", "futureField": {"x": 1}, "exercises": [{"id": 3, "name": "Row", "type": "WEIGHT", "extra": true}]}"""
        val file = BackupCodec.decode(text)
        assertEquals(1, file.exercises.size)
        assertEquals("Row", file.exercises[0].name)
        assertEquals(2.5, file.exercises[0].weightIncrementKg, 0.0)
        assertEquals(null, file.settings)
    }

    @Test
    fun decodeRejectsGarbage() {
        for (bad in listOf("", "not json", "{\"hello\": 1}", "[1,2,3]", "{\"exportedAt\": 1, \"appVersion\": \"x\", \"schemaVersion\": 99}")) {
            try {
                BackupCodec.decode(bad)
                fail("expected failure for: $bad")
            } catch (e: BackupFormatException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun csvEscapesAndConvertsUnits() {
        val file = sample()
        val session = SessionWithExercises(
            file.sessions[0],
            listOf(SessionExerciseWithSets(file.sessionExercises[0], file.sessionSets))
        )
        val csv = CsvExport.build(listOf(session), WeightUnit.KG)
        val lines = csv.trimEnd().lines()
        assertEquals(CsvExport.HEADER, lines[0])
        assertEquals(3, lines.size)
        assertEquals("2026-09-14,1,\"Push, day \"\"A\"\"\",Bench press,1,false,5,80,,5,80,,true,8", lines[1])
        assertEquals("2026-09-14,1,\"Push, day \"\"A\"\"\",Bench press,2,false,5,80,,4,82.5,,true,", lines[2])
        val lb = CsvExport.build(listOf(session), WeightUnit.LB).trimEnd().lines()
        assertEquals("2026-09-14,1,\"Push, day \"\"A\"\"\",Bench press,1,false,5,176.37,,5,176.37,,true,8", lb[1])
        assertEquals(2, CsvExport.rowCount(listOf(session)))
    }
}
