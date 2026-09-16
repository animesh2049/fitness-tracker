package com.animesh.fitnesstracker.ui.diet

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animesh.fitnesstracker.data.model.MealSlot
import com.animesh.fitnesstracker.ui.appContainer
import com.animesh.fitnesstracker.ui.components.AppCard
import com.animesh.fitnesstracker.ui.components.BottomActionBar
import com.animesh.fitnesstracker.ui.components.ConfirmDialog
import com.animesh.fitnesstracker.ui.components.OutlineChipButton
import com.animesh.fitnesstracker.ui.components.PrimaryButton
import com.animesh.fitnesstracker.ui.components.ScreenHeader
import com.animesh.fitnesstracker.ui.components.SecondaryButton
import com.animesh.fitnesstracker.ui.components.Stepper
import com.animesh.fitnesstracker.ui.plan.FieldLabel
import com.animesh.fitnesstracker.ui.plan.PlanTextField
import com.animesh.fitnesstracker.ui.plan.ToggleChip
import com.animesh.fitnesstracker.ui.plan.plural
import com.animesh.fitnesstracker.ui.theme.MonoNumber
import com.animesh.fitnesstracker.ui.theme.Tokens

/** Creates (id 0) or edits a meal: name, slots, servings, macros, ingredients, steps and the prep flag. */
@Composable
fun MealEditorScreen(mealId: Long, onClose: () -> Unit) {
    val container = appContainer()
    val vm: MealEditorViewModel = viewModel(key = "meal-$mealId") { MealEditorViewModel(container, mealId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.closed.collect { onClose() } }
    val cancel: () -> Unit = { if (state.dirty) confirmDiscard = true else onClose() }
    BackHandler(onBack = cancel)

    val d = state.draft
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "Diet · " + if (state.isNew) "New meal" else "Edit meal",
            d.name.ifBlank { if (state.isNew) "New meal" else "Meal" },
            buildString {
                append(plural(d.ingredients.count { it.name.isNotBlank() }, "ingredient"))
                append(" · ")
                append(plural(d.steps.count { it.text.isNotBlank() }, "step"))
                if (d.prepDayBefore) append(" · prep day before")
            }
        )
        if (!state.loaded) {
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item { PlanTextField(d.name, vm::setName, "Name", placeholder = "Rajma chawal", isError = state.error != null, supporting = state.error) }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FieldLabel("Eaten at")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MealSlot.entries.forEach { s -> ToggleChip(slotLabel(s), s in d.slots, { vm.toggleSlot(s) }, Modifier.weight(1f)) }
                        }
                        if (d.slots.isEmpty()) {
                            Text("Pick at least one.", style = MaterialTheme.typography.bodySmall, color = Tokens.Dim, modifier = Modifier.padding(horizontal = 4.dp))
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                        Stepper(
                            "Servings", "${d.servings}",
                            onMinus = { vm.setServings(d.servings - 1) }, onPlus = { vm.setServings(d.servings + 1) },
                            modifier = Modifier.weight(1f)
                        )
                        PlanTextField(d.cookText, vm::setCook, "Cook time (min)", Modifier.weight(1f), numeric = true, placeholder = "Optional")
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FieldLabel("Per serving (optional)")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MacroField(d.kcalText, vm::setKcal, "kcal", Modifier.weight(1f))
                            MacroField(d.proteinText, vm::setProtein, "protein", Modifier.weight(1f))
                            MacroField(d.carbsText, vm::setCarbs, "carbs", Modifier.weight(1f))
                            MacroField(d.fatText, vm::setFat, "fat", Modifier.weight(1f))
                        }
                        Text("Grams for protein, carbs and fat. Leave any blank to skip macros.", style = MaterialTheme.typography.bodySmall, color = Tokens.Dim, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }
                item {
                    AppCard(padding = PaddingValues(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FieldLabel("Ingredients per serving")
                            if (d.ingredients.isEmpty()) {
                                Text("No ingredients yet.", style = MaterialTheme.typography.bodySmall, color = Tokens.Dim, modifier = Modifier.padding(vertical = 4.dp))
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                d.ingredients.forEachIndexed { i, ing ->
                                    IngredientRow(
                                        ing = ing,
                                        canUp = i > 0, canDown = i < d.ingredients.lastIndex,
                                        onName = { vm.setIngredientName(ing.key, it) },
                                        onAmount = { vm.setIngredientAmount(ing.key, it) },
                                        onUnit = { vm.setIngredientUnit(ing.key, it) },
                                        onUp = { vm.moveIngredient(i, i - 1) }, onDown = { vm.moveIngredient(i, i + 1) },
                                        onRemove = { vm.removeIngredient(ing.key) }
                                    )
                                }
                            }
                            OutlineChipButton("+ Add ingredient", vm::addIngredient, contentColor = Tokens.Accent, borderColor = Tokens.AccentBorder)
                        }
                    }
                }
                item {
                    AppCard(padding = PaddingValues(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FieldLabel("Procedure")
                            if (d.steps.isEmpty()) {
                                Text("No steps yet.", style = MaterialTheme.typography.bodySmall, color = Tokens.Dim, modifier = Modifier.padding(vertical = 4.dp))
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                d.steps.forEachIndexed { i, step ->
                                    StepRow(
                                        num = i + 1, step = step,
                                        canUp = i > 0, canDown = i < d.steps.lastIndex,
                                        onText = { vm.setStepText(step.key, it) },
                                        onUp = { vm.moveStep(i, i - 1) }, onDown = { vm.moveStep(i, i + 1) },
                                        onRemove = { vm.removeStep(step.key) }
                                    )
                                }
                            }
                            OutlineChipButton("+ Add step", vm::addStep, contentColor = Tokens.Accent, borderColor = Tokens.AccentBorder)
                        }
                    }
                }
                item {
                    AppCard(padding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("Needs prep the day before", style = MaterialTheme.typography.titleLarge, color = Tokens.Text)
                                    Text("Reminder the evening before it is planned", style = MaterialTheme.typography.bodySmall, color = Tokens.Muted)
                                }
                                Switch(
                                    checked = d.prepDayBefore, onCheckedChange = vm::setPrep,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Tokens.AccentInk, checkedTrackColor = Tokens.Accent,
                                        uncheckedThumbColor = Tokens.Muted, uncheckedTrackColor = Tokens.Surface2, uncheckedBorderColor = Tokens.BorderStrong
                                    )
                                )
                            }
                            if (d.prepDayBefore) {
                                CompactField(
                                    d.prepInstruction, vm::setPrepInstruction, Modifier.fillMaxWidth(),
                                    placeholder = "What to do the evening before, for example soak the rajma overnight.",
                                    singleLine = false, minHeight = 60, borderColor = Tokens.WarningBorder
                                )
                            }
                        }
                    }
                }
            }
        }
        BottomActionBar {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Cancel", cancel, Modifier.weight(1f), height = 52)
                PrimaryButton("Save meal", vm::save, Modifier.weight(2f).testTag("meal_editor_save"), height = 52, enabled = state.canSave)
            }
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            "Discard changes?", "Your edits to this meal will be lost.", "Discard",
            onConfirm = { confirmDiscard = false; vm.discard() }, onDismiss = { confirmDiscard = false }, destructive = true
        )
    }
}

