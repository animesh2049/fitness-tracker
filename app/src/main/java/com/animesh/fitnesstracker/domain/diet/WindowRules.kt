package com.animesh.fitnesstracker.domain.diet

import com.animesh.fitnesstracker.data.model.DietSettings
import com.animesh.fitnesstracker.data.model.MealSlot

/** Validation of the meal windows and reminder time (requirement 14.2). */
object WindowRules {

    private const val LAST_MINUTE = 24 * 60 - 1

    /** Human-readable problems with [settings]; empty when valid. */
    fun validate(settings: DietSettings): List<String> {
        val problems = mutableListOf<String>()
        for (slot in MealSlot.entries) {
            val window = settings.window(slot)
            if (window.last <= window.first) problems += "${label(slot)} ends before it starts"
        }
        for (i in 1 until MealSlot.entries.size) {
            val earlier = MealSlot.entries[i - 1]
            val later = MealSlot.entries[i]
            val previous = settings.window(earlier)
            if (settings.window(later).first < maxOf(previous.first, previous.last)) {
                problems += "${label(later)} overlaps ${earlier.name.lowercase()}"
            }
        }
        if (settings.prepReminderMinute !in 0..LAST_MINUTE) {
            problems += "Prep reminder time must be between ${MealClock.formatMinute(0)} and ${MealClock.formatMinute(LAST_MINUTE)}"
        }
        return problems
    }

    private fun label(slot: MealSlot): String = slot.name.lowercase().replaceFirstChar { it.uppercase() }
}
