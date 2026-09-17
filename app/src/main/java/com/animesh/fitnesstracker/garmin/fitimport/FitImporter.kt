package com.animesh.fitnesstracker.garmin.fitimport

import androidx.room.withTransaction
import com.animesh.fitnesstracker.data.AppDatabase
import com.animesh.fitnesstracker.data.model.SleepNight
import com.animesh.fitnesstracker.data.model.SleepStage
import com.animesh.fitnesstracker.data.model.SyncedFile
import com.animesh.fitnesstracker.domain.health.SessionMatcher
import com.animesh.fitnesstracker.garmin.fit.DecodedFit
import com.animesh.fitnesstracker.garmin.fit.FitDecoder
import com.animesh.fitnesstracker.garmin.fit.FitFileType
import com.animesh.fitnesstracker.garmin.sync.ImportHook
import com.animesh.fitnesstracker.garmin.sync.ImportSummary
import com.animesh.fitnesstracker.garmin.sync.RawFileStore
import com.animesh.fitnesstracker.garmin.sync.StoredFile
import java.io.File
import java.time.ZoneId

/**
 * Turns stored FIT files into health rows (Milestone 19). One Room transaction per file; a file
 * that fails leaves nothing behind but its error in `garmin_files`. Importing the same file twice
 * changes nothing: minute and sample rows are keyed by timestamp, activities by start time plus
 * the file's time_created, nights by the day they end on.
 *
 * @param decode the FIT decoder; tests pass a fake that returns hand built [DecodedFit] values.
 * @param zone the zone that turns timestamps into local days.
 */
