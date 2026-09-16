package com.animesh.fitnesstracker.repository

import com.animesh.fitnesstracker.data.dao.ExerciseDao
import com.animesh.fitnesstracker.data.model.Exercise
import kotlinx.coroutines.flow.Flow

class ExerciseRepository(private val dao: ExerciseDao) {
    fun observeActive(): Flow<List<Exercise>> = dao.observeActive()
    fun observeAll(): Flow<List<Exercise>> = dao.observeAll()
    fun observe(id: Long): Flow<Exercise?> = dao.observeById(id)
    suspend fun get(id: Long): Exercise? = dao.getById(id)

    suspend fun add(exercise: Exercise): Long = dao.insert(exercise)
    suspend fun update(exercise: Exercise) = dao.update(exercise)

    /** True if the exercise has logged history or is used in a group, in which case delete should archive instead. */
    suspend fun isReferenced(id: Long): Boolean = dao.historyCount(id) > 0 || dao.usageCount(id) > 0

    /** Archive when referenced anywhere, otherwise delete outright. Returns true if archived. */
    suspend fun archiveOrDelete(id: Long): Boolean {
        return if (isReferenced(id)) {
            dao.setArchived(id, true); true
        } else {
            dao.delete(id); false
        }
    }

    suspend fun unarchive(id: Long) = dao.setArchived(id, false)
}
