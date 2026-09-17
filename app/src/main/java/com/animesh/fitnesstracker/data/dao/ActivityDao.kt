package com.animesh.fitnesstracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.animesh.fitnesstracker.data.model.Activity
import com.animesh.fitnesstracker.data.model.ActivityLap
import com.animesh.fitnesstracker.data.model.ActivityPoint
import com.animesh.fitnesstracker.data.model.ActivityWithDetails
import kotlinx.coroutines.flow.Flow

@Dao
interface ActivityDao {
    @Query("SELECT * FROM activities ORDER BY startTimestamp DESC")
    fun observeAll(): Flow<List<Activity>>

    /** Activities starting in [fromTs, toTs), newest first. The repository turns a month into that range. */
    @Query("SELECT * FROM activities WHERE startTimestamp >= :fromTs AND startTimestamp < :toTs ORDER BY startTimestamp DESC")
    fun observeStartingBetween(fromTs: Long, toTs: Long): Flow<List<Activity>>

    @Query("SELECT * FROM activities WHERE startTimestamp >= :fromTs AND startTimestamp < :toTs ORDER BY startTimestamp DESC")
    suspend fun startingBetween(fromTs: Long, toTs: Long): List<Activity>

    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun get(id: Long): Activity?

    @Transaction
    @Query("SELECT * FROM activities WHERE id = :id")
    fun observeWithDetails(id: Long): Flow<ActivityWithDetails?>

    @Transaction
    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun getWithDetails(id: Long): ActivityWithDetails?

    /** Activities whose time span intersects [fromTs, toTs]. */
    @Query("SELECT * FROM activities WHERE startTimestamp <= :toTs AND endTimestamp >= :fromTs ORDER BY startTimestamp")
    fun observeOverlapping(fromTs: Long, toTs: Long): Flow<List<Activity>>

    @Query("SELECT * FROM activities WHERE startTimestamp <= :toTs AND endTimestamp >= :fromTs ORDER BY startTimestamp")
    suspend fun overlapping(fromTs: Long, toTs: Long): List<Activity>

    @Query("SELECT * FROM activities WHERE linkedSessionId = :sessionId ORDER BY startTimestamp")
    fun observeForSession(sessionId: Long): Flow<List<Activity>>

    @Query("SELECT * FROM activities WHERE linkedSessionId IS NULL ORDER BY startTimestamp")
    suspend fun unlinked(): List<Activity>

    @Query("SELECT * FROM activities WHERE startTimestamp = :startTimestamp AND fitTimeCreated = :fitTimeCreated")
    suspend fun find(startTimestamp: Long, fitTimeCreated: Long): Activity?

    @Query("SELECT DISTINCT (startTimestamp / 86400) FROM activities ORDER BY startTimestamp DESC")
    suspend fun utcDaysWithActivities(): List<Long>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(activity: Activity): Long

    @Update
    suspend fun update(activity: Activity)

    @Query("UPDATE activities SET linkedSessionId = :sessionId WHERE id = :activityId")
    suspend fun setLinkedSession(activityId: Long, sessionId: Long?)

    @Query("DELETE FROM activities WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLaps(laps: List<ActivityLap>): List<Long>

    @Query("SELECT * FROM activity_laps WHERE activityId = :activityId ORDER BY `index`")
    suspend fun laps(activityId: Long): List<ActivityLap>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoints(points: List<ActivityPoint>): List<Long>

    @Query("SELECT * FROM activity_points WHERE activityId = :activityId ORDER BY timestamp")
    suspend fun points(activityId: Long): List<ActivityPoint>

    @Query("SELECT COUNT(*) FROM activities")
    suspend fun count(): Int

    @Query("DELETE FROM activity_points")
    suspend fun deleteAllPoints()

    @Query("DELETE FROM activity_laps")
    suspend fun deleteAllLaps()

    @Query("DELETE FROM activities")
    suspend fun deleteAll()
}
