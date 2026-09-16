package com.animesh.fitnesstracker.domain.cycle

import com.animesh.fitnesstracker.data.model.Routine

/** The mutable part of a routine that the cycle engine works on. */
data class CycleState(
    val position: Int = 0,
    val lastAdvancedEpochDay: Long? = null,
    val overrideGroupId: Long? = null,
    val overrideEpochDay: Long? = null,
    val restLoggedEpochDay: Long? = null
) {
    companion object {
        fun of(r: Routine) = CycleState(r.position, r.lastAdvancedEpochDay, r.overrideGroupId, r.overrideEpochDay, r.restLoggedEpochDay)
    }

    fun applyTo(r: Routine): Routine = r.copy(
        position = position, lastAdvancedEpochDay = lastAdvancedEpochDay,
        overrideGroupId = overrideGroupId, overrideEpochDay = overrideEpochDay, restLoggedEpochDay = restLoggedEpochDay
    )
}

sealed class TodaySlot {
    /** A workout is due. [swapped] when the user picked a different group for today. */
    data class Workout(val groupId: Long, val position: Int, val swapped: Boolean) : TodaySlot()
    /** A rest slot is scheduled ([logged] = false) or the user logged rest on a workout day ([logged] = true). */
    data class Rest(val position: Int, val logged: Boolean) : TodaySlot()
    data object Empty : TodaySlot()
}

/**
 * Rules (requirements 4.4): the position advances only on session completion or explicit skip.
 * "Rest today" logs rest without advancing. Swap overrides today's group only. A scheduled rest
 * slot advances by itself on the next calendar day.
 */
object CycleEngine {

    /** Normalises the state for [today] (auto-advancing past rest slots left behind by the calendar). */
    fun normalise(slots: List<Long?>, state: CycleState, today: Long): CycleState {
        if (slots.isEmpty()) return state
        var s = state.copy(position = state.position.coerceIn(0, slots.size - 1))
        // Auto-advance a scheduled rest slot once a day has passed since we landed on it.
        var guard = 0
        while (slots[s.position] == null && s.lastAdvancedEpochDay != null && today > s.lastAdvancedEpochDay!! && guard < slots.size) {
            val daysToUse = today - s.lastAdvancedEpochDay!!
            s = s.copy(position = (s.position + 1) % slots.size, lastAdvancedEpochDay = s.lastAdvancedEpochDay!! + 1)
            if (daysToUse <= 1) break
            guard++
        }
        if (slots[s.position] == null && s.lastAdvancedEpochDay == null) {
            s = s.copy(lastAdvancedEpochDay = today)
        }
        // Expire overrides and rest logs from other days.
        if (s.overrideEpochDay != null && s.overrideEpochDay != today) s = s.copy(overrideGroupId = null, overrideEpochDay = null)
        if (s.restLoggedEpochDay != null && s.restLoggedEpochDay != today) s = s.copy(restLoggedEpochDay = null)
        return s
    }

    fun today(slots: List<Long?>, state: CycleState, today: Long): TodaySlot {
        if (slots.isEmpty()) return TodaySlot.Empty
        val s = normalise(slots, state, today)
        if (s.overrideGroupId != null && s.overrideEpochDay == today) return TodaySlot.Workout(s.overrideGroupId, s.position, swapped = true)
        if (s.restLoggedEpochDay == today) return TodaySlot.Rest(s.position, logged = true)
        val group = slots[s.position]
        return if (group == null) TodaySlot.Rest(s.position, logged = false) else TodaySlot.Workout(group, s.position, swapped = false)
    }

    /** Session completed, skipped, or "Done resting": move to the next slot. */
    fun advance(slots: List<Long?>, state: CycleState, today: Long): CycleState {
        if (slots.isEmpty()) return state
        val s = normalise(slots, state, today)
        return s.copy(
            position = (s.position + 1) % slots.size,
            lastAdvancedEpochDay = today,
            overrideGroupId = null, overrideEpochDay = null, restLoggedEpochDay = null
        )
    }

    fun skip(slots: List<Long?>, state: CycleState, today: Long): CycleState = advance(slots, state, today)

    fun restToday(slots: List<Long?>, state: CycleState, today: Long): CycleState =
        normalise(slots, state, today).copy(restLoggedEpochDay = today, overrideGroupId = null, overrideEpochDay = null)

    fun swap(slots: List<Long?>, state: CycleState, groupId: Long, today: Long): CycleState =
        normalise(slots, state, today).copy(overrideGroupId = groupId, overrideEpochDay = today, restLoggedEpochDay = null)

    fun cancelSwap(slots: List<Long?>, state: CycleState, today: Long): CycleState =
        normalise(slots, state, today).copy(overrideGroupId = null, overrideEpochDay = null)

    fun setPosition(slots: List<Long?>, state: CycleState, position: Int, today: Long): CycleState {
        if (slots.isEmpty()) return state
        return normalise(slots, state, today).copy(
            position = position.coerceIn(0, slots.size - 1), lastAdvancedEpochDay = today,
            overrideGroupId = null, overrideEpochDay = null, restLoggedEpochDay = null
        )
    }

    /** The next [count] slots starting from today's, ignoring any swap override. */
    fun upcoming(slots: List<Long?>, state: CycleState, today: Long, count: Int = 7): List<Long?> {
        if (slots.isEmpty()) return emptyList()
        val s = normalise(slots, state, today)
        return List(count) { slots[(s.position + it) % slots.size] }
    }
}
