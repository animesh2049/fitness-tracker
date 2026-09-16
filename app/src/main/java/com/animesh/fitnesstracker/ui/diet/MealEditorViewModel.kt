package com.animesh.fitnesstracker.ui.diet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animesh.fitnesstracker.data.model.Ingredient
import com.animesh.fitnesstracker.data.model.Meal
import com.animesh.fitnesstracker.data.model.MealSlot
import com.animesh.fitnesstracker.data.model.MealStep
import com.animesh.fitnesstracker.di.AppContainer
import com.animesh.fitnesstracker.domain.diet.Scaling
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** An ingredient row in the editor. [key] is stable across reorders so the list can animate and keep focus. */
data class IngredientDraft(val key: Long, val name: String = "", val amountText: String = "1", val unit: String = "")

data class StepDraft(val key: Long, val text: String = "")

/** Editable copy of a meal. Numbers are kept as text so the user can clear and retype them. */
data class MealDraft(
    val name: String = "",
    val slots: Set<MealSlot> = emptySet(),
    val servings: Int = 1,
    val cookText: String = "",
    val kcalText: String = "",
    val proteinText: String = "",
    val carbsText: String = "",
    val fatText: String = "",
    val ingredients: List<IngredientDraft> = emptyList(),
    val steps: List<StepDraft> = emptyList(),
    val prepDayBefore: Boolean = false,
    val prepInstruction: String = ""
)

data class MealEditorState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val draft: MealDraft = MealDraft(),
    val dirty: Boolean = false,
    /** Shown under the name field. */
    val error: String? = null
) {
    val canSave: Boolean get() = loaded && draft.name.isNotBlank() && draft.slots.isNotEmpty()
}

class MealEditorViewModel(private val c: AppContainer, private val mealId: Long) : ViewModel() {
    private val draft = MutableStateFlow(MealDraft())
    private val saved = MutableStateFlow(MealDraft())
    private val loaded = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private var original: Meal? = null
    private var nextKey = 1L

    private val _closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits after a successful save or an explicit discard. */
    val closed: SharedFlow<Unit> = _closed

    val state: StateFlow<MealEditorState> = combine(draft, saved, loaded, error) { d, s, l, err ->
        MealEditorState(loaded = l, isNew = mealId == 0L, draft = d, dirty = d != s, error = err)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MealEditorState())

    init {
        viewModelScope.launch {
            val existing = if (mealId != 0L) c.meals.getMeal(mealId) else null
            original = existing?.meal
            val initial = if (existing == null) {
                MealDraft(ingredients = listOf(IngredientDraft(newKey())), steps = listOf(StepDraft(newKey())))
            } else {
                val m = existing.meal
                MealDraft(
                    name = m.name,
                    slots = m.slotList.toSet(),
                    servings = m.servings.coerceIn(SERVINGS_MIN, SERVINGS_MAX),
                    cookText = m.cookMinutes?.toString() ?: "",
                    kcalText = m.kcal?.let(::formatMacro) ?: "",
                    proteinText = m.proteinG?.let(::formatMacro) ?: "",
                    carbsText = m.carbsG?.let(::formatMacro) ?: "",
                    fatText = m.fatG?.let(::formatMacro) ?: "",
                    ingredients = existing.sortedIngredients.map { IngredientDraft(newKey(), it.name, Scaling.format(it.amount), it.unit) },
                    steps = existing.sortedSteps.map { StepDraft(newKey(), it.text) },
                    prepDayBefore = m.prepDayBefore,
                    prepInstruction = m.prepInstruction
                )
            }
            draft.value = initial
            saved.value = initial
            loaded.value = true
        }
    }

    private fun newKey(): Long = nextKey++

    fun setName(v: String) { draft.update { it.copy(name = v) }; error.value = null }
    fun toggleSlot(s: MealSlot) = draft.update { it.copy(slots = if (s in it.slots) it.slots - s else it.slots + s) }
    fun setServings(n: Int) = draft.update { it.copy(servings = n.coerceIn(SERVINGS_MIN, SERVINGS_MAX)) }
    fun setCook(v: String) = draft.update { it.copy(cookText = v.filter(Char::isDigit)) }
    fun setKcal(v: String) = draft.update { it.copy(kcalText = decimal(v)) }
    fun setProtein(v: String) = draft.update { it.copy(proteinText = decimal(v)) }
    fun setCarbs(v: String) = draft.update { it.copy(carbsText = decimal(v)) }
    fun setFat(v: String) = draft.update { it.copy(fatText = decimal(v)) }
    fun setPrep(on: Boolean) = draft.update { it.copy(prepDayBefore = on) }
    fun setPrepInstruction(v: String) = draft.update { it.copy(prepInstruction = v) }

