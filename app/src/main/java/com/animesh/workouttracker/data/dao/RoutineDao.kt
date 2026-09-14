package com.animesh.workouttracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.animesh.workouttracker.data.model.Routine
import com.animesh.workouttracker.data.model.RoutineSlot
import com.animesh.workouttracker.data.model.RoutineWithSlots
import androidx.room.OnConflictStrategy
import kotlinx.coroutines.flow.Flow

@Dao
interface RoutineDao {
    @Transaction
    @Query("SELECT * FROM routines WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<RoutineWithSlots?>

    @Transaction
    @Query("SELECT * FROM routines WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): RoutineWithSlots?

    @Transaction
    @Query("SELECT * FROM routines ORDER BY isActive DESC, name COLLATE NOCASE")
    fun observeAll(): Flow<List<RoutineWithSlots>>

    @Transaction
    @Query("SELECT * FROM routines WHERE id = :id")
    fun observeById(id: Long): Flow<RoutineWithSlots?>

    @Transaction
    @Query("SELECT * FROM routines WHERE id = :id")
    suspend fun getById(id: Long): RoutineWithSlots?

    @Query("SELECT * FROM routines")
    suspend fun getAllRoutines(): List<Routine>

    @Query("SELECT * FROM routine_slots")
    suspend fun getAllSlots(): List<RoutineSlot>

    @Insert
    suspend fun insertRoutine(routine: Routine): Long

    @Update
    suspend fun updateRoutine(routine: Routine)

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun deleteRoutine(id: Long)

    @Query("UPDATE routines SET isActive = 0")
    suspend fun clearActive()

    @Query("UPDATE routines SET isActive = 1 WHERE id = :id")
    suspend fun markActive(id: Long)

    @Insert
    suspend fun insertSlot(slot: RoutineSlot): Long

    @Insert
    suspend fun insertSlots(slots: List<RoutineSlot>)

    @Query("DELETE FROM routine_slots WHERE routineId = :routineId")
    suspend fun deleteSlotsFor(routineId: Long)

    @Transaction
    suspend fun setActive(id: Long) {
        clearActive()
        markActive(id)
    }

    // Backup support (appended for Milestone 5/6).
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutinesReplace(routines: List<Routine>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRoutinesIgnore(routines: List<Routine>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSlotsReplace(slots: List<RoutineSlot>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSlotsIgnore(slots: List<RoutineSlot>): List<Long>

    @Query("DELETE FROM routines")
    suspend fun deleteAllRoutines()

    @Query("DELETE FROM routine_slots")
    suspend fun deleteAllSlots()
}
