package com.animesh.fitnesstracker.ui.health

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animesh.fitnesstracker.data.model.Activity
import com.animesh.fitnesstracker.data.model.ActivityPoint
import com.animesh.fitnesstracker.data.model.ActivityWithDetails
import com.animesh.fitnesstracker.data.model.SessionWithExercises
import com.animesh.fitnesstracker.data.model.Settings
import com.animesh.fitnesstracker.di.AppContainer
import com.animesh.fitnesstracker.domain.health.HrZones
import com.animesh.fitnesstracker.domain.health.PaceFormat
import com.animesh.fitnesstracker.domain.health.PrimaryBenefit
import com.animesh.fitnesstracker.garmin.sync.WatchInfo
import com.animesh.fitnesstracker.util.Dates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

data class DetailTile(val label: String, val value: String, val sub: String)

data class ZoneRow(val name: String, val fraction: Float, val minutes: String, val range: String)

/** One lap row: "1", "9:42", bar length 0..1 (faster is longer), "92", "19:42". */
data class LapRow(val km: String, val pace: String, val bar: Float, val hr: String, val time: String)

data class LinkedCard(val sessionId: Long, val summary: String)

data class ActivityDetailState(
    val loading: Boolean = true,
    /** The activity was deleted or the id is unknown. */
    val missing: Boolean = false,
    val eyebrow: String = "Activity",
    val title: String = "",
    val subtitle: String? = null,
    val tiles: List<DetailTile> = emptyList(),
    val linked: LinkedCard? = null,
    /** Normalised track for [RoutePreview], null when the activity had no GPS. */
    val route: List<Offset>? = null,
    val routeCaption: String = "",
    val heartRate: CurveSeries = CurveSeries.EMPTY,
    val hrRange: String = "",
    val hrGrid: List<Float> = listOf(80f, 120f, 160f),
    val hrAxis: List<AxisLabel> = emptyList(),
    val zones: List<ZoneRow> = emptyList(),
    /** Present for pace sports with laps. */
    val laps: List<LapRow>? = null,
    val effects: List<DetailTile> = emptyList(),
    val footer: String = ""
)

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityDetailViewModel(private val c: AppContainer, private val activityId: Long) : ViewModel() {
    private val details = c.activities.observeActivity(activityId)

    private val session = details.flatMapLatest { d ->
        val id = d?.activity?.linkedSessionId
        if (id == null) flowOf(null) else c.sessions.observeSession(id)
    }

    val state: StateFlow<ActivityDetailState> = combine(details, session, c.settings.observe(), c.watch.watch) { d, s, settings, watch ->
        if (d == null) ActivityDetailState(loading = false, missing = true, title = "Activity not found") else build(d, s, settings, watch)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivityDetailState())

    private fun build(d: ActivityWithDetails, session: SessionWithExercises?, settings: Settings, watch: WatchInfo?): ActivityDetailState {
        val a = d.activity
        val points = d.sortedPoints
        val maxHr = settings.effectiveMaxHeartRate()
        val day = Dates.epochDayOfSeconds(a.startTimestamp)
        val distance = a.distanceM?.takeIf { it > 0 }
        val speed = a.avgSpeedMps ?: PaceFormat.speed(distance, a.timerSeconds)

        val subtitle = buildList {
            add(HealthFormat.duration(a.timerSeconds))
            distance?.let { add(HealthFormat.km(it)) }
            a.calories?.let { add("$it kcal") }
            if (distance == null && a.bodyBatteryStart != null && a.bodyBatteryEnd != null) add("Body Battery ${a.bodyBatteryStart} to ${a.bodyBatteryEnd}")
        }.joinToString(" · ")

        val maxPoint = points.filter { (it.heartRate ?: 0) > 0 }.maxByOrNull { it.heartRate!! }
        val tiles = if (distance != null) {
            listOf(
                DetailTile("Distance", HealthFormat.km(distance), a.totalAscent?.let { "$it m ascent" } ?: "no ascent data"),
                paceTile(a, speed),
                DetailTile("Avg HR", a.avgHr?.toString() ?: "n/a", a.maxHr?.let { "max $it bpm" } ?: "bpm")
            )
        } else {
            listOf(
                DetailTile("Avg HR", a.avgHr?.toString() ?: "n/a", "bpm"),
                DetailTile("Max HR", a.maxHr?.toString() ?: "n/a", maxPoint?.let { "bpm at ${HealthFormat.timeOfDay(it.timestamp)}" } ?: "bpm"),
                DetailTile("Calories", a.calories?.toString() ?: "n/a", "kcal")
            )
        }

        val zoneSeconds = HrZones.forActivity(a, points, maxHr)
        val fractions = HrZones.fractions(zoneSeconds)
        val ranges = HealthFormat.zoneRanges(zoneLowerBounds(a, maxHr))
        val zones = (0 until HrZones.ZONE_COUNT).map { i ->
            ZoneRow("Z${i + 1}", fractions[i].toFloat(), "${(zoneSeconds[i] / 60.0).roundToInt()} m", ranges.getOrElse(i) { "" })
        }

        return ActivityDetailState(
            loading = false,
            eyebrow = "${a.kind.label} · ${HealthFormat.eyebrowDate(day)} · ${HealthFormat.timeOfDay(a.startTimestamp)}",
            title = a.name,
            subtitle = subtitle,
            tiles = tiles,
            linked = linkedCard(a, session),
            route = route(points),
            routeCaption = listOfNotNull(distance?.let { HealthFormat.km(it) }, a.totalAscent?.let { "$it m ascent" }).joinToString(" · "),
            heartRate = heartRateSeries(a, points),
            hrRange = if (a.minHr != null && a.maxHr != null) "${a.minHr} to ${a.maxHr} bpm" else a.maxHr?.let { "max $it bpm" } ?: "",
            hrAxis = HealthFormat.elapsedAxis(a.timerSeconds),
            zones = zones,
            laps = if (a.kind.usesPace && d.laps.isNotEmpty()) laps(d) else null,
            effects = effects(a),
            footer = footer(a, watch)
        )
    }

    private fun paceTile(a: Activity, speed: Double?): DetailTile {
        val perKm = PaceFormat.secondsPerKm(speed)
        return if (a.kind.usesPace) DetailTile("Avg pace", perKm?.let { HealthFormat.pace(it) } ?: "n/a", "min / km")
        else DetailTile("Avg speed", speed?.let { HealthFormat.oneDecimal(it * 3.6) } ?: "n/a", "km/h")
    }

    private fun linkedCard(a: Activity, session: SessionWithExercises?): LinkedCard? {
        val id = a.linkedSessionId ?: return null
        if (session == null) return LinkedCard(id, "Open the session for its sets and volume")
        val sets = session.completedSets.count { !it.isWarmup }
        val volume = session.volumeKg
        val summary = buildList {
            add(session.session.groupName)
            add("$sets ${if (sets == 1) "set" else "sets"}")
            if (volume > 0) add("${HealthFormat.thousands(volume.roundToInt())} kg volume")
        }.joinToString(" · ")
        return LinkedCard(id, summary)
    }

    private fun laps(d: ActivityWithDetails): List<LapRow> {
        val laps = d.sortedLaps
        val paces = laps.map { PaceFormat.secondsPerKm(it.avgSpeedMps ?: PaceFormat.speed(it.distanceM, it.timerSeconds)) }
        val known = paces.filterNotNull()
        val fastest = known.minOrNull() ?: 0
        val slowest = known.maxOrNull() ?: 0
        var cumulative = 0
        return laps.mapIndexed { i, lap ->
            cumulative += lap.timerSeconds
            val pace = paces[i]
            val distance = lap.distanceM
            LapRow(
                km = if (distance == null || distance >= FULL_LAP_METRES) (i + 1).toString() else HealthFormat.kmValue(distance),
                pace = pace?.let { HealthFormat.pace(it) } ?: "n/a",
                bar = if (pace == null || slowest == fastest) 0.5f else 1f - (pace - fastest).toFloat() / (slowest - fastest),
                hr = lap.avgHr?.toString() ?: "n/a",
                time = HealthFormat.clockDuration(cumulative)
            )
        }
    }

    private fun effects(a: Activity): List<DetailTile> {
        val aerobic = a.aerobicEffect
        val anaerobic = a.anaerobicEffect
        val recovery = a.recoveryMinutes
        val fourth = when {
            a.trainingLoad != null -> DetailTile("Load", HealthFormat.oneDecimal(a.trainingLoad), "training load")
            a.vo2max != null -> DetailTile("VO2 max", HealthFormat.oneDecimal(a.vo2max), "estimate after this activity")
            else -> DetailTile("Load", "n/a", "not in the file")
        }
        val tiles = mutableListOf(
            DetailTile("Aerobic effect", aerobic?.let { HealthFormat.fixedOneDecimal(it) } ?: "n/a", aerobic?.let { HealthFormat.trainingEffectWord(it) } ?: "not in the file"),
            DetailTile("Anaerobic effect", anaerobic?.let { HealthFormat.fixedOneDecimal(it) } ?: "n/a", anaerobic?.let { HealthFormat.trainingEffectWord(it) } ?: "not in the file")
        )
        // Version 0.5: what the watch wrote about the activity's benefit; both hide when it wrote nothing.
        a.performanceCondition?.let { tiles += DetailTile("Performance", signedInt(it), "condition vs your baseline") }
        PrimaryBenefit.label(a.primaryBenefit)?.let { tiles += DetailTile("Benefit", it, "the watch's label") }
        tiles += DetailTile(
            "Recovery", recovery?.let { HealthFormat.hoursRounded(it) } ?: "n/a",
            recovery?.let { "until ${HealthFormat.timeOfDay(a.endTimestamp + it * 60L)}" } ?: "not in the file"
        )
        tiles += fourth
        return tiles
    }

    private fun footer(a: Activity, watch: WatchInfo?): String = buildList {
        add("Recorded on ${watch?.name ?: "your Garmin"}")
        watch?.firmwareVersion?.let { add("firmware $it") }
        add("file ${a.filePath.substringAfterLast('/')}")
    }.joinToString(" · ")

    companion object {
        private const val FULL_LAP_METRES = 950.0
        private const val POINT_GAP_SECONDS = 120L

        /** "+3", "−2" (Unicode minus) or "0" for the performance condition. */
        fun signedInt(value: Int): String = when {
            value > 0 -> "+$value"
            value < 0 -> "${HealthTodayViewModel.MINUS}${-value}"
            else -> "0"
        }

        /** Heart rate over elapsed time, x 0 at the start and 1 at the end, on a 60..170 scale widened when the data needs it. */
        fun heartRateSeries(a: Activity, points: List<ActivityPoint>): CurveSeries {
            val span = (a.endTimestamp - a.startTimestamp).coerceAtLeast(1L).toFloat()
            val runs = ArrayList<MutableList<ChartSample>>()
            var lastTs = Long.MIN_VALUE
            var lo = Int.MAX_VALUE
            var hi = 0
            for (p in points) {
                val hr = p.heartRate ?: continue
                if (hr <= 0) continue
                lo = minOf(lo, hr)
                hi = maxOf(hi, hr)
                val sample = ChartSample(((p.timestamp - a.startTimestamp) / span).coerceIn(0f, 1f), hr.toFloat())
                if (runs.isEmpty() || p.timestamp - lastTs > POINT_GAP_SECONDS) runs += mutableListOf(sample) else runs.last() += sample
                lastTs = p.timestamp
            }
            if (runs.isEmpty()) return CurveSeries.EMPTY
            val min = minOf(60f, floor(lo / 10f) * 10f)
            val max = maxOf(170f, ceil(hi / 10f) * 10f)
            return CurveSeries(runs, min, max)
        }

        /** Track points as east/north offsets with longitude squeezed by the latitude, or null without GPS. */
        fun route(points: List<ActivityPoint>): List<Offset>? {
            val gps = points.filter { it.lat != null && it.lon != null }
            if (gps.size < 2) return null
            val meanLat = gps.map { it.lat!! }.average()
            val squeeze = cos(Math.toRadians(meanLat)).toFloat()
            return gps.map { Offset((it.lon!! * squeeze).toFloat(), it.lat!!.toFloat()) }
        }

        /** Lower bpm bound of zones 1 to 5: the file's boundaries when it had them, else 50 to 90 percent of the max. */
        fun zoneLowerBounds(a: Activity, maxHr: Int): List<Int> {
            val upper = a.zoneBoundsList
            return when {
                upper.size >= 6 -> (0 until 5).map { upper[it] + 1 }
                upper.size == 5 -> listOf(HrZones.bounds(maxHr)[0]) + (0 until 4).map { upper[it] + 1 }
                else -> HrZones.bounds(maxHr)
            }
        }
    }
}
