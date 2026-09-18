package com.animesh.fitnesstracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.animesh.fitnesstracker.MainActivity
import com.animesh.fitnesstracker.R
import com.animesh.fitnesstracker.garmin.sync.SyncRuntime
import com.animesh.fitnesstracker.garmin.sync.SyncState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Foreground service (type connectedDevice) that runs one [com.animesh.fitnesstracker.garmin.sync.SyncSession]
 * through the [com.animesh.fitnesstracker.garmin.sync.WatchController] and mirrors [SyncRuntime.state]
 * into a low-importance notification with a Cancel action. It stops itself when the session ends.
 */
class GarminSyncService : LifecycleService() {
    private var job: Job? = null
    private var observer: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_CANCEL -> {
                job?.cancel()
                if (job == null) stopForegroundAndSelf()
                return START_NOT_STICKY
            }
        }
        val controller = SyncRuntime.controller
        val watchName = controller?.currentWatch?.name
        if (controller == null || watchName == null) {
            startInForeground(buildNotification("Nothing to sync", null))
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        val upload = intent?.action == ACTION_UPLOAD_WORKOUT
        startInForeground(buildNotification(text(SyncState.Connecting, watchName), SyncState.Connecting))
        observer = lifecycleScope.launch {
            SyncRuntime.state.collect { notify(buildNotification(text(it, watchName), it)) }
        }
        job = lifecycleScope.launch {
            SyncRuntime.activeJob = coroutineContext[Job]
            try {
                if (upload) controller.runUpload() else controller.runSync()
            } finally {
                if (SyncRuntime.activeJob === coroutineContext[Job]) SyncRuntime.activeJob = null
                stopForegroundAndSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        observer?.cancel()
        super.onDestroy()
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, 0)
        }
    }

    private fun stopForegroundAndSelf() {
        observer?.cancel(); observer = null
        job = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notify(n: Notification) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, n)
    }

    private fun buildNotification(text: String, state: SyncState?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, GarminSyncService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.garmin_sync_channel_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (state is SyncState.Downloading && state.total > 0) {
            builder.setProgress(state.total, state.done, false)
        } else if (state is SyncState.Uploading && state.totalBytes > 0) {
            builder.setProgress(state.totalBytes, state.sentBytes, false)
        } else if (state != null && state.isRunning) {
            builder.setProgress(0, 0, true)
        }
        if (state == null || state.isRunning) builder.addAction(0, "Cancel", cancel)
        return builder.build()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.garmin_sync_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.garmin_sync_channel_description)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "watch_sync"
        const val NOTIFICATION_ID = 42
        const val ACTION_SYNC = "com.animesh.fitnesstracker.garmin.SYNC"
        const val ACTION_CANCEL = "com.animesh.fitnesstracker.garmin.CANCEL"
        /** Pushes the workout waiting in the [com.animesh.fitnesstracker.garmin.sync.WorkoutOutbox]; the bytes never travel in the Intent. */
        const val ACTION_UPLOAD_WORKOUT = "com.animesh.fitnesstracker.garmin.UPLOAD_WORKOUT"

        /** Notification text for a state, e.g. "Connecting to Forerunner 570" or "Downloading file 3 of 7". */
        fun text(state: SyncState, watchName: String): String = when (state) {
            SyncState.Idle -> "Idle"
            SyncState.Connecting -> "Connecting to $watchName"
            SyncState.Handshake -> "Talking to $watchName"
            SyncState.Listing -> "Listing files on $watchName"
            is SyncState.Downloading -> "Downloading file ${state.done + 1} of ${state.total}"
            SyncState.Importing -> "Importing"
            is SyncState.Uploading -> "Sending ${state.label} to $watchName: ${kb(state.sentBytes)} of ${kb(state.totalBytes)} KB"
            is SyncState.Done -> when {
                state.uploadedWorkout != null -> "Sent ${state.uploadedWorkout} to $watchName"
                state.newFiles == 0 -> "Up to date"
                else -> "Synced ${state.newFiles} new file(s)"
            }
            is SyncState.Failed -> "Sync failed: ${state.reason}"
        }

        /** Bytes as kilobytes with one decimal, "1.2". */
        fun kb(bytes: Int): String = String.format(java.util.Locale.US, "%.1f", bytes / 1024.0)
    }
}
