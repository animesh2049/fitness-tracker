package com.animesh.fitnesstracker.ui.diet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animesh.fitnesstracker.data.model.MealSlot
import com.animesh.fitnesstracker.domain.diet.MealStatus
import com.animesh.fitnesstracker.ui.appContainer
import com.animesh.fitnesstracker.ui.components.AppCard
import com.animesh.fitnesstracker.ui.components.PillTag
import com.animesh.fitnesstracker.ui.components.PrimaryButton
import com.animesh.fitnesstracker.ui.components.ScreenHeader
import com.animesh.fitnesstracker.ui.components.SecondaryButton
import com.animesh.fitnesstracker.ui.components.SectionLabel
import com.animesh.fitnesstracker.ui.theme.MonoNumber
import com.animesh.fitnesstracker.ui.theme.Tokens

@Composable
fun DietTodayScreen(onOpenMeal: (Long) -> Unit, onOpenWeek: () -> Unit, onOpenMeals: () -> Unit, onOpenReminders: () -> Unit) {
    val container = appContainer()
    val vm: DietTodayViewModel = viewModel { DietTodayViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()

    val weekButton: @Composable () -> Unit = {
        val shape = RoundedCornerShape(10.dp)
        IconButton(
            onClick = onOpenWeek,
            modifier = Modifier.size(44.dp).clip(shape).background(Tokens.Surface).border(1.dp, Tokens.Border, shape)
        ) { Icon(Icons.Outlined.CalendarMonth, contentDescription = "Week plan", tint = Tokens.Text) }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(state.dateLine, state.title, state.subtitle, trailing = weekButton)
        if (state.loading) {
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                state.hero?.let { hero -> item("hero") { HeroCard(hero, onOpenMeal, onOpenWeek) } }
                if (state.prepVisible && state.prepText != null) {
                    item("prep") { PrepBanner(state.prepText!!, vm::prepDone) }
                }
                item("meals_label") { SectionLabel("Today's meals", Modifier.padding(top = 8.dp)) }
                items(state.rows, key = { it.entry.slot.name }) { row -> MealRowCard(row, vm) }
                item("totals") {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Day total", style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
                        Text(state.totals, style = MonoNumber, color = Tokens.TextSoft)
                    }
                }
                item("note") {
                    Text(
                        "Snacks and the workout shake are not planned here. Add them as meals if you want them counted.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Tokens.Dim,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
                item("links") {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Meals", onOpenMeals, Modifier.weight(1f))
                        SecondaryButton("Reminders", onOpenReminders, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroCard(hero: DietHero, onOpenMeal: (Long) -> Unit, onOpenWeek: () -> Unit) {
    AppCard(Modifier.testTag("diet_hero"), borderColor = Tokens.AccentBorder, background = Tokens.AccentSurface) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(hero.kicker.uppercase(), style = MaterialTheme.typography.labelMedium, color = Tokens.Accent)
            Text(hero.time, style = MonoNumber.copy(fontSize = 13.sp), color = Tokens.AccentText)
        }
        val meal = hero.entry.meal
        if (meal == null) {
            Text("Nothing planned", style = MaterialTheme.typography.headlineSmall, color = Tokens.Text)
            Text("Pick a meal for this slot in the week plan.", style = MaterialTheme.typography.bodySmall, color = Tokens.AccentText)
            PrimaryButton("Open week plan", onOpenWeek, height = 44)
        } else {
            Text(meal.name, style = MaterialTheme.typography.headlineSmall, color = Tokens.Text)
            Text(hero.qty, style = MonoNumber, color = Tokens.AccentText)
            if (hero.macroChips.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    hero.macroChips.forEach { MacroChip(it) }
                }
            }
            PrimaryButton("Open recipe", { onOpenMeal(meal.id) }, height = 44)
        }
    }
}

@Composable
private fun MacroChip(text: String) {
    Text(
        text,
        style = MonoNumber.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
        color = Tokens.Text,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Tokens.Ground)
            .border(1.dp, Tokens.AccentBorder, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

@Composable
private fun PrepBanner(text: String, onDone: () -> Unit) {
    AppCard(
        Modifier.testTag("diet_prep_banner"),
        borderColor = Tokens.WarningBorder, background = Tokens.WarningInk,
        padding = PaddingValues(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 6.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Schedule, contentDescription = null, tint = Tokens.Warning, modifier = Modifier.size(20.dp).padding(top = 1.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("PREP TONIGHT", style = MaterialTheme.typography.labelMedium, color = Tokens.Warning)
                Text(text, style = MaterialTheme.typography.bodyMedium, color = Tokens.Text)
            }
            TextButton(onClick = onDone, modifier = Modifier.heightIn(min = 44.dp)) {
                Text("Done", style = MaterialTheme.typography.labelLarge, color = Tokens.Warning)
            }
        }
    }
}

@Composable
private fun MealRowCard(row: DietMealRow, vm: DietTodayViewModel) {
    val meal = row.entry.meal
    val now = row.status == MealStatus.NOW
    val badge = when (row.status) { MealStatus.DONE -> "Done"; MealStatus.NOW -> "Now"; MealStatus.LATER -> "Later" }
    val badgeColor = when (row.status) { MealStatus.DONE -> Tokens.Dim; MealStatus.NOW -> Tokens.Accent; MealStatus.LATER -> Tokens.Muted }
    AppCard(
        Modifier.alpha(if (row.status == MealStatus.DONE) 0.6f else 1f),
        borderColor = if (now) Tokens.AccentBorder else Tokens.Border,
        onClick = if (meal != null) ({ vm.toggle(row.entry.slot, meal.id) }) else null
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "${slotLabel(row.entry.slot)} · ${row.window}".uppercase(),
                    style = MaterialTheme.typography.labelMedium, color = if (now) Tokens.Accent else Tokens.Muted
                )
                Text(meal?.name ?: "Nothing planned", style = MaterialTheme.typography.titleLarge, color = if (meal == null) Tokens.Muted else Tokens.Text)
                Text(row.qty, style = MonoNumber.copy(fontSize = 13.sp), color = Tokens.Muted)
            }
            PillTag(badge, color = badgeColor, borderColor = if (now) Tokens.AccentBorder else Tokens.BorderStrong)
        }
        if (row.expanded && meal != null) {
            HorizontalDivider(color = Tokens.Border)
            val ingredients = row.ingredients
            when {
                ingredients == null -> Text("Loading…", style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
                ingredients.isEmpty() -> Text("No ingredients listed.", style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
                else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ingredients.forEach { (ing, qty) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(ing.name, style = MaterialTheme.typography.bodyMedium, color = Tokens.TextSoft, modifier = Modifier.weight(1f))
                            Text(qty, style = MonoNumber, color = Tokens.Text)
                        }
                    }
                }
            }
        }
    }
}

