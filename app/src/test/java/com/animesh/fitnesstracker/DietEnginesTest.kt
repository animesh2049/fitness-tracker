package com.animesh.fitnesstracker

import com.animesh.fitnesstracker.data.model.DietPlan
import com.animesh.fitnesstracker.data.model.DietPlanCell
import com.animesh.fitnesstracker.data.model.DietPlanCellWithMeal
import com.animesh.fitnesstracker.data.model.DietPlanWithCells
import com.animesh.fitnesstracker.data.model.DietSettings
import com.animesh.fitnesstracker.data.model.Ingredient
import com.animesh.fitnesstracker.data.model.Meal
import com.animesh.fitnesstracker.data.model.MealSlot
import com.animesh.fitnesstracker.data.model.MealStep
import com.animesh.fitnesstracker.data.model.MealWithDetails
import com.animesh.fitnesstracker.domain.diet.DayMenu
import com.animesh.fitnesstracker.domain.diet.Macros
import com.animesh.fitnesstracker.domain.diet.MealClock
import com.animesh.fitnesstracker.domain.diet.MealStatus
import com.animesh.fitnesstracker.domain.diet.PrepPlanner
import com.animesh.fitnesstracker.domain.diet.Scaling
import com.animesh.fitnesstracker.domain.diet.WindowRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class DietEnginesTest {
    private val settings = DietSettings()

    // The design's sample meals (design/diet/WeekPlan.dc.html).
    private val chilla = Meal(id = 1, name = "Moong dal chilla", slots = "breakfast", kcal = 450.0, proteinG = 35.0, carbsG = 40.0, fatG = 12.0, prepDayBefore = true, prepInstruction = "soak 1 cup moong dal overnight")
    private val oats = Meal(id = 2, name = "Oats, whey, peanut butter", slots = "breakfast", kcal = 430.0, proteinG = 36.0, carbsG = 45.0, fatG = 14.0)
    private val dal = Meal(id = 3, name = "Dal, sabzi, roti", slots = "lunch", kcal = 600.0, proteinG = 26.0, carbsG = 80.0, fatG = 16.0)
    private val rajma = Meal(id = 4, name = "Rajma chawal", slots = "lunch", kcal = 560.0, proteinG = 20.0, carbsG = 90.0, fatG = 10.0, prepDayBefore = true, prepInstruction = "soak 1 cup rajma overnight")
    private val chole = Meal(id = 5, name = "Chole, brown rice", slots = "lunch", kcal = 580.0, proteinG = 22.0, carbsG = 85.0, fatG = 12.0, prepDayBefore = true, prepInstruction = "")
    private val paneer = Meal(id = 6, name = "Paneer bhurji, roti", slots = "dinner", kcal = 550.0, proteinG = 33.0, carbsG = 40.0, fatG = 24.0)
    private val soya = Meal(id = 7, name = "Soya curry, roti", slots = "dinner", kcal = 520.0, proteinG = 38.0, carbsG = 50.0, fatG = 14.0)
    /** A meal with protein typed but no other macros. */
    private val eggs = Meal(id = 8, name = "Boiled eggs", slots = "breakfast", proteinG = 13.0)
    /** A meal with no macros at all. */
    private val salad = Meal(id = 9, name = "Salad", slots = "lunch,dinner")

    // Mon .. Sun, breakfast/lunch/dinner. Sunday lunch is an explicit empty cell.
    private val sampleWeek: List<List<Meal?>> = listOf(
        listOf(oats, dal, soya), listOf(chilla, dal, paneer), listOf(chilla, rajma, paneer), listOf(oats, dal, soya),
        listOf(chilla, chole, paneer), listOf(oats, dal, soya), listOf(chilla, null, paneer)
    )

    private fun plan(week: List<List<Meal?>>, servings: Double = 1.0): DietPlanWithCells {
        val p = DietPlan(id = 1, name = "Sample", isActive = true)
        var cellId = 0L
        val cells = week.flatMapIndexed { day, meals ->
            meals.mapIndexed { slotIndex, meal ->
                val slot = MealSlot.entries[slotIndex]
                DietPlanCellWithMeal(DietPlanCell(id = ++cellId, planId = 1, dayOfWeek = day, slot = slot, mealId = meal?.id, servings = servings), meal)
            }
        }
        return DietPlanWithCells(p, cells)
    }

    private val tuesday: LocalDate = LocalDate.of(2026, 9, 15)
    private val sunday: LocalDate = LocalDate.of(2026, 9, 20)

    // MealClock

    @Test
    fun windowBoundaries() {
        assertEquals(MealClock.State.Between(MealSlot.BREAKFAST, 360), MealClock.state(settings, 0))
        assertEquals(MealClock.State.Between(MealSlot.BREAKFAST, 360), MealClock.state(settings, 5 * 60))
        assertEquals(MealClock.State.Current(MealSlot.BREAKFAST, 630), MealClock.state(settings, 6 * 60))
        assertEquals(MealClock.State.Current(MealSlot.BREAKFAST, 630), MealClock.state(settings, 7 * 60 + 40))
        assertEquals(MealClock.State.Between(MealSlot.LUNCH, 690), MealClock.state(settings, 10 * 60 + 30))
        assertEquals(MealClock.State.Current(MealSlot.LUNCH, 930), MealClock.state(settings, 12 * 60 + 50))
        assertEquals(MealClock.State.Between(MealSlot.DINNER, 1110), MealClock.state(settings, 15 * 60 + 30))
        assertEquals(MealClock.State.Current(MealSlot.DINNER, 1320), MealClock.state(settings, 21 * 60 + 59))
        assertEquals(MealClock.State.AfterLast(MealSlot.BREAKFAST, 360), MealClock.state(settings, 22 * 60))
        assertEquals(MealClock.State.AfterLast(MealSlot.BREAKFAST, 360), MealClock.state(settings, 23 * 60 + 59))
    }

    @Test
    fun statusOfEachState() {
        val current = MealClock.State.Current(MealSlot.LUNCH, 930)
        assertEquals(MealStatus.DONE, MealClock.statusOf(MealSlot.BREAKFAST, current))
        assertEquals(MealStatus.NOW, MealClock.statusOf(MealSlot.LUNCH, current))
        assertEquals(MealStatus.LATER, MealClock.statusOf(MealSlot.DINNER, current))

        val between = MealClock.State.Between(MealSlot.LUNCH, 690)
        assertEquals(MealStatus.DONE, MealClock.statusOf(MealSlot.BREAKFAST, between))
        assertEquals(MealStatus.LATER, MealClock.statusOf(MealSlot.LUNCH, between))
        assertEquals(MealStatus.LATER, MealClock.statusOf(MealSlot.DINNER, between))

        val early = MealClock.State.Between(MealSlot.BREAKFAST, 360)
        assertEquals(MealStatus.LATER, MealClock.statusOf(MealSlot.BREAKFAST, early))
        assertEquals(MealStatus.LATER, MealClock.statusOf(MealSlot.DINNER, early))

        val after = MealClock.State.AfterLast(MealSlot.BREAKFAST, 360)
        MealSlot.entries.forEach { assertEquals(MealStatus.DONE, MealClock.statusOf(it, after)) }
    }

    @Test
    fun formatMinute() {
        assertEquals("6:00", MealClock.formatMinute(360))
        assertEquals("13:05", MealClock.formatMinute(13 * 60 + 5))
        assertEquals("21:00", MealClock.formatMinute(21 * 60))
        assertEquals("0:00", MealClock.formatMinute(0))
        assertEquals("23:59", MealClock.formatMinute(1439))
    }

    // WindowRules

    @Test
    fun defaultsAreValid() {
        assertEquals(emptyList<String>(), WindowRules.validate(settings))
    }

    @Test
    fun endBeforeStartIsReported() {
        assertEquals(listOf("Breakfast ends before it starts"), WindowRules.validate(settings.copy(breakfastEnd = 5 * 60)))
        assertEquals(listOf("Lunch ends before it starts"), WindowRules.validate(settings.copy(lunchEnd = settings.lunchStart)))
        assertEquals(listOf("Dinner ends before it starts"), WindowRules.validate(settings.copy(dinnerEnd = 18 * 60)))
    }

    @Test
    fun overlapsAreReported() {
        assertEquals(listOf("Lunch overlaps breakfast"), WindowRules.validate(settings.copy(lunchStart = 10 * 60)))
        assertEquals(listOf("Dinner overlaps lunch"), WindowRules.validate(settings.copy(dinnerStart = 15 * 60)))
        // Touching windows are fine: breakfast ends at 10:30, lunch starts at 10:30.
        assertEquals(emptyList<String>(), WindowRules.validate(settings.copy(lunchStart = settings.breakfastEnd)))
        // Out of order counts as an overlap.
        assertEquals(listOf("Lunch overlaps breakfast"), WindowRules.validate(settings.copy(breakfastStart = 12 * 60, breakfastEnd = 13 * 60, lunchStart = 11 * 60, lunchEnd = 11 * 60 + 30)))
    }

    @Test
    fun prepReminderMinuteMustBeWithinTheDay() {
        assertEquals(listOf("Prep reminder time must be between 0:00 and 23:59"), WindowRules.validate(settings.copy(prepReminderMinute = 1440)))
        assertEquals(listOf("Prep reminder time must be between 0:00 and 23:59"), WindowRules.validate(settings.copy(prepReminderMinute = -1)))
    }

    @Test
    fun severalProblemsAreAllReported() {
        val bad = settings.copy(breakfastEnd = 5 * 60, dinnerStart = 15 * 60, prepReminderMinute = 2000)
        assertEquals(listOf("Breakfast ends before it starts", "Dinner overlaps lunch", "Prep reminder time must be between 0:00 and 23:59"), WindowRules.validate(bad))
    }

    // Scaling

    @Test
    fun scaleRoundsToTheNearestQuarter() {
        assertEquals(1.5, Scaling.scale(1.0, 1.5), 0.0)
        assertEquals(2.25, Scaling.scale(1.5, 1.5), 0.0)
        assertEquals(0.25, Scaling.scale(0.33, 1.0), 0.0)
        assertEquals(0.5, Scaling.scale(0.4, 1.0), 0.0)
        assertEquals(150.0, Scaling.scale(100.0, 1.5), 0.0)
        assertEquals(0.75, Scaling.scale(0.5, 1.5), 0.0)
        assertEquals(0.0, Scaling.scale(0.1, 1.0), 0.0)
    }

    @Test
    fun formatDropsTrailingZerosAndKeepsLeadingZero() {
        assertEquals("0.5", Scaling.format(0.5))
        assertEquals("1", Scaling.format(1.0))
        assertEquals("1.5", Scaling.format(1.5))
        assertEquals("2.25", Scaling.format(2.25))
        assertEquals("100", Scaling.format(100.0))
        assertEquals("0.75", Scaling.format(0.75))
    }

    @Test
    fun formatQuantityAppendsUnitWhenPresent() {
        assertEquals("0.5 cup", Scaling.formatQuantity(0.5, "cup"))
        assertEquals("2", Scaling.formatQuantity(2.0, ""))
        assertEquals("2", Scaling.formatQuantity(2.0, "  "))
        assertEquals("150 g", Scaling.formatQuantity(150.0, "g"))
    }

    @Test
    fun macrosScaleAndSummarise() {
        assertNull(Scaling.macros(eggs, 1.0))
        assertNull(Scaling.macros(salad, 2.0))
        val one = Scaling.macros(rajma, 1.0)!!
        assertEquals(Macros(560.0, 20.0, 90.0, 10.0), one)
        assertEquals("560 kcal · 20 g protein", one.summary())
        val half = Scaling.macros(rajma, 1.5)!!
        assertEquals(Macros(840.0, 30.0, 135.0, 15.0), half)
        assertEquals("840 kcal · 30 g protein", half.summary())
        assertEquals(Macros(1400.0, 50.0, 225.0, 25.0), one.plus(half))
        assertEquals("451 kcal · 35 g protein", Macros(450.6, 34.6, 0.0, 0.0).summary())
    }

    // DayMenu

    @Test
    fun entriesAlwaysHaveThreeSlotsAndFillMissingCellsAsEmpty() {
        val p = plan(sampleWeek)
        val partial = p.copy(cells = p.cells.filter { it.cell.dayOfWeek == 0 && it.cell.slot != MealSlot.LUNCH })
        val entries = DayMenu.entries(partial, 0)
        assertEquals(listOf(MealSlot.BREAKFAST, MealSlot.LUNCH, MealSlot.DINNER), entries.map { it.slot })
        assertEquals(oats, entries[0].meal)
        assertFalse(entries[0].isEmpty)
        assertNull(entries[1].cell)
        assertNull(entries[1].meal)
        assertTrue(entries[1].isEmpty)
        assertEquals(1.0, entries[1].servings, 0.0)
        assertEquals(soya, entries[2].meal)

        // An explicit empty cell (Sunday lunch) is empty but keeps its cell.
        val sundayEntries = DayMenu.entries(p, 6)
        assertTrue(sundayEntries[1].isEmpty)
        assertEquals(MealSlot.LUNCH, sundayEntries[1].cell?.slot)

        // No plan at all: three empties.
        val none = DayMenu.entries(null, 3)
        assertEquals(3, none.size)
        assertTrue(none.all { it.isEmpty })

        // Servings come from the cell.
        assertEquals(1.5, DayMenu.entries(plan(sampleWeek, 1.5), 2)[1].servings, 0.0)
    }

    @Test
    fun dayOfWeekIsMondayZero() {
        assertEquals(1, DayMenu.dayOfWeek(tuesday))
        assertEquals(6, DayMenu.dayOfWeek(sunday))
        assertEquals(0, DayMenu.dayOfWeek(sunday.plusDays(1)))
    }

    @Test
    fun totalsSumScaledMacrosOrReturnNull() {
        val wed = DayMenu.entries(plan(sampleWeek), 2)
        assertEquals(Macros(1560.0, 88.0, 170.0, 46.0), DayMenu.totals(wed))
        assertEquals(Macros(2340.0, 132.0, 255.0, 69.0), DayMenu.totals(DayMenu.entries(plan(sampleWeek, 1.5), 2)))

        // Sunday lunch is empty: totals still sum the two non-empty meals.
        assertEquals(Macros(1000.0, 68.0, 80.0, 36.0), DayMenu.totals(DayMenu.entries(plan(sampleWeek), 6)))

        // A meal without macros makes the total unknown.
        val withEggs = plan(listOf(listOf(eggs, dal, soya)))
        assertNull(DayMenu.totals(DayMenu.entries(withEggs, 0)))

        // All empty: nothing to sum.
        assertNull(DayMenu.totals(DayMenu.entries(null, 0)))
    }

    @Test
    fun proteinTotalIgnoresMealsWithoutProtein() {
        val entries = DayMenu.entries(plan(listOf(listOf(eggs, salad, soya)), 2.0), 0)
        assertEquals((13.0 + 38.0) * 2, DayMenu.proteinTotal(entries), 1e-9)
        assertEquals(88.0, DayMenu.proteinTotal(DayMenu.entries(plan(sampleWeek), 2)), 1e-9)
        assertEquals(0.0, DayMenu.proteinTotal(DayMenu.entries(null, 0)), 0.0)
    }

    @Test
    fun scaledIngredientsAreSortedAndFormatted() {
        val details = MealWithDetails(
            meal = rajma,
            ingredients = listOf(
                Ingredient(id = 3, mealId = 4, position = 2, name = "Salt", amount = 0.5, unit = "tsp"),
                Ingredient(id = 1, mealId = 4, position = 0, name = "Rajma (soaked)", amount = 1.0, unit = "cup"),
                Ingredient(id = 2, mealId = 4, position = 1, name = "Onion", amount = 1.0, unit = "piece"),
                Ingredient(id = 4, mealId = 4, position = 3, name = "Water", amount = 2.0, unit = "")
            ),
            steps = listOf(MealStep(id = 1, mealId = 4, position = 0, text = "Pressure cook."))
        )
        val scaled = DayMenu.scaledIngredients(details, 1.5)
        assertEquals(listOf("Rajma (soaked)", "Onion", "Salt", "Water"), scaled.map { it.first.name })
        assertEquals(listOf("1.5 cup", "1.5 piece", "0.75 tsp", "3"), scaled.map { it.second })
        assertEquals(listOf("1 cup", "1 piece", "0.5 tsp", "2"), DayMenu.scaledIngredients(details, 1.0).map { it.second })
    }

    // PrepPlanner

    @Test
    fun forDateListsTomorrowsPrepMealsInSlotOrder() {
        // Tuesday evening: Wednesday has chilla at breakfast and rajma at lunch.
        val items = PrepPlanner.forDate(plan(sampleWeek), tuesday)
        assertEquals(listOf(chilla, rajma), items.map { it.meal })
        assertEquals(listOf(MealSlot.BREAKFAST, MealSlot.LUNCH), items.map { it.slot })
        assertEquals(listOf("soak 1 cup moong dal overnight", "soak 1 cup rajma overnight"), items.map { it.instruction })

        // Monday evening: Tuesday has only chilla.
        assertEquals(listOf(PrepPlanner.PrepItem(chilla, MealSlot.BREAKFAST, "soak 1 cup moong dal overnight")), PrepPlanner.forDate(plan(sampleWeek), tuesday.minusDays(1)))

        // Thursday evening: Friday has chole with a blank instruction.
        val friday = PrepPlanner.forDate(plan(sampleWeek), tuesday.plusDays(2))
        assertEquals(listOf(chilla, chole), friday.map { it.meal })
        assertEquals("Prepare ahead for Chole, brown rice", friday[1].instruction)
    }

    @Test
    fun forDateWrapsFromSundayToMonday() {
        // The sample Monday has nothing to prep.
        assertEquals(emptyList<PrepPlanner.PrepItem>(), PrepPlanner.forDate(plan(sampleWeek), sunday))
        // Put rajma on Monday lunch and Sunday evening picks it up.
        val week = sampleWeek.toMutableList().also { it[0] = listOf(oats, rajma, soya) }
        assertEquals(listOf(PrepPlanner.PrepItem(rajma, MealSlot.LUNCH, "soak 1 cup rajma overnight")), PrepPlanner.forDate(plan(week), sunday))
        // Saturday evening: Sunday has chilla, an empty lunch and paneer.
        assertEquals(listOf(chilla), PrepPlanner.forDate(plan(sampleWeek), sunday.minusDays(1)).map { it.meal })
        assertEquals(emptyList<PrepPlanner.PrepItem>(), PrepPlanner.forDate(null, tuesday))
    }

    @Test
    fun forDateDedupesAMealPlannedTwice() {
        val week = sampleWeek.toMutableList().also { it[2] = listOf(chilla, rajma, rajma) }
        val items = PrepPlanner.forDate(plan(week), tuesday)
        assertEquals(listOf(chilla, rajma), items.map { it.meal })
        assertEquals(listOf(MealSlot.BREAKFAST, MealSlot.LUNCH), items.map { it.slot })
    }

    @Test
    fun bannerVisibleFromPrepTimeUntilMidnightUnlessDone() {
        val day = tuesday.toEpochDay()
        assertFalse(PrepPlanner.bannerVisible(settings, 20 * 60 + 59, day))
        assertTrue(PrepPlanner.bannerVisible(settings, 21 * 60, day))
        assertTrue(PrepPlanner.bannerVisible(settings, 23 * 60 + 59, day))
        assertFalse(PrepPlanner.bannerVisible(settings.copy(prepDoneEpochDay = day), 22 * 60, day))
        assertTrue(PrepPlanner.bannerVisible(settings.copy(prepDoneEpochDay = day - 1), 22 * 60, day))
    }

    @Test
    fun nextPrepTriggerIsTodayOrTomorrow() {
        assertEquals(LocalDateTime.of(2026, 9, 15, 21, 0), PrepPlanner.nextPrepTrigger(settings, LocalDateTime.of(2026, 9, 15, 20, 0)))
        assertEquals(LocalDateTime.of(2026, 9, 16, 21, 0), PrepPlanner.nextPrepTrigger(settings, LocalDateTime.of(2026, 9, 15, 21, 0)))
        assertEquals(LocalDateTime.of(2026, 9, 16, 21, 0), PrepPlanner.nextPrepTrigger(settings, LocalDateTime.of(2026, 9, 15, 21, 30)))
        assertEquals(LocalDateTime.of(2026, 9, 15, 21, 0), PrepPlanner.nextPrepTrigger(settings, LocalDateTime.of(2026, 9, 15, 20, 59, 59)))
        assertNull(PrepPlanner.nextPrepTrigger(settings.copy(prepReminderEnabled = false), LocalDateTime.of(2026, 9, 15, 20, 0)))
    }

    @Test
    fun nextWindowTriggerIsTheNextStartStrictlyAfterNow() {
        assertEquals(MealSlot.DINNER to LocalDateTime.of(2026, 9, 15, 18, 30), PrepPlanner.nextWindowTrigger(settings, LocalDateTime.of(2026, 9, 15, 12, 50)))
        assertEquals(MealSlot.BREAKFAST to LocalDateTime.of(2026, 9, 16, 6, 0), PrepPlanner.nextWindowTrigger(settings, LocalDateTime.of(2026, 9, 15, 22, 30)))
        assertEquals(MealSlot.BREAKFAST to LocalDateTime.of(2026, 9, 16, 6, 0), PrepPlanner.nextWindowTrigger(settings, LocalDateTime.of(2026, 9, 15, 18, 30)))
        assertEquals(MealSlot.LUNCH to LocalDateTime.of(2026, 9, 15, 11, 30), PrepPlanner.nextWindowTrigger(settings, LocalDateTime.of(2026, 9, 15, 6, 0)))
        assertEquals(MealSlot.BREAKFAST to LocalDateTime.of(2026, 9, 15, 6, 0), PrepPlanner.nextWindowTrigger(settings, LocalDateTime.of(2026, 9, 15, 0, 0)))
        assertNull(PrepPlanner.nextWindowTrigger(settings.copy(mealReminderEnabled = false), LocalDateTime.of(2026, 9, 15, 12, 50)))
    }

    @Test
    fun notificationTextTitleDependsOnSlots() {
        assertNull(PrepPlanner.notificationText(emptyList()))
        val lunchOnly = listOf(PrepPlanner.PrepItem(rajma, MealSlot.LUNCH, "soak 1 cup rajma overnight"))
        assertEquals("Prep for tomorrow's lunch" to "Rajma chawal: soak 1 cup rajma overnight", PrepPlanner.notificationText(lunchOnly))
        val two = PrepPlanner.forDate(plan(sampleWeek), tuesday)
        assertEquals(
            "Prep for tomorrow" to "Moong dal chilla: soak 1 cup moong dal overnight\nRajma chawal: soak 1 cup rajma overnight",
            PrepPlanner.notificationText(two)
        )
        val breakfastOnly = listOf(PrepPlanner.PrepItem(chilla, MealSlot.BREAKFAST, "a"), PrepPlanner.PrepItem(oats, MealSlot.BREAKFAST, "b"))
        assertEquals("Prep for tomorrow's breakfast", PrepPlanner.notificationText(breakfastOnly)!!.first)
    }
}
