package com.animesh.workouttracker.ui.plan

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animesh.workouttracker.ui.appContainer
import com.animesh.workouttracker.ui.components.AppCard
import com.animesh.workouttracker.ui.components.BottomActionBar
import com.animesh.workouttracker.ui.components.ConfirmDialog
import com.animesh.workouttracker.ui.components.GhostButton
import com.animesh.workouttracker.ui.components.PrimaryButton
import com.animesh.workouttracker.ui.components.ScreenHeader
import com.animesh.workouttracker.ui.components.SecondaryButton
import com.animesh.workouttracker.ui.components.SectionLabel
import com.animesh.workouttracker.ui.theme.MonoNumber
import com.animesh.workouttracker.ui.theme.Tokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineEditorScreen(routineId: Long, onClose: () -> Unit) {
    val container = appContainer()
    val vm: RoutineEditorViewModel = viewModel(key = "routine-$routineId") { RoutineEditorViewModel(container, routineId) }
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var templateDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.closed.collect { onClose() } }

    val cancel: () -> Unit = { if (state.dirty) confirmDiscard = true else onClose() }
    BackHandler(onBack = cancel)

    val d = state.draft
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            "Plan · " + if (state.isNew) "New routine" else "Edit routine",
            d.name.ifBlank { if (state.isNew) "New routine" else "Routine" },
            if (d.isTemplate) "Template · ${plural(d.slots.size, "slot")}" else "${plural(d.slots.size, "slot")} in the cycle"
        )
        if (!state.loaded) {
            Spacer(Modifier.weight(1f))
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    PlanTextField(d.name, vm::setName, "Routine name", placeholder = "Push Pull Legs", isError = state.error != null, supporting = state.error)
                }
                item { Spacer(Modifier.height(4.dp)) }
                item { SectionLabel("Slots") }
                if (d.slots.isEmpty()) {
                    item {
                        Text("No slots yet. Add workout groups and rest days in the order you want to repeat them.", style = MaterialTheme.typography.bodySmall, color = Tokens.Muted, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }
                itemsIndexed(d.slots, key = { _, s -> s.key }) { i, slot ->
                    SlotEditorRow(
                        index = i,
                        name = state.groupName(slot.groupId),
                        isRest = slot.groupId == null,
                        canUp = i > 0,
                        canDown = i < d.slots.lastIndex,
                        onUp = { vm.move(slot.key, -1) },
                        onDown = { vm.move(slot.key, +1) },
                        onRemove = { vm.removeSlot(slot.key) }
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GhostButton("+ Add group slot", { pickerOpen = true }, Modifier.weight(1f), contentColor = Tokens.Accent, borderColor = Tokens.AccentBorder)
                        GhostButton("+ Add rest slot", vm::addRestSlot, Modifier.weight(1f))
                    }
                }
                item { Spacer(Modifier.height(4.dp)) }
                if (!d.isTemplate) {
                    item {
                        AppCard(padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("Make active", style = MaterialTheme.typography.titleLarge, color = Tokens.Text)
                                    Text(
                                        if (state.wasActive) "This routine is what Today shows." else "Today shows the active routine. Only one can be active.",
                                        style = MaterialTheme.typography.bodySmall, color = Tokens.Muted
                                    )
                                }
                                Switch(
                                    checked = d.makeActive, onCheckedChange = vm::setMakeActive,
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Tokens.AccentInk, checkedTrackColor = Tokens.Accent,
                                        uncheckedThumbColor = Tokens.Muted, uncheckedTrackColor = Tokens.Surface2, uncheckedBorderColor = Tokens.BorderStrong
                                    )
                                )
                            }
                        }
                    }
                }
                item {
                    GhostButton("Save as template", { templateDialog = true }, Modifier.fillMaxWidth())
                }
            }
        }
        BottomActionBar {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Cancel", cancel, Modifier.weight(1f), height = 52)
                PrimaryButton("Save routine", vm::save, Modifier.weight(2f), height = 52, enabled = state.loaded)
            }
        }
    }

    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            body = "Your edits to this routine will be lost.",
            confirmText = "Discard",
            onConfirm = { confirmDiscard = false; vm.discard() },
            onDismiss = { confirmDiscard = false },
            destructive = true
        )
    }
    if (templateDialog) {
        NameDialog(
            "Save as template", d.name.ifBlank { "Routine" } + " template", "Save",
            onConfirm = { vm.saveAsTemplate(it); templateDialog = false },
            onDismiss = { templateDialog = false },
            label = "Template name"
        )
    }
    if (pickerOpen) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { pickerOpen = false }, sheetState = sheet, containerColor = Tokens.Surface) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Add a group", style = MaterialTheme.typography.headlineSmall, color = Tokens.Text, modifier = Modifier.padding(bottom = 4.dp))
                if (state.groups.isEmpty()) {
                    Text("No groups yet. Create one under Plan · Groups first.", style = MaterialTheme.typography.bodyLarge, color = Tokens.Muted)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.groups, key = { it.group.id }) { g ->
                        AppCard(background = Tokens.Ground, onClick = { vm.addGroupSlot(g.group.id); pickerOpen = false }) {
                            TitleRow(g.group.name, groupMeta(g)) {
                                Text("Add", style = MaterialTheme.typography.labelLarge, color = Tokens.Accent)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlotEditorRow(
    index: Int, name: String, isRest: Boolean,
    canUp: Boolean, canDown: Boolean,
    onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit
) {
    AppCard(padding = PaddingValues(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(Tokens.Surface2), contentAlignment = Alignment.Center) {
                Text("${index + 1}", style = MonoNumber, color = if (isRest) Tokens.Dim else Tokens.Text)
            }
            Text(name, style = MaterialTheme.typography.titleLarge, color = if (isRest) Tokens.TextSoft else Tokens.Text, modifier = Modifier.weight(1f))
            IconButton(onClick = onUp, enabled = canUp, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Move up", tint = if (canUp) Tokens.TextSoft else Tokens.Faint)
            }
            IconButton(onClick = onDown, enabled = canDown, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Move down", tint = if (canDown) Tokens.TextSoft else Tokens.Faint)
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Close, contentDescription = "Remove", tint = Tokens.Dim)
            }
        }
    }
}
