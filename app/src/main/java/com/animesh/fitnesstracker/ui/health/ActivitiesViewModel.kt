package com.animesh.fitnesstracker.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animesh.fitnesstracker.data.model.Activity
import com.animesh.fitnesstracker.data.model.ActivityKind
import com.animesh.fitnesstracker.di.AppContainer
import com.animesh.fitnesstracker.domain.health.PaceFormat
import com.animesh.fitnesstracker.util.Dates
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** The chips over the activity list. */
enum class ActivityFilter(val label: String) {
    ALL("All"), STRENGTH("Strength"), WALKS("Walks"), RUNS("Runs"), OTHER("Other");

    fun matches(kind: ActivityKind): Boolean = when (this) {
        ALL -> true
        STRENGTH -> kind == ActivityKind.STRENGTH
        WALKS -> kind == ActivityKind.WALK || kind == ActivityKind.HIKE
        RUNS -> kind == ActivityKind.RUN
        OTHER -> kind != ActivityKind.STRENGTH && kind != ActivityKind.WALK && kind != ActivityKind.HIKE && kind != ActivityKind.RUN
    }
}

/** One month of the list: "September 2026", "8 · 6 h 57 m" and its rows, newest first. */
data class ActivityGroup(val month: String, val total: String, val items: List<HubActivity>)

data class ActivitiesState(
    val loading: Boolean = true,
    /** True when the watch has never recorded anything, regardless of the filter. */
    val empty: Boolean = false,
    /** "3 this month · 2 h 46 m". */
    val summary: String = "",
    val filter: ActivityFilter = ActivityFilter.ALL,
    val groups: List<ActivityGroup> = emptyList()
)

class ActivitiesViewModel(private val c: AppContainer) : ViewModel() {
    private val filter = MutableStateFlow(ActivityFilter.ALL)

    val state: StateFlow<ActivitiesState> = combine(c.activities.observeAll(), filter) { all, f -> build(all, f) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivitiesState())

    fun pick(f: ActivityFilter) {
        filter.value = f
    }

    private fun build(all: List<Activity>, f: ActivityFilter): ActivitiesState {
        val items = all.filter { f.matches(it.kind) }.sortedByDescending { it.startTimestamp }
        val thisMonth = YearMonth.from(LocalDate.ofEpochDay(Dates.todayEpochDay()))
        val groups = items.groupBy { YearMonth.from(LocalDate.ofEpochDay(Dates.epochDayOfSeconds(it.startTimestamp))) }
            .entries.sortedByDescending { it.key }
            .map { (month, acts) ->
                ActivityGroup(
                    month = HealthFormat.monthYear(month),
                    total = "${acts.size} · ${HealthFormat.hoursMinutes(acts.sumOf { it.timerSeconds })}",
                    items = acts.map(::row)
                )
            }
        val inMonth = items.filter { YearMonth.from(LocalDate.ofEpochDay(Dates.epochDayOfSeconds(it.startTimestamp))) == thisMonth }
        val summary = if (inMonth.isEmpty()) "Nothing this month" else "${inMonth.size} this month · ${HealthFormat.hoursMinutes(inMonth.sumOf { it.timerSeconds })}"
        return ActivitiesState(loading = false, empty = all.isEmpty(), summary = summary, filter = f, groups = groups)
    }

    private fun row(a: Activity): HubActivity {
        val day = Dates.epochDayOfSeconds(a.startTimestamp)
        val meta = buildList {
            add(HealthFormat.shortDay(day))
            add(HealthFormat.timeOfDay(a.startTimestamp))
            val distance = a.distanceM
            if (distance != null && distance > 0) {
                add(HealthFormat.km(distance))
                val speed = a.avgSpeedMps ?: PaceFormat.speed(distance, a.timerSeconds)
                val perKm = PaceFormat.secondsPerKm(speed)
                if (a.kind.usesPace && perKm != null) add("${HealthFormat.pace(perKm)} /km")
                else if (!a.kind.usesPace && speed != null) add(PaceFormat.kmh(speed))
            } else {
                a.avgHr?.let { add("avg $it bpm") }
            }
        }.joinToString(" · ")
        return HubActivity(
            id = a.id,
            kind = a.kind,
            name = a.name,
            meta = meta,
            duration = HealthFormat.duration(a.timerSeconds),
            sub = a.calories?.let { "$it kcal" },
            linked = a.linkedSessionId != null
        )
    }
}
