package com.animesh.fitnesstracker.ui.diet

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animesh.fitnesstracker.data.model.MealSlot
import com.animesh.fitnesstracker.data.model.MealWithDetails
import com.animesh.fitnesstracker.domain.diet.DayMenu
import com.animesh.fitnesstracker.domain.diet.MealClock
import com.animesh.fitnesstracker.ui.appContainer
import com.animesh.fitnesstracker.ui.components.AppCard
import com.animesh.fitnesstracker.ui.components.BottomActionBar
import com.animesh.fitnesstracker.ui.components.ConfirmDialog
import com.animesh.fitnesstracker.ui.components.GhostButton
import com.animesh.fitnesstracker.ui.components.PillTag
import com.animesh.fitnesstracker.ui.components.SecondaryButton
import com.animesh.fitnesstracker.ui.components.SectionLabel
import com.animesh.fitnesstracker.ui.components.StatTile
import com.animesh.fitnesstracker.ui.components.Stepper
import com.animesh.fitnesstracker.ui.theme.MonoNumber
import com.animesh.fitnesstracker.ui.theme.MonoStat
import com.animesh.fitnesstracker.ui.theme.Tokens
import kotlin.math.roundToInt

private const val MIN_SERVINGS = 1
private const val MAX_SERVINGS = 6

/** Four-across tiles need a smaller number than the default stat size. */
private val MealStat = MonoStat.copy(fontSize = 18.sp, lineHeight = 22.sp)

@Composable
fun MealScreen(mealId: Long, onEdit: (Long) -> Unit, onBack: () -> Unit, onAddToWeek: () -> Unit) {
    val container = appContainer()
    val vm: MealViewModel = viewModel(key = "meal_$mealId") { MealViewModel(container, mealId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var servings by rememberSaveable { mutableIntStateOf(1) }

    LaunchedEffect(Unit) { vm.deleted.collect { onBack() } }

    val details = state.meal
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = Tokens.TextSoft)
            ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
            Text("DIET · MEAL", style = MaterialTheme.typography.labelMedium, color = Tokens.Muted, modifier = Modifier.weight(1f).padding(start = 4.dp))
            GhostButton("Edit", { onEdit(mealId) }, contentColor = Tokens.Accent, borderColor = Tokens.AccentBorder)
        }

        if (details == null) {
            Spacer(Modifier.weight(1f))
        } else {
            MealBody(details, servings, state.settings.prepReminderMinute, Modifier.weight(1f),
                onMinus = { servings = (servings - 1).coerceAtLeast(MIN_SERVINGS) },
                onPlus = { servings = (servings + 1).coerceAtMost(MAX_SERVINGS) })
            BottomActionBar {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Delete", vm::requestDelete, Modifier.weight(1f), height = 52, contentColor = Tokens.Danger)
                    SecondaryButton("Add to week plan", onAddToWeek, Modifier.weight(2f), height = 52)
                }
            }
        }
    }

    val usage = state.pendingDeleteUsage
    if (usage != null && details != null) {
        val body = when (usage) {
            0 -> "The recipe, its ingredients and steps are removed. No week plan slot uses it."
            1 -> "The recipe, its ingredients and steps are removed. One slot in the week plan uses it and will become empty."
            else -> "The recipe, its ingredients and steps are removed. $usage slots in the week plan use it and will become empty."
        }
        ConfirmDialog(
            title = "Delete ${details.meal.name}?",
            body = body,
            confirmText = "Delete",
            onConfirm = vm::confirmDelete,
            onDismiss = vm::cancelDelete,
            destructive = true
        )
    }
}

@Composable
private fun MealBody(details: MealWithDetails, servings: Int, prepReminderMinute: Int, modifier: Modifier, onMinus: () -> Unit, onPlus: () -> Unit) {
    val meal = details.meal
    val s = servings.toDouble()
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(meal.name, style = MaterialTheme.typography.headlineLarge, color = Tokens.Text)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                meal.slotList.forEach { PillTag(slotLabel(it)) }
                if (meal.prepDayBefore) PillTag("Prep day before", color = Tokens.Warning, borderColor = Tokens.WarningBorder)
                meal.cookMinutes?.let { Text("$it min cook", style = MonoNumber.copy(fontSize = 13.sp), color = Tokens.Muted) }
            }
        }

        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MacroTile("kcal", meal.kcal?.let { "${(it * s).roundToInt()}" }, Modifier.weight(1f))
            MacroTile("protein", meal.proteinG?.let { "${(it * s).roundToInt()} g" }, Modifier.weight(1f), valueColor = Tokens.Accent)
            MacroTile("carbs", meal.carbsG?.let { "${(it * s).roundToInt()} g" }, Modifier.weight(1f))
            MacroTile("fat", meal.fatG?.let { "${(it * s).roundToInt()} g" }, Modifier.weight(1f))
        }

        AppCard(padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Ingredients", Modifier.weight(1f))
                Stepper(
                    label = if (servings == 1) "Serving" else "Servings", value = servings.toString(),
                    onMinus = onMinus, onPlus = onPlus,
                    minusEnabled = servings > MIN_SERVINGS, plusEnabled = servings < MAX_SERVINGS
                )
            }
            val rows = DayMenu.scaledIngredients(details, s)
            if (rows.isEmpty()) {
                Text("No ingredients listed.", style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted)
            } else {
                Column {
                    rows.forEach { (ing, qty) ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 36.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(ing.name, style = MaterialTheme.typography.bodyLarge, color = Tokens.Text, modifier = Modifier.weight(1f))
                            Text(qty, style = MonoNumber, color = Tokens.TextSoft)
                        }
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.Surface2))
                    }
                }
            }
        }

        if (meal.prepDayBefore) {
            AppCard(borderColor = Tokens.WarningBorder, background = Tokens.WarningInk, padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
                Text("DAY BEFORE", style = MaterialTheme.typography.labelMedium, color = Tokens.Warning)
                val instruction = meal.prepInstruction.trim().ifBlank { "Prepare ahead for ${meal.name}." }.let { if (it.endsWith('.')) it else "$it." }
                Text(
                    "$instruction The app reminds you at ${MealClock.formatMinute(prepReminderMinute)} the evening before this meal is planned.",
                    style = MaterialTheme.typography.bodyMedium, color = Tokens.Text
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Procedure")
            val steps = details.sortedSteps
            if (steps.isEmpty()) {
                Text("No steps written yet.", style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted, modifier = Modifier.padding(horizontal = 4.dp))
            }
            steps.forEachIndexed { i, step ->
                AppCard(padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        Text("${i + 1}", style = MonoNumber.copy(fontWeight = FontWeight.SemiBold), color = Tokens.Accent, modifier = Modifier.width(22.dp))
                        Text(step.text, style = MaterialTheme.typography.bodyLarge, color = Tokens.Text)
                    }
                }
            }
        }
    }
}

@Composable
private fun MacroTile(label: String, value: String?, modifier: Modifier, valueColor: androidx.compose.ui.graphics.Color = Tokens.Text) {
    StatTile(
        label = label,
        value = value ?: "Not set",
        modifier = modifier.fillMaxHeight(),
        valueColor = if (value == null) Tokens.Dim else valueColor,
        valueStyle = if (value == null) MonoNumber else MealStat
    )
}

private fun slotLabel(slot: MealSlot): String = slot.name.lowercase().replaceFirstChar { it.uppercase() }
