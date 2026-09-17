package com.animesh.fitnesstracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.animesh.fitnesstracker.data.model.DayLog
import com.animesh.fitnesstracker.data.model.Session
import com.animesh.fitnesstracker.data.model.SessionExercise
import com.animesh.fitnesstracker.data.model.SessionSet
import com.animesh.fitnesstracker.data.model.SessionStatus
import com.animesh.fitnesstracker.data.model.SessionWithExercises
import androidx.room.OnConflictStrategy
import kotlinx.coroutines.flow.Flow

/** A logged set joined with the date of its session, for progression and charts. */
data class DatedSet(
    val sessionId: Long,
    val epochDay: Long,
    val startedAt: Long,
    val exerciseId: Long,
    val position: Int,
    val targetReps: Int?,
    val targetWeightKg: Double?,
    val targetSeconds: Int?,
    val actualReps: Int?,
    val actualWeightKg: Double?,
    val actualSeconds: Int?,
    val isWarmup: Boolean,
    val completed: Boolean
) {
    fun toSessionSet(): SessionSet = SessionSet(
        sessionExerciseId = 0, position = position,
        targetReps = targetReps, targetWeightKg = targetWeightKg, targetSeconds = targetSeconds,
        actualReps = actualReps, actualWeightKg = actualWeightKg, actualSeconds = actualSeconds,
        isWarmup = isWarmup, completed = completed
    )
}

@Dao
interface SessionDao {
    @Transaction
    @Query("SELECT * FROM sessions WHERE status = 'IN_PROGRESS' ORDER BY startedAt DESC LIMIT 1")
    fun observeInProgress(): Flow<SessionWithExercises?>

    @Transaction
    @Query("SELECT * FROM sessions WHERE status = 'IN_PROGRESS' ORDER BY startedAt DESC LIMIT 1")
    suspend fun getInProgress(): SessionWithExercises?

    @Transaction
    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeSession(id: Long): Flow<SessionWithExercises?>

    @Transaction
    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSession(id: Long): SessionWithExercises?

    @Transaction
    @Query("SELECT * FROM sessions WHERE epochDay BETWEEN :fromDay AND :toDay AND status != 'IN_PROGRESS' ORDER BY startedAt DESC")
    fun observeSessionsBetween(fromDay: Long, toDay: Long): Flow<List<SessionWithExercises>>

