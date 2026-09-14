package com.animesh.workouttracker.repository

import androidx.room.withTransaction
import com.animesh.workouttracker.data.AppDatabase
import com.animesh.workouttracker.data.model.Exercise
import com.animesh.workouttracker.data.model.ExerciseType
import com.animesh.workouttracker.data.model.GroupExercise
import com.animesh.workouttracker.data.model.GroupWithExercises
import com.animesh.workouttracker.data.model.SetPrescription
import com.animesh.workouttracker.data.model.WorkoutGroup
import kotlinx.coroutines.flow.Flow

class GroupRepository(private val db: AppDatabase) {
    private val dao get() = db.groupDao()

    fun observeGroups(): Flow<List<GroupWithExercises>> = dao.observeGroups()
    fun observeTemplates(): Flow<List<GroupWithExercises>> = dao.observeTemplates()
    fun observeGroup(id: Long): Flow<GroupWithExercises?> = dao.observeGroup(id)
    suspend fun getGroup(id: Long): GroupWithExercises? = dao.getGroup(id)

    suspend fun createGroup(name: String, notes: String = ""): Long {
        val order = dao.getAllGroups().size
        return dao.insertGroup(WorkoutGroup(name = name, notes = notes, sortOrder = order))
    }

    suspend fun updateGroup(group: WorkoutGroup) = dao.updateGroup(group)
    suspend fun deleteGroup(id: Long) = dao.deleteGroup(id)

    suspend fun duplicateGroup(id: Long, newName: String? = null, asTemplate: Boolean = false): Long = db.withTransaction {
        val src = dao.getGroup(id) ?: error("Group $id not found")
        val newId = dao.insertGroup(
            src.group.copy(id = 0, name = newName ?: (src.group.name + " copy"), isTemplate = asTemplate, createdAt = System.currentTimeMillis())
        )
        src.sortedExercises.forEach { ge ->
            val geId = dao.insertGroupExercise(ge.groupExercise.copy(id = 0, groupId = newId))
            dao.insertPrescriptions(ge.sortedSets.map { it.copy(id = 0, groupExerciseId = geId) })
        }
        newId
    }

    suspend fun addExercise(groupId: Long, exercise: Exercise): Long = db.withTransaction {
        val existing = dao.getGroup(groupId)?.exercises?.size ?: 0
        val geId = dao.insertGroupExercise(GroupExercise(groupId = groupId, exerciseId = exercise.id, position = existing))
        val defaults = defaultSets(exercise)
        dao.insertPrescriptions(defaults.mapIndexed { i, s -> s.copy(groupExerciseId = geId, position = i) })
        geId
    }

    private fun defaultSets(exercise: Exercise): List<SetPrescription> = when (exercise.type) {
        ExerciseType.WEIGHT -> List(3) { SetPrescription(groupExerciseId = 0, position = it, targetReps = 10, targetWeightKg = 20.0) }
        ExerciseType.BODYWEIGHT -> List(3) { SetPrescription(groupExerciseId = 0, position = it, targetReps = 8) }
        ExerciseType.TIMED -> List(3) { SetPrescription(groupExerciseId = 0, position = it, targetSeconds = 30) }
    }

    suspend fun removeExercise(groupExerciseId: Long, groupId: Long) = db.withTransaction {
        dao.deleteGroupExercise(groupExerciseId)
        renumber(groupId)
    }

    suspend fun moveExercise(groupId: Long, fromIndex: Int, toIndex: Int) = db.withTransaction {
        val list = dao.getGroup(groupId)?.sortedExercises?.map { it.groupExercise }?.toMutableList() ?: return@withTransaction
        if (fromIndex !in list.indices || toIndex !in list.indices) return@withTransaction
        val item = list.removeAt(fromIndex)
        list.add(toIndex, item)
        list.forEachIndexed { i, ge -> if (ge.position != i) dao.updateGroupExercise(ge.copy(position = i)) }
    }

    private suspend fun renumber(groupId: Long) {
        val list = dao.getGroup(groupId)?.sortedExercises?.map { it.groupExercise } ?: return
        list.forEachIndexed { i, ge -> if (ge.position != i) dao.updateGroupExercise(ge.copy(position = i)) }
    }

    suspend fun setSuperset(groupExercise: GroupExercise, superset: Boolean) =
        dao.updateGroupExercise(groupExercise.copy(supersetWithNext = superset))

    /** Adds a set copying the last one (as a working set), or a sensible default when the exercise has none. */
    suspend fun addSet(groupExerciseId: Long, exercise: Exercise): Long {
        val existing = dao.prescriptionsFor(groupExerciseId)
        val last = existing.lastOrNull()
        val next = last?.copy(id = 0, position = existing.size, isWarmup = false)
            ?: defaultSets(exercise).first().copy(groupExerciseId = groupExerciseId, position = 0)
        return dao.insertPrescription(next)
    }

    suspend fun updateSet(set: SetPrescription) = dao.updatePrescription(set)

    suspend fun removeSet(set: SetPrescription) = db.withTransaction {
        dao.deletePrescription(set.id)
        dao.prescriptionsFor(set.groupExerciseId).forEachIndexed { i, s -> if (s.position != i) dao.updatePrescription(s.copy(position = i)) }
    }

    suspend fun saveAsTemplate(groupId: Long, name: String): Long = duplicateGroup(groupId, name, asTemplate = true)
    suspend fun createFromTemplate(templateId: Long, name: String): Long = duplicateGroup(templateId, name, asTemplate = false)
}
