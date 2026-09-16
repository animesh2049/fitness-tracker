package com.animesh.fitnesstracker.ui.diet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animesh.fitnesstracker.data.model.DietPlanWithCells
import com.animesh.fitnesstracker.data.model.DietSettings
import com.animesh.fitnesstracker.data.model.MealSlot
import com.animesh.fitnesstracker.di.AppContainer
import com.animesh.fitnesstracker.domain.diet.DayMenu
import com.animesh.fitnesstracker.domain.diet.MealClock
import com.animesh.fitnesstracker.domain.diet.PrepPlanner
import com.animesh.fitnesstracker.domain.diet.Scaling
import com.animesh.fitnesstracker.domain.diet.WindowRules
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A mock notification card for the "How they look" section. */
data class ReminderPreview(
    /** "Fitness Tracker · Tue 21:00". */
    val header: String,
    val title: String,
    val body: String,
    val actions: List<String> = emptyList()
)

data class DietRemindersState(
    val loading: Boolean = true,
    val settings: DietSettings = DietSettings(),
    /** Problems with the saved windows plus the message from the last rejected change. */
    val problems: List<String> = emptyList(),
    val prepPreview: ReminderPreview = SAMPLE_PREP,
    val windowPreview: ReminderPreview = SAMPLE_WINDOW
)

private const val APP_NAME = "Fitness Tracker"
private val SAMPLE_PREP = ReminderPreview(
    "$APP_NAME · Tue 21:00", "Prep for tomorrow's lunch",
    "Rajma chawal: soak 1 cup rajma in plenty of water overnight, at least 8 hours.", listOf("Done", "Open recipe")
)
private val SAMPLE_WINDOW = ReminderPreview("$APP_NAME · Wed 11:30", "Lunch: Rajma chawal", "1 serving · 560 kcal · 20 g protein")

class DietRemindersViewModel(private val c: AppContainer) : ViewModel() {
    private val rejected = MutableStateFlow<List<String>>(emptyList())
    private val dayName = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())

    val state: StateFlow<DietRemindersState> = combine(c.dietSettings.observe(), c.dietPlans.observeActive(), rejected) { settings, plan, rejected ->
        val now = LocalDateTime.now()
        DietRemindersState(
            loading = false,
            settings = settings,
            problems = (WindowRules.validate(settings) + rejected).distinct(),
            prepPreview = prepPreview(settings, plan, now),
            windowPreview = windowPreview(settings, plan, now)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DietRemindersState())

    fun setWindowStart(slot: MealSlot, minute: Int) = change { s ->
        when (slot) {
            MealSlot.BREAKFAST -> s.copy(breakfastStart = minute)
            MealSlot.LUNCH -> s.copy(lunchStart = minute)
            MealSlot.DINNER -> s.copy(dinnerStart = minute)
        }
    }

    fun setWindowEnd(slot: MealSlot, minute: Int) = change { s ->
        when (slot) {
            MealSlot.BREAKFAST -> s.copy(breakfastEnd = minute)
            MealSlot.LUNCH -> s.copy(lunchEnd = minute)
            MealSlot.DINNER -> s.copy(dinnerEnd = minute)
        }
    }

    fun setPrepMinute(minute: Int) = change { it.copy(prepReminderMinute = minute) }

    fun setPrepEnabled(on: Boolean) {
        viewModelScope.launch { c.dietSettings.update { it.copy(prepReminderEnabled = on) } }
    }

    fun setMealEnabled(on: Boolean) {
        viewModelScope.launch { c.dietSettings.update { it.copy(mealReminderEnabled = on) } }
    }

    /** Applies [transform] only when the result passes [WindowRules]; otherwise keeps the saved value and shows why. */
    private fun change(transform: (DietSettings) -> DietSettings) {
        viewModelScope.launch {
            val candidate = transform(c.dietSettings.get())
            val problems = WindowRules.validate(candidate)
            if (problems.isEmpty()) {
                rejected.value = emptyList()
                c.dietSettings.update(transform)
            } else {
                rejected.value = problems
            }
        }
    }

    private fun prepPreview(settings: DietSettings, plan: DietPlanWithCells?, now: LocalDateTime): ReminderPreview {
        val items = PrepPlanner.forDate(plan, now.toLocalDate())
        val (title, body) = PrepPlanner.notificationText(items) ?: return SAMPLE_PREP
        val header = "$APP_NAME · ${now.toLocalDate().format(dayName)} ${MealClock.formatMinute(settings.prepReminderMinute)}"
        return ReminderPreview(header, title, body, listOf("Done", "Open recipe"))
    }

    private fun windowPreview(settings: DietSettings, plan: DietPlanWithCells?, now: LocalDateTime): ReminderPreview {
        val (slot, at) = PrepPlanner.nextWindowTrigger(settings.copy(mealReminderEnabled = true), now) ?: return SAMPLE_WINDOW
        val entry = DayMenu.entries(plan, DayMenu.dayOfWeek(at.toLocalDate())).firstOrNull { it.slot == slot }
        val meal = entry?.meal ?: return SAMPLE_WINDOW
        val header = "$APP_NAME · ${at.toLocalDate().format(dayName)} ${MealClock.formatMinute(at.hour * 60 + at.minute)}"
        val servings = if (entry.servings == 1.0) "1 serving" else "${Scaling.format(entry.servings)} servings"
        val body = Scaling.macros(meal, entry.servings)?.let { "$servings · ${it.summary()}" } ?: servings
        val label = slot.name.lowercase().replaceFirstChar { it.uppercase() }
        return ReminderPreview(header, "$label: ${meal.name}", body)
    }
}
