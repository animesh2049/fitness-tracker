package com.animesh.fitnesstracker.domain.planner

import com.animesh.fitnesstracker.data.model.SessionExerciseWithSets
import com.animesh.fitnesstracker.data.model.SessionSet

/** One thing to do next in a session: a specific set of a specific exercise. */
data class Step(
    val exerciseIndex: Int,
    val setIndex: Int,
    val set: SessionSet,
    /** False inside a superset round until the round's last exercise. */
    val restAfter: Boolean
)

/**
 * Orders the sets of a session. Plain exercises go set by set. A superset chain (each exercise
 * flagged supersetWithNext up to the last) alternates: A1 B1 A2 B2 ..., resting only after the
 * last exercise of each round. Skipped exercises are left out.
 */
object SessionFlow {
    fun steps(exercises: List<SessionExerciseWithSets>): List<Step> {
        val out = mutableListOf<Step>()
        var i = 0
        val sorted = exercises.sortedBy { it.exercise.position }
        while (i < sorted.size) {
            // Collect the chain starting at i.
            var end = i
            while (end < sorted.size - 1 && sorted[end].exercise.supersetWithNext) end++
            val chain = (i..end).filter { !sorted[it].exercise.skipped }
            if (chain.isEmpty()) { i = end + 1; continue }
            if (chain.size == 1) {
                val idx = chain[0]
                sorted[idx].sortedSets.forEachIndexed { s, set -> out += Step(idx, s, set, restAfter = true) }
            } else {
                val rounds = chain.maxOf { sorted[it].sets.size }
                for (round in 0 until rounds) {
                    val inRound = chain.filter { round < sorted[it].sets.size }
                    inRound.forEachIndexed { k, idx ->
                        out += Step(idx, round, sorted[idx].sortedSets[round], restAfter = k == inRound.size - 1)
                    }
                }
            }
            i = end + 1
        }
        return out
    }

    /** The first step whose set is not completed, or null when the session is done. */
    fun current(steps: List<Step>): Step? = steps.firstOrNull { !it.set.completed }

    fun next(steps: List<Step>, after: Step): Step? =
        steps.dropWhile { !(it.exerciseIndex == after.exerciseIndex && it.setIndex == after.setIndex) }.drop(1).firstOrNull { !it.set.completed }
}
