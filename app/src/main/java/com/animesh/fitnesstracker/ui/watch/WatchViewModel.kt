package com.animesh.fitnesstracker.ui.watch

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animesh.fitnesstracker.di.AppContainer
import com.animesh.fitnesstracker.garmin.fitimport.HealthRebuild
import com.animesh.fitnesstracker.garmin.sync.ImportSummary
import com.animesh.fitnesstracker.garmin.sync.SyncLogLine
import com.animesh.fitnesstracker.garmin.sync.SyncState
import com.animesh.fitnesstracker.garmin.sync.WatchInfo
import com.animesh.fitnesstracker.util.Dates
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One watch found by the BLE scan, with the signal word the row shows. */
data class ScanRow(val name: String, val address: String, val signal: String)

data class WatchScreenState(
    val watch: WatchInfo? = null,
    val syncState: SyncState = SyncState.Idle,
    val log: List<SyncLogLine> = emptyList(),
    val fileCount: Int = 0,
    /** Unix seconds of the oldest stored watch file, null when nothing is stored. */
    val earliestFileSeconds: Long? = null,
    val scanning: Boolean = false,
    /** True once a scan has run in this screen, so "no watches found" can be told apart from "not scanned yet". */
    val scanned: Boolean = false,
    val hits: List<ScanRow> = emptyList(),
    /** Address of the watch being paired, null when no pairing is in flight. */
    val pairing: String? = null,
    /** One-line message under the scan or sync button. */
    val message: String? = null,
    val messageIsError: Boolean = false,
    /** True when the message is about Bluetooth being off, so the screen can offer the system settings. */
    val bluetoothOff: Boolean = false,
    /** Outcome of the last export, import or re-import, shown under the Data card. */
    val dataStatus: String? = null,
    val dataBusy: Boolean = false,
    /** Version 0.5: the automatic rebuild after a schema change, while it runs or when it failed. */
    val rebuildStatus: String? = null,
    val rebuildError: Boolean = false,
    val loaded: Boolean = false
) {
    val paired: Boolean get() = watch != null
    val syncing: Boolean get() = syncState.isRunning

    val subtitle: String
        get() = when {
            watch == null && scanning -> "Looking for watches"
            watch == null -> "No watch paired"
            syncing -> "Sync in progress"
            else -> "${watch.name} · paired ${dayMonth(watch.pairedAtMillis)}"
        }

    val lastSyncLabel: String get() = watch?.lastSyncAtMillis?.let { relative(it) } ?: "never"
    val filesLabel: String get() = String.format(Locale.US, "%,d", fileCount)
    val dataSinceLabel: String get() = earliestFileSeconds?.let { dayMonth(it * 1000) } ?: "none"

    /** What the Downloading state means for the progress bar. */
    val progressFraction: Float
        get() = when (val s = syncState) {
            is SyncState.Connecting -> 0.05f
            is SyncState.Handshake -> 0.10f
            is SyncState.Listing -> 0.15f
            is SyncState.Downloading -> if (s.total <= 0) 0.2f else 0.2f + 0.7f * (s.done.toFloat() / s.total)
            is SyncState.Importing -> 0.95f
            is SyncState.Uploading -> if (s.totalBytes <= 0) 0.2f else 0.2f + 0.75f * (s.sentBytes.toFloat() / s.totalBytes)
            else -> 0f
        }

    val progressLabel: String
        get() = when (val s = syncState) {
            is SyncState.Connecting -> "connecting"
            is SyncState.Handshake -> "handshake"
            is SyncState.Listing -> "reading directory"
            is SyncState.Downloading -> if (s.total > 0) "file ${(s.done + 1).coerceAtMost(s.total)} of ${s.total}" else "downloading"
            is SyncState.Importing -> "importing"
            is SyncState.Uploading -> "${kb(s.sentBytes)} of ${kb(s.totalBytes)} KB"
            else -> ""
        }

    val currentFileLabel: String
        get() = when (val s = syncState) {
            is SyncState.Downloading -> s.fileLabel
            is SyncState.Importing -> "Reading the downloaded files"
            is SyncState.Connecting -> "Waking the watch"
            is SyncState.Handshake -> "Talking to the watch"
            is SyncState.Listing -> "Asking what is new"
            is SyncState.Uploading -> "Sending ${s.label}"
            else -> ""
        }

    /** Line under the Sync now button after the last run, null while idle with nothing to report. */
    val syncOutcome: String?
        get() = when (val s = syncState) {
            is SyncState.Done -> when {
                s.uploadedWorkout != null -> "Sent ${s.uploadedWorkout} to the watch"
                s.newFiles == 0 -> "Nothing new on the watch"
                else -> "Synced ${s.newFiles} new ${if (s.newFiles == 1) "file" else "files"}"
            }
            is SyncState.Failed -> s.reason
            else -> null
        }

    val syncFailed: Boolean get() = syncState is SyncState.Failed

    /** "Push day · Thu 17 Sep · 2 h ago" for the status card, null when nothing was ever sent. */
    val lastSentLabel: String?
        get() {
            val name = watch?.lastPushedWorkoutName ?: return null
            val at = watch.lastPushedAtMillis ?: return name
            return "$name · ${relative(at)}"
        }

    private fun kb(bytes: Int): String = String.format(Locale.US, "%.1f", bytes / 1024.0)
}