/** Name on the first line with move and remove controls; amount and unit on the second. */
@Composable
private fun IngredientRow(
    ing: IngredientDraft,
    canUp: Boolean, canDown: Boolean,
    onName: (String) -> Unit, onAmount: (String) -> Unit, onUnit: (String) -> Unit,
    onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            CompactField(ing.name, onName, Modifier.weight(1f).padding(end = 6.dp), placeholder = "Ingredient")
            MoveButtons(canUp, canDown, onUp, onDown)
            RemoveButton(onRemove)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CompactField(ing.amountText, onAmount, Modifier.width(72.dp), placeholder = "1", mono = true, keyboardType = KeyboardType.Decimal, centered = true)
            CompactField(ing.unit, onUnit, Modifier.width(88.dp), placeholder = "unit", centered = true)
            Text("per serving", style = MaterialTheme.typography.bodySmall, color = Tokens.Dim)
        }
    }
}

/** Numbered multi-line step with move and remove controls. */
@Composable
private fun StepRow(
    num: Int, step: StepDraft,
    canUp: Boolean, canDown: Boolean,
    onText: (String) -> Unit, onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit
) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$num", style = MonoNumber, color = Tokens.Accent, modifier = Modifier.width(20.dp).padding(top = 12.dp))
        CompactField(
            step.text, onText, Modifier.weight(1f).fillMaxHeight().padding(end = 6.dp),
            placeholder = "Describe this step", singleLine = false, minHeight = 44
        )
        Column {
            IconButton(onClick = onUp, enabled = canUp, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Move up", tint = if (canUp) Tokens.TextSoft else Tokens.Faint)
            }
            IconButton(onClick = onDown, enabled = canDown, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Move down", tint = if (canDown) Tokens.TextSoft else Tokens.Faint)
            }
        }
        RemoveButton(onRemove)
    }
}

