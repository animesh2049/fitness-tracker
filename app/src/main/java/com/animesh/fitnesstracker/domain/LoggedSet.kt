package com.animesh.fitnesstracker.domain

import com.animesh.fitnesstracker.data.dao.DatedSet
import com.animesh.fitnesstracker.data.model.SessionSet

/** A set as the domain engines see it: what was planned, what happened, and when. */
data class LoggedSet(
    val epochDay: Long,
    val sessionId: Long = 0,
    val targetReps: Int? = null,
    val targetWeightKg: Double? = null,
    val targetSeconds: Int? = null,
    val actualReps: Int? = null,
    val actualWeightKg: Double? = null,
    val actualSeconds: Int? = null,
    val isWarmup: Boolean = false,
    val completed: Boolean = true
) {
    val hitTarget: Boolean
        get() = completed && when {
            targetSeconds != null -> (actualSeconds ?: 0) >= targetSeconds
            targetReps != null -> (actualReps ?: 0) >= targetReps &&
                (targetWeightKg == null || (actualWeightKg ?: 0.0) >= targetWeightKg - 1e-9)
            else -> true
        }

    companion object {
        fun from(d: DatedSet) = LoggedSet(
            epochDay = d.epochDay, sessionId = d.sessionId,
            targetReps = d.targetReps, targetWeightKg = d.targetWeightKg, targetSeconds = d.targetSeconds,
            actualReps = d.actualReps, actualWeightKg = d.actualWeightKg, actualSeconds = d.actualSeconds,
            isWarmup = d.isWarmup, completed = d.completed
        )

        fun from(s: SessionSet, epochDay: Long, sessionId: Long) = LoggedSet(
            epochDay = epochDay, sessionId = sessionId,
            targetReps = s.targetReps, targetWeightKg = s.targetWeightKg, targetSeconds = s.targetSeconds,
            actualReps = s.actualReps, actualWeightKg = s.actualWeightKg, actualSeconds = s.actualSeconds,
            isWarmup = s.isWarmup, completed = s.completed
        )
    }
}

/** Groups a flat, newest-first list of sets into sessions (newest first), working sets only. */
fun List<LoggedSet>.bySession(): List<List<LoggedSet>> =
    filter { !it.isWarmup }
        .groupBy { it.sessionId to it.epochDay }
        .entries
        .sortedByDescending { it.key.second }
        .map { it.value }
