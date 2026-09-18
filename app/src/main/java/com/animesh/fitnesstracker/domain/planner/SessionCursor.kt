package com.animesh.fitnesstracker.domain.planner

import com.animesh.fitnesstracker.data.model.SessionExerciseWithSets
import com.animesh.fitnesstracker.data.model.SessionSet

/** What comes after the current step of the exercise the screen is looking at. */
sealed class UpNext {
    /** The next unlogged set of the same exercise. */
    data class NextSet(val step: Step) : UpNext()
    /** The next unlogged set of another member of the same superset chain. */
    data class Partner(val exerciseIndex: Int, val step: Step) : UpNext()
    /** The next unlogged set of a different exercise (or the earliest pending one when nothing follows). */
    data class NextExercise(val exerciseIndex: Int, val step: Step) : UpNext()
    /** Every set of the session is logged or skipped. */
    data object Finish : UpNext()

    /** Index of the exercise this points at, or null for [Finish]. */
    val exerciseIndexOrNull: Int?
        get() = when (this) {
            is NextSet -> step.exerciseIndex
            is Partner -> exerciseIndex
            is NextExercise -> exerciseIndex
            Finish -> null
        }
}

/**
 * The exercise the session screen is looking at. [SessionFlow.steps] stays the canonical order of
 * sets (it encodes supersets and rest rules); the cursor only decides which exercise is on screen,
 * so the user can move back to a skipped or finished exercise. Indices are into the exercises
 * sorted by position, the same order [SessionFlow.steps] uses.
 */
object SessionCursor {
    /**
     * Index of the exercise to show: the explicit cursor when that row still exists, else the
     * exercise of the first unlogged step, else the last exercise. Returns -1 for an empty session.
     */
    fun resolve(exercises: List<SessionExerciseWithSets>, steps: List<Step>, cursorId: Long?): Int {
        val sorted = exercises.sortedBy { it.exercise.position }
        if (cursorId != null) {
            val explicit = sorted.indexOfFirst { it.exercise.id == cursorId }
            if (explicit >= 0) return explicit
        }
        return SessionFlow.current(steps)?.exerciseIndex ?: (sorted.size - 1)
    }

    /** The exercise's first unlogged set, or null when it is complete or skipped (skipped rows have no steps). */
    fun currentStep(steps: List<Step>, exerciseIndex: Int): Step? =
        steps.firstOrNull { it.exerciseIndex == exerciseIndex && !it.set.completed }

    /** Moves by [delta] exercises, clamped to the ends. Skipped and finished exercises are visited too. */
    fun neighbour(count: Int, index: Int, delta: Int): Int =
        if (count <= 0) -1 else (index + delta).coerceIn(0, count - 1)

    /**
     * The exercise the flow continues with after [justLoggedStep]: the exercise of the next unlogged
     * step in flow order (so a superset keeps alternating A1 B1 A2 B2), or the same exercise when
     * nothing follows.
     */
    fun afterDoneSet(steps: List<Step>, justLoggedStep: Step): Int =
        SessionFlow.next(steps, justLoggedStep)?.exerciseIndex ?: justLoggedStep.exerciseIndex

    /** Describes what comes after the current step of the exercise at [exerciseIndex]. */
    fun upNext(exercises: List<SessionExerciseWithSets>, steps: List<Step>, exerciseIndex: Int): UpNext {
        val cur = currentStep(steps, exerciseIndex)
        val following = if (cur != null) SessionFlow.next(steps, cur)
        else steps.firstOrNull { it.exerciseIndex > exerciseIndex && !it.set.completed }
        val target = following
            ?: steps.firstOrNull { !it.set.completed && it != cur }
            ?: return UpNext.Finish
        return when {
            target.exerciseIndex == exerciseIndex -> UpNext.NextSet(target)
            sameChain(exercises, exerciseIndex, target.exerciseIndex) -> UpNext.Partner(target.exerciseIndex, target)
            else -> UpNext.NextExercise(target.exerciseIndex, target)
        }
    }

    /** Text for [upNext]: "Set n · target" on the same exercise, "<name> · Set n" elsewhere, or "Finish session". */
    fun upNextText(u: UpNext, exercises: List<SessionExerciseWithSets>, setText: (SessionSet) -> String): String {
        val sorted = exercises.sortedBy { it.exercise.position }
        fun name(i: Int) = sorted.getOrNull(i)?.exercise?.exerciseName ?: ""
        return when (u) {
            is UpNext.NextSet -> "Set ${u.step.setIndex + 1} · ${setText(u.step.set)}"
            is UpNext.Partner -> "${name(u.exerciseIndex)} · Set ${u.step.setIndex + 1}"
            is UpNext.NextExercise -> "${name(u.exerciseIndex)} · Set ${u.step.setIndex + 1}"
            UpNext.Finish -> "Finish session"
        }
    }

    /**
     * The step a running timer belongs to, looked up by its tag (a session set id) so it is found
     * even after the user navigated to another exercise. Falls back to the raw rows for sets that
     * are not in the flow any more (their exercise was skipped meanwhile).
     */
    fun stepForSet(exercises: List<SessionExerciseWithSets>, steps: List<Step>, setId: Long): Step? {
        steps.firstOrNull { it.set.id == setId }?.let { return it }
        val sorted = exercises.sortedBy { it.exercise.position }
        sorted.forEachIndexed { i, ex ->
            val sets = ex.sortedSets
            val k = sets.indexOfFirst { it.id == setId }
            if (k >= 0) return Step(i, k, sets[k], restAfter = true)
        }
        return null
    }

    private fun sameChain(exercises: List<SessionExerciseWithSets>, a: Int, b: Int): Boolean {
        if (a == b) return true
        val sorted = exercises.sortedBy { it.exercise.position }
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        if (lo < 0 || hi >= sorted.size) return false
        return (lo until hi).all { sorted[it].exercise.supersetWithNext }
    }
}
