package com.animesh.fitnesstracker.ui.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.animesh.fitnesstracker.data.model.RoutineWithSlots
import com.animesh.fitnesstracker.di.AppContainer
import com.animesh.fitnesstracker.ui.appContainer
import com.animesh.fitnesstracker.ui.components.AppCard
import com.animesh.fitnesstracker.ui.components.BottomActionBar
import com.animesh.fitnesstracker.ui.components.ConfirmDialog
import com.animesh.fitnesstracker.ui.components.EmptyState
import com.animesh.fitnesstracker.ui.components.OutlineChipButton
import com.animesh.fitnesstracker.ui.components.PillTag
import com.animesh.fitnesstracker.ui.components.PrimaryButton
import com.animesh.fitnesstracker.ui.components.ScreenHeader
import com.animesh.fitnesstracker.ui.components.SecondaryButton
import com.animesh.fitnesstracker.ui.components.SectionLabel
import com.animesh.fitnesstracker.ui.theme.Tokens
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RoutineRow(val routine: RoutineWithSlots, val summary: String) {
    val id: Long get() = routine.routine.id
    val name: String get() = routine.routine.name
    val isActive: Boolean get() = routine.routine.isActive
}

data class RoutinesState(val loading: Boolean = true, val routines: List<RoutineRow> = emptyList(), val templates: List<RoutineRow> = emptyList())

class RoutinesViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<RoutinesState> = c.routines.observeAll().map { all ->
        val rows = all.map { r ->
            val names = r.sortedSlots.map { it.group?.name ?: "Rest" }
            val summary = if (names.isEmpty()) "No slots yet" else "${plural(names.size, "slot")} · ${names.joinToString(", ")}"
            RoutineRow(r, summary)
        }
        RoutinesState(false, rows.filter { !it.routine.routine.isTemplate }, rows.filter { it.routine.routine.isTemplate })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoutinesState())

    fun setActive(id: Long) = viewModelScope.launch { c.routines.setActive(id) }
    fun delete(id: Long) = viewModelScope.launch { c.routines.delete(id) }
    fun duplicate(id: Long, name: String) = viewModelScope.launch { c.routines.duplicate(id, name, asTemplate = false) }
    fun saveAsTemplate(id: Long, name: String) = viewModelScope.launch { c.routines.duplicate(id, name, asTemplate = true) }
    fun useTemplate(id: Long, name: String) = viewModelScope.launch { c.routines.duplicate(id, name, asTemplate = false) }
}

private sealed interface RoutinesDialog {
    data class Duplicate(val row: RoutineRow) : RoutinesDialog
    data class Template(val row: RoutineRow) : RoutinesDialog
    data class Use(val row: RoutineRow) : RoutinesDialog
    data class Delete(val row: RoutineRow) : RoutinesDialog
}

@Composable
fun RoutinesScreen(onEditRoutine: (Long) -> Unit, onBack: () -> Unit) {
    val container = appContainer()
    val vm: RoutinesViewModel = viewModel { RoutinesViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<RoutinesDialog?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Plan · Routines", "All routines", "One is active at a time. Templates are starting points.")
        if (!state.loading && state.routines.isEmpty() && state.templates.isEmpty()) {
            EmptyState("No routines yet", "A routine is a repeating cycle of workout groups and rest days.", Modifier.weight(1f)) {
                PrimaryButton("New routine", { onEditRoutine(0L) })
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { SectionLabel("Routines") }
                items(state.routines, key = { it.id }) { row ->
                    RoutineCard(row, onClick = { onEditRoutine(row.id) }) {
                        if (!row.isActive) OutlineChipButton("Set active", { vm.setActive(row.id) }, contentColor = Tokens.Accent, borderColor = Tokens.AccentBorder)
                        OverflowMenu(
                            listOf(
                                "Edit" to { onEditRoutine(row.id) },
                                "Duplicate" to { dialog = RoutinesDialog.Duplicate(row) },
                                "Save as template" to { dialog = RoutinesDialog.Template(row) },
                                "Delete" to { dialog = RoutinesDialog.Delete(row) }
                            )
                        )
                    }
                }
                if (state.templates.isNotEmpty()) {
                    item { Spacer(Modifier.height(8.dp)) }
                    item { SectionLabel("Templates") }
                    items(state.templates, key = { it.id }) { row ->
                        RoutineCard(row, onClick = { onEditRoutine(row.id) }) {
                            OutlineChipButton("Use template", { dialog = RoutinesDialog.Use(row) }, contentColor = Tokens.Accent, borderColor = Tokens.AccentBorder)
                            OverflowMenu(
                                listOf(
                                    "Edit" to { onEditRoutine(row.id) },
                                    "Delete" to { dialog = RoutinesDialog.Delete(row) }
                                )
                            )
                        }
                    }
                }
            }
        }
        BottomActionBar {
            PrimaryButton("New routine", { onEditRoutine(0L) })
            SecondaryButton("Back", onBack, Modifier.fillMaxWidth(), height = 44)
        }
    }

    when (val d = dialog) {
        null -> Unit
        is RoutinesDialog.Duplicate -> NameDialog("Duplicate routine", d.row.name + " copy", "Duplicate", { vm.duplicate(d.row.id, it); dialog = null }, { dialog = null })
        is RoutinesDialog.Template -> NameDialog("Save as template", d.row.name, "Save", { vm.saveAsTemplate(d.row.id, it); dialog = null }, { dialog = null }, label = "Template name")
        is RoutinesDialog.Use -> NameDialog("New routine from template", d.row.name, "Create", { vm.useTemplate(d.row.id, it); dialog = null }, { dialog = null }, label = "Routine name")
        is RoutinesDialog.Delete -> ConfirmDialog(
            title = "Delete ${d.row.name}?",
            body = if (d.row.isActive) "This is the active routine. Today will show nothing scheduled until you activate another one. Logged sessions are kept." else "Logged sessions are kept.",
            confirmText = "Delete",
            onConfirm = { vm.delete(d.row.id); dialog = null },
            onDismiss = { dialog = null },
            destructive = true
        )
    }
}

@Composable
private fun RoutineCard(row: RoutineRow, onClick: () -> Unit, actions: @Composable () -> Unit) {
    AppCard(borderColor = if (row.isActive) Tokens.AccentBorder else Tokens.Border, onClick = onClick) {
        TitleRow(row.name, row.summary) { if (row.isActive) PillTag("Active", color = Tokens.Accent, borderColor = Tokens.AccentBorder) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, alignment = Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            actions()
        }
    }
}