class FitImporter(
    private val db: AppDatabase,
    private val store: RawFileStore,
    private val decode: (ByteArray) -> DecodedFit = FitDecoder::decode,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = System::currentTimeMillis
) : ImportHook {
    private val rows = FitRows(zone)

    /** What one file contributed. */
    data class FileResult(val minuteSamples: Int = 0, val sleepNights: Int = 0, val activities: Int = 0)

    override suspend fun importFiles(files: List<StoredFile>): ImportSummary {
        val ordered = files.sortedWith(compareBy<StoredFile> { it.watchTimestamp ?: Long.MAX_VALUE }.thenBy { it.file.path })
        var imported = 0
        var failed = 0
        var minutes = 0
        var nights = 0
        var activities = 0
        val errors = ArrayList<String>()
        val touchedNights = LinkedHashSet<Long>()
        for (file in ordered) {
            val path = relativePath(file)
            try {
                val result = importOne(file, path, touchedNights)
                imported++
                minutes += result.minuteSamples
                nights += result.sleepNights
                activities += result.activities
            } catch (e: Exception) {
                failed++
                val message = e.message?.lineSequence()?.firstOrNull()?.take(200) ?: e.javaClass.simpleName
                errors += "$path: $message"
                recordFailure(file, path, message)
            }
        }
        if (touchedNights.isNotEmpty()) refreshNights(touchedNights)
        return ImportSummary(imported, failed, minutes, nights, activities, errors)
    }

    /** Empties every health and activity table and replays every stored file, oldest first. */
    suspend fun reimportAll(): ImportSummary {
        db.withTransaction {
            val health = db.healthDao()
            val activity = db.activityDao()
            activity.deleteAllPoints()
            activity.deleteAllLaps()
            activity.deleteAll()
            health.deleteAllMinutes()
            health.deleteAllStress()
            health.deleteAllSpo2()
            health.deleteAllRespiration()
            health.deleteAllHrvValues()
            health.deleteAllRestingHr()
            health.deleteAllHrvSummaries()
            health.deleteAllSleepStages()
            health.deleteAllSleepNights()
            health.deleteAllMetrics()
            health.deleteAllIntensity()
            db.syncedFileDao().deleteAll()
        }
        return importFiles(store.listAll())
    }

    /** Path of a stored file relative to the store root, the key in `garmin_files`. */
    fun relativePath(file: StoredFile): String = relativePath(file.file)

    private fun relativePath(file: File): String =
        file.relativeToOrNull(store.root)?.path?.replace(File.separatorChar, '/') ?: file.path

    private suspend fun importOne(file: StoredFile, path: String, touchedNights: MutableSet<Long>): FileResult {
        val bytes = file.file.readBytes()
        val fit = decode(bytes)
        val type = fit.fileId.type
        val registry = db.syncedFileDao()
        return db.withTransaction {
            val result = when {
                type == null -> FileResult()
                type.isMonitoring -> importMonitoring(fit, touchedNights)
                type == FitFileType.SLEEP -> importSleep(fit, touchedNights)
                type == FitFileType.HRV_STATUS -> importHrv(fit, touchedNights)
                type == FitFileType.METRICS -> importMetrics(fit)
                type == FitFileType.ACTIVITY -> importActivity(fit, path)
                else -> FileResult()
            }
            val existing = registry.getByPath(path)
            val row = (existing ?: SyncedFile(watchIndex = file.watchIndex, fitType = file.fitType, watchTimestamp = file.watchTimestamp, path = path, sizeBytes = bytes.size.toLong()))
                .copy(
                    watchIndex = file.watchIndex, fitType = file.fitType, watchTimestamp = file.watchTimestamp ?: existing?.watchTimestamp,
                    sizeBytes = bytes.size.toLong(), importedAt = now(), importError = null,
                    minuteSamples = result.minuteSamples, activities = result.activities
                )
            registry.upsert(row)
            result
        }
    }

    private suspend fun recordFailure(file: StoredFile, path: String, message: String) {
        try {
            val registry = db.syncedFileDao()
            val existing = registry.getByPath(path)
            val row = existing?.copy(importError = message) ?: SyncedFile(
                watchIndex = file.watchIndex, fitType = file.fitType, watchTimestamp = file.watchTimestamp, path = path,
                sizeBytes = file.sizeBytes, importError = message
            )
            registry.upsert(row)
        } catch (_: Exception) {
            // The failure is already in the summary; a registry write error must not mask it.
        }
    }

    private suspend fun importMonitoring(fit: DecodedFit, touchedNights: MutableSet<Long>): FileResult {
        val dao = db.healthDao()
        val firstTs = rows.firstMinuteTimestamp(fit)
        val prior = if (firstTs == null) DayCounters.ZERO else {
            val day = rows.epochDay(firstTs)
            DayCounters(dao.stepsBefore(day, firstTs), dao.distanceBefore(day, firstTs), dao.kcalBefore(day, firstTs))
        }
        val m = rows.monitoring(fit, prior)
        val inserted = if (m.minutes.isEmpty()) 0 else dao.insertMinutesIgnore(m.minutes).count { it != -1L }
        if (m.stress.isNotEmpty()) dao.insertStress(m.stress)
        if (m.restingHr.isNotEmpty()) dao.insertRestingHr(m.restingHr)
        if (m.spo2.isNotEmpty()) dao.insertSpo2(m.spo2)
        if (m.respiration.isNotEmpty()) dao.insertRespiration(m.respiration)
        if (m.intensity.isNotEmpty()) dao.insertIntensity(m.intensity)
        if (m.metrics.isNotEmpty()) dao.insertMetrics(m.metrics)
        var nights = 0
        for (window in m.sleepWindows) {
            val bounds = SleepBounds(window.start, window.end, fromEvent = true)
            val day = rows.epochDay(bounds.end)
            val prior = dao.sleepNight(day)
            val stages = dao.sleepStages(day)
            val night = rows.sleepNight(fit, bounds, stages, prior, dao.hrvSummary(day))
            if (night != prior) dao.upsertSleepNight(night)
            touchedNights += day
            if (prior == null) nights++
        }
        // Overnight averages of nights that end within the file's span may have changed.
        val span = m.minutes.map { it.timestamp } + m.respiration.map { it.timestamp } + m.spo2.map { it.timestamp }
        if (span.isNotEmpty()) {
            val fromDay = rows.epochDay(span.min())
            val toDay = rows.epochDay(span.max()) + 1
            dao.sleepNightsBetween(fromDay, toDay).forEach { touchedNights += it.epochDay }
        }
        return FileResult(minuteSamples = inserted, sleepNights = nights)
    }

    private suspend fun importSleep(fit: DecodedFit, touchedNights: MutableSet<Long>): FileResult {
        val dao = db.healthDao()
        val bounds = rows.sleepBounds(fit) ?: return FileResult()
        val fileStages = rows.sleepStages(fit, bounds)
        val realSleep = fileStages.any { it.stage == SleepStage.LIGHT || it.stage == SleepStage.DEEP || it.stage == SleepStage.REM }
        if (!realSleep && !bounds.fromEvent) return FileResult()
        if (fileStages.isNotEmpty()) dao.insertSleepStages(fileStages)
        val day = rows.epochDay(bounds.end)
        val prior = dao.sleepNight(day)
        val allStages = dao.sleepStages(day)
        val night = rows.sleepNight(fit, bounds, allStages, prior, dao.hrvSummary(day))
        if (night != prior) dao.upsertSleepNight(night)
        touchedNights += day
        return FileResult(sleepNights = 1)
    }

    private suspend fun importHrv(fit: DecodedFit, touchedNights: MutableSet<Long>): FileResult {
        val dao = db.healthDao()
        val h = rows.hrv(fit)
        if (h.summaries.isNotEmpty()) dao.insertHrvSummaries(h.summaries)
        if (h.values.isNotEmpty()) dao.insertHrvValues(h.values)
        h.summaries.forEach { touchedNights += it.epochDay }
        return FileResult()
    }

    private suspend fun importMetrics(fit: DecodedFit): FileResult {
        val metrics = rows.metrics(fit)
        if (metrics.isNotEmpty()) db.healthDao().insertMetrics(metrics)
        return FileResult()
    }

    private suspend fun importActivity(fit: DecodedFit, path: String): FileResult {
        val dao = db.activityDao()
        val a = rows.activity(fit, path) ?: return FileResult()
        if (dao.find(a.activity.startTimestamp, a.activity.fitTimeCreated) != null) return FileResult()
        val sessions = db.sessionDao().getCompletedOverlapping(a.activity.startTimestamp * 1000, a.activity.endTimestamp * 1000)
        val linked = SessionMatcher.match(a.activity, sessions)?.id
        val id = dao.insert(a.activity.copy(linkedSessionId = linked))
        if (a.laps.isNotEmpty()) dao.insertLaps(a.laps.map { it.copy(activityId = id) })
        if (a.points.isNotEmpty()) dao.insertPoints(a.points.map { it.copy(activityId = id) })
        return FileResult(activities = 1)
    }

    /** Recomputes the overnight averages (respiration, SpO2, lowest HR, HRV) of the given nights from the stored samples. */
    private suspend fun refreshNights(days: Set<Long>) = db.withTransaction {
        val dao = db.healthDao()
        for (day in days) {
            val night = dao.sleepNight(day) ?: continue
            val hrv = dao.hrvSummary(day)
            val updated = night.copy(
                avgRespiration = dao.avgRespirationBetween(night.startTimestamp, night.endTimestamp + 1) ?: night.avgRespiration,
                avgSpo2 = dao.avgSpo2Between(night.startTimestamp, night.endTimestamp + 1) ?: night.avgSpo2,
                lowestHr = dao.lowestHeartRateBetween(night.startTimestamp, night.endTimestamp + 1) ?: night.lowestHr,
                avgHrvMs = hrv?.lastNightAvg ?: night.avgHrvMs,
                hrvStatus = hrv?.status ?: night.hrvStatus
            )
            if (updated != night) dao.updateSleepNight(updated)
        }
    }

    companion object {
        /** True for a night the importer created from an event window before its stages arrived. */
        fun isEventOnly(night: SleepNight): Boolean = night.source == SleepNight.SOURCE_EVENT && night.totalSeconds == 0
    }
}
