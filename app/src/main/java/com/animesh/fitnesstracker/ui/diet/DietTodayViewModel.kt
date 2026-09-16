package com.animesh.fitnesstracker.ui.diet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animesh.fitnesstracker.data.model.DietPlanWithCells
import com.animesh.fitnesstracker.data.model.DietSettings
import com.animesh.fitnesstracker.data.model.Ingredient
import com.animesh.fitnesstracker.data.model.MealSlot
import com.animesh.fitnesstracker.data.model.MealWithDetails
import com.animesh.fitnesstracker.di.AppContainer
import com.animesh.fitnesstracker.domain.diet.DayMenu
import com.animesh.fitnesstracker.domain.diet.MealClock
import com.animesh.fitnesstracker.domain.diet.MealStatus
import com.animesh.fitnesstracker.domain.diet.PrepPlanner
import com.animesh.fitnesstracker.domain.diet.Scaling
import com.animesh.fitnesstracker.util.Dates
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import kotlin.math.roundToInt

/** One of today's three slot rows. [ingredients] is null until the meal's details have been loaded. */
data class DietMealRow(
    val entry: DayMenu.Entry,
    val status: MealStatus,
    /** "6:00 to 10:30". */
    val window: String,
    /** "1 serving · 40 min cook". */
    val qty: String,
    val expanded: Boolean,
    val ingredients: List<Pair<Ingredient, String>>?
)

/** The big card at the top: the current meal, or the next one between windows. */
data class DietHero(
    val entry: DayMenu.Entry,
    /** "Now · Lunch", "Next · Dinner at 18:30", "Next · Breakfast, tomorrow 6:00". */
    val kicker: String,
    /** "Until 15:30", "Opens 18:30", "Tomorrow 6:00". */
    val time: String,
    val qty: String,
    /** "560 kcal", "20 g protein", "10 g fat" for the entry's servings. */
    val macroChips: List<String>
)

data class DietTodayState(
    val loading: Boolean = true,
    val dateLine: String = "Diet",
    val title: String = "Today",
    val subtitle: String? = null,
    val hero: DietHero? = null,
    val rows: List<DietMealRow> = emptyList(),
    /** "1600 kcal · 94 g protein". */
    val totals: String = "",
    val prepText: String? = null,
    val prepVisible: Boolean = false
)

class DietTodayViewModel(private val c: AppContainer) : ViewModel() {
    private val expanded = MutableStateFlow<MealSlot?>(null)
    private val details = MutableStateFlow<Map<Long, MealWithDetails>>(emptyMap())

    /** Re-evaluates the meal clock every 30 seconds so the current slot moves on without a restart. */
    private val ticker = flow {
        while (true) {
            emit(LocalDateTime.now())
            delay(30_000)
        }
    }

    init {
        viewModelScope.launch { c.dietPlans.ensureActive() }
    }

    val state: StateFlow<DietTodayState> = combine(
        c.dietPlans.observeActive(), c.dietSettings.observe(), ticker, expanded, details
    ) { plan, settings, now, open, cache -> build(plan, settings, now, open, cache) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DietTodayState())

