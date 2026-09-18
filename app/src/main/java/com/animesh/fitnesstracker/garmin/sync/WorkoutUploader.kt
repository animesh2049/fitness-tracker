package com.animesh.fitnesstracker.garmin.sync

import com.animesh.fitnesstracker.garmin.ble.Crc16
import com.animesh.fitnesstracker.garmin.gfdi.Capabilities
import com.animesh.fitnesstracker.garmin.gfdi.CreateStatus
import com.animesh.fitnesstracker.garmin.gfdi.FileFlag
import com.animesh.fitnesstracker.garmin.gfdi.FitFileType
import com.animesh.fitnesstracker.garmin.gfdi.GfdiId
import com.animesh.fitnesstracker.garmin.gfdi.GfdiMessage
import com.animesh.fitnesstracker.garmin.gfdi.GfdiOut
import com.animesh.fitnesstracker.garmin.gfdi.GfdiStatus
import com.animesh.fitnesstracker.garmin.gfdi.Handshake
import com.animesh.fitnesstracker.garmin.gfdi.SystemEvent
import com.animesh.fitnesstracker.garmin.gfdi.TransferStatus
import com.animesh.fitnesstracker.garmin.gfdi.UploadStatus
import java.security.SecureRandom

/**
 * Pushes one workout FIT file (128/5) to the watch over a connected, handshaken [GfdiEndpoint], the way
 * Gadgetbridge's `FileTransferHandler.Upload` does: optional SET_FILE_FLAG(DELETE) of the previously
 * pushed file, CREATE_FILE, UPLOAD_REQUEST, FILE_TRANSFER_DATA chunks of `maxPacketSize - 13` bytes each
 * carrying the running CRC-16 over everything sent so far, then SYSTEM_EVENT SYNC_COMPLETE. The watch's
 * per-chunk status is honoured: RESEND and OFFSET_MISMATCH rewind to the offset it names, CRC_MISMATCH
 * resends the chunk, ABORT fails; more than [MAX_RETRIES] bad chunks fail the upload. Messages that are
 * not ours go to [responder]. Runs only when no download is in flight (the caller serialises sessions).
 */
