package com.animesh.fitnesstracker.domain.diet

import com.animesh.fitnesstracker.data.model.DietPlanCell
import com.animesh.fitnesstracker.data.model.DietPlanWithCells
import com.animesh.fitnesstracker.data.model.Ingredient
import com.animesh.fitnesstracker.data.model.Meal
import com.animesh.fitnesstracker.data.model.MealSlot
import com.animesh.fitnesstracker.data.model.MealWithDetails
import java.time.LocalDate

/** One day of the week plan, read as three slot entries (requirements FR40 and FR41). */
object DayMenu {

    /** A slot of one day. [cell] is null when the plan has no cell for it; [meal] is null when the slot is empty. */
    data class Entry(val slot: MealSlot, val cell: DietPlanCell?, val meal: Meal?, val servings: Double) {
        val isEmpty: Boolean get() = meal == null
    }

    /** Three entries in slot order for [dayOfWeek] (0 is Monday, 6 is Sunday); empty when the plan or cell is missing. */
    fun entries(plan: DietPlanWithCells?, dayOfWeek: Int): List<Entry> {
        val cells = plan?.day(dayOfWeek).orEmpty()
        return MealSlot.entries.map { slot ->
            val withMeal = cells.firstOrNull { it.cell.slot == slot }
            Entry(slot, withMeal?.cell, withMeal?.meal, withMeal?.cell?.servings ?: 1.0)
        }
    }

    /** Monday is 0, Sunday is 6. */
    fun dayOfWeek(date: LocalDate): Int = date.dayOfWeek.value - 1

    /** Scaled macros summed over non-empty entries; null when all are empty or any non-empty meal lacks macros. */
    fun totals(entries: List<Entry>): Macros? {
        val meals = entries.filter { !it.isEmpty }
        if (meals.isEmpty()) return null
        var total: Macros? = null
        for (entry in meals) {
            val m = Scaling.macros(entry.meal!!, entry.servings) ?: return null
            total = total?.plus(m) ?: m
        }
        return total
    }

    /** Scaled protein summed over entries whose meal has a protein value; other entries are ignored. */
    fun proteinTotal(entries: List<Entry>): Double =
        entries.sumOf { entry -> entry.meal?.proteinG?.let { it * entry.servings } ?: 0.0 }

    /** Each ingredient in position order paired with its formatted quantity for [servings] servings. */
    fun scaledIngredients(details: MealWithDetails, servings: Double): List<Pair<Ingredient, String>> =
        details.sortedIngredients.map { it to Scaling.formatQuantity(Scaling.scale(it.amount, servings), it.unit) }
}
