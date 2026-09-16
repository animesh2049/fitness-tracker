package com.animesh.fitnesstracker.ui.diet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animesh.fitnesstracker.data.model.MealSlot
import com.animesh.fitnesstracker.ui.appContainer
import com.animesh.fitnesstracker.ui.components.AppCard
import com.animesh.fitnesstracker.ui.components.BottomActionBar
import com.animesh.fitnesstracker.ui.components.OutlineChipButton
import com.animesh.fitnesstracker.ui.components.PillTag
import com.animesh.fitnesstracker.ui.components.PrimaryButton
import com.animesh.fitnesstracker.ui.components.ScreenHeader
import com.animesh.fitnesstracker.ui.components.SecondaryButton
import com.animesh.fitnesstracker.ui.plan.PlanTextField
import com.animesh.fitnesstracker.ui.plan.ToggleChip
import com.animesh.fitnesstracker.ui.plan.plural
import com.animesh.fitnesstracker.ui.theme.MonoNumber
import com.animesh.fitnesstracker.ui.theme.Tokens

/** The meal library: search, slot filter, and a card per meal with open, edit and inline delete. */
@Composable
fun MealsScreen(onOpenMeal: (Long) -> Unit, onEditMeal: (Long) -> Unit, onBack: () -> Unit) {
    val container = appContainer()
    val vm: MealsViewModel = viewModel { MealsViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Diet · Meals", "Meals", "${plural(state.total, "meal")} · ${state.prepCount} need prep the day before")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PlanTextField(state.search, vm::setSearch, "Search meals", placeholder = "Rajma, oats, paneer…")
            SlotFilterRow(state.slot, vm::setSlot)
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!state.loading && state.rows.isEmpty()) {
                item {
                    Text(
                        if (state.search.isBlank() && state.slot == null) "No meals here. Add one, or restore the ones you deleted from a backup." else "Nothing matches that search.",
                        style = MaterialTheme.typography.bodyMedium, color = Tokens.Muted, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp)
                    )
                }
            }
            items(state.rows, key = { it.id }) { row ->
                MealCard(
                    row = row,
                    confirm = state.confirming?.takeIf { it.mealId == row.id },
                    onOpen = { onOpenMeal(row.id) },
                    onEdit = { onEditMeal(row.id) },
                    onAskDelete = { vm.askDelete(row.id) },
                    onConfirmDelete = vm::confirmDelete,
                    onCancelDelete = vm::cancelDelete
                )
            }
        }
        BottomActionBar {
            PrimaryButton("+ Add meal", { onEditMeal(0L) }, height = 52)
            SecondaryButton("Back", onBack, Modifier.fillMaxWidth(), height = 44)
        }
    }
}

/** All / Breakfast / Lunch / Dinner, equal widths; All is selected when [selected] is null. */
@Composable
private fun SlotFilterRow(selected: MealSlot?, onSelect: (MealSlot?) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleChip("All", selected == null, { onSelect(null) }, Modifier.weight(1f))
        MealSlot.entries.forEach { s -> ToggleChip(slotLabel(s), selected == s, { onSelect(s) }, Modifier.weight(1f)) }
    }
}

@Composable
private fun MealCard(
    row: MealListRow,
    confirm: DeleteConfirm?,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onAskDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit
) {
    val meal = row.meal
    AppCard(padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(meal.name, style = MaterialTheme.typography.titleLarge, color = Tokens.Text)
                    Text(row.macrosLine, style = MonoNumber, color = Tokens.Muted)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (meal.prepDayBefore) PillTag("Prep", color = Tokens.Warning, borderColor = Tokens.WarningBorder)
                    meal.slotList.forEach { PillTag(slotLabel(it)) }
                }
            }
            if (confirm != null) {
                DeleteStrip(confirm.text, onConfirmDelete, onCancelDelete)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlineChipButton("Open", onOpen, borderColor = Tokens.BorderStrong)
                    OutlineChipButton("Edit", onEdit, borderColor = Tokens.BorderStrong)
                    Spacer(Modifier.weight(1f))
                    MutedTextButton("Delete", onAskDelete)
                }
            }
        }
    }
}

/** Danger-tinted inline confirm row: text, Delete and Keep. */
@Composable
private fun DeleteStrip(text: String, onDelete: () -> Unit, onKeep: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Tokens.Danger.copy(alpha = 0.08f)).border(1.dp, Tokens.DangerBorder, shape).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = Tokens.Danger, modifier = Modifier.weight(1f))
        Button(
            onClick = onDelete, modifier = Modifier.height(40.dp), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Tokens.Danger, contentColor = Tokens.Ground)
        ) { Text("Delete", style = MaterialTheme.typography.labelLarge.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize)) }
        Button(
            onClick = onKeep, modifier = Modifier.height(40.dp), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 12.dp),
            border = BorderStroke(1.dp, Tokens.Faint),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Tokens.Text)
        ) { Text("Keep", style = MaterialTheme.typography.bodySmall) }
    }
}

/** Borderless 40 dp button in the dim colour, for the low-emphasis Delete action. */
@Composable
private fun MutedTextButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = Modifier.height(40.dp), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Tokens.Dim)
    ) { Text(text, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)) }
}
