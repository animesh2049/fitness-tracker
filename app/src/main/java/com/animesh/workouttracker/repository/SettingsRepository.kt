package com.animesh.workouttracker.repository

import com.animesh.workouttracker.data.dao.SettingsDao
import com.animesh.workouttracker.data.model.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository(private val dao: SettingsDao) {
    fun observe(): Flow<Settings> = dao.observe().map { it ?: Settings() }
    suspend fun get(): Settings = dao.get() ?: Settings()
    suspend fun update(transform: (Settings) -> Settings) = dao.upsert(transform(get()))
}
