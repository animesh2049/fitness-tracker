package com.animesh.fitnesstracker.ui.diet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animesh.fitnesstracker.data.model.DietSettings
import com.animesh.fitnesstracker.data.model.MealWithDetails
import com.animesh.fitnesstracker.di.AppContainer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MealState(
    val loading: Boolean = true,
    val meal: MealWithDetails? = null,
    val settings: DietSettings = DietSettings(),
    /** Non-null while the delete confirmation is open: how many week plan slots would become empty. */
    val pendingDeleteUsage: Int? = null
)

class MealViewModel(private val c: AppContainer, private val mealId: Long) : ViewModel() {
    private val pendingDelete = MutableStateFlow<Int?>(null)

    private val _deleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits once the meal has been deleted, for navigation. */
    val deleted: SharedFlow<Unit> = _deleted

    val state: StateFlow<MealState> = combine(c.meals.observeMeal(mealId), c.dietSettings.observe(), pendingDelete) { meal, settings, pending ->
        MealState(loading = false, meal = meal, settings = settings, pendingDeleteUsage = pending)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MealState())

    fun requestDelete() {
        viewModelScope.launch { pendingDelete.value = c.meals.usageCount(mealId) }
    }

    fun cancelDelete() {
        pendingDelete.value = null
    }

    fun confirmDelete() {
        viewModelScope.launch {
            pendingDelete.value = null
            c.meals.delete(mealId)
            _deleted.tryEmit(Unit)
        }
    }
}
