package com.animesh.fitnesstracker.ui.health

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animesh.fitnesstracker.data.model.Activity
import com.animesh.fitnesstracker.data.model.ActivityKind
import com.animesh.fitnesstracker.data.model.HealthMinute
import com.animesh.fitnesstracker.data.model.Settings
import com.animesh.fitnesstracker.data.model.SleepStage
import com.animesh.fitnesstracker.data.model.StressSample
import com.animesh.fitnesstracker.di.AppContainer
import com.animesh.fitnesstracker.domain.health.DaySummary
import com.animesh.fitnesstracker.domain.health.SleepNights
import com.animesh.fitnesstracker.garmin.sync.SyncState
import com.animesh.fitnesstracker.garmin.sync.WatchInfo
import com.animesh.fitnesstracker.repository.DayInputs
import com.animesh.fitnesstracker.util.Dates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.ceil

/** The accent strip under the day switcher: paired watch, sync status and the action chip. */
data class SyncStrip(
    val paired: Boolean,
    /** "Forerunner 570 · 71%" or "No watch paired". */
    val watchLine: String,
    /** "Synced 12 min ago · 1,284 files", "Downloading file 3 of 7 · MONITOR", "Sync failed · ...". */
    val statusLine: String,
    /** "Sync now", "Syncing…" or "Pair". */
    val buttonText: String,
    val running: Boolean
)

/** One row of the hub's Activities section. */
data class HubActivity(
    val id: Long,
    val kind: ActivityKind,
    val name: String,
    val meta: String,
    val duration: String,
    val sub: String?,
    val linked: Boolean
)

/** The sleep card: window, asleep time, score pill and the four stage shares (deep, light, REM, awake). */
data class SleepCardData(
    val window: String,
    val duration: String,
    val scoreLabel: String?,
    val stageWeights: List<Float>,
    /** Name and duration per stage in the same order as [stageWeights]. */
    val stageLabels: List<Pair<String, String>>
)

data class HealthTodayState(
    val loading: Boolean = true,
    /** No watch paired and nothing imported: the hub shows the pairing empty state instead of the cards. */
    val empty: Boolean = false,
    val epochDay: Long = 0,
    val isToday: Boolean = true,
    val canGoBack: Boolean = true,
    val eyebrow: String = "Health",
    val title: String = "Today",
    val subtitle: String? = null,
    val dayLabel: String = "",
    val sync: SyncStrip? = null,
    val steps: String = "0",
    val stepsSub: String = "",
    val distance: String = "0 km",
    val kcal: String = "0",
    val heartRate: CurveSeries = CurveSeries.EMPTY,
    val hrGrid: List<Float> = listOf(60f, 100f, 140f),
    val hrRange: String = "",
    val hrResting: Float? = null,
    val hrRest: String = "",
    val hrAvg: String = "",
    val hrMaxLabel: String = "Max",
    val hrMax: String = "",
    val battery: CurveSeries = CurveSeries.EMPTY,
    val batteryNow: String = "",
    val batteryHigh: String = "",
    val batteryLow: String = "",
    val stressAvg: String = "",
    val stressWeights: List<Float> = listOf(0f, 0f, 0f, 0f),
    val stressHours: List<String> = listOf("0 h", "0 h", "0 h", "0 h"),
    val sleep: SleepCardData? = null,
    val hrv: String = "",
    val hrvSub: String = "",
    val intensity: String = "",
    val intensitySub: String = "this week, target 150",
    val activities: List<HubActivity> = emptyList()
)

/** Everything for one day that the screen state is built from. */
private data class DayBundle(val inputs: DayInputs, val activities: List<Pair<Activity, String?>>, val settings: Settings)

@OptIn(ExperimentalCoroutinesApi::class)
class HealthTodayViewModel(private val c: AppContainer) : ViewModel() {
    private val day = MutableStateFlow(Dates.todayEpochDay())

