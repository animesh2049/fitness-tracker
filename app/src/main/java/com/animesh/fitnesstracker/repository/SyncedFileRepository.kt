package com.animesh.fitnesstracker.repository

import com.animesh.fitnesstracker.data.AppDatabase
import com.animesh.fitnesstracker.data.model.SyncedFile
import kotlinx.coroutines.flow.Flow

/** The registry of raw FIT files and their import state (garmin_files). */
class SyncedFileRepository(private val db: AppDatabase) {
    private val dao get() = db.syncedFileDao()

    fun observeAll(): Flow<List<SyncedFile>> = dao.observeAll()
    fun observeFailed(): Flow<List<SyncedFile>> = dao.observeFailed()
    fun observeCount(): Flow<Int> = dao.observeCount()
    fun observeTotalBytes(): Flow<Long> = dao.observeTotalBytes()
    fun observeLastImportedAt(): Flow<Long?> = dao.observeLastImportedAt()

    suspend fun getAll(): List<SyncedFile> = dao.getAll()
    suspend fun getByPath(path: String): SyncedFile? = dao.getByPath(path)
    suspend fun getUnimported(): List<SyncedFile> = dao.getUnimported()

    /** True when the watch file was downloaded and imported without error, so a sync can skip it. */
    suspend fun isImported(watchIndex: Int, fitType: Int, watchTimestamp: Long?): Boolean =
        dao.find(watchIndex, fitType, watchTimestamp)?.let { it.importedAt != null && it.importError == null } ?: false

    suspend fun upsert(file: SyncedFile): Long = dao.upsert(file)
    suspend fun delete(id: Long) = dao.delete(id)
}
