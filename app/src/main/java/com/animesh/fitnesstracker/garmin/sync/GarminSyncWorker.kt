package com.animesh.fitnesstracker.garmin.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Milestone 22 background sync: a periodic WorkManager job (interval = `WatchInfo.backgroundSyncHours`,
 * battery not low) that runs the same [SyncSession] as the foreground service, directly and with a
 * 5 minute limit. It gives up quietly when nothing is paired, permissions are missing, Bluetooth is off
 * or a sync is already running.
 */
class GarminSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val controller = SyncRuntime.controller ?: return Result.success()
        if (controller.currentWatch == null) return Result.success()
        if (SyncRuntime.isRunning) return Result.success()
        if (!BluetoothPermissions.granted(applicationContext) || !controller.isBluetoothOn()) return Result.success()
        SyncRuntime.activeJob = currentCoroutineContext().job
        try {
            val outcome = withTimeoutOrNull(LIMIT_MS) { controller.runSync() }
            if (outcome == null) {
                controller.syncLog.log("Background sync stopped after ${LIMIT_MS / 60_000} minutes")
                SyncRuntime.state.value = SyncState.Failed("Background sync timed out", System.currentTimeMillis())
            }
        } finally {
            SyncRuntime.activeJob = null
        }
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "garmin_background_sync"
        const val LIMIT_MS = 5L * 60 * 1000
    }
}

/** Enqueues the unique periodic sync every [hours] hours, or cancels it when [hours] is 0 or less. */
fun scheduleBackgroundSync(context: Context, hours: Int) {
    val wm = WorkManager.getInstance(context)
    if (hours <= 0) {
        wm.cancelUniqueWork(GarminSyncWorker.UNIQUE_NAME)
        return
    }
    val request = PeriodicWorkRequestBuilder<GarminSyncWorker>(hours.toLong(), TimeUnit.HOURS)
        .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
        .build()
    wm.enqueueUniquePeriodicWork(GarminSyncWorker.UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
}