    /** Re-renders every minute so "Synced 12 min ago" and the day boundary move on without a restart. */
    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    /** Failure timestamp the user has already seen (the hub was left while it showed), so it is not shown twice. */
    private var acknowledgedFailureAt: Long? = null
    private var visibleFailureAt: Long? = null

    init {
        viewModelScope.launch { runCatching { c.activities.linkUnlinked() } }
    }

    private val dayData = day.flatMapLatest { d ->
        combine(
            c.health.observeDay(d),
            c.activities.observeDay(d).mapLatest { acts -> acts.sortedBy { it.startTimestamp }.map { it to sessionName(it) } },
            c.settings.observe()
        ) { inputs, acts, settings -> DayBundle(inputs, acts, settings) }
    }

    val state: StateFlow<HealthTodayState> = combine(
        dayData, c.watch.watch, c.watch.syncState, c.syncedFiles.observeCount(), ticker
    ) { bundle, watch, sync, files, now -> build(bundle, watch, sync, files, now) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HealthTodayState())

    fun previousDay() {
        val today = Dates.todayEpochDay()
        if (day.value > today - MAX_DAYS_BACK) day.value = day.value - 1
    }

    fun nextDay() {
        val today = Dates.todayEpochDay()
        if (day.value < today) day.value = day.value + 1
    }

    fun syncNow() {
        c.watch.requestSync()
    }

    override fun onCleared() {
        acknowledgedFailureAt = visibleFailureAt
        super.onCleared()
    }

    private suspend fun sessionName(activity: Activity): String? {
        val id = activity.linkedSessionId ?: return null
        return runCatching { c.sessions.getSession(id)?.session?.groupName }.getOrNull()
    }

    private fun build(bundle: DayBundle, watch: WatchInfo?, sync: SyncState, files: Int, now: Long): HealthTodayState {
        val inputs = bundle.inputs
        val d = inputs.epochDay
        val today = Dates.todayEpochDay()
        val isToday = d == today
        val summary = DaySummary.compute(d, inputs.minutes, inputs.stress, inputs.restingHr?.bpm, inputs.intensityWeek, bundle.settings.stepGoal)
        val acts = bundle.activities.map { (a, sessionName) -> hubActivity(a, sessionName) }
        val empty = watch == null && files == 0 && !summary.hasData && acts.isEmpty() && inputs.night == null

        val dayStart = Dates.dayStartSeconds(d)
        val hr = heartRateSeries(inputs.minutes, dayStart)
        val battery = batterySeries(inputs.stress, dayStart)
        val bands = summary.stressBands
        val night = inputs.night
        val hrv = inputs.hrv

        return HealthTodayState(
            loading = false,
            empty = empty,
            epochDay = d,
            isToday = isToday,
            canGoBack = d > today - MAX_DAYS_BACK,
            eyebrow = "Health · ${HealthFormat.eyebrowDate(d)}",
            title = if (isToday) "Today" else HealthFormat.weekdayName(d),
            subtitle = subtitle(bundle.activities, summary, isToday),
            dayLabel = HealthFormat.dayLabel(d),
            sync = syncStrip(watch, sync, files, now),
            steps = HealthFormat.thousands(summary.steps),
            stepsSub = HealthFormat.percentOfGoal(summary.steps, summary.stepGoal),
            distance = HealthFormat.km(summary.distanceM),
            kcal = HealthFormat.thousands(summary.activeKcal),
            heartRate = hr,
            hrRange = if (summary.hrMin != null && summary.hrMax != null) "${summary.hrMin} to ${summary.hrMax.value} bpm" else "no readings",
            hrResting = summary.restingHr?.toFloat(),
            hrRest = summary.restingHr?.toString() ?: "n/a",
            hrAvg = summary.hrAvg?.toString() ?: "n/a",
            hrMaxLabel = summary.hrMax?.let { "Max · ${HealthFormat.timeOfDay(it.timestamp)}" } ?: "Max",
            hrMax = summary.hrMax?.value?.toString() ?: "n/a",
            battery = battery,
            batteryNow = summary.bodyBatteryCurrent?.let { if (isToday) "${it.value} now" else "${it.value} at bedtime" } ?: "no readings",
            batteryHigh = summary.bodyBatteryHigh?.let { "${it.value} · ${HealthFormat.timeOfDay(it.timestamp)}" } ?: "n/a",
            batteryLow = summary.bodyBatteryLow?.let { "${it.value} · ${HealthFormat.timeOfDay(it.timestamp)}" } ?: "n/a",
            stressAvg = summary.stressAvg?.toString() ?: "n/a",
            stressWeights = bands.minutes.map { it.toFloat() },
            stressHours = bands.minutes.map { HealthFormat.hoursShort(it) },
            sleep = night?.let { n ->
                val totals = SleepNights.totals(n)
                val stageSeconds = listOf(totals.deepSeconds, totals.lightSeconds, totals.remSeconds, totals.awakeSeconds)
                val names = listOf(SleepStage.DEEP, SleepStage.LIGHT, SleepStage.REM, SleepStage.AWAKE).map { SleepNights.STAGE_NAMES.getValue(it) }
                SleepCardData(
                    window = HealthFormat.window(n.startTimestamp, n.endTimestamp),
                    duration = HealthFormat.duration(if (totals.asleepSeconds > 0) totals.asleepSeconds else n.durationSeconds),
                    scoreLabel = n.score?.let { "Score $it" },
                    stageWeights = stageSeconds.map { it.toFloat() },
                    stageLabels = names.zip(stageSeconds.map { HealthFormat.duration(it) })
                )
            },
            hrv = hrv?.lastNightAvg?.let { "${it.toInt()} ms" } ?: "n/a",
            hrvSub = hrvSub(hrv?.statusLabel, hrv?.baselineBalancedLower, hrv?.baselineBalancedUpper, hrv != null),
            intensity = "${summary.intensityMinutes} / ${summary.intensityTarget}",
            intensitySub = "this week, target ${summary.intensityTarget}",
            activities = acts
        )
    }

