package com.animesh.fitnesstracker

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.animesh.fitnesstracker.backup.BackupCodec
import com.animesh.fitnesstracker.backup.ImportMode
import com.animesh.fitnesstracker.data.AppDatabase
import com.animesh.fitnesstracker.data.Seed
import com.animesh.fitnesstracker.data.model.Ingredient
import com.animesh.fitnesstracker.data.model.Meal
import com.animesh.fitnesstracker.data.model.MealSlot
import com.animesh.fitnesstracker.data.model.MealStep
import com.animesh.fitnesstracker.data.model.SessionStatus
import com.animesh.fitnesstracker.data.model.Settings
import com.animesh.fitnesstracker.repository.DietPlanRepository
import com.animesh.fitnesstracker.repository.GroupRepository
import com.animesh.fitnesstracker.repository.MealRepository
import com.animesh.fitnesstracker.repository.PlannedExercise
import com.animesh.fitnesstracker.repository.PlannedSet
import com.animesh.fitnesstracker.repository.SessionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DaoTest {
    private lateinit var db: AppDatabase
    private lateinit var sessions: SessionRepository
    private lateinit var groups: GroupRepository
    private lateinit var meals: MealRepository
    private lateinit var dietPlans: DietPlanRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        sessions = SessionRepository(db)
        groups = GroupRepository(db)
        meals = MealRepository(db)
        dietPlans = DietPlanRepository(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun seedCreatesLibraryAndSampleRoutine() = runTest {
        Seed.runIfNeeded(db)
        assertTrue(db.exerciseDao().count() >= 35)
        val routine = db.routineDao().getActive()
        assertNotNull(routine)
        assertEquals(4, routine!!.slots.size)
        assertNull(routine.sortedSlots[3].slot.groupId)
        val push = db.groupDao().getGroup(routine.sortedSlots[0].slot.groupId!!)!!
        assertEquals("Push day", push.group.name)
        assertEquals(5, push.exercises.size)
        assertEquals(15, push.workingSetCount)
        // Seeding twice is a no-op.
        Seed.runIfNeeded(db)
        assertEquals(1, db.routineDao().getAllRoutines().size)
    }

    @Test
    fun inProgressSessionIsRecoverableAndSetsAreQueriedByExercise() = runTest {
        Seed.runIfNeeded(db)
        val bench = db.exerciseDao().getAll().first { it.name == "Bench press" }
        val plan = listOf(
            PlannedExercise(bench, null, false, 90, listOf(PlannedSet(8, 60.0), PlannedSet(8, 60.0), PlannedSet(8, 60.0)))
        )
        val id = sessions.start(null, "Push day", null, plan)
        val inProgress = sessions.getInProgress()
        assertNotNull(inProgress)
        assertEquals(id, inProgress!!.session.id)
        assertEquals(3, inProgress.exercises[0].sets.size)

        val sets = inProgress.exercises[0].sortedSets
        sessions.completeSet(sets[0], 8, 60.0, null)
        sessions.completeSet(sets[1], 8, 60.0, null)
        sessions.completeSet(sets[2], 7, 60.0, null)
        // Not visible to history queries until completed.
        assertEquals(0, sessions.setsForExercise(bench.id).size)
        sessions.finish(id, "", writeBackTargets = false)
        assertNull(sessions.getInProgress())
        val dated = sessions.setsForExercise(bench.id)
        assertEquals(3, dated.size)
        assertEquals(7, dated.last().actualReps)
        assertEquals(SessionStatus.COMPLETED, sessions.getSession(id)!!.session.status)
    }

    @Test
    fun finishWritesAcceptedTargetsBackToPrescriptions() = runTest {
        Seed.runIfNeeded(db)
        val routine = db.routineDao().getActive()!!
        val push = db.groupDao().getGroup(routine.sortedSlots[0].slot.groupId!!)!!
        val benchGe = push.sortedExercises[0]
        assertEquals(60.0, benchGe.sortedSets[1].targetWeightKg)
        val plan = listOf(
            PlannedExercise(
                benchGe.exercise, benchGe.groupExercise.id, false, 90,
                listOf(PlannedSet(8, 40.0, isWarmup = true), PlannedSet(8, 62.5), PlannedSet(8, 62.5), PlannedSet(8, 62.5))
            )
        )
        val id = sessions.start(push.group.id, "Push day", routine.routine.id, plan)
        sessions.finish(id, "", writeBackTargets = true)
        val after = db.groupDao().getGroup(push.group.id)!!.sortedExercises[0].sortedSets
        assertEquals(40.0, after[0].targetWeightKg) // warm-up untouched
        assertEquals(62.5, after[1].targetWeightKg)
        assertEquals(62.5, after[3].targetWeightKg)
    }

    @Test
    fun groupRepositoryAddsAndRemovesSets() = runTest {
        Seed.runIfNeeded(db)
        val bench = db.exerciseDao().getAll().first { it.name == "Bench press" }
        val gid = groups.createGroup("Test")
        val geId = groups.addExercise(gid, bench)
        var g = groups.observeGroup(gid).first()!!
        assertEquals(3, g.exercises[0].sets.size)
        groups.addSet(geId, bench)
        g = groups.observeGroup(gid).first()!!
        assertEquals(4, g.exercises[0].sets.size)
        groups.removeSet(g.exercises[0].sortedSets[0])
        g = groups.observeGroup(gid).first()!!
        assertEquals(listOf(0, 1, 2), g.exercises[0].sortedSets.map { it.position })
    }

    private suspend fun mealNamed(name: String): Meal = db.mealDao().getAllMeals().first { it.name == name }

    @Test
    fun dietSeedCreatesMealsAndActivePlan() = runTest {
        Seed.runIfNeeded(db)
        assertEquals(7, db.mealDao().count())
        val plan = dietPlans.getActive()
        assertNotNull(plan)
        assertEquals("Cutting week", plan!!.plan.name)
        assertEquals(20, plan.cells.size)
        assertEquals(20, plan.cells.count { it.cell.mealId != null && it.meal != null })
        assertEquals(2, plan.day(6).size) // Sunday lunch is empty
        assertEquals(1, meals.usageCount(mealNamed("Rajma chawal").id))
        assertEquals(3, meals.usageCount(mealNamed("Oats with whey and peanut butter").id))
        val chilla = meals.getMeal(mealNamed("Moong dal chilla with curd").id)!!
        assertTrue(chilla.meal.prepDayBefore)
        assertEquals(listOf(MealSlot.BREAKFAST), chilla.meal.slotList)
        assertEquals(8, chilla.ingredients.size)
        assertEquals(5, chilla.steps.size)
        assertEquals((0 until 5).toList(), chilla.sortedSteps.map { it.position })
        assertEquals(listOf("Moong dal chilla with curd"), meals.observeMeals("chilla", MealSlot.BREAKFAST).first().map { it.name })
        assertEquals(3, meals.observeMeals(slot = MealSlot.LUNCH).first().size)
        assertTrue(db.dietSettingsDao().get()!!.seeded)
        // Seeding twice is a no-op.
        Seed.runIfNeeded(db)
        assertEquals(7, db.mealDao().count())
        assertEquals(1, db.dietPlanDao().getAllPlans().size)
    }

    @Test
    fun dietSeedRunsOnUpgradeFromVersionOne() = runTest {
        // A version 1 installation: workout content already seeded, no diet settings row yet.
        db.settingsDao().upsert(Settings(seeded = true))
        Seed.runIfNeeded(db)
        assertEquals(0, db.exerciseDao().count())
        assertEquals(7, db.mealDao().count())
        assertNotNull(dietPlans.getActive())
        assertTrue(db.dietSettingsDao().get()!!.seeded)
    }

    @Test
    fun upsertWithDetailsReplacesIngredients() = runTest {
        val id = meals.save(
            Meal(name = "Khichdi", slots = "lunch,dinner"),
            listOf(
                Ingredient(mealId = 0, position = 0, name = "Rice", amount = 0.5, unit = "cup"),
                Ingredient(mealId = 0, position = 1, name = "Moong dal", amount = 0.25, unit = "cup")
            ),
            listOf(MealStep(mealId = 0, position = 0, text = "Pressure cook everything for 3 whistles."))
        )
        var m = meals.observeMeal(id).first()!!
        assertEquals(listOf("Rice", "Moong dal"), m.sortedIngredients.map { it.name })
        assertEquals(1, m.steps.size)
        meals.save(
            m.meal.copy(cookMinutes = 20),
            listOf(Ingredient(mealId = 99, position = 7, name = "Ghee", amount = 1.0, unit = "tsp")),
            m.sortedSteps + MealStep(mealId = 0, position = 0, text = "Top with ghee.")
        )
        m = meals.getMeal(id)!!
        assertEquals(20, m.meal.cookMinutes)
        assertEquals(listOf("Ghee"), m.ingredients.map { it.name })
        assertEquals(id, m.ingredients[0].mealId)
        assertEquals(0, m.ingredients[0].position)
        assertEquals(listOf(0, 1), m.sortedSteps.map { it.position })
        assertEquals(1, db.mealDao().getAllIngredients().size)
        try {
            meals.save(Meal(name = " khichdi "), emptyList(), emptyList())
            fail("duplicate name accepted")
        } catch (e: IllegalArgumentException) {
            assertEquals("A meal called khichdi already exists", e.message)
        }
        assertEquals(1, db.mealDao().count())
    }

    @Test
    fun deletingMealNullsItsCells() = runTest {
        Seed.runIfNeeded(db)
        val rajma = mealNamed("Rajma chawal")
        meals.delete(rajma.id)
        val plan = dietPlans.getActive()!!
        val wedLunch = plan.day(2).first { it.cell.slot == MealSlot.LUNCH }
        assertNull(wedLunch.cell.mealId)
        assertNull(wedLunch.meal)
        assertEquals(20, plan.cells.size)
        assertEquals(19, plan.cells.count { it.cell.mealId != null })
        assertEquals(0, db.mealDao().getAllIngredients().count { it.mealId == rajma.id })
        assertEquals(0, db.mealDao().getAllSteps().count { it.mealId == rajma.id })
    }

    @Test
    fun copyDayFillsWeek() = runTest {
        val oats = meals.save(Meal(name = "Oats", slots = "breakfast"), emptyList(), emptyList())
        val dal = meals.save(Meal(name = "Dal", slots = "lunch"), emptyList(), emptyList())
        assertNull(dietPlans.getActive())
        dietPlans.setCell(0, MealSlot.BREAKFAST, oats)
        dietPlans.setCell(0, MealSlot.LUNCH, dal, servings = 1.5)
        val plan = dietPlans.observeActive().first()!!
        assertEquals(DietPlanRepository.DEFAULT_NAME, plan.plan.name)
        assertEquals(2, plan.cells.size)
        dietPlans.copyDay(0)
        var filled = dietPlans.getActive()!!
        assertEquals(14, filled.cells.size)
        val sundayLunch = filled.day(6).first { it.cell.slot == MealSlot.LUNCH }
        assertEquals(dal, sundayLunch.cell.mealId)
        assertEquals(1.5, sundayLunch.cell.servings, 0.0)
        // Replacing and clearing a cell respects the unique (plan, day, slot) index.
        dietPlans.setCell(3, MealSlot.LUNCH, oats)
        filled = dietPlans.getActive()!!
        assertEquals(14, filled.cells.size)
        assertEquals(oats, filled.day(3).first { it.cell.slot == MealSlot.LUNCH }.cell.mealId)
        dietPlans.clearCell(3, MealSlot.LUNCH)
        assertEquals(13, dietPlans.getActive()!!.cells.size)
        assertEquals(1, db.dietPlanDao().getAllPlans().size)
    }

    @Test
    fun importCountsIncludeDietTables() = runTest {
        Seed.runIfNeeded(db)
        val json = BackupCodec.export(db, "test")
        val replaced = BackupCodec.import(db, json, ImportMode.REPLACE)
        assertEquals(7, replaced.counts["meals"])
        assertEquals(1, replaced.counts["dietPlans"])
        assertEquals(20, replaced.counts["dietPlanCells"])
        assertEquals(1, replaced.counts["dietSettings"])
        assertTrue(replaced.counts.getValue("ingredients") > 0)
        assertTrue(replaced.counts.getValue("mealSteps") > 0)
        val merged = BackupCodec.import(db, json, ImportMode.MERGE)
        for (key in listOf("meals", "ingredients", "mealSteps", "dietPlans", "dietPlanCells", "dietSettings")) {
            assertEquals(key, 0, merged.counts[key])
        }
        assertEquals(7, db.mealDao().count())
        assertEquals(20, dietPlans.getActive()!!.cells.size)
    }
}