    fun addIngredient() = draft.update { it.copy(ingredients = it.ingredients + IngredientDraft(newKey())) }
    fun setIngredientName(key: Long, v: String) = updateIngredient(key) { it.copy(name = v) }
    fun setIngredientAmount(key: Long, v: String) = updateIngredient(key) { it.copy(amountText = decimal(v)) }
    fun setIngredientUnit(key: Long, v: String) = updateIngredient(key) { it.copy(unit = v) }
    fun removeIngredient(key: Long) = draft.update { d -> d.copy(ingredients = d.ingredients.filter { it.key != key }) }
    fun moveIngredient(from: Int, to: Int) = draft.update { d -> d.copy(ingredients = moved(d.ingredients, from, to)) }

    fun addStep() = draft.update { it.copy(steps = it.steps + StepDraft(newKey())) }
    fun setStepText(key: Long, v: String) = draft.update { d -> d.copy(steps = d.steps.map { if (it.key == key) it.copy(text = v) else it }) }
    fun removeStep(key: Long) = draft.update { d -> d.copy(steps = d.steps.filter { it.key != key }) }
    fun moveStep(from: Int, to: Int) = draft.update { d -> d.copy(steps = moved(d.steps, from, to)) }

    private fun updateIngredient(key: Long, f: (IngredientDraft) -> IngredientDraft) =
        draft.update { d -> d.copy(ingredients = d.ingredients.map { if (it.key == key) f(it) else it }) }

    private fun <T> moved(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        return list.toMutableList().apply { add(to, removeAt(from)) }
    }

    fun save() {
        val d = draft.value
        val name = d.name.trim()
        if (name.isEmpty()) { error.value = "Give the meal a name."; return }
        if (d.slots.isEmpty()) { error.value = "Pick at least one meal time."; return }
        viewModelScope.launch {
            val base = original ?: Meal(name = name)
            val meal = base.copy(
                name = name,
                slots = Meal.slotsString(MealSlot.entries.filter { it in d.slots }),
                servings = d.servings.coerceIn(SERVINGS_MIN, SERVINGS_MAX),
                cookMinutes = d.cookText.toIntOrNull()?.takeIf { it > 0 },
                kcal = parseMacro(d.kcalText),
                proteinG = parseMacro(d.proteinText),
                carbsG = parseMacro(d.carbsText),
                fatG = parseMacro(d.fatText),
                prepDayBefore = d.prepDayBefore,
                prepInstruction = if (d.prepDayBefore) d.prepInstruction.trim() else ""
            )
            val ingredients = d.ingredients
                .filter { it.name.isNotBlank() }
                .mapIndexed { i, ing ->
                    Ingredient(mealId = base.id, position = i, name = ing.name.trim(), amount = parseAmount(ing.amountText), unit = ing.unit.trim())
                }
            val steps = d.steps.filter { it.text.isNotBlank() }.mapIndexed { i, s -> MealStep(mealId = base.id, position = i, text = s.text.trim()) }
            try {
                c.meals.save(meal, ingredients, steps)
                saved.value = d
                _closed.tryEmit(Unit)
            } catch (e: IllegalArgumentException) {
                error.value = e.message ?: "Could not save the meal."
            }
        }
    }

    fun discard() { _closed.tryEmit(Unit) }

    private fun decimal(v: String): String = v.replace(',', '.').filter { it.isDigit() || it == '.' }
    private fun parseMacro(text: String): Double? = text.trim().toDoubleOrNull()?.takeIf { it >= 0 }
    private fun parseAmount(text: String): Double = text.trim().toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
    private fun formatMacro(v: Double): String = Scaling.format(v)

    companion object {
        const val SERVINGS_MIN = 1
        const val SERVINGS_MAX = 12
    }
}
