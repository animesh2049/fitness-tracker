package com.animesh.fitnesstracker.domain.progression

import com.animesh.fitnesstracker.data.model.Exercise
import com.animesh.fitnesstracker.data.model.ExerciseType
import com.animesh.fitnesstracker.data.model.ProgressionRule
import com.animesh.fitnesstracker.domain.LoggedSet
import com.animesh.fitnesstracker.domain.bySession
import com.animesh.fitnesstracker.util.Weights
import kotlin.math.ceil
import kotlin.math.roundToInt

enum class SuggestionKind { WEIGHT_UP, REPS_UP, TIME_UP, DELOAD, HOLD }

/**
 * A proposed change to the working-set targets of one exercise. [HOLD] carries a reason only
 * and no new targets (shown as a note, never as an accept prompt).
 */
data class Suggestion(
    val kind: SuggestionKind,
    val newReps: Int? = null,
    val newWeightKg: Double? = null,
    val newSeconds: Int? = null,
    val reason: String
) {
    val isActionable: Boolean get() = kind != SuggestionKind.HOLD
}

/** The working-set targets the group currently prescribes for an exercise. */
data class CurrentTargets(val reps: Int?, val weightKg: Double?, val seconds: Int?, val workingSets: Int)

data class ProgressionConfig(val deloadAfterFailures: Int = 3, val deloadPercent: Int = 10)

object ProgressionEngine {

    /**
     * @param history every logged set for this exercise, newest first, any number of sessions.
     * @param fmtWeight formats a kg value for the reason string (unit aware).
     */
    fun suggest(
        exercise: Exercise,
        current: CurrentTargets,
        history: List<LoggedSet>,
        config: ProgressionConfig = ProgressionConfig(),
        fmtWeight: (Double) -> String = { Weights.format(it) + " kg" }
    ): Suggestion? {
        if (exercise.progressionRule == ProgressionRule.NONE) return null
        val sessions = history.bySession()
        val last = sessions.firstOrNull { it.isNotEmpty() } ?: return null

        val failures = sessions.takeWhile { s -> s.isNotEmpty() && !s.all { it.hitTarget } }.size
        if (failures >= config.deloadAfterFailures) {
            return deload(exercise, current, failures, config, fmtWeight)
        }

        val allHit = last.all { it.hitTarget }
        val summary = summarise(last, fmtWeight)
        if (!allHit) {
            val hits = last.count { it.hitTarget }
            return Suggestion(SuggestionKind.HOLD, reason = "$hits of ${last.size} sets hit target last session ($summary). Same targets today.")
        }

        return when (exercise.progressionRule) {
            ProgressionRule.LINEAR_WEIGHT -> linearWeight(exercise, current, last, summary, fmtWeight)
            ProgressionRule.DOUBLE_PROGRESSION -> doubleProgression(exercise, current, last, summary, fmtWeight)
            ProgressionRule.LINEAR_TIME -> linearTime(exercise, current, last, summary)
            ProgressionRule.LINEAR_REPS -> linearReps(exercise, current, last, summary)
            ProgressionRule.NONE -> null
        }
    }

    private fun linearWeight(ex: Exercise, cur: CurrentTargets, last: List<LoggedSet>, summary: String, fmt: (Double) -> String): Suggestion? {
        val base = cur.weightKg ?: return null
        val lastWeight = last.maxOf { it.targetWeightKg ?: 0.0 }
        // The user already raised the prescription by hand past what was done last time: nothing to add.
        if (lastWeight < base - 1e-9) return null
        val next = Weights.roundTo(base + ex.weightIncrementKg, ex.weightIncrementKg)
        return Suggestion(
            SuggestionKind.WEIGHT_UP, newWeightKg = next, newReps = cur.reps,
            reason = "Hit $summary last session. Linear progression adds ${fmt(ex.weightIncrementKg)}."
        )
    }

