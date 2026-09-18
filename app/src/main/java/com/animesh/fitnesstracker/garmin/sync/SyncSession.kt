package com.animesh.fitnesstracker.garmin.sync

import com.animesh.fitnesstracker.garmin.ble.GfdiLink
import com.animesh.fitnesstracker.garmin.ble.LinkKind
import com.animesh.fitnesstracker.garmin.ble.MultiLink
import com.animesh.fitnesstracker.garmin.ble.PlainCobsLink
import com.animesh.fitnesstracker.garmin.ble.Transport
import com.animesh.fitnesstracker.garmin.ble.TransportState
import com.animesh.fitnesstracker.garmin.gfdi.Handshake
import com.animesh.fitnesstracker.garmin.gfdi.PhoneInfo
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * One complete session with the watch: connect (30 s), bring up the GFDI link, run the [Handshake]
 * (20 s), then either [run] the download sync ([FileSync] with 60 s of silence per file, then the
 * [ImportHook]) or [runUpload] a workout push ([WorkoutUploader]), then disconnect. The two never share a
 * connection, so an upload can never interleave with a download. Every step is logged to [SyncLog];
 * progress goes to the `onState` callback. Cancelling the calling coroutine tears the connection down
 * cleanly. Timeouts and transport pieces are injectable so the whole session runs against a fake watch
 * in unit tests.
 */