    private fun build(plan: DietPlanWithCells?, settings: DietSettings, now: LocalDateTime, open: MealSlot?, cache: Map<Long, MealWithDetails>): DietTodayState {
        val today = now.toLocalDate()
        val minute = now.hour * 60 + now.minute
        val clock = MealClock.state(settings, minute)
        val entries = DayMenu.entries(plan, DayMenu.dayOfWeek(today))

        val rows = entries.map { e ->
            val loaded = e.meal?.let { cache[it.id] }
            DietMealRow(
                entry = e,
                status = MealClock.statusOf(e.slot, clock),
                window = windowLabel(settings, e.slot),
                qty = qtyLine(e),
                expanded = open == e.slot,
                ingredients = loaded?.let { DayMenu.scaledIngredients(it, e.servings) }
            )
        }

        val heroSlot: MealSlot
        val heroEntry: DayMenu.Entry
        val kicker: String
        val heroTime: String
        val title: String
        val subtitle: String
        when (clock) {
            is MealClock.State.Current -> {
                heroSlot = clock.slot
                heroEntry = entries.first { it.slot == heroSlot }
                kicker = "Now · ${slotLabel(heroSlot)}"
                heroTime = "Until ${MealClock.formatMinute(clock.endsAtMinute)}"
                title = slotLabel(heroSlot)
                subtitle = "Meal ${heroSlot.ordinal + 1} of ${MealSlot.entries.size} · window ${windowLabel(settings, heroSlot)}"
            }
            is MealClock.State.Between -> {
                heroSlot = clock.next
                heroEntry = entries.first { it.slot == heroSlot }
                kicker = "Next · ${slotLabel(heroSlot)} at ${MealClock.formatMinute(clock.startsAtMinute)}"
                heroTime = "Opens ${MealClock.formatMinute(clock.startsAtMinute)}"
                title = slotLabel(heroSlot)
                subtitle = "Meal ${heroSlot.ordinal + 1} of ${MealSlot.entries.size} · window ${windowLabel(settings, heroSlot)}"
            }
            is MealClock.State.AfterLast -> {
                heroSlot = clock.nextSlot
                heroEntry = DayMenu.entries(plan, DayMenu.dayOfWeek(today.plusDays(1))).first { it.slot == heroSlot }
                val start = MealClock.formatMinute(clock.startsAtMinute)
                kicker = "Next · ${slotLabel(heroSlot)}, tomorrow $start"
                heroTime = "Tomorrow $start"
                title = "All meals done"
                subtitle = "Tomorrow starts with ${slotLabel(heroSlot).lowercase()} at $start"
            }
        }
        val hero = DietHero(heroEntry, kicker, heroTime, qtyLine(heroEntry), macroChips(heroEntry))

        val totals = DayMenu.totals(entries)?.summary() ?: run {
            if (entries.all { it.isEmpty }) "Nothing planned"
            else "${DayMenu.proteinTotal(entries).roundToInt()} g protein · kcal not set for every meal"
        }

        val prepItems = PrepPlanner.forDate(plan, today)
        val prepVisible = prepItems.isNotEmpty() && PrepPlanner.bannerVisible(settings, minute, today.toEpochDay())
        val prepText = prepItems.takeIf { it.isNotEmpty() }?.joinToString(" ") { item ->
            "${item.instruction.trim().trimEnd('.')} for ${item.slot.name.lowercase()} (${item.meal.name})."
        }

        return DietTodayState(
            loading = false,
            dateLine = "Diet · ${Dates.shortDay(today.toEpochDay())} · ${MealClock.formatMinute(minute)}",
            title = title,
            subtitle = subtitle,
            hero = hero,
            rows = rows,
            totals = totals,
            prepText = prepText,
            prepVisible = prepVisible
        )
    }

    /** Expands or collapses a row; loads the meal's ingredients the first time it opens. */
    fun toggle(slot: MealSlot, mealId: Long?) {
        expanded.value = if (expanded.value == slot) null else slot
        if (mealId != null && mealId !in details.value) {
            viewModelScope.launch {
                c.meals.getMeal(mealId)?.let { d -> details.value = details.value + (mealId to d) }
            }
        }
    }

    /** Hides the prep banner for the rest of today. */
    fun prepDone() {
        viewModelScope.launch {
            val today = Dates.todayEpochDay()
            c.dietSettings.update { it.copy(prepDoneEpochDay = today) }
        }
    }

    private fun windowLabel(settings: DietSettings, slot: MealSlot): String {
        val w = settings.window(slot)
        return "${MealClock.formatMinute(w.first)} to ${MealClock.formatMinute(w.last)}"
    }

    private fun qtyLine(entry: DayMenu.Entry): String {
        val meal = entry.meal ?: return "Add one in the week plan"
        val n = Scaling.format(entry.servings)
        val servings = if (entry.servings == 1.0) "1 serving" else "$n servings"
        return meal.cookMinutes?.let { "$servings · $it min cook" } ?: servings
    }

    private fun macroChips(entry: DayMenu.Entry): List<String> {
        val meal = entry.meal ?: return emptyList()
        val s = entry.servings
        return listOfNotNull(
            meal.kcal?.let { "${(it * s).roundToInt()} kcal" },
            meal.proteinG?.let { "${(it * s).roundToInt()} g protein" },
            meal.fatG?.let { "${(it * s).roundToInt()} g fat" }
        )
    }

    private fun slotLabel(slot: MealSlot): String = slot.name.lowercase().replaceFirstChar { it.uppercase() }
}
