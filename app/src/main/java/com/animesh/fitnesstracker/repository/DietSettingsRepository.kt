package com.animesh.fitnesstracker.repository

import com.animesh.fitnesstracker.data.dao.DietSettingsDao
import com.animesh.fitnesstracker.data.model.DietSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DietSettingsRepository(private val dao: DietSettingsDao) {
    fun observe(): Flow<DietSettings> = dao.observe().map { it ?: DietSettings() }
    suspend fun get(): DietSettings = dao.get() ?: DietSettings()
    suspend fun update(transform: (DietSettings) -> DietSettings) = dao.upsert(transform(get()))
}
