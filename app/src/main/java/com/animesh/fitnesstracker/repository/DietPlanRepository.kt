package com.animesh.fitnesstracker.repository

import androidx.room.withTransaction
import com.animesh.fitnesstracker.data.AppDatabase
import com.animesh.fitnesstracker.data.model.DietPlan
import com.animesh.fitnesstracker.data.model.DietPlanCell
import com.animesh.fitnesstracker.data.model.DietPlanWithCells
import com.animesh.fitnesstracker.data.model.MealSlot
import kotlinx.coroutines.flow.Flow

class DietPlanRepository(private val db: AppDatabase) {
    private val dao get() = db.dietPlanDao()

    fun observeActive(): Flow<DietPlanWithCells?> = dao.observeActive()
    suspend fun getActive(): DietPlanWithCells? = dao.getActive()
    fun observePlans(): Flow<List<DietPlanWithCells>> = dao.observePlans()
    fun observeTemplates(): Flow<List<DietPlanWithCells>> = dao.observeTemplates()
    fun observe(id: Long): Flow<DietPlanWithCells?> = dao.observePlan(id)
    suspend fun get(id: Long): DietPlanWithCells? = dao.getPlan(id)

    /** The active plan, creating an empty one called "My week" when there is none. */
    suspend fun ensureActive(): DietPlanWithCells = db.withTransaction {
        dao.getActive() ?: run {
            val id = dao.insertPlan(DietPlan(name = DEFAULT_NAME, isActive = true))
            dao.setActive(id)
            dao.getPlan(id) ?: error("Plan $id not found")
        }
    }

    /** Puts [mealId] (null for empty) in a cell of the active plan. */
    suspend fun setCell(dayOfWeek: Int, slot: MealSlot, mealId: Long?, servings: Double = 1.0) = db.withTransaction {
        val plan = ensureActive().plan
        dao.upsertCell(DietPlanCell(planId = plan.id, dayOfWeek = dayOfWeek, slot = slot, mealId = mealId, servings = servings))
        Unit
    }

    /** Empties a cell of the active plan. */
    suspend fun clearCell(dayOfWeek: Int, slot: MealSlot) = db.withTransaction {
        val plan = dao.getActive()?.plan ?: return@withTransaction
        dao.clearCell(plan.id, dayOfWeek, slot)
    }

    /** Copies one day of the active plan onto the other six. */
    suspend fun copyDay(fromDay: Int) = db.withTransaction {
        val plan = dao.getActive()?.plan ?: return@withTransaction
        dao.copyDay(plan.id, fromDay)
    }

    suspend fun create(name: String, makeActive: Boolean): Long = db.withTransaction {
        val id = dao.insertPlan(DietPlan(name = name, isActive = false))
        if (makeActive || dao.getActive() == null) dao.setActive(id)
        id
    }

    suspend fun rename(plan: DietPlan, name: String) = dao.updatePlan(plan.copy(name = name))
    suspend fun update(plan: DietPlan) = dao.updatePlan(plan)
    suspend fun setActive(id: Long) = dao.setActive(id)

    /** Deletes the plan and its cells. Deleting the active plan leaves none active until [ensureActive] runs. */
    suspend fun delete(id: Long) = dao.deletePlan(id)

    suspend fun duplicate(id: Long, name: String, asTemplate: Boolean): Long = db.withTransaction {
        val src = dao.getPlan(id) ?: error("Plan $id not found")
        val newId = dao.insertPlan(DietPlan(name = name, isActive = false, isTemplate = asTemplate))
        dao.insertCells(src.cells.map { it.cell.copy(id = 0, planId = newId) })
        newId
    }

    /** Saves a copy of a plan as a template (never active). */
    suspend fun saveAsTemplate(id: Long, name: String): Long = duplicate(id, name, asTemplate = true)

    /** Creates a plan from a template; it becomes active when no plan is active yet. */
    suspend fun createFromTemplate(templateId: Long, name: String): Long = db.withTransaction {
        val id = duplicate(templateId, name, asTemplate = false)
        if (dao.getActive() == null) dao.setActive(id)
        id
    }

    companion object {
        const val DEFAULT_NAME = "My week"
    }
}
