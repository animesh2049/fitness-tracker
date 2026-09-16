package com.animesh.fitnesstracker

import com.animesh.fitnesstracker.data.model.DietPlanCell
import com.animesh.fitnesstracker.data.model.Meal
import com.animesh.fitnesstracker.data.model.MealSlot
import com.animesh.fitnesstracker.domain.diet.DayMenu
import com.animesh.fitnesstracker.service.DietReminderText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DietReminderTextTest {
    private val rajma = Meal(id = 4, name = "Rajma chawal", slots = "lunch", kcal = 560.0, proteinG = 20.0, carbsG = 90.0, fatG = 10.0)
    private val eggs = Meal(id = 8, name = "Boiled eggs", slots = "breakfast", proteinG = 13.0)

    private fun entry(slot: MealSlot, meal: Meal?, servings: Double = 1.0) =
        DayMenu.Entry(slot, meal?.let { DietPlanCell(planId = 1, dayOfWeek = 0, slot = slot, mealId = it.id, servings = servings) }, meal, servings)

    @Test
    fun `window notification matches the design copy`() {
        val e = entry(MealSlot.LUNCH, rajma)
        assertEquals("Lunch: Rajma chawal", DietReminderText.windowTitle(e))
        assertEquals("1 serving · 560 kcal · 20 g protein", DietReminderText.windowBody(e))
    }

    @Test
    fun `servings scale the macros and pluralise`() {
        val e = entry(MealSlot.DINNER, rajma, servings = 1.5)
        assertEquals("Dinner: Rajma chawal", DietReminderText.windowTitle(e))
        assertEquals("1.5 servings · 840 kcal · 30 g protein", DietReminderText.windowBody(e))
        assertEquals("0.5 serving", DietReminderText.servingsLabel(0.5))
        assertEquals("2 servings", DietReminderText.servingsLabel(2.0))
    }

    @Test
    fun `missing macros are named instead of guessed`() {
        assertEquals("1 serving · macros not set", DietReminderText.windowBody(entry(MealSlot.BREAKFAST, eggs)))
    }

    @Test
    fun `empty slot produces no text`() {
        val e = entry(MealSlot.BREAKFAST, null)
        assertNull(DietReminderText.windowTitle(e))
        assertNull(DietReminderText.windowBody(e))
    }
}
