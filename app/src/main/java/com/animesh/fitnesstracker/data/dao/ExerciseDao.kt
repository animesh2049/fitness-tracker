package com.animesh.fitnesstracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.animesh.fitnesstracker.data.model.Exercise
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseDao {
    @Query("SELECT * FROM exercises WHERE archived = 0 ORDER BY name COLLATE NOCASE")
    fun observeActive(): Flow<List<Exercise>>

    @Query("SELECT * FROM exercises ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Exercise>>

    @Query("SELECT * FROM exercises WHERE id = :id")
    fun observeById(id: Long): Flow<Exercise?>

    @Query("SELECT * FROM exercises WHERE id = :id")
    suspend fun getById(id: Long): Exercise?

    @Query("SELECT * FROM exercises")
    suspend fun getAll(): List<Exercise>

    @Query("SELECT COUNT(*) FROM exercises")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM session_exercises WHERE exerciseId = :exerciseId")
    suspend fun historyCount(exerciseId: Long): Int

    @Query("SELECT COUNT(*) FROM group_exercises WHERE exerciseId = :exerciseId")
    suspend fun usageCount(exerciseId: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(exercise: Exercise): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(exercises: List<Exercise>): List<Long>

    @Update
    suspend fun update(exercise: Exercise)

    @Query("UPDATE exercises SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    @Query("DELETE FROM exercises WHERE id = :id")
    suspend fun delete(id: Long)

    // Backup support (appended for Milestone 5/6).
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllReplace(exercises: List<Exercise>): List<Long>

    @Query("DELETE FROM exercises")
    suspend fun deleteAll()
}
