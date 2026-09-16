package com.animesh.fitnesstracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.animesh.fitnesstracker.data.model.Ingredient
import com.animesh.fitnesstracker.data.model.Meal
import com.animesh.fitnesstracker.data.model.MealStep
import com.animesh.fitnesstracker.data.model.MealWithDetails
import kotlinx.coroutines.flow.Flow

@Dao
interface MealDao {
    /** Non-archived meals whose name contains [query]; when [slot] is given (lower-case name) only meals tagged for it. */
    @Query(
        "SELECT * FROM meals WHERE archived = 0 AND name LIKE '%' || :query || '%' " +
            "AND (:slot IS NULL OR slots LIKE '%' || :slot || '%') ORDER BY name COLLATE NOCASE"
    )
    fun observeMeals(query: String, slot: String?): Flow<List<Meal>>

    @Transaction
    @Query("SELECT * FROM meals WHERE id = :id")
    fun observeMeal(id: Long): Flow<MealWithDetails?>

    @Transaction
    @Query("SELECT * FROM meals WHERE id = :id")
    suspend fun getMeal(id: Long): MealWithDetails?

    @Query("SELECT * FROM meals")
    suspend fun getAllMeals(): List<Meal>

    @Query("SELECT * FROM ingredients")
    suspend fun getAllIngredients(): List<Ingredient>

    @Query("SELECT * FROM meal_steps")
    suspend fun getAllSteps(): List<MealStep>

    @Query("SELECT COUNT(*) FROM meals")
    suspend fun count(): Int

    @Insert
    suspend fun insertMeal(meal: Meal): Long

    @Update
    suspend fun updateMeal(meal: Meal)

    @Query("DELETE FROM meals WHERE id = :id")
    suspend fun deleteMeal(id: Long)

    @Query("UPDATE meals SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    @Insert
    suspend fun insertIngredients(ingredients: List<Ingredient>)

    @Query("DELETE FROM ingredients WHERE mealId = :mealId")
    suspend fun deleteIngredientsFor(mealId: Long)

    @Insert
    suspend fun insertSteps(steps: List<MealStep>)

    @Query("DELETE FROM meal_steps WHERE mealId = :mealId")
    suspend fun deleteStepsFor(mealId: Long)

    /** Inserts (id 0) or updates the meal and replaces its ingredient and step lists, renumbering positions. Returns the meal id. */
    @Transaction
    suspend fun upsertWithDetails(meal: Meal, ingredients: List<Ingredient>, steps: List<MealStep>): Long {
        val id = if (meal.id == 0L) insertMeal(meal) else { updateMeal(meal); meal.id }
        deleteIngredientsFor(id)
        deleteStepsFor(id)
        insertIngredients(ingredients.mapIndexed { i, ing -> ing.copy(id = 0, mealId = id, position = i) })
        insertSteps(steps.mapIndexed { i, s -> s.copy(id = 0, mealId = id, position = i) })
        return id
    }

    /** Number of cells in the active plan that reference this meal (they become empty when it is deleted). */
    @Query("SELECT COUNT(*) FROM diet_plan_cells c JOIN diet_plans p ON p.id = c.planId WHERE p.isActive = 1 AND c.mealId = :mealId")
    suspend fun usageCount(mealId: Long): Int

    /** True when another meal (not [excludingId]) already has this name, ignoring case and surrounding spaces. */
    @Query("SELECT EXISTS(SELECT 1 FROM meals WHERE LOWER(TRIM(name)) = LOWER(TRIM(:name)) AND id != :excludingId)")
    suspend fun nameExists(name: String, excludingId: Long): Boolean

    // Backup support.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMealsReplace(meals: List<Meal>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMealsIgnore(meals: List<Meal>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIngredientsReplace(ingredients: List<Ingredient>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIngredientsIgnore(ingredients: List<Ingredient>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStepsReplace(steps: List<MealStep>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStepsIgnore(steps: List<MealStep>): List<Long>

    @Query("DELETE FROM meals")
    suspend fun deleteAllMeals()

    @Query("DELETE FROM ingredients")
    suspend fun deleteAllIngredients()

    @Query("DELETE FROM meal_steps")
    suspend fun deleteAllSteps()
}
