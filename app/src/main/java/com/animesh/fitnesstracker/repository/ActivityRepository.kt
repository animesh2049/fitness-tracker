package com.animesh.fitnesstracker.repository

import androidx.room.withTransaction
import com.animesh.fitnesstracker.data.AppDatabase
import com.animesh.fitnesstracker.data.model.Activity
import com.animesh.fitnesstracker.data.model.ActivityWithDetails
import com.animesh.fitnesstracker.domain.health.SessionMatcher
import com.animesh.fitnesstracker.util.Dates
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow

class ActivityRepository(private val db: AppDatabase, private val zone: ZoneId = ZoneId.systemDefault()) {
    private val dao get() = db.activityDao()

    fun observeAll(): Flow<List<Activity>> = dao.observeAll()

    /** Activities that started in the local calendar month, newest first. */
    fun observeMonth(month: YearMonth): Flow<List<Activity>> {
        val start = month.atDay(1).atStartOfDay(zone).toEpochSecond()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toEpochSecond()
        return dao.observeStartingBetween(start, end)
    }

    /** Activities that started on the local day, newest first. */
    fun observeDay(epochDay: Long): Flow<List<Activity>> =
        dao.observeStartingBetween(Dates.dayStartSeconds(epochDay, zone), Dates.dayEndSeconds(epochDay, zone))

    fun observeActivity(id: Long): Flow<ActivityWithDetails?> = dao.observeWithDetails(id)
    suspend fun getActivity(id: Long): ActivityWithDetails? = dao.getWithDetails(id)

    /** Activities whose span intersects [fromTs, toTs] (Unix seconds). */
    fun observeOverlapping(fromTs: Long, toTs: Long): Flow<List<Activity>> = dao.observeOverlapping(fromTs, toTs)

    /** Activities linked to an app session, for the session detail and finish summary. */
    fun observeForSession(sessionId: Long): Flow<List<Activity>> = dao.observeForSession(sessionId)

    suspend fun setLinkedSession(activityId: Long, sessionId: Long?) = dao.setLinkedSession(activityId, sessionId)

    suspend fun delete(id: Long) = dao.delete(id)

    /**
     * Links every unlinked activity to a completed session it overlaps by at least half the
     * session's duration (FR52). Returns how many links were made. Safe to call after every
     * session finish and after every import.
     */
    suspend fun linkUnlinked(): Int = db.withTransaction {
        val unlinked = dao.unlinked()
        if (unlinked.isEmpty()) return@withTransaction 0
        val from = unlinked.minOf { it.startTimestamp } * 1000
        val to = unlinked.maxOf { it.endTimestamp } * 1000
        val sessions = db.sessionDao().getCompletedOverlapping(from, to)
        val pairs = SessionMatcher.linkActivities(unlinked, sessions)
        for ((activity, session) in pairs) dao.setLinkedSession(activity.id, session.id)
        pairs.size
    }

    /** Local months that have activities, newest first, for the grouped list. */
    suspend fun monthsWithActivities(): List<YearMonth> =
        dao.utcDaysWithActivities().map { YearMonth.from(LocalDate.ofEpochDay(it)) }.distinct().sortedDescending()
}
