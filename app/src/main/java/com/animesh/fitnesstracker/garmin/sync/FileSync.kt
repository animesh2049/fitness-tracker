package com.animesh.fitnesstracker.garmin.sync

import com.animesh.fitnesstracker.garmin.ble.Crc16
import com.animesh.fitnesstracker.garmin.gfdi.DirectoryEntry
import com.animesh.fitnesstracker.garmin.gfdi.FileFlag
import com.animesh.fitnesstracker.garmin.gfdi.GfdiId
import com.animesh.fitnesstracker.garmin.gfdi.GfdiMessage
import com.animesh.fitnesstracker.garmin.gfdi.GfdiOut
import com.animesh.fitnesstracker.garmin.gfdi.Handshake
import com.animesh.fitnesstracker.garmin.gfdi.SystemEvent
import com.animesh.fitnesstracker.garmin.gfdi.TransferStatus
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The classic GFDI file protocol, phone side: FILTER(ONLY_NEW), DOWNLOAD_REQUEST of the directory
 * (index 0), 16-byte directory entries, then one DOWNLOAD_REQUEST per wanted FIT file (dataType 128,
 * subtypes 4, 15, 28, 32, 44, 49, 68) that is not already in the [RawFileStore]. Each FILE_TRANSFER_DATA
 * chunk is checked for offset and running CRC and answered with RESPONSE(5004, nextOffset); a bad chunk
 * gets CRC_MISMATCH or OFFSET_MISMATCH with the offset we expect. Every stored file is archived with
 * SET_FILE_FLAG(ARCHIVE). SYNCHRONIZATION and FILE_AVAILABLE pushes during the session extend the queue;
 * when it drains, SYSTEM_EVENT SYNC_COMPLETE is sent. Messages that are not ours go to [responder].
 */
