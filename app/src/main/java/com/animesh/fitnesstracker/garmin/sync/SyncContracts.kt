package com.animesh.fitnesstracker.garmin.sync

import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * Contracts between the Bluetooth sync stack (Milestones 16, 17, 22), the FIT importer
 * (Milestone 19) and the UI (Milestone 21). Everything else in `garmin/` is an implementation detail.
 */

/** Progress of one sync run, observed by the Health tab and the Watch screen. */
sealed class SyncState {
    data object Idle : SyncState()
    data object Connecting : SyncState()
    data object Handshake : SyncState()
    data object Listing : SyncState()
    data class Downloading(val done: Int, val total: Int, val fileLabel: String) : SyncState()
    data object Importing : SyncState()
    data class Done(val newFiles: Int, val finishedAtMillis: Long) : SyncState()
    data class Failed(val reason: String, val failedAtMillis: Long) : SyncState()

    val isRunning: Boolean get() = this !is Idle && this !is Done && this !is Failed
}

/** A raw file the watch gave us, kept on disk after import. */
data class StoredFile(
    /** Watch directory index at download time (0 when the file was imported from a zip or USB copy). */
    val watchIndex: Int,
    /** FIT file_id.type as the watch reported it in its directory listing (4 activity, 32 monitor, 49 sleep, ...). */
    val fitType: Int,
    /** Unix seconds from the watch's directory entry, or null when the watch had no date for it. */
    val watchTimestamp: Long?,
    val file: File
) {
    val sizeBytes: Long get() = file.length()
}

/**
 * Where raw FIT files live: `<filesDir>/garmin/<type>/<yyyy>/<type>_<yyyy-MM-dd_HH-mm-ss>_<index>.fit`.
 * Implemented in Milestone 17; used by the importer's re-import and by the zip export and import.
 */
interface RawFileStore {
    val root: File
    /** True when a file with this watch index and timestamp is already on disk with size > 0. */
    fun exists(watchIndex: Int, fitType: Int, watchTimestamp: Long?): Boolean
    /** Writes the bytes and returns the stored file. Overwrites an existing entry with the same name. */
    fun save(watchIndex: Int, fitType: Int, watchTimestamp: Long?, bytes: ByteArray): StoredFile
    /** Stores a file that did not come from the watch directly (zip or USB copy); index 0, timestamp from the FIT header if known. */
    fun saveImported(fitType: Int, timeCreated: Long?, bytes: ByteArray): StoredFile
    /** Every stored file, oldest first. */
    fun listAll(): List<StoredFile>
    fun totalBytes(): Long
}

/** What the importer reports after processing a batch of files. */
data class ImportSummary(
    val filesImported: Int,
    val filesFailed: Int,
    val minuteSamples: Int = 0,
    val sleepNights: Int = 0,
    val activities: Int = 0,
    val errors: List<String> = emptyList()
)

/**
 * Bridge from the sync stack to the importer. The sync service calls it once per run with the files
 * it just downloaded; the Watch screen calls it for "Re-import all" with every stored file.
 * Implemented by `garmin/import/FitImporter` (Milestone 19).
 */
fun interface ImportHook {
    suspend fun importFiles(files: List<StoredFile>): ImportSummary
}

/** The paired watch as the sync stack knows it. Persisted by the sync stack (SharedPreferences), not Room. */
data class WatchInfo(
    val macAddress: String,
    val name: String,
    val unitId: Long? = null,
    val firmwareVersion: String? = null,
    val firstConnectDone: Boolean = false,
    val pairedAtMillis: Long = 0L,
    val lastSyncAtMillis: Long? = null,
    val lastBatteryPercent: Int? = null,
    val autoSyncOnOpen: Boolean = true,
    /** 0 disables background sync; otherwise the WorkManager interval in hours. */
    val backgroundSyncHours: Int = 0,
    val keepConnectedDuringSessions: Boolean = false
)

/** One line of the sync log shown on the Watch screen. */
data class SyncLogLine(val atMillis: Long, val text: String)

/**
 * What the UI needs from the sync stack. Implemented in Milestone 17 (`WatchController`).
 * The UI never touches Bluetooth classes directly.
 */
interface WatchGateway {
    val watch: Flow<WatchInfo?>
    val syncState: Flow<SyncState>
    val log: Flow<List<SyncLogLine>>
    /** Starts a sync in the foreground service; no-op if one is running or no watch is paired. */
    fun requestSync()
    fun cancelSync()
    suspend fun updateWatch(transform: (WatchInfo) -> WatchInfo)
    /** Removes the pairing (and the Android bond when possible). Imported data is untouched. */
    suspend fun forgetWatch()
}