@Composable
private fun MoveButtons(canUp: Boolean, canDown: Boolean, onUp: () -> Unit, onDown: () -> Unit) {
    IconButton(onClick = onUp, enabled = canUp, modifier = Modifier.size(40.dp)) {
        Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Move up", tint = if (canUp) Tokens.TextSoft else Tokens.Faint)
    }
    IconButton(onClick = onDown, enabled = canDown, modifier = Modifier.size(40.dp)) {
        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Move down", tint = if (canDown) Tokens.TextSoft else Tokens.Faint)
    }
}

@Composable
private fun RemoveButton(onClick: () -> Unit) {
    Box(
        Modifier.size(width = 44.dp, height = 40.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text("×", style = MaterialTheme.typography.titleLarge, color = Tokens.Dim)
    }
}

/** Bordered inline text box on the ground colour, 40 dp tall when single line, as in the design's ingredient rows. */
@Composable
private fun CompactField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    mono: Boolean = false,
    centered: Boolean = false,
    singleLine: Boolean = true,
    minHeight: Int = 40,
    keyboardType: KeyboardType = KeyboardType.Text,
    borderColor: Color = Tokens.BorderStrong
) {
    val shape = RoundedCornerShape(8.dp)
    val base = if (mono) MonoNumber else MaterialTheme.typography.bodyMedium
    val style = base.copy(color = Tokens.Text, textAlign = if (centered) TextAlign.Center else TextAlign.Start)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .then(if (singleLine) Modifier.height(minHeight.dp) else Modifier.heightIn(min = minHeight.dp))
            .clip(shape).background(Tokens.Ground).border(1.dp, borderColor, shape)
            .padding(horizontal = 10.dp, vertical = if (singleLine) 0.dp else 10.dp),
        textStyle = style,
        cursorBrush = SolidColor(Tokens.Accent),
        singleLine = singleLine,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        decorationBox = { inner ->
            Box(Modifier.fillMaxSize(), contentAlignment = if (singleLine) (if (centered) Alignment.Center else Alignment.CenterStart) else Alignment.TopStart) {
                if (value.isEmpty()) Text(placeholder, style = style, color = Tokens.Dim, maxLines = if (singleLine) 1 else 3)
                inner()
            }
        }
    )
}

/** Centered number with a tiny label underneath, one of the four per-serving macro boxes. */
@Composable
private fun MacroField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier.height(56.dp).clip(shape).background(Tokens.Surface).border(1.dp, Tokens.BorderStrong, shape).padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MonoNumber.copy(color = Tokens.Text, textAlign = TextAlign.Center, fontSize = 15.sp),
            cursorBrush = SolidColor(Tokens.Accent),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { inner() }
            }
        )
        Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.sp, fontWeight = FontWeight.Normal), color = Tokens.Muted)
    }
}