    private fun subtitle(activities: List<Pair<Activity, String?>>, summary: DaySummary, isToday: Boolean): String {
        val last = activities.lastOrNull()
        if (last != null) {
            val (a, sessionName) = last
            return if (sessionName != null) "$sessionName done at ${HealthFormat.timeOfDay(a.endTimestamp)}"
            else "${a.name} at ${HealthFormat.timeOfDay(a.startTimestamp)}"
        }
        return when {
            summary.hasData -> "Rest day"
            isToday -> "Nothing synced yet today"
            else -> "Nothing synced for this day"
        }
    }

    private fun hubActivity(a: Activity, sessionName: String?): HubActivity {
        val meta = buildList {
            add(HealthFormat.timeOfDay(a.startTimestamp))
            if (a.kind.usesPace && a.distanceM != null) add(HealthFormat.km(a.distanceM))
            a.avgHr?.let { add("avg $it bpm") }
            if (a.linkedSessionId != null) add("linked to your session")
        }.joinToString(" · ")
        return HubActivity(
            id = a.id,
            kind = a.kind,
            name = if (sessionName != null) "$sessionName · ${a.kind.label}" else a.name,
            meta = meta,
            duration = HealthFormat.duration(a.timerSeconds),
            sub = a.calories?.let { "$it kcal" },
            linked = a.linkedSessionId != null
        )
    }

    private fun hrvSub(status: String?, low: Double?, high: Double?, present: Boolean): String {
        if (!present) return "no reading last night"
        val baseline = if (low != null && high != null) "baseline ${low.toInt()} to ${high.toInt()}" else null
        return listOfNotNull(status, baseline).joinToString(" · ")
    }

