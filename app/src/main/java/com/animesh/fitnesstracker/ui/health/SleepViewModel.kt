package com.animesh.fitnesstracker.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animesh.fitnesstracker.data.model.HealthMinute
import com.animesh.fitnesstracker.data.model.RestingHrDaily
import com.animesh.fitnesstracker.data.model.SleepStage
import com.animesh.fitnesstracker.di.AppContainer
import com.animesh.fitnesstracker.domain.health.HypnogramSegment
import com.animesh.fitnesstracker.domain.health.SleepNights
import com.animesh.fitnesstracker.repository.NightInputs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlin.math.ceil
import kotlin.math.roundToInt

/** One row of the stage table: dot colour index into [HealthColors.HypnogramLanes], name, share and duration. */
data class StageRow(val lane: Int, val name: String, val fraction: Float, val duration: String, val percent: String)

data class SleepTile(val label: String, val value: String, val sub: String)

data class SleepState(
    val loading: Boolean = true,
    val epochDay: Long = 0,
    val hasNight: Boolean = false,
    val eyebrow: String = "Sleep",
    val title: String = "",
    val subtitle: String? = null,
    val nightLabel: String = "",
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val scoreLabel: String? = null,
    val bars: List<HypnogramBar> = emptyList(),
    val ticks: List<AxisLabel> = emptyList(),
    val rows: List<StageRow> = emptyList(),
    val tiles: List<SleepTile> = emptyList()
)

private data class NightBundle(val night: NightInputs, val restingHr: RestingHrDaily?, val minutes: List<HealthMinute>)

@OptIn(ExperimentalCoroutinesApi::class)
class SleepViewModel(private val c: AppContainer, initialDay: Long) : ViewModel() {
    private val day = MutableStateFlow(initialDay)

    private val bundle = day.flatMapLatest { d ->
        combine(c.health.observeNight(d), c.health.observeRestingHr(d), c.health.observeMinutes(d), c.health.observeMinutes(d - 1)) { n, r, today, yesterday ->
            NightBundle(n, r, yesterday + today)
        }
    }

    val state: StateFlow<SleepState> = combine(bundle, c.health.observeSleepNightDays()) { b, days -> build(b, days) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SleepState())

    fun previousNight() {
        val days = nightDays ?: return
        days.lastOrNull { it < day.value }?.let { day.value = it }
    }

    fun nextNight() {
        val days = nightDays ?: return
        days.firstOrNull { it > day.value }?.let { day.value = it }
    }

    private var nightDays: List<Long>? = null

    private fun build(b: NightBundle, days: List<Long>): SleepState {
        val sorted = days.sorted()
        nightDays = sorted
        val d = b.night.epochDay
        val night = b.night.night
        val eyebrow = "Sleep · night to ${HealthFormat.eyebrowDate(d)}"
        val nav = SleepState(
            loading = false,
            epochDay = d,
            eyebrow = eyebrow,
            nightLabel = HealthFormat.nightLabel(d),
            canGoBack = sorted.any { it < d },
            canGoForward = sorted.any { it > d }
        )
        if (night == null) {
            return nav.copy(title = "No sleep", subtitle = "Nothing recorded for this night")
        }

        val totals = SleepNights.totals(night).takeIf { it.totalSeconds > 0 } ?: SleepNights.totalsFromStages(b.night.stages)
        val segments = SleepNights.hypnogram(b.night.stages, night.startTimestamp, night.endTimestamp)
        val span = (night.endTimestamp - night.startTimestamp).coerceAtLeast(1L).toFloat()
        val bars = segments.map { s ->
            HypnogramBar((s.startTimestamp - night.startTimestamp) / span, (s.endTimestamp - night.startTimestamp) / span, laneOf(s.stage))
        }
        val hours = ceil(span / 3600f).toInt()
        val ticks = (0..hours step 2).mapNotNull { h ->
            val f = h * 3600f / span
            if (f > 1f) null else AxisLabel(f, "+${h}h")
        }
        val rows = listOf(SleepStage.DEEP, SleepStage.LIGHT, SleepStage.REM, SleepStage.AWAKE).map { stage ->
            val seconds = totals.seconds(stage)
            StageRow(
                lane = laneOf(stage),
                name = SleepNights.STAGE_NAMES.getValue(stage),
                fraction = if (totals.totalSeconds == 0) 0f else seconds.toFloat() / totals.totalSeconds,
                duration = HealthFormat.duration(seconds),
                percent = "${totals.percent(stage)}%"
            )
        }

        val asleepSeconds = if (totals.asleepSeconds > 0) totals.asleepSeconds else night.durationSeconds
        val fellAsleep = fellAsleepMinutes(segments)
        val window = HealthFormat.clockWindow(night.startTimestamp, night.endTimestamp)
        val subtitle = if (fellAsleep > 0) "$window · fell asleep in $fellAsleep min" else window
        val scoreLabel = night.score?.let { s -> "Score $s" + SleepNights.scoreWord(s).let { if (it.isEmpty()) "" else " · $it" } }

        return nav.copy(
            hasNight = true,
            title = HealthFormat.duration(asleepSeconds),
            subtitle = subtitle,
            scoreLabel = scoreLabel,
            bars = bars,
            ticks = ticks,
            rows = rows,
            tiles = tiles(b, asleepSeconds)
        )
    }

