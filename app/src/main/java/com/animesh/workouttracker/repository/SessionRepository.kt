package com.animesh.workouttracker.repository

import androidx.room.withTransaction
import com.animesh.workouttracker.data.AppDatabase
import com.animesh.workouttracker.data.dao.DatedSet
import com.animesh.workouttracker.data.model.DayLog
import com.animesh.workouttracker.data.model.DayLogKind
import com.animesh.workouttracker.data.model.Exercise
import com.animesh.workouttracker.data.model.ExerciseType
import com.animesh.workouttracker.data.model.Session
import com.animesh.workouttracker.data.model.SessionExercise
import com.animesh.workouttracker.data.model.SessionSet
import com.animesh.workouttracker.data.model.SessionStatus
import com.animesh.workouttracker.data.model.SessionWithExercises
import com.animesh.workouttracker.util.Dates
import kotlinx.coroutines.flow.Flow

/** What a session is started from: a snapshot of targets with any accepted suggestions already applied. */
data class PlannedExercise(
    val exercise: Exercise,
    val groupExerciseId: Long?,
    val supersetWithNext: Boolean,
    val restSeconds: Int,
    val sets: List<PlannedSet>
)

data class PlannedSet(
    val targetReps: Int? = null,
    val targetWeightKg: Double? = null,
    val targetSeconds: Int? = null,
    val isWarmup: Boolean = false
)

class SessionRepository(private val db: AppDatabase) {
    private val dao get() = db.sessionDao()

    fun observeInProgress(): Flow<SessionWithExercises?> = dao.observeInProgress()
    suspend fun getInProgress(): SessionWithExercises? = dao.getInProgress()
    fun observeSession(id: Long): Flow<SessionWithExercises?> = dao.observeSession(id)
    suspend fun getSession(id: Long): SessionWithExercises? = dao.getSession(id)
    fun observeBetween(fromDay: Long, toDay: Long): Flow<List<SessionWithExercises>> = dao.observeSessionsBetween(fromDay, toDay)
    fun observeRecent(limit: Int = 20): Flow<List<SessionWithExercises>> = dao.observeRecent(limit)
    fun observeDayLogsBetween(fromDay: Long, toDay: Long): Flow<List<DayLog>> = dao.observeDayLogsBetween(fromDay, toDay)
    suspend fun setsForExercise(exerciseId: Long): List<DatedSet> = dao.setsForExercise(exerciseId)
    fun observeSetsForExercise(exerciseId: Long): Flow<List<DatedSet>> = dao.observeSetsForExercise(exerciseId)
    fun observeExercisesWithHistory(): Flow<List<Long>> = dao.observeExercisesWithHistory()

    suspend fun start(groupId: Long?, groupName: String, routineId: Long?, plan: List<PlannedExercise>): Long = db.withTransaction {
        val now = System.currentTimeMillis()
        val sessionId = dao.insertSession(
            Session(groupId = groupId, groupName = groupName, routineId = routineId, epochDay = Dates.epochDayOf(now), startedAt = now)
        )
        plan.forEachIndexed { index, p ->
            val seId = dao.insertSessionExercise(
                SessionExercise(
                    sessionId = sessionId, exerciseId = p.exercise.id, exerciseName = p.exercise.name,
                    exerciseType = p.exercise.type, groupExerciseId = p.groupExerciseId, position = index,
                    supersetWithNext = p.supersetWithNext, restSeconds = p.restSeconds
                )
            )
            dao.insertSets(
                p.sets.mapIndexed { i, s ->
                    SessionSet(
                        sessionExerciseId = seId, position = i,
                        targetReps = s.targetReps, targetWeightKg = s.targetWeightKg, targetSeconds = s.targetSeconds,
                        actualReps = s.targetReps, actualWeightKg = s.targetWeightKg, actualSeconds = s.targetSeconds,
                        isWarmup = s.isWarmup
                    )
                }
            )
        }
        sessionId
    }

    /** Writes the set as done with its actual values. Called the moment the user taps Done. */
    suspend fun completeSet(set: SessionSet, reps: Int?, weightKg: Double?, seconds: Int?, rpe: Int? = null) =
        dao.updateSet(
            set.copy(actualReps = reps, actualWeightKg = weightKg, actualSeconds = seconds, rpe = rpe, completed = true, completedAt = System.currentTimeMillis())
        )