/** State and actions for the Watch screen: scanning, pairing, syncing, the FIT archive and the sync log. */
class WatchViewModel(private val c: AppContainer) : ViewModel() {
    private data class Local(
        val scanning: Boolean = false,
        val scanned: Boolean = false,
        val hits: List<ScanRow> = emptyList(),
        val pairing: String? = null,
        val message: String? = null,
        val messageIsError: Boolean = false,
        val bluetoothOff: Boolean = false,
        val dataStatus: String? = null,
        val dataBusy: Boolean = false
    )

    private val local = MutableStateFlow(Local())
    private var scanJob: Job? = null

    private val files = combine(
        c.syncedFiles.observeCount(),
        c.syncedFiles.observeAll().map { list -> list.mapNotNull { it.watchTimestamp }.minOrNull() }.distinctUntilChanged(),
        c.healthRebuild.state
    ) { count, earliest, rebuild -> Triple(count, earliest, rebuild) }

    val state: StateFlow<WatchScreenState> = combine(c.watch.watch, c.watch.syncState, c.watch.log, files, local) { w, sync, log, f, l ->
        val rebuild = f.third
        WatchScreenState(
            watch = w,
            syncState = sync,
            log = log,
            fileCount = f.first,
            earliestFileSeconds = f.second,
            rebuildStatus = when (rebuild) {
                is HealthRebuild.State.Running -> "Rebuilding health data from stored files\u2026"
                is HealthRebuild.State.Failed -> "Rebuild failed: ${rebuild.message}"
                else -> null
            },
            rebuildError = rebuild is HealthRebuild.State.Failed,
            scanning = l.scanning,
            scanned = l.scanned,
            hits = l.hits,
            pairing = l.pairing,
            message = l.message,
            messageIsError = l.messageIsError,
            bluetoothOff = l.bluetoothOff,
            dataStatus = l.dataStatus,
            dataBusy = l.dataBusy,
            loaded = true
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WatchScreenState())

    // Scanning and pairing

    /** Called once the runtime permissions are granted. Refuses politely when Bluetooth is off. */
    fun startScan() {
        if (local.value.scanning) return
        if (!c.watch.isBluetoothOn()) {
            local.update { it.copy(message = "Bluetooth is off. Turn it on, then scan again.", messageIsError = true, bluetoothOff = true) }
            return
        }
        local.update { it.copy(scanning = true, scanned = true, hits = emptyList(), message = null, messageIsError = false, bluetoothOff = false) }
        scanJob = viewModelScope.launch {
            c.watch.scan()
                .catch { e -> local.update { it.copy(message = e.message ?: "Scan failed", messageIsError = true, bluetoothOff = e.message?.contains("off") == true) } }
                .onCompletion { cause ->
                    local.update { l ->
                        // Only a scan that ran to its timeout reports "none found"; a cancelled one stays quiet.
                        val none = cause == null && l.hits.isEmpty() && l.message == null
                        l.copy(
                            scanning = false,
                            message = if (none) "No Garmin watches found. Put the watch in pairing mode (Settings, Phone, Pair phone) and scan again." else l.message
                        )
                    }
                }
                .collect { hit -> local.update { it.copy(hits = it.hits + ScanRow(hit.name, hit.address, signalWord(hit.rssi))) } }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        local.update { it.copy(scanning = false) }
    }

    fun permissionDenied() {
        stopScan()
        local.update { it.copy(message = "Bluetooth permission is needed to find and sync the watch. Allow it in the system settings for this app.", messageIsError = true, bluetoothOff = false) }
    }

    /** Bonds with the watch, stores it as paired and kicks off the first sync. */
    fun pair(row: ScanRow) {
        if (local.value.pairing != null) return
        stopScan()
        local.update { it.copy(pairing = row.address, message = "Pairing with ${row.name}. Confirm on the watch if it asks.", messageIsError = false) }
        viewModelScope.launch {
            try {
                c.watch.pair(row.address, row.name)
                local.update { it.copy(pairing = null, message = null, hits = emptyList(), scanned = false) }
                c.watch.requestSync()
            } catch (e: Exception) {
                local.update { it.copy(pairing = null, message = "Pairing failed: ${e.message ?: e.javaClass.simpleName}", messageIsError = true) }
            }
        }
    }

    // Syncing

    /** Called once the runtime permissions are granted. */
    fun syncNow() {
        if (!c.watch.isBluetoothOn()) {
            local.update { it.copy(message = "Bluetooth is off. Turn it on to sync.", messageIsError = true, bluetoothOff = true) }
            return
        }
        local.update { it.copy(message = null, messageIsError = false, bluetoothOff = false) }
        c.watch.requestSync()
    }

    fun cancelSync() = c.watch.cancelSync()

    fun setAutoSyncOnOpen(on: Boolean) = updateWatch { it.copy(autoSyncOnOpen = on) }

    fun setBackgroundSyncHours(hours: Int) = updateWatch { it.copy(backgroundSyncHours = hours.coerceIn(0, 24)) }

    fun setKeepConnected(on: Boolean) = updateWatch { it.copy(keepConnectedDuringSessions = on) }

    fun forgetWatch() {
        stopScan()
        viewModelScope.launch {
            c.watch.forgetWatch()
            local.update { it.copy(message = null, messageIsError = false, dataStatus = null, hits = emptyList(), scanned = false) }
        }
    }

    private fun updateWatch(transform: (WatchInfo) -> WatchInfo) {
        viewModelScope.launch { c.watch.updateWatch(transform) }
    }

    // FIT archive

    fun exportZip(uri: Uri) = runData {
        val out = c.appContext.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("Could not open the file for writing")
        val n = out.use { c.fitArchive.exportZip(c.rawFiles, it) }
        "Exported $n ${plural(n, "file")} to ${displayName(uri)}"
    }

    /** Zips (by name or content type) go through the zip importer; everything else is treated as a FIT file. */
    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        runData {
            val resolver = c.appContext.contentResolver
            var total = ImportSummary(0, 0)
            val fits = ArrayList<Pair<String, ByteArray>>()
            for (uri in uris) {
                val name = displayName(uri)
                val type = runCatching { resolver.getType(uri) }.getOrNull()
                val isZip = name.endsWith(".zip", ignoreCase = true) || type == "application/zip" || type == "application/x-zip-compressed"
                if (isZip) {
                    val input = resolver.openInputStream(uri) ?: throw IllegalStateException("Could not open $name")
                    total += input.use { c.fitArchive.importZip(it, c.rawFiles, c.fitImporter) }
                } else {
                    val input = resolver.openInputStream(uri) ?: throw IllegalStateException("Could not open $name")
                    fits += name to input.use { it.readBytes() }
                }
            }
            if (fits.isNotEmpty()) total += c.fitArchive.importFits(fits, c.rawFiles, c.fitImporter)
            val head = "Imported ${total.filesImported} ${plural(total.filesImported, "file")}, ${total.filesFailed} failed"
            val detail = total.errors.firstOrNull()?.let { ": $it" } ?: ""
            head + detail
        }
    }

    fun reimportAll() = runData {
        val s = c.fitImporter.reimportAll()
        c.healthRebuild.markDone()
        val parts = ArrayList<String>()
        parts += "${String.format(Locale.US, "%,d", s.minuteSamples)} minutes"
        parts += "${s.sleepNights} ${plural(s.sleepNights, "night")}"
        parts += "${s.activities} ${plural(s.activities, "activity", "activities")}"
        val failed = if (s.filesFailed > 0) ", ${s.filesFailed} failed" else ""
        "Re-imported ${s.filesImported} ${plural(s.filesImported, "file")}$failed: ${parts.joinToString(", ")}"
    }

    private fun runData(block: suspend () -> String) {
        if (local.value.dataBusy) return
        local.update { it.copy(dataBusy = true, dataStatus = null) }
        viewModelScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                "Failed: ${e.message ?: e.javaClass.simpleName}"
            }
            local.update { it.copy(dataBusy = false, dataStatus = result) }
        }
    }

    // Log

    /** The whole in-memory log as text, for the share sheet. */
    fun logText(): String {
        val lines = state.value.log
        if (lines.isEmpty()) return "No sync log yet."
        val w = state.value.watch
        val header = if (w != null) "Fitness Tracker sync log for ${w.name} (${w.macAddress}, fw ${w.firmwareVersion ?: "unknown"})\n\n" else "Fitness Tracker sync log\n\n"
        return header + lines.joinToString("\n") { "${logTime(it.atMillis)} ${it.text}" }
    }

    private fun displayName(uri: Uri): String {
        try {
            c.appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx)?.let { if (it.isNotBlank()) return it }
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private operator fun ImportSummary.plus(o: ImportSummary) = ImportSummary(
        filesImported + o.filesImported, filesFailed + o.filesFailed, minuteSamples + o.minuteSamples,
        sleepNights + o.sleepNights, activities + o.activities, errors + o.errors
    )

    companion object {
        fun signalWord(rssi: Int): String = when {
            rssi > -60 -> "strong"
            rssi > -75 -> "ok"
            else -> "weak"
        }

        fun plural(n: Int, one: String, many: String = one + "s"): String = if (n == 1) one else many
    }
}

private val logTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/** "21:08:02" in the local zone, for log lines. */
fun logTime(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(logTimeFormat)

/** "2 Jun" for a millisecond timestamp in the local zone. */
fun dayMonth(millis: Long): String = Dates.dayMonth(Dates.epochDayOf(millis))

/** "now", "12 min ago", "3 h ago", "yesterday", else the day and month. */
fun relative(millis: Long, now: Long = System.currentTimeMillis()): String {
    val diff = (now - millis).coerceAtLeast(0)
    val minutes = diff / 60_000
    val hours = diff / 3_600_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours h ago"
        Dates.epochDayOf(millis) == Dates.epochDayOf(now) - 1 -> "yesterday"
        else -> dayMonth(millis)
    }
}
