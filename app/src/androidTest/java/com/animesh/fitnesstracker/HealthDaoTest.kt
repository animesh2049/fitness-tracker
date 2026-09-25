package com.animesh.fitnesstracker

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.animesh.fitnesstracker.data.AppDatabase
import com.animesh.fitnesstracker.data.model.ActivityKind
import com.animesh.fitnesstracker.data.model.BodyBatteryKind
import com.animesh.fitnesstracker.data.model.HealthMinute
import com.animesh.fitnesstracker.data.model.MetricType
import com.animesh.fitnesstracker.data.model.Session
import com.animesh.fitnesstracker.data.model.SessionStatus
import com.animesh.fitnesstracker.data.model.SleepNight
import com.animesh.fitnesstracker.data.model.StressSample
import com.animesh.fitnesstracker.domain.health.DaySummary
import com.animesh.fitnesstracker.domain.health.TrendMetric
import com.animesh.fitnesstracker.garmin.fit.BodyBatteryEventRec
import com.animesh.fitnesstracker.garmin.fit.DailySleepRec
import com.animesh.fitnesstracker.garmin.fit.DecodedFit
import com.animesh.fitnesstracker.garmin.fit.EventRec
import com.animesh.fitnesstracker.garmin.fit.FileIdRec
import com.animesh.fitnesstracker.garmin.fit.HrvSummaryRec
import com.animesh.fitnesstracker.garmin.fit.MonitoringRec
import com.animesh.fitnesstracker.garmin.fit.RespirationRec
import com.animesh.fitnesstracker.garmin.fit.RestingHrRec
import com.animesh.fitnesstracker.garmin.fit.SessionRec
import com.animesh.fitnesstracker.garmin.fit.SleepDemandRec
import com.animesh.fitnesstracker.garmin.fit.SleepStageRec
import com.animesh.fitnesstracker.garmin.fit.SleepStatsRec
import com.animesh.fitnesstracker.garmin.fit.StressRec
import com.animesh.fitnesstracker.garmin.fit.TrainingLoadRec
import com.animesh.fitnesstracker.garmin.fitimport.FitImporter
import com.animesh.fitnesstracker.garmin.sync.RawFileStore
import com.animesh.fitnesstracker.garmin.sync.StoredFile
import com.animesh.fitnesstracker.repository.ActivityRepository
import com.animesh.fitnesstracker.repository.HealthRepository
import com.animesh.fitnesstracker.repository.SyncedFileRepository
import com.animesh.fitnesstracker.util.Dates
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Day queries and importer idempotency against a real in-memory Room database. The decoder is
 * faked: a stored file's bytes are a key into a map of hand built [DecodedFit] values.
 */