    suspend fun updateSet(set: SessionSet) = dao.updateSet(set)

    suspend fun uncompleteSet(set: SessionSet) = dao.updateSet(set.copy(completed = false, completedAt = null))

    suspend fun addSet(sessionExerciseId: Long, template: SessionSet?, exerciseType: ExerciseType): Long {
        val sets = dao.setsForSessionExercise(sessionExerciseId)
        val position = sets.size
        val base = template ?: sets.lastOrNull()
        val newSet = when {
            base != null -> base.copy(id = 0, position = position, completed = false, completedAt = null, rpe = null, isWarmup = false,
                actualReps = base.targetReps, actualWeightKg = base.targetWeightKg, actualSeconds = base.targetSeconds)
            exerciseType == ExerciseType.TIMED -> SessionSet(sessionExerciseId = sessionExerciseId, position = position, targetSeconds = 30, actualSeconds = 30)
            else -> SessionSet(sessionExerciseId = sessionExerciseId, position = position, targetReps = 10, actualReps = 10, targetWeightKg = 0.0, actualWeightKg = 0.0)
        }
        return dao.insertSet(newSet)
    }

    suspend fun removeSet(set: SessionSet) = dao.deleteSet(set.id)

    suspend fun setExerciseSkipped(exercise: SessionExercise, skipped: Boolean) = dao.updateSessionExercise(exercise.copy(skipped = skipped))
    suspend fun setExerciseNotes(exercise: SessionExercise, notes: String) = dao.updateSessionExercise(exercise.copy(notes = notes))

    suspend fun addExercise(sessionId: Long, exercise: Exercise, restSeconds: Int, sets: List<PlannedSet>): Long = db.withTransaction {
        val count = dao.getSession(sessionId)?.exercises?.size ?: 0
        val seId = dao.insertSessionExercise(
            SessionExercise(
                sessionId = sessionId, exerciseId = exercise.id, exerciseName = exercise.name, exerciseType = exercise.type,
                groupExerciseId = null, position = count, restSeconds = restSeconds
            )
        )
        dao.insertSets(
            sets.mapIndexed { i, s ->
                SessionSet(
                    sessionExerciseId = seId, position = i, targetReps = s.targetReps, targetWeightKg = s.targetWeightKg,
                    targetSeconds = s.targetSeconds, actualReps = s.targetReps, actualWeightKg = s.targetWeightKg,
                    actualSeconds = s.targetSeconds, isWarmup = s.isWarmup
                )
            }
        )
        seId
    }

    /**
     * Marks the session complete. When [writeBackTargets] is true the session's target snapshot
     * (which already includes accepted suggestions) becomes the group's prescription.
     */
    suspend fun finish(sessionId: Long, notes: String, writeBackTargets: Boolean) = db.withTransaction {
        val s = dao.getSession(sessionId) ?: return@withTransaction
        dao.updateSession(s.session.copy(status = SessionStatus.COMPLETED, endedAt = System.currentTimeMillis(), notes = notes))
        if (writeBackTargets) {
            val groupDao = db.groupDao()
            s.exercises.forEach { se ->
                val geId = se.exercise.groupExerciseId ?: return@forEach
                val prescriptions = groupDao.prescriptionsFor(geId)
                se.sortedSets.forEach { set ->
                    val p = prescriptions.getOrNull(set.position) ?: return@forEach
                    if (p.isWarmup) return@forEach
                    val updated = p.copy(targetReps = set.targetReps, targetWeightKg = set.targetWeightKg, targetSeconds = set.targetSeconds)
                    if (updated != p) groupDao.updatePrescription(updated)
                }
            }
        }
    }

    suspend fun abandon(sessionId: Long) = dao.setStatus(sessionId, SessionStatus.ABANDONED, System.currentTimeMillis())
    suspend fun updateSession(session: Session) = dao.updateSession(session)
    suspend fun delete(sessionId: Long) = dao.deleteSession(sessionId)

    /** Removes the rest or skipped record for a day. Does not touch the routine's cycle position. */
    suspend fun clearDayLog(epochDay: Long) = dao.deleteDayLogsOn(epochDay)

    suspend fun logDay(epochDay: Long, kind: DayLogKind, groupName: String) = db.withTransaction {
        dao.deleteDayLogsOn(epochDay)
        dao.insertDayLog(DayLog(epochDay = epochDay, kind = kind, groupName = groupName))
    }
}