    @Transaction
    @Query("SELECT * FROM sessions WHERE status != 'IN_PROGRESS' ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SessionWithExercises>>

    @Query("SELECT * FROM sessions")
    suspend fun getAllSessions(): List<Session>

    /** Finished sessions whose [startedAt, endedAt] (millis) intersects the range, for linking watch activities. */
    @Query(
        "SELECT * FROM sessions WHERE status = 'COMPLETED' AND endedAt IS NOT NULL " +
            "AND startedAt <= :toMillis AND endedAt >= :fromMillis ORDER BY startedAt"
    )
    suspend fun getCompletedOverlapping(fromMillis: Long, toMillis: Long): List<Session>

    @Query("SELECT * FROM session_exercises")
    suspend fun getAllSessionExercises(): List<SessionExercise>

    @Query("SELECT * FROM session_sets")
    suspend fun getAllSessionSets(): List<SessionSet>

    @Query("SELECT * FROM session_sets WHERE sessionExerciseId = :sessionExerciseId ORDER BY position")
    suspend fun setsForSessionExercise(sessionExerciseId: Long): List<SessionSet>

    @Query(
        """
        SELECT s.id AS sessionId, s.epochDay, s.startedAt, se.exerciseId, ss.position,
               ss.targetReps, ss.targetWeightKg, ss.targetSeconds,
               ss.actualReps, ss.actualWeightKg, ss.actualSeconds, ss.isWarmup, ss.completed
        FROM session_sets ss
        JOIN session_exercises se ON se.id = ss.sessionExerciseId
        JOIN sessions s ON s.id = se.sessionId
        WHERE se.exerciseId = :exerciseId AND s.status = 'COMPLETED' AND se.skipped = 0
        ORDER BY s.startedAt DESC, ss.position ASC
        """
    )
    suspend fun setsForExercise(exerciseId: Long): List<DatedSet>

    @Query(
        """
        SELECT s.id AS sessionId, s.epochDay, s.startedAt, se.exerciseId, ss.position,
               ss.targetReps, ss.targetWeightKg, ss.targetSeconds,
               ss.actualReps, ss.actualWeightKg, ss.actualSeconds, ss.isWarmup, ss.completed
        FROM session_sets ss
        JOIN session_exercises se ON se.id = ss.sessionExerciseId
        JOIN sessions s ON s.id = se.sessionId
        WHERE se.exerciseId = :exerciseId AND s.status = 'COMPLETED' AND se.skipped = 0
        ORDER BY s.startedAt DESC, ss.position ASC
        """
    )
    fun observeSetsForExercise(exerciseId: Long): Flow<List<DatedSet>>

    @Query("SELECT DISTINCT se.exerciseId FROM session_exercises se JOIN sessions s ON s.id = se.sessionId WHERE s.status = 'COMPLETED'")
    fun observeExercisesWithHistory(): Flow<List<Long>>

    @Insert
    suspend fun insertSession(session: Session): Long

    @Update
    suspend fun updateSession(session: Session)

    @Query("UPDATE sessions SET status = :status, endedAt = :endedAt WHERE id = :id")
    suspend fun setStatus(id: Long, status: SessionStatus, endedAt: Long?)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Insert
    suspend fun insertSessionExercise(exercise: SessionExercise): Long

    @Update
    suspend fun updateSessionExercise(exercise: SessionExercise)

    @Query("DELETE FROM session_exercises WHERE id = :id")
    suspend fun deleteSessionExercise(id: Long)

    @Insert
    suspend fun insertSet(set: SessionSet): Long

    @Insert
    suspend fun insertSets(sets: List<SessionSet>)

    @Update
    suspend fun updateSet(set: SessionSet)

    @Query("DELETE FROM session_sets WHERE id = :id")
    suspend fun deleteSet(id: Long)

    @Query("SELECT * FROM day_logs WHERE epochDay BETWEEN :fromDay AND :toDay ORDER BY epochDay")
    fun observeDayLogsBetween(fromDay: Long, toDay: Long): Flow<List<DayLog>>

    @Query("SELECT * FROM day_logs")
    suspend fun getAllDayLogs(): List<DayLog>

    @Insert
    suspend fun insertDayLog(log: DayLog): Long

    @Query("DELETE FROM day_logs WHERE epochDay = :epochDay")
    suspend fun deleteDayLogsOn(epochDay: Long)

    // Backup support (appended for Milestone 5/6).
    @Transaction
    @Query("SELECT * FROM sessions WHERE status != 'IN_PROGRESS' ORDER BY startedAt ASC")
    suspend fun getFinishedSessions(): List<SessionWithExercises>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessionsReplace(sessions: List<Session>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSessionsIgnore(sessions: List<Session>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessionExercisesReplace(exercises: List<SessionExercise>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSessionExercisesIgnore(exercises: List<SessionExercise>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSetsReplace(sets: List<SessionSet>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSetsIgnore(sets: List<SessionSet>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDayLogsReplace(logs: List<DayLog>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDayLogsIgnore(logs: List<DayLog>): List<Long>

    @Query("DELETE FROM sessions")
    suspend fun deleteAllSessions()

    @Query("DELETE FROM session_exercises")
    suspend fun deleteAllSessionExercises()

    @Query("DELETE FROM session_sets")
    suspend fun deleteAllSets()

    @Query("DELETE FROM day_logs")
    suspend fun deleteAllDayLogs()
}
