package com.animesh.fitnesstracker.ui.diet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animesh.fitnesstracker.data.model.Meal
import com.animesh.fitnesstracker.data.model.MealSlot
import com.animesh.fitnesstracker.di.AppContainer
import com.animesh.fitnesstracker.domain.diet.DayMenu
import com.animesh.fitnesstracker.repository.DietPlanRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/** One grid cell: the meal in a day and slot, or empty. */
data class WeekCell(val day: Int, val slot: MealSlot, val mealId: Long?, val name: String?, val prep: Boolean)

data class WeekDayRow(val day: Int, val label: String, val isToday: Boolean, val proteinText: String, val cells: List<WeekCell>)

/** The cell being picked for, with the meals tagged for its slot. */
data class WeekPick(val day: Int, val slot: MealSlot, val options: List<MealListRow>)

data class WeekPlanState(
    val loading: Boolean = true,
    val planName: String = DietPlanRepository.DEFAULT_NAME,
    val today: Int = 0,
    val days: List<WeekDayRow> = emptyList(),
    val pick: WeekPick? = null
) {
    val todayLabel: String get() = DAY_LABELS[today]

    companion object {
        val DAY_LABELS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class WeekPlanViewModel(private val c: AppContainer) : ViewModel() {
    private val picking = MutableStateFlow<Pair<Int, MealSlot>?>(null)
    private val today = DayMenu.dayOfWeek(LocalDate.now())

    private val options = picking.flatMapLatest { p -> if (p == null) flowOf(emptyList()) else c.meals.observeMeals("", p.second) }

    val state: StateFlow<WeekPlanState> = combine(c.dietPlans.observeActive(), picking, options) { plan, p, opts ->
        val days = WeekPlanState.DAY_LABELS.mapIndexed { day, label ->
            val entries = DayMenu.entries(plan, day)
            WeekDayRow(
                day = day, label = label, isToday = day == today,
                proteinText = "${DayMenu.proteinTotal(entries).roundToInt()} g",
                cells = entries.map { e -> WeekCell(day, e.slot, e.meal?.id, e.meal?.name, e.meal?.prepDayBefore == true) }
            )
        }
        WeekPlanState(
            loading = plan == null,
            planName = plan?.plan?.name ?: DietPlanRepository.DEFAULT_NAME,
            today = today,
            days = days,
            pick = p?.let { (day, slot) -> WeekPick(day, slot, opts.map { m -> MealListRow(m, mealMacrosLine(m)) }) }
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeekPlanState(today = today))

    init {
        viewModelScope.launch { c.dietPlans.ensureActive() }
    }

    fun openCell(day: Int, slot: MealSlot) { picking.value = day to slot }
    fun closePicker() { picking.value = null }

    /** Puts [meal] in the cell being picked for and closes the picker. */
    fun pick(meal: Meal) {
        val (day, slot) = picking.value ?: return
        picking.value = null
        viewModelScope.launch { c.dietPlans.setCell(day, slot, meal.id) }
    }

    /** Empties the cell being picked for and closes the picker. */
    fun leaveEmpty() {
        val (day, slot) = picking.value ?: return
        picking.value = null
        viewModelScope.launch { c.dietPlans.clearCell(day, slot) }
    }

    /** Copies today's three meals onto the other six days. */
    fun copyTodayToAll() = viewModelScope.launch { c.dietPlans.copyDay(today) }
}
