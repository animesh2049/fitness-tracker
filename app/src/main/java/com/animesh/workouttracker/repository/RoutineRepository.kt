package com.animesh.workouttracker.repository

import androidx.room.withTransaction
import com.animesh.workouttracker.data.AppDatabase
import com.animesh.workouttracker.data.model.Routine
import com.animesh.workouttracker.data.model.RoutineSlot
import com.animesh.workouttracker.data.model.RoutineWithSlots
import kotlinx.coroutines.flow.Flow

class RoutineRepository(private val db: AppDatabase) {
    private val dao get() = db.routineDao()

    fun observeActive(): Flow<RoutineWithSlots?> = dao.observeActive()
    suspend fun getActive(): RoutineWithSlots? = dao.getActive()
    fun observeAll(): Flow<List<RoutineWithSlots>> = dao.observeAll()
    fun observe(id: Long): Flow<RoutineWithSlots?> = dao.observeById(id)
    suspend fun get(id: Long): RoutineWithSlots? = dao.getById(id)

    /** @param slotGroupIds one entry per slot; null is a rest slot. */
    suspend fun create(name: String, slotGroupIds: List<Long?>, makeActive: Boolean): Long = db.withTransaction {
        val id = dao.insertRoutine(Routine(name = name, isActive = false))
        dao.insertSlots(slotGroupIds.mapIndexed { i, g -> RoutineSlot(routineId = id, position = i, groupId = g) })
        if (makeActive || dao.getActive() == null) dao.setActive(id)
        id
    }

    suspend fun rename(routine: Routine, name: String) = dao.updateRoutine(routine.copy(name = name))

    suspend fun replaceSlots(routineId: Long, slotGroupIds: List<Long?>) = db.withTransaction {
        val routine = dao.getById(routineId)?.routine ?: return@withTransaction
        dao.deleteSlotsFor(routineId)
        dao.insertSlots(slotGroupIds.mapIndexed { i, g -> RoutineSlot(routineId = routineId, position = i, groupId = g) })
        val pos = if (slotGroupIds.isEmpty()) 0 else routine.position.coerceIn(0, slotGroupIds.size - 1)
        dao.updateRoutine(routine.copy(position = pos, overrideGroupId = null, overrideEpochDay = null))
    }

    suspend fun setActive(id: Long) = dao.setActive(id)
    suspend fun delete(id: Long) = dao.deleteRoutine(id)

    suspend fun duplicate(id: Long, name: String, asTemplate: Boolean): Long = db.withTransaction {
        val src = dao.getById(id) ?: error("Routine $id not found")
        val newId = dao.insertRoutine(Routine(name = name, isActive = false, isTemplate = asTemplate))
        dao.insertSlots(src.sortedSlots.map { it.slot.copy(id = 0, routineId = newId) })
        newId
    }

    suspend fun update(routine: Routine) = dao.updateRoutine(routine)

    /** Saves a routine as a template (never active). @param slotGroupIds one entry per slot; null is a rest slot. */
    suspend fun createTemplate(name: String, slotGroupIds: List<Long?>): Long = db.withTransaction {
        val id = dao.insertRoutine(Routine(name = name, isActive = false, isTemplate = true))
        dao.insertSlots(slotGroupIds.mapIndexed { i, g -> RoutineSlot(routineId = id, position = i, groupId = g) })
        id
    }
}
