package com.animesh.fitnesstracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.animesh.fitnesstracker.data.model.SyncedFile
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncedFileDao {
    @Query("SELECT * FROM garmin_files ORDER BY watchTimestamp DESC, id DESC")
    fun observeAll(): Flow<List<SyncedFile>>

    @Query("SELECT * FROM garmin_files ORDER BY watchTimestamp ASC, id ASC")
    suspend fun getAll(): List<SyncedFile>

    @Query("SELECT * FROM garmin_files WHERE path = :path")
    suspend fun getByPath(path: String): SyncedFile?

    @Query("SELECT * FROM garmin_files WHERE watchIndex = :watchIndex AND fitType = :fitType AND watchTimestamp IS :watchTimestamp LIMIT 1")
    suspend fun find(watchIndex: Int, fitType: Int, watchTimestamp: Long?): SyncedFile?

    @Query("SELECT * FROM garmin_files WHERE importedAt IS NULL ORDER BY watchTimestamp ASC, id ASC")
    suspend fun getUnimported(): List<SyncedFile>

    @Query("SELECT * FROM garmin_files WHERE importError IS NOT NULL ORDER BY watchTimestamp DESC")
    fun observeFailed(): Flow<List<SyncedFile>>

    @Query("SELECT COUNT(*) FROM garmin_files")
    fun observeCount(): Flow<Int>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM garmin_files")
    fun observeTotalBytes(): Flow<Long>

    @Query("SELECT MAX(importedAt) FROM garmin_files")
    fun observeLastImportedAt(): Flow<Long?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(file: SyncedFile): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(file: SyncedFile): Long

    @Update
    suspend fun update(file: SyncedFile)

    @Query("UPDATE garmin_files SET importedAt = :importedAt, importError = NULL, minuteSamples = :minuteSamples, activities = :activities WHERE id = :id")
    suspend fun markImported(id: Long, importedAt: Long, minuteSamples: Int, activities: Int)

    @Query("UPDATE garmin_files SET importError = :error WHERE id = :id")
    suspend fun markFailed(id: Long, error: String)

    @Query("DELETE FROM garmin_files WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM garmin_files")
    suspend fun deleteAll()
}
