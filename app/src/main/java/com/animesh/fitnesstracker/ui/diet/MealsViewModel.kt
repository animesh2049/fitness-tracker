package com.animesh.fitnesstracker.ui.diet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animesh.fitnesstracker.data.model.Meal
import com.animesh.fitnesstracker.data.model.MealSlot
import com.animesh.fitnesstracker.di.AppContainer
import com.animesh.fitnesstracker.domain.diet.Scaling
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One meal card in the library. */
data class MealListRow(val meal: Meal, val macrosLine: String) {
    val id: Long get() = meal.id
}

/** The inline delete strip open on one card. */
data class DeleteConfirm(val mealId: Long, val text: String)

data class MealsState(
    val loading: Boolean = true,
    val rows: List<MealListRow> = emptyList(),
    val search: String = "",
    val slot: MealSlot? = null,
    /** All non-archived meals, regardless of the search or filter. */
    val total: Int = 0,
    /** How many of [total] need prep the day before. */
    val prepCount: Int = 0,
    val confirming: DeleteConfirm? = null
)

/** "450 kcal · 35 g protein", or "Macros not set" when the meal has no complete macros. */
fun mealMacrosLine(meal: Meal): String = Scaling.macros(meal, 1.0)?.summary() ?: "Macros not set"

/** Slot label as shown on chips and pills. */
fun slotLabel(slot: MealSlot): String = slot.name.lowercase().replaceFirstChar { it.uppercase() }

@OptIn(ExperimentalCoroutinesApi::class)
class MealsViewModel(private val c: AppContainer) : ViewModel() {
    private val search = MutableStateFlow("")
    private val slot = MutableStateFlow<MealSlot?>(null)
    private val confirming = MutableStateFlow<DeleteConfirm?>(null)

    private val filtered = combine(search, slot) { q, s -> q to s }.flatMapLatest { (q, s) -> c.meals.observeMeals(q, s) }

    val state: StateFlow<MealsState> = combine(filtered, c.meals.observeMeals(), search, slot, confirming) { rows, all, q, s, confirm ->
        MealsState(
            loading = false,
            rows = rows.map { MealListRow(it, mealMacrosLine(it)) },
            search = q,
            slot = s,
            total = all.size,
            prepCount = all.count { it.prepDayBefore },
            confirming = confirm?.takeIf { d -> rows.any { it.id == d.mealId } }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MealsState())

    fun setSearch(q: String) { search.value = q }
    fun setSlot(s: MealSlot?) { slot.value = s }

    /** Opens the inline confirm strip on the card, with the number of week plan slots that would empty. */
    fun askDelete(id: Long) = viewModelScope.launch {
        val uses = c.meals.usageCount(id)
        val text = if (uses > 0) "Used in $uses ${if (uses == 1) "slot" else "slots"} of the week plan. Those slots become empty." else "Delete this meal?"
        confirming.value = DeleteConfirm(id, text)
    }

    fun cancelDelete() { confirming.value = null }

    fun confirmDelete() {
        val id = confirming.value?.mealId ?: return
        confirming.value = null
        viewModelScope.launch { c.meals.delete(id) }
    }
}