    private fun tiles(b: NightBundle, asleepSeconds: Int): List<SleepTile> {
        val night = b.night.night ?: return emptyList()
        val hrv = b.night.hrv
        val hrvValue = hrv?.lastNightAvg ?: night.avgHrvMs
        val baseline = if (hrv?.baselineBalancedLower != null && hrv.baselineBalancedUpper != null)
            "baseline ${hrv.baselineBalancedLower.toInt()} to ${hrv.baselineBalancedUpper.toInt()}" else null
        val hrvSub = when {
            hrvValue == null -> "no reading"
            else -> listOfNotNull(hrv?.statusLabel, baseline).joinToString(" · ").ifEmpty { "overnight average" }
        }

        val lowest = b.minutes
            .filter { it.worn && (it.heartRate ?: 0) > 0 && it.timestamp >= night.startTimestamp && it.timestamp <= night.endTimestamp }
            .minByOrNull { it.heartRate!! }
        val lowestSub = when {
            lowest != null -> "lowest ${lowest.heartRate} at ${HealthFormat.timeOfDay(lowest.timestamp)}"
            night.lowestHr != null -> "lowest ${night.lowestHr}"
            else -> "during the night"
        }
        val restingValue = b.restingHr?.bpm?.toString() ?: night.lowestHr?.toString() ?: "n/a"

        return listOf(
            SleepTile("Restless", night.restlessMoments?.toString() ?: "n/a", if (asleepSeconds > 0) "moments" else "not counted"),
            SleepTile("HRV", hrvValue?.let { "${it.roundToInt()} ms" } ?: "n/a", hrvSub),
            SleepTile("Respiration", night.avgRespiration?.let { HealthFormat.oneDecimal(it) } ?: "n/a", "breaths / min"),
            SleepTile("SpO2", night.avgSpo2?.let { "${it.roundToInt()}%" } ?: "n/a", "overnight average"),
            SleepTile("Resting HR", restingValue, lowestSub),
            SleepTile("Skin temp", "n/a", "not read yet")
        )
    }

    companion object {
        /** Hypnogram lane for a FIT stage: awake on top, then REM, light, deep. */
        fun laneOf(stage: Int): Int = SleepNights.LANE_ORDER.indexOf(stage).coerceAtLeast(0)

        /** Minutes awake at the very start of the night before the first sleep stage, 0 when the night opened asleep. */
        fun fellAsleepMinutes(segments: List<HypnogramSegment>): Int {
            var seconds = 0
            for (s in segments) {
                if (s.stage != SleepStage.AWAKE) break
                seconds += s.seconds
            }
            return if (segments.any { it.stage != SleepStage.AWAKE }) seconds / 60 else 0
        }
    }
}
