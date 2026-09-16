package com.animesh.fitnesstracker.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animesh.fitnesstracker.data.model.SessionSet
import com.animesh.fitnesstracker.data.model.SessionStatus
import com.animesh.fitnesstracker.data.model.SessionWithExercises
import com.animesh.fitnesstracker.data.model.WeightUnit
import com.animesh.fitnesstracker.di.AppContainer
import com.animesh.fitnesstracker.util.Dates
import com.animesh.fitnesstracker.util.Weights
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SessionDetailState(
    val session: SessionWithExercises? = null,
    val unit: WeightUnit = WeightUnit.KG,
    val dateLabel: String = "",
    val meta: String = "",
    val abandoned: Boolean = false,
    val loaded: Boolean = false
)

class SessionDetailViewModel(private val c: AppContainer, private val sessionId: Long) : ViewModel() {
    private var notesJob: Job? = null

    val state: StateFlow<SessionDetailState> = combine(c.sessions.observeSession(sessionId), c.settings.observe()) { s, settings ->
        if (s == null) SessionDetailState(loaded = true, unit = settings.unit) else {
            val parts = ArrayList<String>()
            val ended = s.session.endedAt
            if (ended != null) parts += Dates.formatDuration((ended - s.session.startedAt).coerceAtLeast(0))
            val sets = s.completedSets.count { !it.isWarmup }
            parts += if (sets == 1) "1 set" else "$sets sets"
            if (s.volumeKg > 0) parts += Weights.formatVolume(s.volumeKg, settings.unit)
            SessionDetailState(
                session = s,
                unit = settings.unit,
                dateLabel = Dates.shortDay(s.session.epochDay),
                meta = parts.joinToString(" · "),
                abandoned = s.session.status == SessionStatus.ABANDONED,
                loaded = true
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionDetailState())

    fun saveSet(set: SessionSet) {
        viewModelScope.launch { c.sessions.updateSet(set) }
    }

    /** Persists notes shortly after the user stops typing. */
    fun saveNotes(text: String) {
        notesJob?.cancel()
        notesJob = viewModelScope.launch {
            delay(300)
            val current = state.value.session?.session ?: c.sessions.getSession(sessionId)?.session ?: return@launch
            if (current.notes != text) c.sessions.updateSession(current.copy(notes = text))
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            c.sessions.delete(sessionId)
            onDone()
        }
    }
}
