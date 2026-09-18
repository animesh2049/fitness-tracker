package com.animesh.fitnesstracker.garmin.sync

import java.io.File

/** A workout FIT file waiting to be pushed to the watch, with the name shown in progress and the log. */
data class PendingWorkoutUpload(val bytes: ByteArray, val name: String)

/**
 * Hands the encoded workout from the UI to the foreground service without going through an Intent
 * extra (a FIT file can exceed the binder limit). The bytes sit in [SyncRuntime.pendingUpload] and are
 * mirrored to `<filesDir>/garmin/outbox/pending.fit` (name in `pending.name`) so a service restarted by
 * the system can still find them. [take] prefers the in-memory copy and clears both.
 */
class WorkoutOutbox(private val dir: File) {
    private val fitFile get() = File(dir, "pending.fit")
    private val nameFile get() = File(dir, "pending.name")

    fun put(upload: PendingWorkoutUpload) {
        SyncRuntime.pendingUpload = upload
        runCatching {
            dir.mkdirs()
            fitFile.writeBytes(upload.bytes)
            nameFile.writeText(upload.name)
        }
    }

    fun take(): PendingWorkoutUpload? {
        val inMemory = SyncRuntime.pendingUpload
        SyncRuntime.pendingUpload = null
        val result = inMemory ?: runCatching {
            if (fitFile.exists() && fitFile.length() > 0) {
                PendingWorkoutUpload(fitFile.readBytes(), nameFile.takeIf { it.exists() }?.readText()?.ifBlank { null } ?: "Workout")
            } else null
        }.getOrNull()
        clear()
        return result
    }

    fun clear() {
        SyncRuntime.pendingUpload = null
        runCatching { fitFile.delete(); nameFile.delete() }
    }
}
