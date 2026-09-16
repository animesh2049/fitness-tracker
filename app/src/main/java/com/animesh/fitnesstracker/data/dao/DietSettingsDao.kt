package com.animesh.fitnesstracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.animesh.fitnesstracker.data.model.DietSettings
import kotlinx.coroutines.flow.Flow

@Dao
interface DietSettingsDao {
    @Query("SELECT * FROM diet_settings WHERE id = 1")
    fun observe(): Flow<DietSettings?>

    @Query("SELECT * FROM diet_settings WHERE id = 1")
    suspend fun get(): DietSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: DietSettings)

    // Backup support.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(settings: DietSettings): Long
}
