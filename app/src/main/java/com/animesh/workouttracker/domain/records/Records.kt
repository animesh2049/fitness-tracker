package com.animesh.workouttracker.domain.records

import com.animesh.workouttracker.domain.LoggedSet
import kotlin.math.roundToInt

enum class RecordKind { HEAVIEST_FOR_REPS, MOST_REPS_AT_WEIGHT, BEST_E1RM, LONGEST_HOLD }

data class PersonalRecord(
    val kind: RecordKind,
    val label: String,
    val value: Double,
    val epochDay: Long,
    val reps: Int? = null,
    val weightKg: Double? = null,
    val seconds: Int? = null
)

object Records {
    /** Epley estimated one rep max. Returns the weight itself for 1 rep. */
    fun epley(weightKg: Double, reps: Int): Double = if (reps <= 1) weightKg else weightKg * (1 + reps / 30.0)

    fun bestE1rm(history: List<LoggedSet>): LoggedSet? =
        working(history).filter { (it.actualWeightKg ?: 0.0) > 0 && (it.actualReps ?: 0) > 0 }
            .maxByOrNull { epley(it.actualWeightKg!!, it.actualReps!!) }

    /** Heaviest completed weight for each rep count actually performed, best first. */
    fun heaviestForReps(history: List<LoggedSet>): Map<Int, LoggedSet> =
        working(history).filter { (it.actualWeightKg ?: 0.0) > 0 && (it.actualReps ?: 0) > 0 }
            .groupBy { it.actualReps!! }
            .mapValues { (_, sets) -> sets.maxBy { it.actualWeightKg!! } }

    fun longestHold(history: List<LoggedSet>): LoggedSet? =
        working(history).filter { (it.actualSeconds ?: 0) > 0 }.maxByOrNull { it.actualSeconds!! }

    /** Top-line records for the Progress screen. */
    fun summary(history: List<LoggedSet>, fmtWeight: (Double) -> String): List<PersonalRecord> {
        val out = mutableListOf<PersonalRecord>()
        val hold = longestHold(history)
        if (hold != null) {
            out += PersonalRecord(RecordKind.LONGEST_HOLD, "Longest hold", hold.actualSeconds!!.toDouble(), hold.epochDay, seconds = hold.actualSeconds)
            return out
        }
        val byReps = heaviestForReps(history)
        val topWeight = byReps.values.maxByOrNull { it.actualWeightKg!! }
        if (topWeight != null) {
            out += PersonalRecord(
                RecordKind.HEAVIEST_FOR_REPS, "Heaviest for ${topWeight.actualReps} reps", topWeight.actualWeightKg!!,
                topWeight.epochDay, reps = topWeight.actualReps, weightKg = topWeight.actualWeightKg
            )
        }
        val mostRepsAtTop = working(history).filter { it.actualWeightKg == topWeight?.actualWeightKg }.maxByOrNull { it.actualReps ?: 0 }
        if (mostRepsAtTop != null && topWeight != null && mostRepsAtTop.actualReps != topWeight.actualReps) {
            out += PersonalRecord(
                RecordKind.MOST_REPS_AT_WEIGHT, "Most reps at ${fmtWeight(topWeight.actualWeightKg!!)}", mostRepsAtTop.actualReps!!.toDouble(),
                mostRepsAtTop.epochDay, reps = mostRepsAtTop.actualReps, weightKg = mostRepsAtTop.actualWeightKg
            )
        }
        val e1 = bestE1rm(history)
        if (e1 != null) {
            out += PersonalRecord(RecordKind.BEST_E1RM, "Best estimated 1RM", epley(e1.actualWeightKg!!, e1.actualReps!!).roundToInt().toDouble(), e1.epochDay, reps = e1.actualReps, weightKg = e1.actualWeightKg)
        }
        return out
    }

    /**
     * Which records does [candidate] beat, given [history] (which must not contain the candidate)?
     * Used for the in-session celebration.
     */
    fun beats(history: List<LoggedSet>, candidate: LoggedSet): List<RecordKind> {
        if (candidate.isWarmup || !candidate.completed) return emptyList()
        val prior = working(history)
        // The very first logged sets of an exercise are a baseline, not a record.
        if (prior.isEmpty()) return emptyList()
        val out = mutableListOf<RecordKind>()
        val secs = candidate.actualSeconds
        if (secs != null && secs > 0) {
            val best = longestHold(history)?.actualSeconds ?: 0
            if (secs > best) out += RecordKind.LONGEST_HOLD
            return out
        }
        val w = candidate.actualWeightKg ?: return out
        val r = candidate.actualReps ?: return out
        if (w <= 0 || r <= 0) return out
        // Heaviest for reps: beats every earlier set done for at least as many reps (a rep max).
        val prevAtLeastReps = prior.filter { (it.actualReps ?: 0) >= r }.maxOfOrNull { it.actualWeightKg ?: 0.0 } ?: 0.0
        if (w > prevAtLeastReps + 1e-9) out += RecordKind.HEAVIEST_FOR_REPS
        val prevRepsAtWeight = prior.filter { (it.actualWeightKg ?: 0.0) >= w - 1e-9 }.maxOfOrNull { it.actualReps ?: 0 } ?: 0
        if (r > prevRepsAtWeight && prevRepsAtWeight > 0) out += RecordKind.MOST_REPS_AT_WEIGHT
        val prevE1 = bestE1rm(history)?.let { epley(it.actualWeightKg!!, it.actualReps!!) } ?: 0.0
        if (epley(w, r) > prevE1 + 1e-9) out += RecordKind.BEST_E1RM
        return out
    }

    private fun working(history: List<LoggedSet>) = history.filter { it.completed && !it.isWarmup }
}
