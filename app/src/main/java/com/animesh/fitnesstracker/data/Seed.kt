package com.animesh.fitnesstracker.data

import androidx.room.withTransaction
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

    /**
     * Seeds the workout content on first launch, then the diet content. The diet part has its
     * own flag so an installation upgraded from version 1 (workout already seeded) still gets it.
     */
    suspend fun runIfNeeded(db: AppDatabase) {
        val settings = db.settingsDao().get()
        if (settings?.seeded != true) {
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
        runDietIfNeeded(db)
    }

    /** Seeds the starter meals and the "Cutting week" plan once, guarded by [DietSettings.seeded]. */
    suspend fun runDietIfNeeded(db: AppDatabase) {
        val dietSettings = db.dietSettingsDao().get()
        if (dietSettings?.seeded == true) return
        db.withTransaction {
            if (db.mealDao().count() == 0) {
                seedDiet(db)
            }
            db.dietSettingsDao().upsert((dietSettings ?: DietSettings()).copy(seeded = true))
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

    /** One starter meal: the recipe row plus its per-serving ingredients and procedure. */
    class SeedMeal(val meal: Meal, val ingredients: List<Ingredient>, val steps: List<String>)

    private fun ing(name: String, amount: Double, unit: String) = Ingredient(mealId = 0, position = 0, name = name, amount = amount, unit = unit)

    private fun meal(
        name: String, slot: MealSlot, kcal: Double, protein: Double, carbs: Double, fat: Double, cook: Int,
        prep: String? = null, ingredients: List<Ingredient>, steps: List<String>
    ) = SeedMeal(
        Meal(
            name = name, slots = Meal.slotsString(listOf(slot)), cookMinutes = cook,
            kcal = kcal, proteinG = protein, carbsG = carbs, fatG = fat,
            prepDayBefore = prep != null, prepInstruction = prep ?: ""
        ),
        ingredients, steps
    )

    /** Seven lacto-vegetarian meals, quantities per serving. */
    val meals: List<SeedMeal> = listOf(
        meal(
            "Moong dal chilla with curd", MealSlot.BREAKFAST, 450.0, 35.0, 45.0, 12.0, cook = 25,
            prep = "Soak 1 cup moong dal in water overnight, at least 6 hours.",
            ingredients = listOf(
                ing("Moong dal (soaked)", 1.0, "cup"), ing("Curd", 1.0, "katori"), ing("Onion, finely chopped", 0.5, "piece"),
                ing("Green chilli", 1.0, "piece"), ing("Ginger, grated", 1.0, "tsp"), ing("Coriander leaves", 2.0, "tbsp"),
                ing("Salt", 0.5, "tsp"), ing("Oil", 2.0, "tsp")
            ),
            steps = listOf(
                "Drain the soaked dal and blend with ginger, chilli and a little water to a thick pourable batter.",
                "Stir in onion, coriander and salt.",
                "Heat a tawa, brush with oil and spread a ladle of batter thin.",
                "Cook 2 to 3 minutes per side until golden. Repeat for the remaining batter.",
                "Serve hot with curd."
            )
        ),
        meal(
            "Oats with whey and peanut butter", MealSlot.BREAKFAST, 430.0, 36.0, 40.0, 14.0, cook = 10,
            ingredients = listOf(
                ing("Rolled oats", 50.0, "g"), ing("Milk", 250.0, "ml"), ing("Whey protein", 1.0, "scoop"),
                ing("Peanut butter", 1.0, "tbsp"), ing("Banana", 0.5, "piece"), ing("Cinnamon", 0.25, "tsp")
            ),
            steps = listOf(
                "Cook the oats in milk over a low flame for 5 to 6 minutes, stirring.",
                "Take off the heat and let it cool for a minute so the whey does not clump.",
                "Stir in the whey and peanut butter until smooth.",
                "Top with sliced banana and cinnamon."
            )
        ),
        meal(
            "Dal, sabzi, roti and curd", MealSlot.LUNCH, 600.0, 26.0, 85.0, 14.0, cook = 40,
            ingredients = listOf(
                ing("Toor dal", 0.5, "cup"), ing("Mixed vegetables (lauki, beans, carrot)", 150.0, "g"), ing("Whole wheat atta", 90.0, "g"),
                ing("Curd", 1.0, "katori"), ing("Onion", 1.0, "piece"), ing("Tomato", 1.0, "piece"),
                ing("Turmeric", 0.5, "tsp"), ing("Cumin seeds", 0.5, "tsp"), ing("Ghee", 1.0, "tsp"), ing("Salt", 1.0, "tsp")
            ),
            steps = listOf(
                "Pressure cook the dal with turmeric and salt for 3 whistles, then temper with ghee and cumin.",
                "Saute onion and tomato, add the vegetables and cook covered until soft.",
                "Knead the atta with water, rest 10 minutes and roll into 3 rotis. Cook on a hot tawa.",
                "Serve the dal and sabzi with the rotis and a katori of curd."
            )
        ),
        meal(
            "Rajma chawal", MealSlot.LUNCH, 560.0, 20.0, 92.0, 10.0, cook = 40,
            prep = "Soak 1/2 cup rajma in plenty of water overnight, at least 8 hours.",
            ingredients = listOf(
                ing("Rajma (soaked)", 0.5, "cup"), ing("Rice", 0.75, "cup"), ing("Onion", 1.0, "piece"), ing("Tomato", 2.0, "piece"),
                ing("Ginger garlic paste", 1.0, "tsp"), ing("Rajma masala", 1.0, "tsp"), ing("Oil", 2.0, "tsp"), ing("Salt", 1.0, "tsp")
            ),
            steps = listOf(
                "Pressure cook the soaked rajma with salt for 5 to 6 whistles until soft.",
                "Fry onion in oil until golden, add ginger garlic paste, tomato and masala and cook until the oil separates.",
                "Add the rajma with its water and simmer 15 minutes, mashing a few beans to thicken.",
                "Cook the rice separately and serve the rajma over it."
            )
        ),
        meal(
            "Chole with brown rice", MealSlot.LUNCH, 580.0, 22.0, 90.0, 12.0, cook = 45,
            prep = "Soak 1/2 cup chana overnight.",
            ingredients = listOf(
                ing("Kabuli chana (soaked)", 0.5, "cup"), ing("Brown rice", 0.75, "cup"), ing("Onion", 1.0, "piece"), ing("Tomato", 2.0, "piece"),
                ing("Chole masala", 1.5, "tsp"), ing("Tea bag", 1.0, "piece"), ing("Oil", 2.0, "tsp"), ing("Salt", 1.0, "tsp")
            ),
            steps = listOf(
                "Pressure cook the soaked chana with the tea bag and salt for 5 whistles. Discard the tea bag.",
                "Fry onion until brown, add tomato and chole masala and cook to a thick masala.",
                "Add the chana and simmer 15 minutes.",
                "Cook the brown rice for 30 minutes and serve with the chole."
            )
        ),
        meal(
            "Paneer bhurji with roti", MealSlot.DINNER, 550.0, 33.0, 45.0, 22.0, cook = 20,
            ingredients = listOf(
                ing("Paneer, crumbled", 150.0, "g"), ing("Whole wheat atta", 60.0, "g"), ing("Onion", 1.0, "piece"),
                ing("Tomato", 1.0, "piece"), ing("Capsicum", 0.5, "piece"), ing("Green chilli", 1.0, "piece"),
                ing("Turmeric", 0.25, "tsp"), ing("Garam masala", 0.5, "tsp"), ing("Oil", 1.0, "tsp"), ing("Salt", 0.5, "tsp")
            ),
            steps = listOf(
                "Saute onion, chilli and capsicum in oil until soft, then add tomato and the spices.",
                "Add the crumbled paneer and cook 3 to 4 minutes, stirring gently.",
                "Roll 2 rotis from the atta and cook on a tawa.",
                "Serve the bhurji with the rotis."
            )
        ),
        meal(
            "Soya chunk curry with roti", MealSlot.DINNER, 520.0, 38.0, 50.0, 14.0, cook = 30,
            ingredients = listOf(
                ing("Soya chunks", 60.0, "g"), ing("Whole wheat atta", 60.0, "g"), ing("Onion", 1.0, "piece"), ing("Tomato", 1.0, "piece"),
                ing("Curd", 2.0, "tbsp"), ing("Ginger garlic paste", 1.0, "tsp"), ing("Coriander powder", 1.0, "tsp"),
                ing("Red chilli powder", 0.5, "tsp"), ing("Oil", 2.0, "tsp"), ing("Salt", 1.0, "tsp")
            ),
            steps = listOf(
                "Boil the soya chunks in salted water for 5 minutes, drain and squeeze out the water.",
                "Fry onion, add ginger garlic paste, tomato, curd and the spices and cook until the oil separates.",
                "Add the chunks and a cup of water and simmer 10 minutes.",
                "Roll 2 rotis from the atta and cook on a tawa. Serve with the curry."
            )
        )
    )

    private suspend fun seedDiet(db: AppDatabase) {
        val mealDao = db.mealDao()
        val ids = HashMap<String, Long>()
        meals.forEach { m ->
            ids[m.meal.name] = mealDao.upsertWithDetails(
                m.meal, m.ingredients, m.steps.map { MealStep(mealId = 0, position = 0, text = it) }
            )
        }
        val oats = ids.getValue("Oats with whey and peanut butter")
        val chilla = ids.getValue("Moong dal chilla with curd")
        val dal = ids.getValue("Dal, sabzi, roti and curd")
        val rajma = ids.getValue("Rajma chawal")
        val chole = ids.getValue("Chole with brown rice")
        val paneer = ids.getValue("Paneer bhurji with roti")
        val soya = ids.getValue("Soya chunk curry with roti")
        // Monday through Sunday: breakfast, lunch, dinner. Null is an empty cell (no row).
        val week: List<List<Long?>> = listOf(
            listOf(oats, dal, soya),
            listOf(chilla, dal, paneer),
            listOf(chilla, rajma, paneer),
            listOf(oats, dal, soya),
            listOf(chilla, chole, paneer),
            listOf(oats, dal, soya),
            listOf(chilla, null, paneer)
        )
        val planId = db.dietPlanDao().insertPlan(DietPlan(name = "Cutting week", isActive = true))
        db.dietPlanDao().insertCells(
            week.flatMapIndexed { day, slots ->
                slots.mapIndexedNotNull { i, mealId ->
                    mealId?.let { DietPlanCell(planId = planId, dayOfWeek = day, slot = MealSlot.entries[i], mealId = it) }
                }
            }
        )
    }
}
