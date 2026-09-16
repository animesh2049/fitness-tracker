package com.animesh.fitnesstracker.domain.diet

import com.animesh.fitnesstracker.data.model.DietPlanWithCells
import com.animesh.fitnesstracker.data.model.DietSettings
import com.animesh.fitnesstracker.data.model.Meal
import com.animesh.fitnesstracker.data.model.MealSlot
import java.time.LocalDate
import java.time.LocalDateTime

/** Day-before prep reminders and reminder scheduling (requirements FR42 and FR43). */
object PrepPlanner {

    /** A meal planned for tomorrow that needs preparing today. */
    data class PrepItem(val meal: Meal, val slot: MealSlot, val instruction: String)

    /** Prep items for the meals planned on the day after [date], in slot order, one per distinct meal. */
    fun forDate(plan: DietPlanWithCells?, date: LocalDate): List<PrepItem> {
        val tomorrow = DayMenu.dayOfWeek(date.plusDays(1))
        return DayMenu.entries(plan, tomorrow)
            .mapNotNull { entry -> entry.meal?.takeIf { it.prepDayBefore }?.let { it to entry.slot } }
            .distinctBy { (meal, _) -> meal }
            .map { (meal, slot) -> PrepItem(meal, slot, meal.prepInstruction.ifBlank { "Prepare ahead for ${meal.name}" }) }
    }

    /** True from the prep reminder minute until the end of the day, unless prep was marked done on [epochDay]. */
    fun bannerVisible(settings: DietSettings, minuteOfDay: Int, epochDay: Long): Boolean =
        settings.prepDoneEpochDay != epochDay && minuteOfDay >= settings.prepReminderMinute

    /** The next prep reminder strictly after [now], today or tomorrow; null when the reminder is off. */
    fun nextPrepTrigger(settings: DietSettings, now: LocalDateTime): LocalDateTime? {
        if (!settings.prepReminderEnabled) return null
        val today = at(now.toLocalDate(), settings.prepReminderMinute)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    /** The next window start strictly after [now] (today, or tomorrow's breakfast); null when window reminders are off. */
    fun nextWindowTrigger(settings: DietSettings, now: LocalDateTime): Pair<MealSlot, LocalDateTime>? {
        if (!settings.mealReminderEnabled) return null
        val date = now.toLocalDate()
        for (slot in MealSlot.entries) {
            val start = at(date, settings.window(slot).first)
            if (start.isAfter(now)) return slot to start
        }
        val first = MealSlot.entries.first()
        return first to at(date.plusDays(1), settings.window(first).first)
    }

    /** Notification title and body for [items]; null when there is nothing to prep. */
    fun notificationText(items: List<PrepItem>): Pair<String, String>? {
        if (items.isEmpty()) return null
        val slots = items.map { it.slot }.distinct()
        val title = if (slots.size == 1) "Prep for tomorrow's ${slots.single().name.lowercase()}" else "Prep for tomorrow"
        val body = items.joinToString("\n") { "${it.meal.name}: ${it.instruction}" }
        return title to body
    }

    private fun at(date: LocalDate, minuteOfDay: Int): LocalDateTime = date.atStartOfDay().plusMinutes(minuteOfDay.toLong())
}
