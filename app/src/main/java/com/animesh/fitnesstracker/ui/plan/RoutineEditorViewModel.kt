package com.animesh.fitnesstracker.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animesh.fitnesstracker.data.model.GroupWithExercises
import com.animesh.fitnesstracker.di.AppContainer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One slot in the draft. [key] keeps list items stable while reordering. */
data class SlotDraft(val key: Long, val groupId: Long?)

data class RoutineDraft(
    val name: String = "",
    val slots: List<SlotDraft> = emptyList(),
    val makeActive: Boolean = false,
    val isTemplate: Boolean = false
)

data class RoutineEditorState(
    val loaded: Boolean = false,
    val isNew: Boolean = true,
    val draft: RoutineDraft = RoutineDraft(),
    val dirty: Boolean = false,
    val groups: List<GroupWithExercises> = emptyList(),
    val error: String? = null,
    val wasActive: Boolean = false
) {
    fun groupName(id: Long?): String = if (id == null) "Rest" else groups.firstOrNull { it.group.id == id }?.group?.name ?: "Missing group"
}

class RoutineEditorViewModel(private val c: AppContainer, private val routineId: Long) : ViewModel() {
    private val draft = MutableStateFlow(RoutineDraft())
    private val saved = MutableStateFlow(RoutineDraft())
    private val loaded = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)
    private val wasActive = MutableStateFlow(false)
    private var nextKey = 1L

    private val _events = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits once the routine is saved (or discarded) and the editor should close. */
    val closed: SharedFlow<Unit> = _events

    val state: StateFlow<RoutineEditorState> = combine(
        draft, saved, loaded, c.groups.observeGroups(), combine(error, wasActive) { e, a -> e to a }
    ) { d, s, l, groups, (err, active) ->
        RoutineEditorState(loaded = l, isNew = routineId == 0L, draft = d, dirty = d != s, groups = groups, error = err, wasActive = active)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoutineEditorState())

    init {
        viewModelScope.launch {
            val existing = if (routineId != 0L) c.routines.get(routineId) else null
            val initial = if (existing == null) {
                // A brand new routine becomes active when nothing else is.
                RoutineDraft(makeActive = c.routines.getActive() == null)
            } else {
                RoutineDraft(
                    name = existing.routine.name,
                    slots = existing.sortedSlots.map { SlotDraft(nextKey++, it.slot.groupId) },
                    makeActive = existing.routine.isActive,
                    isTemplate = existing.routine.isTemplate
                )
            }
            wasActive.value = existing?.routine?.isActive == true
            draft.value = initial
            saved.value = initial
            loaded.value = true
        }
    }

    fun setName(name: String) { draft.update { it.copy(name = name) }; error.value = null }
    fun setMakeActive(active: Boolean) = draft.update { it.copy(makeActive = active) }

    fun addGroupSlot(groupId: Long) = draft.update { it.copy(slots = it.slots + SlotDraft(nextKey++, groupId)) }
    fun addRestSlot() = draft.update { it.copy(slots = it.slots + SlotDraft(nextKey++, null)) }
    fun removeSlot(key: Long) = draft.update { it.copy(slots = it.slots.filter { s -> s.key != key }) }

    fun move(key: Long, delta: Int) = draft.update { d ->
        val list = d.slots.toMutableList()
        val from = list.indexOfFirst { it.key == key }
        val to = from + delta
        if (from < 0 || to !in list.indices) return@update d
        val item = list.removeAt(from)
        list.add(to, item)
        d.copy(slots = list)
    }

    fun save() {
        val d = draft.value
        if (d.name.isBlank()) { error.value = "Give the routine a name."; return }
        viewModelScope.launch {
            val ids = d.slots.map { it.groupId }
            if (routineId == 0L) {
                c.routines.create(d.name.trim(), ids, d.makeActive)
            } else {
                val existing = c.routines.get(routineId) ?: return@launch
                c.routines.rename(existing.routine, d.name.trim())
                c.routines.replaceSlots(routineId, ids)
                if (d.makeActive && !existing.routine.isActive) c.routines.setActive(routineId)
                if (!d.makeActive && existing.routine.isActive) {
                    val fresh = c.routines.get(routineId)?.routine ?: return@launch
                    c.routines.update(fresh.copy(isActive = false))
                }
            }
            saved.value = d
            _events.tryEmit(Unit)
        }
    }

    /** Stores the current draft as a separate template routine. The editor stays open. */
    fun saveAsTemplate(name: String) {
        val d = draft.value
        viewModelScope.launch { c.routines.createTemplate(name, d.slots.map { it.groupId }) }
    }

    fun discard() { _events.tryEmit(Unit) }
}