    private fun doubleProgression(ex: Exercise, cur: CurrentTargets, last: List<LoggedSet>, summary: String, fmt: (Double) -> String): Suggestion? {
        val reps = cur.reps ?: return null
        val weight = cur.weightKg
        val minReps = ex.repRangeMin.coerceAtLeast(1)
        val maxReps = ex.repRangeMax.coerceAtLeast(minReps)
        return if (reps >= maxReps && weight != null) {
            val next = Weights.roundTo(weight + ex.weightIncrementKg, ex.weightIncrementKg)
            Suggestion(
                SuggestionKind.WEIGHT_UP, newWeightKg = next, newReps = minReps,
                reason = "Hit $summary, the top of the $minReps to $maxReps range. Add ${fmt(ex.weightIncrementKg)} and drop back to $minReps reps."
            )
        } else {
            val nextReps = (reps + 1).coerceAtMost(maxReps)
            if (nextReps == reps) return null
            Suggestion(
                SuggestionKind.REPS_UP, newReps = nextReps, newWeightKg = weight,
                reason = "Hit $summary. Double progression in the $minReps to $maxReps range adds a rep."
            )
        }
    }

    private fun linearTime(ex: Exercise, cur: CurrentTargets, last: List<LoggedSet>, summary: String): Suggestion? {
        val base = cur.seconds ?: return null
        val step = ex.timeStepSeconds.coerceAtLeast(5)
        val raw = base + step
        val next = ex.timeMaxSeconds?.let { minOf(raw, it) } ?: raw
        val rounded = (next / 5.0).roundToInt() * 5
        if (rounded <= base) {
            return Suggestion(SuggestionKind.HOLD, reason = "Held $summary. Already at the ${base} s cap for this exercise.")
        }
        return Suggestion(SuggestionKind.TIME_UP, newSeconds = rounded, reason = "Held $summary last session. Adds $step s.")
    }

    private fun linearReps(ex: Exercise, cur: CurrentTargets, last: List<LoggedSet>, summary: String): Suggestion? {
        val base = cur.reps ?: return null
        val step = ex.repStep.coerceAtLeast(1)
        return Suggestion(SuggestionKind.REPS_UP, newReps = base + step, newWeightKg = cur.weightKg, reason = "Hit $summary last session. Adds $step rep${if (step > 1) "s" else ""}.")
    }

    private fun deload(ex: Exercise, cur: CurrentTargets, failures: Int, config: ProgressionConfig, fmt: (Double) -> String): Suggestion? {
        val factor = 1 - config.deloadPercent / 100.0
        return when (ex.type) {
            ExerciseType.TIMED -> {
                val base = cur.seconds ?: return null
                val next = (ceil(base * factor / 5.0) * 5).toInt().coerceAtLeast(5)
                Suggestion(SuggestionKind.DELOAD, newSeconds = next, reason = "Missed the target $failures sessions in a row. Deload ${config.deloadPercent} percent and rebuild.")
            }
            else -> {
                val base = cur.weightKg ?: return null
                if (base <= 0.0) return null
                val next = Weights.roundTo(base * factor, ex.weightIncrementKg).coerceAtLeast(0.0)
                if (next >= base) return null
                Suggestion(
                    SuggestionKind.DELOAD, newWeightKg = next, newReps = cur.reps,
                    reason = "Missed reps at ${fmt(base)} $failures sessions in a row. Deload ${config.deloadPercent} percent and rebuild."
                )
            }
        }
    }

    /** "3 × 8 at 60 kg" or "3 × 45 s" for the reason text. */
    private fun summarise(sets: List<LoggedSet>, fmt: (Double) -> String): String {
        val first = sets.first()
        return if (first.targetSeconds != null) {
            "${sets.size} × ${first.actualSeconds ?: first.targetSeconds} s"
        } else {
            val reps = sets.map { it.actualReps ?: 0 }
            val repsText = if (reps.distinct().size == 1) "${sets.size} × ${reps.first()}" else reps.joinToString(" · ")
            val w = first.actualWeightKg ?: first.targetWeightKg
            if (w != null && w > 0) "$repsText at ${fmt(w)}" else repsText
        }
    }
}
