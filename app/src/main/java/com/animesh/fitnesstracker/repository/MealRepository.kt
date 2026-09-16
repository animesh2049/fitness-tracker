package com.animesh.fitnesstracker.repository

import androidx.room.withTransaction
import com.animesh.fitnesstracker.data.AppDatabase
import com.animesh.fitnesstracker.data.model.Ingredient
import com.animesh.fitnesstracker.data.model.Meal
import com.animesh.fitnesstracker.data.model.MealSlot
import com.animesh.fitnesstracker.data.model.MealStep
import com.animesh.fitnesstracker.data.model.MealWithDetails
import kotlinx.coroutines.flow.Flow

class MealRepository(private val db: AppDatabase) {
    private val dao get() = db.mealDao()

    /** Non-archived meals matching [query] (any substring of the name) and, when given, tagged for [slot]. */
    fun observeMeals(query: String = "", slot: MealSlot? = null): Flow<List<Meal>> =
        dao.observeMeals(query.trim(), slot?.name?.lowercase())

    fun observeMeal(id: Long): Flow<MealWithDetails?> = dao.observeMeal(id)
    suspend fun getMeal(id: Long): MealWithDetails? = dao.getMeal(id)

    /**
     * Inserts (id 0) or updates the meal with its full ingredient and step lists. Returns the meal id.
     * @throws IllegalArgumentException when another meal already has the same name.
     */
    suspend fun save(meal: Meal, ingredients: List<Ingredient>, steps: List<MealStep>): Long = db.withTransaction {
        val name = meal.name.trim()
        require(name.isNotEmpty()) { "A meal needs a name" }
        if (dao.nameExists(name, meal.id)) throw IllegalArgumentException("A meal called $name already exists")
        dao.upsertWithDetails(meal.copy(name = name), ingredients, steps)
    }

    /** Deletes the meal; its ingredients and steps go with it and any plan cell using it becomes empty (SET_NULL). */
    suspend fun delete(id: Long) = dao.deleteMeal(id)

    suspend fun setArchived(id: Long, archived: Boolean) = dao.setArchived(id, archived)

    /** Number of cells in the active week plan that would become empty if the meal were deleted. */
    suspend fun usageCount(id: Long): Int = dao.usageCount(id)
}
