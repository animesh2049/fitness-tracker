package com.animesh.fitnesstracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.animesh.fitnesstracker.data.model.DietPlan
import com.animesh.fitnesstracker.data.model.DietPlanCell
import com.animesh.fitnesstracker.data.model.DietPlanWithCells
import com.animesh.fitnesstracker.data.model.MealSlot
import kotlinx.coroutines.flow.Flow

@Dao
interface DietPlanDao {
    @Transaction
    @Query("SELECT * FROM diet_plans WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<DietPlanWithCells?>

    @Transaction
    @Query("SELECT * FROM diet_plans WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): DietPlanWithCells?

    @Transaction
    @Query("SELECT * FROM diet_plans WHERE isTemplate = 0 ORDER BY isActive DESC, name COLLATE NOCASE")
    fun observePlans(): Flow<List<DietPlanWithCells>>

    @Transaction
    @Query("SELECT * FROM diet_plans WHERE isTemplate = 1 ORDER BY name COLLATE NOCASE")
    fun observeTemplates(): Flow<List<DietPlanWithCells>>

    @Transaction
    @Query("SELECT * FROM diet_plans WHERE id = :id")
    fun observePlan(id: Long): Flow<DietPlanWithCells?>

    @Transaction
    @Query("SELECT * FROM diet_plans WHERE id = :id")
    suspend fun getPlan(id: Long): DietPlanWithCells?

    @Query("SELECT * FROM diet_plans")
    suspend fun getAllPlans(): List<DietPlan>

    @Query("SELECT * FROM diet_plan_cells")
    suspend fun getAllCells(): List<DietPlanCell>

    @Insert
    suspend fun insertPlan(plan: DietPlan): Long

    @Update
    suspend fun updatePlan(plan: DietPlan)

    @Query("DELETE FROM diet_plans WHERE id = :id")
    suspend fun deletePlan(id: Long)

    @Query("UPDATE diet_plans SET isActive = 0")
    suspend fun deactivateAll()

    @Query("UPDATE diet_plans SET isActive = 1 WHERE id = :id")
    suspend fun activate(id: Long)

    @Transaction
    suspend fun setActive(id: Long) {
        deactivateAll()
        activate(id)
    }

    @Insert
    suspend fun insertCell(cell: DietPlanCell): Long

    @Insert
    suspend fun insertCells(cells: List<DietPlanCell>)

    /** Removes the cell, which the grid then treats as empty. */
    @Query("DELETE FROM diet_plan_cells WHERE planId = :planId AND dayOfWeek = :dayOfWeek AND slot = :slot")
    suspend fun clearCell(planId: Long, dayOfWeek: Int, slot: MealSlot)

    /** Replaces whatever cell holds the same plan, day and slot (the unique index) with [cell]. */
    @Transaction
    suspend fun upsertCell(cell: DietPlanCell): Long {
        clearCell(cell.planId, cell.dayOfWeek, cell.slot)
        return insertCell(cell.copy(id = 0))
    }

    @Query("SELECT * FROM diet_plan_cells WHERE planId = :planId AND dayOfWeek = :dayOfWeek")
    suspend fun cellsForDay(planId: Long, dayOfWeek: Int): List<DietPlanCell>

    @Query("DELETE FROM diet_plan_cells WHERE planId = :planId AND dayOfWeek != :dayOfWeek")
    suspend fun deleteCellsExceptDay(planId: Long, dayOfWeek: Int)

    /** Copies the cells of [fromDay] onto every other day of the plan, replacing what was there. */
    @Transaction
    suspend fun copyDay(planId: Long, fromDay: Int) {
        val source = cellsForDay(planId, fromDay)
        deleteCellsExceptDay(planId, fromDay)
        insertCells((0..6).filter { it != fromDay }.flatMap { day -> source.map { it.copy(id = 0, dayOfWeek = day) } })
    }

    // Backup support.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlansReplace(plans: List<DietPlan>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlansIgnore(plans: List<DietPlan>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCellsReplace(cells: List<DietPlanCell>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCellsIgnore(cells: List<DietPlanCell>): List<Long>

    @Query("DELETE FROM diet_plans")
    suspend fun deleteAllPlans()

    @Query("DELETE FROM diet_plan_cells")
    suspend fun deleteAllCells()
}
