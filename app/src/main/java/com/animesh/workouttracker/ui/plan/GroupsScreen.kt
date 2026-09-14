package com.animesh.workouttracker.ui.plan

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
import androidx.compose.runtime.LaunchedEffect
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
import com.animesh.workouttracker.data.model.GroupWithExercises
import com.animesh.workouttracker.di.AppContainer
import com.animesh.workouttracker.ui.appContainer
import com.animesh.workouttracker.ui.components.AppCard
import com.animesh.workouttracker.ui.components.BottomActionBar
import com.animesh.workouttracker.ui.components.ConfirmDialog
import com.animesh.workouttracker.ui.components.EmptyState
import com.animesh.workouttracker.ui.components.OutlineChipButton
import com.animesh.workouttracker.ui.components.PrimaryButton
import com.animesh.workouttracker.ui.components.ScreenHeader
import com.animesh.workouttracker.ui.components.SecondaryButton
import com.animesh.workouttracker.ui.components.SectionLabel
import com.animesh.workouttracker.ui.theme.Tokens
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class GroupsState(val loading: Boolean = true, val groups: List<GroupWithExercises> = emptyList(), val templates: List<GroupWithExercises> = emptyList())

class GroupsViewModel(private val c: AppContainer) : ViewModel() {
    private val _created = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    /** Emits the id of a group that was just created, for navigation to its editor. */
    val created: SharedFlow<Long> = _created

    val state: StateFlow<GroupsState> = combine(c.groups.observeGroups(), c.groups.observeTemplates()) { g, t -> GroupsState(false, g, t) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupsState())

    fun newGroup() = viewModelScope.launch { _created.tryEmit(c.groups.createGroup("New group")) }
    fun duplicate(id: Long) = viewModelScope.launch { c.groups.duplicateGroup(id) }
    fun saveAsTemplate(id: Long, name: String) = viewModelScope.launch { c.groups.saveAsTemplate(id, name) }
    fun createFromTemplate(id: Long, name: String) = viewModelScope.launch { _created.tryEmit(c.groups.createFromTemplate(id, name)) }
    fun delete(id: Long) = viewModelScope.launch { c.groups.deleteGroup(id) }
    suspend fun routineUsage(id: Long): Int = c.groups.routineUsageCount(id)
}

private sealed interface GroupsDialog {
    data class Template(val group: GroupWithExercises) : GroupsDialog
    data class FromTemplate(val group: GroupWithExercises) : GroupsDialog
    data class Delete(val group: GroupWithExercises, val routineSlots: Int) : GroupsDialog
}

@Composable
fun GroupsScreen(onOpenGroup: (Long) -> Unit, onBack: () -> Unit) {
    val container = appContainer()
    val vm: GroupsViewModel = viewModel { GroupsViewModel(container) }
    val state by vm.state.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<GroupsDialog?>(null) }

    LaunchedEffect(Unit) { vm.created.collect { onOpenGroup(it) } }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Plan · Groups", "Groups", "A group is one day's list of exercises and sets.")
        if (!state.loading && state.groups.isEmpty() && state.templates.isEmpty()) {
            EmptyState("No groups yet", "Create a group like \"Push day\" and fill it with exercises from the library.", Modifier.weight(1f)) {
                PrimaryButton("New group", vm::newGroup)
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item { SectionLabel("Groups") }
                items(state.groups, key = { it.group.id }) { g ->
                    GroupCard(g, onClick = { onOpenGroup(g.group.id) }) {
                        OverflowMenu(
                            listOf(
                                "Edit" to { onOpenGroup(g.group.id) },
                                "Duplicate" to { vm.duplicate(g.group.id) },
                                "Save as template" to { dialog = GroupsDialog.Template(g) },
                                "Delete" to { openDelete(vm, g) { dialog = it } }
                            )
                        )
                    }
                }
                if (state.templates.isNotEmpty()) {
                    item { Spacer(Modifier.height(8.dp)) }
                    item { SectionLabel("Templates") }
                    items(state.templates, key = { it.group.id }) { g ->
                        GroupCard(g, onClick = { onOpenGroup(g.group.id) }) {
                            OutlineChipButton("Create from template", { dialog = GroupsDialog.FromTemplate(g) }, contentColor = Tokens.Accent, borderColor = Tokens.AccentBorder)
                            OverflowMenu(
                                listOf(
                                    "Edit template" to { onOpenGroup(g.group.id) },
                                    "Delete" to { dialog = GroupsDialog.Delete(g, 0) }
                                )
                            )
                        }
                    }
                }
            }
        }
        BottomActionBar {
            PrimaryButton("New group", vm::newGroup)
            SecondaryButton("Back", onBack, Modifier.fillMaxWidth(), height = 44)
        }
    }

    when (val d = dialog) {
        null -> Unit
        is GroupsDialog.Template -> NameDialog("Save as template", d.group.group.name, "Save", { vm.saveAsTemplate(d.group.group.id, it); dialog = null }, { dialog = null }, label = "Template name")
        is GroupsDialog.FromTemplate -> NameDialog("New group from template", d.group.group.name, "Create", { vm.createFromTemplate(d.group.group.id, it); dialog = null }, { dialog = null }, label = "Group name")
        is GroupsDialog.Delete -> ConfirmDialog(
            title = "Delete ${d.group.group.name}?",
            body = buildString {
                append("The group and its set prescriptions are removed. Logged sessions are kept.")
                if (d.routineSlots > 0) append(" It is used in ${plural(d.routineSlots, "routine slot")}; those slots become rest days.")
                else if (!d.group.group.isTemplate) append(" It will be removed from any routine that uses it.")
            },
            confirmText = "Delete",
            onConfirm = { vm.delete(d.group.group.id); dialog = null },
            onDismiss = { dialog = null },
            destructive = true
        )
    }
}

private fun openDelete(vm: GroupsViewModel, g: GroupWithExercises, show: (GroupsDialog) -> Unit) {
    vm.viewModelScope.launch { show(GroupsDialog.Delete(g, vm.routineUsage(g.group.id))) }
}

@Composable
private fun GroupCard(g: GroupWithExercises, onClick: () -> Unit, actions: @Composable () -> Unit) {
    AppCard(onClick = onClick, padding = PaddingValues(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) { TitleRow(g.group.name, groupMeta(g)) }
            actions()
        }
    }
}