@RunWith(AndroidJUnit4::class)
class HealthDaoTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val wed: LocalDate = LocalDate.of(2026, 9, 16)
    private val wedDay = wed.toEpochDay()

    private lateinit var db: AppDatabase
    private lateinit var root: File
    private lateinit var store: TestStore
    private lateinit var importer: FitImporter
    private lateinit var health: HealthRepository
    private lateinit var activities: ActivityRepository
    private lateinit var files: SyncedFileRepository
    private val fits = HashMap<String, DecodedFit>()

    private fun at(date: LocalDate, time: String): Long = LocalDateTime.of(date, LocalTime.parse(time)).atZone(zone).toEpochSecond()
    private fun at(time: String): Long = at(wed, time)

    /** Files live under a temp root; their bytes are just the key of the decoded value to return. */
    private inner class TestStore(override val root: File) : RawFileStore {
        private val list = ArrayList<StoredFile>()
        override fun exists(watchIndex: Int, fitType: Int, watchTimestamp: Long?) = list.any { it.watchIndex == watchIndex && it.fitType == fitType && it.watchTimestamp == watchTimestamp }
        override fun save(watchIndex: Int, fitType: Int, watchTimestamp: Long?, bytes: ByteArray): StoredFile {
            val file = File(root, "$fitType/f_${watchTimestamp}_$watchIndex.fit")
            file.parentFile.mkdirs()
            file.writeBytes(bytes)
            return StoredFile(watchIndex, fitType, watchTimestamp, file).also { list += it }
        }
        override fun saveImported(fitType: Int, timeCreated: Long?, bytes: ByteArray) = save(0, fitType, timeCreated, bytes)
        override fun listAll(): List<StoredFile> = list.sortedBy { it.watchTimestamp ?: 0 }
        override fun totalBytes(): Long = list.sumOf { it.sizeBytes }
    }

    private fun stored(key: String, fit: DecodedFit, index: Int, type: Int, ts: Long): StoredFile {
        fits[key] = fit
        return store.save(index, type, ts, key.toByteArray())
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        root = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "garmin-test-${System.nanoTime()}").apply { mkdirs() }
        store = TestStore(root)
        importer = FitImporter(db, store, decode = { bytes -> fits.getValue(String(bytes)) }, zone = zone, now = { 1_758_000_000_000 })
        health = HealthRepository(db, zone)
        activities = ActivityRepository(db, zone)
        files = SyncedFileRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private fun mon(ts: Long, steps: Long? = null, hr: Int? = null, dist: Double? = null, kcal: Int? = null, moderate: Int? = null) =
        MonitoringRec(ts, hr, steps, dist, kcal, if (steps != null) 6 else null, null, moderate, null)

    private fun monitoringFit(created: Long, vararg recs: MonitoringRec, stress: List<StressRec> = emptyList(), events: List<EventRec> = emptyList()) =
        DecodedFit(
            FileIdRec(32, timeCreated = created), monitoring = recs.toList(), stress = stress, events = events,
            restingHr = listOf(RestingHrRec(created, 56, 54)), respiration = listOf(RespirationRec(at("03:00"), 14.2))
        )

    @Test
    fun dayQueriesReturnTotalsHeartRateAndBodyBatteryForTheDay() = runTest {
        val fit = monitoringFit(
            at("21:22"),
            mon(at("07:00"), steps = 100, hr = 60, dist = 80.0, kcal = 5),
            mon(at("07:01"), steps = 130, hr = 71, dist = 104.0, kcal = 6),
            mon(at("18:05"), steps = 8_412, hr = 158, dist = 6_100.0, kcal = 412, moderate = 4),
            mon(at(wed.plusDays(1), "00:01"), steps = 20, hr = 55, dist = 15.0, kcal = 1),
            stress = listOf(StressRec(at("06:10"), 12, 88), StressRec(at("12:00"), 40, 60), StressRec(at("22:30"), -1, 45))
        )
        val summary = importer.importFiles(listOf(stored("m1", fit, 1, 32, at("21:22"))))
        assertEquals(1, summary.filesImported)
        assertEquals(0, summary.filesFailed)
        assertTrue(summary.minuteSamples > 3)

        val totals = health.observeDayTotals(wedDay).first()!!
        assertEquals(8_412, totals.steps)
        assertEquals(6_100.0, totals.distanceM, 0.001)
        assertEquals(412, totals.activeKcal)
        val next = health.observeDayTotals(wedDay + 1).first()!!
        assertEquals(20, next.steps)

        val hr = health.observeDayHeartRateBetween(wedDay, wedDay + 1).first()
        assertEquals(2, hr.size)
        assertEquals(60, hr[0].hrMin)
        assertEquals(158, hr[0].hrMax)
        assertEquals(55, hr[1].hrMax)

        val stress = health.observeStress(wedDay).first()
        assertEquals(3, stress.size)
        assertNull(stress[2].stress)
        assertEquals(45, stress[2].bodyBattery)
        val dayStress = health.observeDayStressBetween(wedDay, wedDay).first().single()
        assertEquals(wedDay, dayStress.epochDay)
        assertEquals(45, dayStress.bodyBatteryMin)
        assertEquals(88, dayStress.bodyBatteryMax)
        assertEquals(26.0, dayStress.stressAvg!!, 0.001)

        assertEquals(54, health.observeRestingHr(wedDay).first()!!.bpm)
        assertEquals(4, health.observeIntensityWeek(wedDay).first().sumOf { it.moderate })
        assertEquals(mapOf(wedDay to 8_412.0, wedDay + 1 to 20.0), health.observeTrendValues(TrendMetric.STEPS, wedDay - 7, wedDay + 1).first())
        assertEquals(mapOf(wedDay to 45.0), health.observeTrendValues(TrendMetric.BODY_BATTERY_LOW, wedDay - 7, wedDay + 1).first())

        val inputs = health.observeDay(wedDay).first()
        val day = DaySummary.compute(wedDay, inputs.minutes, inputs.stress, inputs.restingHr?.bpm, inputs.intensityWeek, 10_000, zone)
        assertEquals(8_412, day.steps)
        assertEquals(88, day.bodyBatteryHigh!!.value)
        assertEquals("06:10", Dates.hhmm(day.bodyBatteryHigh!!.timestamp, zone))
        assertEquals(45, day.bodyBatteryLow!!.value)
        assertEquals(54, day.restingHr)
        assertEquals(4, day.intensityMinutes)
    }

    @Test
    fun reimportingTheSameFileChangesNothing() = runTest {
        val fit = monitoringFit(
            at("21:22"),
            mon(at("07:00"), steps = 100, hr = 60, dist = 80.0, kcal = 5),
            mon(at("07:01"), steps = 130, hr = 71, dist = 104.0, kcal = 6),
            mon(at("07:12"), steps = 200, hr = 90, dist = 160.0, kcal = 9),
            stress = listOf(StressRec(at("06:10"), 12, 88))
        )
        val file = stored("m1", fit, 1, 32, at("21:22"))
        importer.importFiles(listOf(file))
        val before = health.observeMinutes(wedDay).first()
        val beforeTotals = health.observeDayTotals(wedDay).first()!!
        assertEquals(200, beforeTotals.steps)

        val again = importer.importFiles(listOf(file))
        assertEquals(1, again.filesImported)
        assertEquals(0, again.minuteSamples)
        assertEquals(before, health.observeMinutes(wedDay).first())
        assertEquals(beforeTotals, health.observeDayTotals(wedDay).first()!!)
        assertEquals(1, db.healthDao().countStress())
        val registry = files.getAll()
        assertEquals(1, registry.size)
        assertEquals(1_758_000_000_000, registry[0].importedAt)
        assertNull(registry[0].importError)
        assertEquals("32/f_${at("21:22")}_1.fit", registry[0].path)
        assertTrue(files.isImported(1, 32, at("21:22")))
    }

    @Test
    fun aSecondFileOfTheSameDayContinuesTheCountersWithoutDoubleCounting() = runTest {
        val first = monitoringFit(at("12:00"), mon(at("07:00"), steps = 100, dist = 80.0, kcal = 5), mon(at("07:01"), steps = 130, dist = 104.0, kcal = 6))
        val second = monitoringFit(at("21:22"), mon(at("12:05"), steps = 500, dist = 400.0, kcal = 25), mon(at("12:06"), steps = 520, dist = 416.0, kcal = 26))
        importer.importFiles(listOf(stored("a", first, 1, 32, at("12:00")), stored("b", second, 2, 32, at("21:22"))))
        val totals = health.observeDayTotals(wedDay).first()!!
        assertEquals(520, totals.steps)
        assertEquals(416.0, totals.distanceM, 0.001)
        assertEquals(26, totals.activeKcal)
        val minutes = health.observeMinutes(wedDay).first()
        assertEquals(370, minutes.first { it.timestamp == at("12:04") }.steps)
    }

    @Test
    fun reimportAllRebuildsEverythingFromTheStore() = runTest {
        val fit = monitoringFit(at("21:22"), mon(at("07:00"), steps = 100), mon(at("07:01"), steps = 130))
        val file = stored("m1", fit, 1, 32, at("21:22"))
        importer.importFiles(listOf(file))
        db.healthDao().insertMinutesReplace(listOf(HealthMinute(at("06:00"), wedDay, steps = 999)))
        assertEquals(1_129, health.observeDayTotals(wedDay).first()!!.steps)
        val summary = importer.reimportAll()
        assertEquals(1, summary.filesImported)
        assertEquals(130, health.observeDayTotals(wedDay).first()!!.steps)
        assertEquals(1, files.getAll().size)
    }

    @Test
    fun aFailingFileIsRecordedAndDoesNotStopTheBatch() = runTest {
        val good = monitoringFit(at("21:22"), mon(at("07:00"), steps = 100))
        val goodFile = stored("good", good, 1, 32, at("21:22"))
        val badFile = store.save(2, 32, at("21:30"), "missing".toByteArray())
        val summary = importer.importFiles(listOf(goodFile, badFile))
        assertEquals(1, summary.filesImported)
        assertEquals(1, summary.filesFailed)
        assertEquals(1, summary.errors.size)
        val failed = files.observeFailed().first().single()
        assertNotNull(failed.importError)
        assertNull(failed.importedAt)
        assertEquals(2, files.getAll().size)
    }

    @Test
    fun sleepNightIsAssembledFromStagesEventsHrvAndOvernightSamples() = runTest {
        val start = at(wed.minusDays(1), "23:41")
        var t = start
        val segments = listOf(1 to 4, 2 to 22, 3 to 38, 2 to 36, 4 to 20, 2 to 30, 3 to 27, 1 to 5, 2 to 42, 4 to 28, 2 to 40, 3 to 20, 2 to 38, 4 to 42, 1 to 10, 2 to 30)
        val stages = segments.map { (stage, minutes) -> t += minutes * 60L; SleepStageRec(t, stage) }
        val end = t
        val sleep = DecodedFit(
            FileIdRec(49, timeCreated = end), sleepStages = stages, sleepStats = listOf(SleepStatsRec(end, 81)),
            events = listOf(EventRec(start, 74, 0, null), EventRec(end, 74, 1, null))
        )
        val hrv = DecodedFit(FileIdRec(68, timeCreated = end + 60), hrvSummary = listOf(HrvSummaryRec(end + 60, 60.0, 58.0, 75.0, 52.0, 52.0, 66.0, 4)))
        val monitor = monitoringFit(
            at("21:22"), mon(at("02:00"), steps = 0, hr = 52), mon(at("04:10"), steps = 0, hr = 49), mon(at("09:00"), steps = 300, hr = 80)
        )
        val summary = importer.importFiles(
            listOf(stored("s", sleep, 1, 49, end), stored("h", hrv, 2, 68, end + 60), stored("m", monitor, 3, 32, at("21:22")))
        )
        assertEquals(1, summary.sleepNights)

        val night = health.observeSleepNight(wedDay).first()!!
        assertEquals(start, night.startTimestamp)
        assertEquals(end, night.endTimestamp)
        assertEquals(7 * 3600 + 12 * 60, night.totalSeconds)
        assertEquals(85 * 60, night.deepSeconds)
        assertEquals(81, night.score)
        assertEquals(SleepNight.SOURCE_EVENT, night.source)
        assertEquals(58.0, night.avgHrvMs!!, 0.0)
        assertEquals(4, night.hrvStatus)
        assertEquals(49, night.lowestHr)
        assertEquals(14.2, night.avgRespiration!!, 0.0)
        assertEquals(16, health.observeSleepStages(wedDay).first().size)
        assertEquals(listOf(wedDay), health.observeSleepNightDays().first())
        val inputs = health.observeNight(wedDay).first()
        assertEquals(night, inputs.night)
        assertEquals(58.0, inputs.hrv!!.lastNightAvg!!, 0.0)
        assertEquals(mapOf(wedDay to 81.0), health.observeTrendValues(TrendMetric.SLEEP_SCORE, wedDay - 7, wedDay).first())

        // Importing the sleep file again leaves the night untouched.
        importer.importFiles(listOf(store.listAll().first { it.fitType == 49 }))
        assertEquals(night, health.observeSleepNight(wedDay).first())
        assertEquals(16, health.observeSleepStages(wedDay).first().size)
    }

    @Test
    fun sleepNeedAndBodyBatteryReachTheNightWhicheverFileComesFirst() = runTest {
        val start = at(wed.minusDays(1), "23:41")
        val end = at("06:53")
        val sleep = DecodedFit(
            FileIdRec(49, timeCreated = end), sleepStages = listOf(SleepStageRec(end, 2)), sleepStats = listOf(SleepStatsRec(end, 81, deepSleepScore = 74)),
            events = listOf(EventRec(start, 74, 0, null), EventRec(end, 74, 1, null))
        )
        // Written the evening before: the need for the coming night.
        val eveningBefore = at(wed.minusDays(1), "21:00")
        val demand = DecodedFit(FileIdRec(44, timeCreated = eveningBefore), sleepDemand = listOf(SleepDemandRec(eveningBefore, 480, 520)))
        // Written the morning after: Body Battery over the night.
        val morning = DecodedFit(FileIdRec(44, timeCreated = end + 300), dailySleep = listOf(DailySleepRec(end + 300, 81, 900, start, end, 330, 330, 41, 92)))
        val monitor = monitoringFit(
            at("21:22"), mon(at("07:00"), steps = 100, hr = 60),
            events = emptyList()
        ).copy(bodyBatteryEvents = listOf(BodyBatteryEventRec(start, 4, 432, 51, end), BodyBatteryEventRec(at("07:05"), 0, 58, -10, at("08:03"))))

        // Metrics first, then the sleep file, then the monitoring file: order must not matter.
        importer.importFiles(listOf(stored("d", demand, 1, 44, eveningBefore), stored("m", morning, 2, 44, end + 300)))
        assertNull(health.observeSleepNight(wedDay).first())
        importer.importFiles(listOf(stored("s", sleep, 3, 49, end)))
        val night = health.observeSleepNight(wedDay).first()!!
        assertEquals(520, night.sleepNeedMin)
        assertEquals(480, night.sleepBaselineMin)
        assertEquals(41, night.bodyBatteryStart)
        assertEquals(92, night.bodyBatteryEnd)
        assertEquals(51, night.bodyBatteryGain)
        assertEquals(74, night.deepScore)

        importer.importFiles(listOf(stored("mon", monitor, 4, 32, at("21:22"))))
        val events = health.observeBodyBatteryEvents(wedDay).first()
        assertEquals("the night's charge is filed under the morning it ended on, the walk under its own day", 2, events.size)
        assertEquals(BodyBatteryKind.SLEEP, events[0].kind)
        assertEquals(51, events[0].delta)
        assertEquals(BodyBatteryKind.ACTIVITY, events[1].kind)
        assertEquals(-10, events[1].delta)
        assertTrue(health.observeBodyBatteryEvents(wedDay - 1).first().isEmpty())
        val inputs = health.observeDay(wedDay).first()
        assertEquals(2, inputs.bodyBatteryEvents.size)
        assertEquals(2, db.healthDao().countBodyBatteryEvents())

        // A rebuild from the store gives the same night as the incremental imports did (the monitoring file added its overnight samples).
        val afterMonitor = health.observeSleepNight(wedDay).first()!!
        assertEquals(14.2, afterMonitor.avgRespiration!!, 0.0)
        importer.reimportAll()
        assertEquals(afterMonitor, health.observeSleepNight(wedDay).first())
        assertEquals(2, db.healthDao().countBodyBatteryEvents())
    }

    @Test
    fun dayTotalsIncludeMetresClimbed() = runTest {
        val fit = monitoringFit(
            at("21:22"),
            mon(at("07:00"), steps = 100, hr = 60),
            MonitoringRec(at("07:00"), null, null, null, null, null, null, null, null, ascentM = 3.5, descentM = 1.0),
            mon(at("07:05"), steps = 130, hr = 61),
            MonitoringRec(at("07:05"), null, null, null, null, null, null, null, null, ascentM = 2.5, descentM = 0.0)
        )
        importer.importFiles(listOf(stored("asc", fit, 1, 32, at("21:22"))))
        val totals = health.observeDayTotals(wedDay).first()!!
        assertEquals(6.0, totals.ascentM, 1e-9)
        assertEquals(1.0, totals.descentM, 1e-9)
        assertEquals(130, totals.steps)
        assertEquals(6.0, health.observeDayTotalsBetween(wedDay, wedDay).first().single().ascentM, 1e-9)
    }

    @Test
    fun metricsAreKeyedByDayAndType() = runTest {
        val t = at("08:00")
        val fit = DecodedFit(FileIdRec(44, timeCreated = t), trainingLoad = listOf(TrainingLoadRec(t, 120, 100, 1.2), TrainingLoadRec(t + 60, 125, 100, 1.25)))
        val file = stored("k", fit, 1, 44, t)
        importer.importFiles(listOf(file))
        importer.importFiles(listOf(file))
        val acute = health.observeMetrics(MetricType.TRAINING_LOAD_ACUTE, wedDay - 1, wedDay).first()
        assertEquals(1, acute.size)
        assertEquals(125.0, acute[0].value, 0.0)
        assertEquals(mapOf(wedDay to 125.0), health.observeTrendValues(TrendMetric.TRAINING_LOAD, wedDay - 7, wedDay).first())
        assertEquals(125.0, health.observeLatestMetric(MetricType.TRAINING_LOAD_ACUTE, wedDay + 5).first()!!.value, 0.0)
    }

    @Test
    fun activitiesAreKeyedOnStartAndFileTimeAndLinkToOverlappingSessions() = runTest {
        val start = at("18:10")
        val sessionId = db.sessionDao().insertSession(
            Session(groupId = null, groupName = "Push day", routineId = null, epochDay = wedDay, startedAt = (start - 300) * 1000, endedAt = (start + 3000) * 1000, status = SessionStatus.COMPLETED)
        )
        val fit = DecodedFit(
            FileIdRec(4, timeCreated = start - 5),
            sessions = listOf(
                SessionRec(
                    timestamp = start + 3700, startTime = start, sport = 10, subSport = 20, sportProfileName = "Strength", totalElapsedTime = 3700.0,
                    totalTimerTime = 3400.0, totalDistance = null, totalCalories = 320, avgHeartRate = 118, maxHeartRate = 158
                )
            )
        )
        val file = stored("act", fit, 1, 4, start - 5)
        val first = importer.importFiles(listOf(file))
        assertEquals(1, first.activities)
        val second = importer.importFiles(listOf(file))
        assertEquals(0, second.activities)
        assertEquals(1, db.activityDao().count())

        val list = activities.observeDay(wedDay).first()
        assertEquals(1, list.size)
        assertEquals(ActivityKind.STRENGTH, list[0].kind)
        assertEquals("Strength", list[0].name)
        assertEquals(sessionId, list[0].linkedSessionId)
        assertEquals(1, activities.observeForSession(sessionId).first().size)
        assertEquals(1, activities.observeOverlapping(start + 100, start + 200).first().size)
        assertTrue(activities.observeOverlapping(start + 5000, start + 6000).first().isEmpty())
        val details = activities.observeActivity(list[0].id).first()!!
        assertEquals(list[0], details.activity)
        assertEquals(1, activities.monthsWithActivities().size)

        // Deleting the session clears the link; the activity stays.
        db.sessionDao().deleteSession(sessionId)
        assertNull(activities.observeDay(wedDay).first()[0].linkedSessionId)
        assertEquals(0, activities.linkUnlinked())
    }
}
