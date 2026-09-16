package com.animesh.fitnesstracker.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A recipe. Quantities and macros are per serving; [servings] is how many servings the
 * recipe as written makes. [slots] is a comma separated list of [MealSlot] names in lower
 * case, for example "breakfast,lunch".
 */
@Serializable
@Entity(tableName = "meals", indices = [Index("name", unique = true)])
data class Meal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val slots: String = "",
    val servings: Int = 1,
    val cookMinutes: Int? = null,
    val kcal: Double? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val prepDayBefore: Boolean = false,
    val prepInstruction: String = "",
    val notes: String = "",
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    val slotList: List<MealSlot>
        get() = slots.split(',').mapNotNull { s -> MealSlot.entries.firstOrNull { it.name.equals(s.trim(), ignoreCase = true) } }

    /** True when every macro is present, so day totals can be summed. */
    val hasMacros: Boolean get() = kcal != null && proteinG != null && carbsG != null && fatG != null

    companion object {
        fun slotsString(slots: Collection<MealSlot>): String = slots.distinct().joinToString(",") { it.name.lowercase() }
    }
}

@Serializable
@Entity(
    tableName = "ingredients",
    foreignKeys = [ForeignKey(entity = Meal::class, parentColumns = ["id"], childColumns = ["mealId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("mealId")]
)
data class Ingredient(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mealId: Long,
    val position: Int,
    val name: String,
    /** Amount per serving. */
    val amount: Double,
    /** Free text: g, ml, cup, katori, tsp, tbsp, piece. */
    val unit: String = ""
)

@Serializable
@Entity(
    tableName = "meal_steps",
    foreignKeys = [ForeignKey(entity = Meal::class, parentColumns = ["id"], childColumns = ["mealId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("mealId")]
)
data class MealStep(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mealId: Long,
    val position: Int,
    val text: String
)

/** A weekly menu: seven days by three slots. Exactly one plan is active. */
@Serializable
@Entity(tableName = "diet_plans")
data class DietPlan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isActive: Boolean = false,
    val isTemplate: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

/** One cell of the week grid. [dayOfWeek] is 0 for Monday through 6 for Sunday. A null [mealId] is an empty cell. */
@Serializable
@Entity(
    tableName = "diet_plan_cells",
    foreignKeys = [
        ForeignKey(entity = DietPlan::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Meal::class, parentColumns = ["id"], childColumns = ["mealId"], onDelete = ForeignKey.SET_NULL)
    ],
    indices = [Index("planId"), Index("mealId"), Index(value = ["planId", "dayOfWeek", "slot"], unique = true)]
)
data class DietPlanCell(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val dayOfWeek: Int,
    val slot: MealSlot,
    val mealId: Long? = null,
    /** Serving multiplier applied to the meal's per-serving quantities. */
    val servings: Double = 1.0
)

/**
 * Meal windows and reminder settings. Times are minutes since midnight. Windows must not
 * overlap and each end must be after its start.
 */
@Serializable
@Entity(tableName = "diet_settings")
data class DietSettings(
    @PrimaryKey val id: Int = 1,
    val breakfastStart: Int = 6 * 60,
    val breakfastEnd: Int = 10 * 60 + 30,
    val lunchStart: Int = 11 * 60 + 30,
    val lunchEnd: Int = 15 * 60 + 30,
    val dinnerStart: Int = 18 * 60 + 30,
    val dinnerEnd: Int = 22 * 60,
    val prepReminderEnabled: Boolean = true,
    /** Minutes since midnight; 1260 is 21:00. */
    val prepReminderMinute: Int = 21 * 60,
    val mealReminderEnabled: Boolean = true,
    /** Epoch day on which the user tapped Done on the prep reminder; hides the banner for that day. */
    val prepDoneEpochDay: Long? = null,
    @ColumnInfo(defaultValue = "0") val seeded: Boolean = false
) {
    fun window(slot: MealSlot): IntRange = when (slot) {
        MealSlot.BREAKFAST -> breakfastStart..breakfastEnd
        MealSlot.LUNCH -> lunchStart..lunchEnd
        MealSlot.DINNER -> dinnerStart..dinnerEnd
    }
}
