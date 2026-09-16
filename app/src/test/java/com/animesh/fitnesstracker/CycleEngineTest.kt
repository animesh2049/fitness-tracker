package com.animesh.fitnesstracker

import com.animesh.fitnesstracker.domain.cycle.CycleEngine
import com.animesh.fitnesstracker.domain.cycle.CycleState
import com.animesh.fitnesstracker.domain.cycle.TodaySlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CycleEngineTest {
    private val slots: List<Long?> = listOf(1L, 2L, 3L, null) // Push, Pull, Legs, Rest
    private val day = 20_000L

    @Test
    fun todayIsTheSlotAtPosition() {
        assertEquals(TodaySlot.Workout(1L, 0, false), CycleEngine.today(slots, CycleState(), day))
        assertEquals(TodaySlot.Rest(3, false), CycleEngine.today(slots, CycleState(position = 3, lastAdvancedEpochDay = day), day))
    }

    @Test
    fun advanceWrapsAndDoesNotDependOnCalendar() {
        var s = CycleState()
        s = CycleEngine.advance(slots, s, day)
        assertEquals(1, s.position)
        // Five days later, still on Pull day: a missed day never loses a workout.
        assertEquals(TodaySlot.Workout(2L, 1, false), CycleEngine.today(slots, s, day + 5))
        s = CycleEngine.advance(slots, s, day + 5)
        s = CycleEngine.advance(slots, s, day + 5)
        s = CycleEngine.advance(slots, s, day + 5)
        assertEquals(0, s.position)
    }

    @Test
    fun restTodayDoesNotAdvance() {
        val s = CycleEngine.restToday(slots, CycleState(), day)
        assertEquals(TodaySlot.Rest(0, true), CycleEngine.today(slots, s, day))
        // Next day the workout is back.
        assertEquals(TodaySlot.Workout(1L, 0, false), CycleEngine.today(slots, s, day + 1))
    }

    @Test
    fun skipMovesOn() {
        val s = CycleEngine.skip(slots, CycleState(), day)
        assertEquals(TodaySlot.Workout(2L, 1, false), CycleEngine.today(slots, s, day))
    }

    @Test
    fun swapOverridesTodayOnlyAndCycleContinuesFromScheduledSlot() {
        var s = CycleEngine.swap(slots, CycleState(), 3L, day)
        assertEquals(TodaySlot.Workout(3L, 0, true), CycleEngine.today(slots, s, day))
        // Finishing the swapped session advances from the scheduled slot (Push -> Pull).
        s = CycleEngine.advance(slots, s, day)
        assertEquals(TodaySlot.Workout(2L, 1, false), CycleEngine.today(slots, s, day))
        // An unused override expires the next day.
        val stale = CycleEngine.swap(slots, CycleState(), 3L, day)
        assertEquals(TodaySlot.Workout(1L, 0, false), CycleEngine.today(slots, stale, day + 1))
    }

    @Test
    fun scheduledRestAdvancesByItselfNextDay() {
        val onRest = CycleEngine.advance(slots, CycleState(position = 2), day) // land on Rest today
        assertEquals(TodaySlot.Rest(3, false), CycleEngine.today(slots, onRest, day))
        assertEquals(TodaySlot.Workout(1L, 0, false), CycleEngine.today(slots, onRest, day + 1))
        assertEquals(TodaySlot.Workout(1L, 0, false), CycleEngine.today(slots, onRest, day + 3))
    }

    @Test
    fun doneRestingAdvancesImmediately() {
        val onRest = CycleEngine.advance(slots, CycleState(position = 2), day)
        val after = CycleEngine.advance(slots, onRest, day)
        assertEquals(TodaySlot.Workout(1L, 0, false), CycleEngine.today(slots, after, day))
    }

    @Test
    fun upcomingListsFromToday() {
        assertEquals(listOf(1L, 2L, 3L, null, 1L, 2L, 3L), CycleEngine.upcoming(slots, CycleState(), day))
    }

    @Test
    fun emptyRoutineIsSafe() {
        assertEquals(TodaySlot.Empty, CycleEngine.today(emptyList(), CycleState(), day))
        assertTrue(CycleEngine.upcoming(emptyList(), CycleState(), day).isEmpty())
    }

    @Test
    fun setPositionClampsAndClearsOverrides() {
        val s = CycleEngine.setPosition(slots, CycleEngine.swap(slots, CycleState(), 3L, day), 9, day)
        assertEquals(3, s.position)
        assertEquals(null, s.overrideGroupId)
    }
}