    private fun syncStrip(watch: WatchInfo?, sync: SyncState, files: Int, now: Long): SyncStrip {
        val paired = watch != null
        val running = sync.isRunning
        val failed = sync as? SyncState.Failed
        visibleFailureAt = failed?.failedAtMillis?.takeIf { it != acknowledgedFailureAt }
        val status = when (sync) {
            SyncState.Connecting -> "Connecting…"
            SyncState.Handshake -> "Handshake…"
            SyncState.Listing -> "Listing files…"
            is SyncState.Downloading -> "Downloading file ${(sync.done + 1).coerceAtMost(sync.total)} of ${sync.total} · ${sync.fileLabel}"
            SyncState.Importing -> "Importing…"
            is SyncState.Failed -> if (visibleFailureAt != null) "Sync failed · ${sync.reason}" else restLine(watch, files, now)
            else -> restLine(watch, files, now)
        }
        return SyncStrip(
            paired = paired,
            watchLine = if (watch != null) HealthFormat.watchLine(watch.name, watch.lastBatteryPercent) else "No watch paired",
            statusLine = status,
            buttonText = when {
                running -> "Syncing…"
                paired -> "Sync now"
                else -> "Pair"
            },
            running = running
        )
    }

    private fun restLine(watch: WatchInfo?, files: Int, now: Long): String =
        if (watch == null) "Pair a Garmin to read steps, heart rate and sleep"
        else "${HealthFormat.relativeSync(watch.lastSyncAtMillis, now)} · ${HealthFormat.thousands(files)} ${if (files == 1) "file" else "files"}"

    companion object {
        const val MAX_DAYS_BACK = 365L
        private const val HR_BUCKET_SECONDS = 300L
        private const val HR_BUCKETS = 86_400 / HR_BUCKET_SECONDS.toInt()
        /** Runs break when consecutive samples are further apart than this. */
        private const val HR_GAP_BUCKETS = 3
        private const val BATTERY_GAP_SECONDS = 30 * 60L

        /** Five-minute averages of the worn minutes' heart rate over the day, on a 40..160 scale (higher when the day peaks above). */
        fun heartRateSeries(minutes: List<HealthMinute>, dayStart: Long): CurveSeries {
            val sums = DoubleArray(HR_BUCKETS)
            val counts = IntArray(HR_BUCKETS)
            for (m in minutes) {
                val hr = m.heartRate ?: continue
                if (!m.worn || hr <= 0) continue
                val i = ((m.timestamp - dayStart) / HR_BUCKET_SECONDS).toInt()
                if (i !in 0 until HR_BUCKETS) continue
                sums[i] += hr
                counts[i]++
            }
            val runs = ArrayList<MutableList<ChartSample>>()
            var lastIndex = -100
            var peak = 0f
            for (i in 0 until HR_BUCKETS) {
                if (counts[i] == 0) continue
                val v = (sums[i] / counts[i]).toFloat()
                if (v > peak) peak = v
                val sample = ChartSample((i + 0.5f) / HR_BUCKETS, v)
                if (i - lastIndex > HR_GAP_BUCKETS || runs.isEmpty()) runs += mutableListOf(sample) else runs.last() += sample
                lastIndex = i
            }
            val max = maxOf(160f, ceil(peak / 20f) * 20f)
            return CurveSeries(runs, 40f, max)
        }

        /** Body Battery samples across the day on a 0..100 scale, broken where the watch was off for half an hour or more. */
        fun batterySeries(stress: List<StressSample>, dayStart: Long): CurveSeries {
            val runs = ArrayList<MutableList<ChartSample>>()
            var lastTs = Long.MIN_VALUE
            for (s in stress.sortedBy { it.timestamp }) {
                val bb = s.bodyBattery ?: continue
                val x = ((s.timestamp - dayStart) / 86_400f).coerceIn(0f, 1f)
                val sample = ChartSample(x, bb.toFloat())
                if (runs.isEmpty() || s.timestamp - lastTs > BATTERY_GAP_SECONDS) runs += mutableListOf(sample) else runs.last() += sample
                lastTs = s.timestamp
            }
            return CurveSeries(runs, 0f, 100f)
        }
    }
}
