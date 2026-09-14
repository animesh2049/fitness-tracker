package com.animesh.workouttracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.animesh.workouttracker.data.model.Settings
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 1")
    fun observe(): Flow<Settings?>

    @Query("SELECT * FROM settings WHERE id = 1")
    suspend fun get(): Settings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: Settings)

    // Backup support (appended for Milestone 5/6).
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(settings: Settings): Long
}
