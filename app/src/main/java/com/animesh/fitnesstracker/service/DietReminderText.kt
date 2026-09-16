package com.animesh.fitnesstracker.service

import com.animesh.fitnesstracker.data.model.MealSlot
import com.animesh.fitnesstracker.domain.diet.DayMenu
import com.animesh.fitnesstracker.domain.diet.Scaling

/** Pure text for the meal window notification (requirement FR43); kept free of Android so it can be unit tested. */
object DietReminderText {

    /** "Lunch" from [MealSlot.LUNCH]. */
    fun slotLabel(slot: MealSlot): String = slot.name.lowercase().replaceFirstChar { it.uppercase() }

    /** "Lunch: Rajma chawal"; null when the slot is empty. */
    fun windowTitle(entry: DayMenu.Entry): String? = entry.meal?.let { "${slotLabel(entry.slot)}: ${it.name}" }

    /** "1 serving · 560 kcal · 20 g protein", or "2 servings · macros not set"; null when the slot is empty. */
    fun windowBody(entry: DayMenu.Entry): String? {
        val meal = entry.meal ?: return null
        val macros = Scaling.macros(meal, entry.servings)?.summary() ?: "macros not set"
        return "${servingsLabel(entry.servings)} · $macros"
    }

    /** "1 serving", "0.5 serving", "2 servings", "1.5 servings". */
    fun servingsLabel(servings: Double): String {
        val n = Scaling.format(servings)
        return if (servings <= 1.0) "$n serving" else "$n servings"
    }
}
