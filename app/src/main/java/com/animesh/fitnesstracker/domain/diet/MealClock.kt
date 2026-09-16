package com.animesh.fitnesstracker.domain.diet

import com.animesh.fitnesstracker.data.model.DietSettings
import com.animesh.fitnesstracker.data.model.MealSlot

/** How a slot relates to the current time of day on the Diet tab. */
enum class MealStatus { DONE, NOW, LATER }

/**
 * Places a minute of the day against the meal windows (requirement FR41). A window includes
 * its start minute and excludes its end minute.
 */
object MealClock {

    sealed interface State {
        /** Inside [slot]'s window, which closes at [endsAtMinute]. */
        data class Current(val slot: MealSlot, val endsAtMinute: Int) : State
        /** Between windows; [next] opens at [startsAtMinute] later today. */
        data class Between(val next: MealSlot, val startsAtMinute: Int) : State
        /** Past the last window; [nextSlot] is tomorrow's first meal, opening at [startsAtMinute]. */
        data class AfterLast(val nextSlot: MealSlot, val startsAtMinute: Int) : State
    }

    /** @param minuteOfDay 0..1439. */
    fun state(settings: DietSettings, minuteOfDay: Int): State {
        for (slot in MealSlot.entries) {
            val window = settings.window(slot)
            if (minuteOfDay < window.first) return State.Between(slot, window.first)
            if (minuteOfDay < window.last) return State.Current(slot, window.last)
        }
        val first = MealSlot.entries.first()
        return State.AfterLast(first, settings.window(first).first)
    }

    /** Slots before the current one are done, the current is now, later ones are later. */
    fun statusOf(slot: MealSlot, state: State): MealStatus = when (state) {
        is State.Current -> when {
            slot.ordinal < state.slot.ordinal -> MealStatus.DONE
            slot == state.slot -> MealStatus.NOW
            else -> MealStatus.LATER
        }
        is State.Between -> if (slot.ordinal < state.next.ordinal) MealStatus.DONE else MealStatus.LATER
        is State.AfterLast -> MealStatus.DONE
    }

    /** "6:00", "13:05", "21:00": 24 hour, no leading zero on the hour. */
    fun formatMinute(minute: Int): String = "${minute / 60}:${(minute % 60).toString().padStart(2, '0')}"
}