class SyncSession(
    private val transportFactory: (address: String, scope: CoroutineScope) -> Transport,
    private val store: RawFileStore,
    private val importHook: ImportHook,
    private val log: SyncLog,
    private val phone: PhoneInfo,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val connectTimeoutMs: Long = 30_000,
    private val handshakeTimeoutMs: Long = Handshake.TIMEOUT_MS,
    private val fileSilenceMs: Long = 60_000,
    private val requestReliable: Boolean = true,
    private val uploadSilenceMs: Long = 30_000
) {
    data class Outcome(
        val success: Boolean,
        val reason: String? = null,
        val newFiles: List<StoredFile> = emptyList(),
        val importSummary: ImportSummary? = null,
        val unitId: Long? = null,
        val firmwareVersion: String? = null,
        val batteryPercent: Int? = null,
        /** Watch index of the workout file written by [runUpload], null for a download sync or a failed upload. */
        val uploadedIndex: Int? = null,
        val uploadedBytes: Int = 0,
        val uploadDurationMs: Long = 0
    )

    /** What [runUpload] pushes: the encoded FIT bytes, the workout name and the index of the previous push to delete. */
    data class WorkoutUpload(val bytes: ByteArray, val name: String, val previousIndex: Int?)

    private class Connected(val endpoint: GfdiEndpoint, val handshake: Handshake)

    /** Download sync: list, download new files, import them. */
    suspend fun run(watch: WatchInfo, firstConnect: Boolean, onState: suspend (SyncState) -> Unit): Outcome {
        log.log("Sync started for ${watch.name} (${watch.macAddress})" + if (firstConnect) ", first connect" else "")
        var newFiles: List<StoredFile> = emptyList()
        return session(watch, firstConnect, "Sync", onState, onFailure = { Outcome(success = false, newFiles = newFiles) }) { c ->
            val sync = FileSync(c.endpoint, c.handshake, store, log::log, fileSilenceMs, zone)
            newFiles = sync.run(onState)
            onState(SyncState.Importing)
            val summary = if (newFiles.isEmpty()) null else importHook.importFiles(newFiles)
            if (summary != null) {
                log.log("Imported ${summary.filesImported} file(s), ${summary.filesFailed} failed" +
                    if (summary.errors.isEmpty()) "" else ": " + summary.errors.take(3).joinToString("; "))
            }
            onState(SyncState.Done(newFiles.size, clock()))
            Outcome(success = true, newFiles = newFiles, importSummary = summary)
        }
    }

    /** Workout push: delete the previous push, upload the new file, tell the watch the sync is complete. */
    suspend fun runUpload(watch: WatchInfo, firstConnect: Boolean, upload: WorkoutUpload, onState: suspend (SyncState) -> Unit): Outcome {
        log.log("Workout push started for ${watch.name} (${watch.macAddress}): ${upload.name}" + if (firstConnect) ", first connect" else "")
        return session(watch, firstConnect, "Workout push", onState, onFailure = { Outcome(success = false) }) { c ->
            onState(SyncState.Uploading(0, upload.bytes.size, upload.name))
            val uploader = WorkoutUploader(c.endpoint, c.handshake, log::log, uploadSilenceMs, clock)
            val result = uploader.upload(upload.bytes, upload.name, upload.previousIndex) { sent, total ->
                onState(SyncState.Uploading(sent, total, upload.name))
            }
            onState(SyncState.Done(0, clock(), uploadedWorkout = upload.name))
            Outcome(success = true, uploadedIndex = result.fileIndex, uploadedBytes = result.bytesSent, uploadDurationMs = result.durationMs)
        }
    }

    /**
     * Connects, handshakes, runs [body], disconnects. Success comes from [body]; failures are logged as
     * "<what> failed: reason" and reported through [onFailure] (filled in with the handshake facts).
     */
    private suspend fun session(
        watch: WatchInfo,
        firstConnect: Boolean,
        what: String,
        onState: suspend (SyncState) -> Unit,
        onFailure: () -> Outcome,
        body: suspend (Connected) -> Outcome
    ): Outcome {
        var handshake: Handshake? = null
        try {
            val outcome = coroutineScope {
                val transport = transportFactory(watch.macAddress, this)
                var link: GfdiLink? = null
                var pump: Job? = null
                var watcher: Job? = null
                try {
                    onState(SyncState.Connecting)
                    timed(connectTimeoutMs, "Connection") { transport.connect() }
                    val connected = transport.state.value as? TransportState.Connected ?: throw SyncException("Not connected")
                    log.log("Connected over ${connected.kind}, write chunk ${connected.maxWrite} bytes")
                    val l = when (connected.kind) {
                        LinkKind.MULTI_LINK -> MultiLink(transport::send, connected.maxWrite, this, requestReliable, log = log::log)
                        LinkKind.V1 -> PlainCobsLink(transport::send, connected.maxWrite)
                    }
                    link = l
                    val endpoint = GfdiEndpoint(l, log::log)
                    pump = launch {
                        transport.incoming.collect { l.onNotification(it) }
                        // The transport usually knows why; the state watcher below may not have run yet.
                        endpoint.fail((transport.state.value as? TransportState.Disconnected)?.reason ?: "Link closed")
                    }
                    watcher = launch {
                        val s = transport.state.first { it is TransportState.Disconnected } as TransportState.Disconnected
                        endpoint.fail(s.reason ?: "Disconnected")
                    }
                    onState(SyncState.Handshake)
                    timed(handshakeTimeoutMs, "Handle registration") { l.open() }
                    if (l is MultiLink) log.log("GFDI handle ${l.handle}" + if (l.reliable) " (reliable)" else "")
                    val hs = Handshake(endpoint::send, phone, firstConnect, clock, zone, log::log)
                    handshake = hs
                    runHandshake(endpoint, hs)
                    body(Connected(endpoint, hs))
                } finally {
                    pump?.cancel()
                    watcher?.cancel()
                    link?.close()
                    transport.close()
                }
            }
            log.log("Disconnected")
            return outcome.copy(
                unitId = handshake?.deviceInfo?.unitNumber, firmwareVersion = handshake?.deviceInfo?.softwareVersionString,
                batteryPercent = handshake?.batteryPercent
            )
        } catch (e: CancellationException) {
            log.log("$what cancelled")
            onState(SyncState.Idle)
            throw e
        } catch (e: Exception) {
            val reason = e.message ?: e.javaClass.simpleName
            log.log("$what failed: $reason")
            onState(SyncState.Failed(reason, clock()))
            return onFailure().copy(
                reason = reason,
                unitId = handshake?.deviceInfo?.unitNumber, firmwareVersion = handshake?.deviceInfo?.softwareVersionString,
                batteryPercent = handshake?.batteryPercent
            )
        }
    }

    /** Pumps messages through the handshake until it reports ready or the handshake timeout passes. */
    private suspend fun runHandshake(endpoint: GfdiEndpoint, hs: Handshake) {
        val deadline = clock() + handshakeTimeoutMs
        while (!hs.isReady()) {
            val now = clock()
            val remaining = deadline - now
            if (remaining <= 0) {
                val got = if (hs.deviceInfo == null) "no DEVICE_INFORMATION" else if (hs.configuredAtMillis == null) "no CONFIGURATION" else "no file type list"
                throw SyncException("Handshake timed out ($got)")
            }
            val wait = minOf(remaining, hs.graceRemainingMs(now) ?: remaining).coerceAtLeast(1)
            val msg = endpoint.receive(wait) ?: continue
            hs.onMessage(msg)
        }
        log.log("Handshake complete")
    }

    private suspend fun <T> timed(ms: Long, what: String, block: suspend () -> T): T =
        try {
            withTimeout(ms) { block() }
        } catch (e: TimeoutCancellationException) {
            throw SyncException("$what timed out after ${ms / 1000} s")
        }
}