class WorkoutUploader(
    private val endpoint: GfdiEndpoint,
    private val responder: Handshake,
    private val log: (String) -> Unit = {},
    private val silenceMs: Long = 30_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fileIdSource: () -> Long = { SecureRandom().nextLong() }
) {
    data class Result(
        /** Directory index the watch assigned to the new file. */
        val fileIndex: Int,
        val bytesSent: Int,
        val durationMs: Long,
        /** True when the previous workout index was deleted (false when there was none or the watch refused). */
        val previousDeleted: Boolean
    )

    /**
     * Uploads [bytes] under [label] (the workout name, for the log), deleting [previousIndex] first when
     * given. [onProgress] gets (sentBytes, totalBytes) after every acknowledged chunk.
     */
    suspend fun upload(bytes: ByteArray, label: String, previousIndex: Int?, onProgress: suspend (Int, Int) -> Unit): Result {
        val started = clock()
        log("Workout upload: $label (${bytes.size} bytes)")
        checkCapabilities()
        val previousDeleted = if (previousIndex != null && previousIndex > 0) deletePrevious(previousIndex) else false
        val index = createFile(bytes.size.toLong())
        val (startOffset, startCrc) = requestUpload(index, bytes.size.toLong())
        onProgress(startOffset, bytes.size)
        sendChunks(bytes, startOffset, startCrc, onProgress)
        val duration = clock() - started
        log("Upload done: ${bytes.size} bytes to index $index in $duration ms")
        syncComplete()
        return Result(index, bytes.size, duration, previousDeleted)
    }

    private fun checkCapabilities() {
        // Neither signal is authoritative: the Forerunner 570 lists 15 file types without 128/5 yet still
        // takes workouts, so both are logged as hints and CREATE_FILE's reply decides.
        val caps = responder.watchCapabilities
        if (caps != null && !Capabilities.has(caps, Capabilities.WORKOUT_DOWNLOAD)) {
            log("Watch does not advertise workout download (capability ${Capabilities.WORKOUT_DOWNLOAD}), trying anyway")
        }
        when (responder.supportsFileType(FitFileType.DATA_TYPE_FIT, FitFileType.WORKOUT)) {
            true -> log("Watch accepts workout files (128/5)")
            false -> log("Watch did not list workout files (128/5) among its supported file types, letting CREATE_FILE decide")
            null -> log("Watch never listed its file types, letting CREATE_FILE decide")
        }
    }

    private suspend fun deletePrevious(index: Int): Boolean {
        log("Deleting previous workout (index $index)")
        endpoint.send(GfdiOut.setFileFlag(index, FileFlag.DELETE))
        val status = await("delete of index $index") {
            it is GfdiMessage.SetFileFlagStatus || (it is GfdiMessage.GenericStatus && it.originalId == GfdiId.SET_FILE_FLAG)
        }
        val ok = when (status) {
            is GfdiMessage.SetFileFlagStatus -> status.status == GfdiStatus.ACK && status.flagsStatus == 0
            is GfdiMessage.GenericStatus -> status.status == GfdiStatus.ACK
            else -> false
        }
        if (ok) log("Previous workout index $index deleted") else log("Watch did not delete index $index ($status), continuing")
        return ok
    }

    private suspend fun createFile(size: Long): Int {
        val fileId = fileIdSource()
        log("CREATE_FILE 128/5, $size bytes, id ${java.lang.Long.toHexString(fileId)}")
        endpoint.send(GfdiOut.createFile(size, fileId = fileId))
        val reply = await("CREATE_FILE status") {
            it is GfdiMessage.CreateFileStatus || (it is GfdiMessage.GenericStatus && it.originalId == GfdiId.CREATE_FILE)
        }
        if (reply is GfdiMessage.GenericStatus) throw SyncException("Watch refused CREATE_FILE with status ${reply.status}")
        val status = reply as GfdiMessage.CreateFileStatus
        if (status.status != GfdiStatus.ACK) throw SyncException("Watch refused CREATE_FILE with status ${status.status}")
        when (status.createStatus) {
            CreateStatus.OK -> Unit
            CreateStatus.DUPLICATE -> throw SyncException("The watch already has this workout")
            CreateStatus.NO_SLOTS, CreateStatus.NO_SPACE_FOR_TYPE ->
                throw SyncException("The watch's workout list is full, delete some workouts on the watch")
            CreateStatus.NO_SPACE -> throw SyncException("The watch has no free space for the workout")
            CreateStatus.UNSUPPORTED -> throw SyncException("The watch does not accept workout files")
            else -> throw SyncException("CREATE_FILE failed: ${CreateStatus.name(status.createStatus)}")
        }
        log("Watch created file index ${status.fileIndex} (type ${status.dataType}/${status.subType}, number ${status.number})")
        return status.fileIndex
    }

    /** Returns (offset, crcSeed) to start from; both 0 for a fresh file. */
    private suspend fun requestUpload(index: Int, size: Long): Pair<Int, Int> {
        endpoint.send(GfdiOut.uploadRequest(index, size))
        val reply = await("UPLOAD_REQUEST status") {
            it is GfdiMessage.UploadRequestStatus || (it is GfdiMessage.GenericStatus && it.originalId == GfdiId.UPLOAD_REQUEST)
        }
        if (reply is GfdiMessage.GenericStatus) throw SyncException("Watch refused UPLOAD_REQUEST with status ${reply.status}")
        val status = reply as GfdiMessage.UploadRequestStatus
        if (!status.ok) throw SyncException("Watch refused the upload: ${UploadStatus.name(status.uploadStatus)}")
        if (status.maxSize in 1 until size) throw SyncException("Workout is $size bytes but the watch allows ${status.maxSize}")
        if (status.offset >= size) throw SyncException("Watch asked to start at offset ${status.offset} of $size")
        log("UPLOAD_REQUEST index $index accepted: offset ${status.offset}, max ${status.maxSize}, crc seed ${status.crcSeed}")
        return status.offset.toInt() to status.crcSeed
    }

    private suspend fun sendChunks(bytes: ByteArray, startOffset: Int, startCrc: Int, onProgress: suspend (Int, Int) -> Unit) {
        val chunkSize = chunkSize()
        log("Sending ${bytes.size - startOffset} bytes in chunks of $chunkSize")
        var offset = startOffset
        var crc = startCrc
        var retries = 0
        while (offset < bytes.size) {
            val n = minOf(chunkSize, bytes.size - offset)
            val chunkCrc = Crc16.compute(bytes, offset, n, seed = crc)
            endpoint.send(GfdiOut.fileTransferData(offset.toLong(), chunkCrc, bytes.copyOfRange(offset, offset + n)))
            val status = await("transfer status at offset $offset") { it is GfdiMessage.FileTransferDataStatus } as GfdiMessage.FileTransferDataStatus
            if (status.status != GfdiStatus.ACK) throw SyncException("Watch answered a chunk with status ${status.status}")
            val next = status.nextOffset.toInt()
            when (status.transferStatus) {
                TransferStatus.OK -> {
                    if (next == offset + n) {
                        offset = next
                        crc = chunkCrc
                    } else {
                        log("Watch acknowledged up to offset $next instead of ${offset + n}, continuing from there")
                        offset = next.coerceIn(0, bytes.size)
                        crc = Crc16.compute(bytes, 0, offset)
                    }
                    onProgress(offset, bytes.size)
                }
                TransferStatus.RESEND, TransferStatus.OFFSET_MISMATCH -> {
                    retries++
                    log("Watch asked to resend from offset $next (${TransferStatus.name(status.transferStatus)}, retry $retries of $MAX_RETRIES)")
                    offset = next.coerceIn(0, bytes.size)
                    crc = Crc16.compute(bytes, 0, offset)
                }
                TransferStatus.CRC_MISMATCH -> {
                    retries++
                    log("CRC mismatch reported at offset $offset, resending (retry $retries of $MAX_RETRIES)")
                }
                TransferStatus.SYNC_PAUSED -> {
                    retries++
                    log("Watch paused the transfer at offset $offset, resending (retry $retries of $MAX_RETRIES)")
                }
                TransferStatus.ABORT -> throw SyncException("The watch aborted the transfer at offset $offset")
                else -> throw SyncException("Unknown transfer status ${status.transferStatus} at offset $offset")
            }
            if (retries > MAX_RETRIES) throw SyncException("Too many bad chunks, giving up at offset $offset")
        }
    }

    private suspend fun syncComplete() {
        endpoint.send(GfdiOut.systemEvent(SystemEvent.SYNC_COMPLETE))
        log("SYNC_COMPLETE sent")
        // The ACK is nice to have, not required: a watch that stays quiet has still stored the file.
        val deadline = clock() + ACK_WAIT_MS
        while (true) {
            val remaining = deadline - clock()
            if (remaining <= 0) return
            val msg = endpoint.receive(remaining) ?: return
            if (msg is GfdiMessage.GenericStatus && msg.originalId == GfdiId.SYSTEM_EVENT) {
                log("Watch acknowledged SYNC_COMPLETE")
                return
            }
            responder.onMessage(msg)
        }
    }

    private fun chunkSize(): Int {
        val max = responder.deviceInfo?.maxPacketSize ?: 0
        val packet = if (max in 32..65534) max else DEFAULT_MAX_PACKET
        return (packet - GfdiOut.FILE_TRANSFER_DATA_OVERHEAD).coerceAtLeast(16)
    }

    /** Reads messages until [wanted] matches, letting the responder answer everything else. */
    private suspend fun await(what: String, wanted: (GfdiMessage) -> Boolean): GfdiMessage {
        while (true) {
            val msg = endpoint.receive(silenceMs)
                ?: throw SyncException("Watch went silent for ${silenceMs / 1000} s while waiting for $what")
            if (wanted(msg)) return msg
            responder.onMessage(msg)
        }
    }

    companion object {
        const val MAX_RETRIES = 5
        const val DEFAULT_MAX_PACKET = 375
        const val ACK_WAIT_MS = 3_000L
    }
}