class FileSync(
    private val endpoint: GfdiEndpoint,
    private val responder: Handshake,
    private val store: RawFileStore,
    private val log: (String) -> Unit = {},
    private val silenceMs: Long = 60_000,
    private val zone: ZoneId = ZoneId.systemDefault()
) {
    private val queue = ArrayDeque<DirectoryEntry>()
    private val seenIndexes = HashSet<Int>()
    private var relistRequested = false
    private var relists = 0
    private val downloaded = ArrayList<StoredFile>()
    private var archived = 0

    /** Runs the whole sync and returns the files that were newly written to the store. */
    suspend fun run(onProgress: suspend (SyncState) -> Unit): List<StoredFile> {
        responder.onPush = { push ->
            when (push) {
                is GfdiMessage.Synchronization -> relistRequested = true
                is GfdiMessage.FileAvailable -> consider(push.entry, "pushed")
                else -> Unit
            }
        }
        try {
            onProgress(SyncState.Listing)
            endpoint.send(GfdiOut.filterOnlyNew())
            awaitStatusFor(GfdiId.FILTER)
            listDirectory()
            while (true) {
                val entry = queue.removeFirstOrNull()
                if (entry == null) {
                    if (relistRequested && relists < MAX_RELISTS) {
                        relistRequested = false
                        relists++
                        log("Watch requested a sync mid-session, listing again")
                        listDirectory()
                        continue
                    }
                    break
                }
                val total = downloaded.size + queue.size + 1
                onProgress(SyncState.Downloading(downloaded.size, total, label(entry)))
                val exists = store.exists(entry.index, entry.subType, entry.unixTimestamp)
                if (exists) {
                    log("Already have ${label(entry)} (index ${entry.index}), archiving only")
                    archive(entry)
                    continue
                }
                val bytes = download(entry.index, label(entry))
                if (bytes == null) {
                    log("Skipped ${label(entry)} (index ${entry.index})")
                    continue
                }
                val stored = store.save(entry.index, entry.subType, entry.unixTimestamp, bytes)
                downloaded.add(stored)
                log("Saved ${stored.file.name} (${bytes.size} bytes)")
                archive(entry)
            }
            endpoint.send(GfdiOut.systemEvent(SystemEvent.SYNC_COMPLETE))
            log("Sync complete: ${downloaded.size} new file(s), $archived archived on the watch")
            return downloaded.toList()
        } finally {
            responder.onPush = null
        }
    }

    private suspend fun listDirectory() {
        val bytes = download(0, "directory") ?: throw SyncException("Watch refused the directory listing")
        val entries = DirectoryEntry.parseDirectory(bytes)
        val fit = entries.count { it.isFit }
        log("Directory: ${entries.size} entries, $fit FIT files")
        for (e in entries) consider(e, "listed")
    }

    private fun consider(e: DirectoryEntry, origin: String) {
        if (!e.isFit || e.subType !in WANTED_SUBTYPES) return
        if (e.index == 0 || !seenIndexes.add(e.index)) return
        queue.addLast(e)
        if (origin == "pushed") log("Watch announced ${label(e)} (index ${e.index})")
    }

    private suspend fun archive(entry: DirectoryEntry) {
        endpoint.send(GfdiOut.setFileFlag(entry.index, FileFlag.ARCHIVE))
        val status = await("archive of index ${entry.index}") { it is GfdiMessage.SetFileFlagStatus || (it is GfdiMessage.GenericStatus && it.originalId == GfdiId.SET_FILE_FLAG) }
        if (status is GfdiMessage.SetFileFlagStatus && status.status == 0 && status.flagsStatus == 0) archived++
        else if (status is GfdiMessage.GenericStatus && status.status == 0) archived++
        else log("Watch did not archive index ${entry.index}: $status")
    }

    /**
     * DOWNLOAD_REQUEST(index, NEW) followed by the chunk loop. Returns null when the watch refuses the
     * index (status other than OK) so the caller can move on.
     */
    private suspend fun download(index: Int, label: String): ByteArray? {
        endpoint.send(GfdiOut.downloadRequest(index))
        val status = await("download status for $label") { it is GfdiMessage.DownloadRequestStatus } as GfdiMessage.DownloadRequestStatus
        if (!status.ok) {
            log("Download of $label refused: status ${status.status}, downloadStatus ${status.downloadStatus}")
            return null
        }
        val size = status.maxFileSize
        if (size == 0L) return ByteArray(0)
        val buf = ByteArrayOutputStream(size.toInt().coerceAtLeast(16))
        var crc = 0
        var badChunks = 0
        while (buf.size() < size) {
            val chunk = await("data for $label") { it is GfdiMessage.FileTransferData } as GfdiMessage.FileTransferData
            val received = buf.size().toLong()
            if (chunk.offset != received) {
                badChunks++
                log("Offset mismatch on $label: got ${chunk.offset}, expected $received")
                endpoint.send(GfdiOut.fileTransferDataStatus(TransferStatus.OFFSET_MISMATCH, received))
                if (badChunks > MAX_BAD_CHUNKS) throw SyncException("Too many bad chunks for $label")
                continue
            }
            val next = Crc16.compute(chunk.data, seed = crc)
            if (next != chunk.crc) {
                badChunks++
                log("CRC mismatch on $label at offset $received")
                endpoint.send(GfdiOut.fileTransferDataStatus(TransferStatus.CRC_MISMATCH, received))
                if (badChunks > MAX_BAD_CHUNKS) throw SyncException("Too many bad chunks for $label")
                continue
            }
            crc = next
            buf.write(chunk.data, 0, chunk.data.size)
            endpoint.send(GfdiOut.fileTransferDataStatus(TransferStatus.OK, buf.size().toLong()))
        }
        val bytes = buf.toByteArray()
        return if (bytes.size > size) bytes.copyOf(size.toInt()) else bytes
    }

    private suspend fun awaitStatusFor(originalId: Int): GfdiMessage =
        await("status for $originalId") { it is GfdiMessage.GenericStatus && it.originalId == originalId }

    /** Reads messages until [wanted] matches, letting the responder answer everything else. */
    private suspend fun await(what: String, wanted: (GfdiMessage) -> Boolean): GfdiMessage {
        while (true) {
            val msg = endpoint.receive(silenceMs)
                ?: throw SyncException("Watch went silent for ${silenceMs / 1000} s while waiting for $what")
            if (wanted(msg)) return msg
            responder.onMessage(msg)
        }
    }

    /** "MONITOR 16 Sep" style label for progress and logs. */
    fun label(e: DirectoryEntry): String {
        val type = FileRawFileStore.typeName(e.subType)
        val ts = e.unixTimestamp ?: return "$type #${e.index}"
        return "$type " + DAY.format(Instant.ofEpochSecond(ts).atZone(zone))
    }

    companion object {
        val WANTED_SUBTYPES = setOf(4, 15, 28, 32, 44, 49, 68)
        const val MAX_BAD_CHUNKS = 5
        const val MAX_RELISTS = 3
        private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    }
}
