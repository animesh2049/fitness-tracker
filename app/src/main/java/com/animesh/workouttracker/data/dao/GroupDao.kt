package com.animesh.workouttracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.animesh.workouttracker.data.model.GroupExercise
import com.animesh.workouttracker.data.model.GroupWithExercises
import com.animesh.workouttracker.data.model.SetPrescription
import com.animesh.workouttracker.data.model.WorkoutGroup
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Transaction
    @Query("SELECT * FROM workout_groups WHERE isTemplate = 0 ORDER BY sortOrder, name COLLATE NOCASE")
    fun observeGroups(): Flow<List<GroupWithExercises>>

    @Transaction
    @Query("SELECT * FROM workout_groups WHERE isTemplate = 1 ORDER BY name COLLATE NOCASE")
    fun observeTemplates(): Flow<List<GroupWithExercises>>

    @Transaction
    @Query("SELECT * FROM workout_groups WHERE id = :id")
    fun observeGroup(id: Long): Flow<GroupWithExercises?>

    @Transaction
    @Query("SELECT * FROM workout_groups WHERE id = :id")
    suspend fun getGroup(id: Long): GroupWithExercises?

    @Query("SELECT * FROM workout_groups")
    suspend fun getAllGroups(): List<WorkoutGroup>

    @Query("SELECT * FROM group_exercises")
    suspend fun getAllGroupExercises(): List<GroupExercise>

    @Query("SELECT * FROM set_prescriptions")
    suspend fun getAllPrescriptions(): List<SetPrescription>

    @Query("SELECT COUNT(*) FROM workout_groups WHERE isTemplate = 0")
    suspend fun count(): Int

    @Insert
    suspend fun insertGroup(group: WorkoutGroup): Long

    @Update
    suspend fun updateGroup(group: WorkoutGroup)

    @Query("DELETE FROM workout_groups WHERE id = :id")
    suspend fun deleteGroup(id: Long)

    @Insert
    suspend fun insertGroupExercise(groupExercise: GroupExercise): Long

    @Update
    suspend fun updateGroupExercise(groupExercise: GroupExercise)

    @Query("DELETE FROM group_exercises WHERE id = :id")
    suspend fun deleteGroupExercise(id: Long)

    @Query("DELETE FROM group_exercises WHERE groupId = :groupId")
    suspend fun deleteGroupExercisesFor(groupId: Long)

    @Insert
    suspend fun insertPrescription(set: SetPrescription): Long

    @Insert
    suspend fun insertPrescriptions(sets: List<SetPrescription>)

    @Update
    suspend fun updatePrescription(set: SetPrescription)

    @Query("DELETE FROM set_prescriptions WHERE id = :id")
    suspend fun deletePrescription(id: Long)

    @Query("SELECT * FROM set_prescriptions WHERE groupExerciseId = :groupExerciseId ORDER BY position")
    suspend fun prescriptionsFor(groupExerciseId: Long): List<SetPrescription>

    @Query("SELECT COUNT(*) FROM routine_slots WHERE groupId = :groupId")
    suspend fun routineUsageCount(groupId: Long): Int
}
