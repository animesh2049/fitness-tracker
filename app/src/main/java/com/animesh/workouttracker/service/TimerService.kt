package com.animesh.workouttracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.animesh.workouttracker.MainActivity
import com.animesh.workouttracker.R
import com.animesh.workouttracker.WorkoutApplication
import com.animesh.workouttracker.domain.timer.Countdown
import com.animesh.workouttracker.domain.timer.TimerKind
import com.animesh.workouttracker.util.Dates
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the countdown running while the screen is off. All state lives in
 * [TimerController]; this service only ticks it, renders the notification, and plays the end cue.
 */
class TimerService : LifecycleService() {
    private val controller: TimerController get() = (application as WorkoutApplication).container.timer
    private var ticker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_ADD -> controller.add(30)
            ACTION_SKIP -> controller.skip()
        }
        val c = controller.countdown.value
        if (c == null) {
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }
        startInForeground(buildNotification(c, controller.remainingSeconds()))
        acquireWakeLock()
        if (ticker?.isActive != true) ticker = lifecycleScope.launch { runTicker() }
        return START_NOT_STICKY
    }

    private suspend fun runTicker() {
        var warned = false
        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            val finished = controller.tick(now)
            if (finished != null) {
                cue(strong = true)
                // Give a chained countdown (get ready -> work -> rest) a moment to start before stopping.
                delay(1200)
                if (controller.countdown.value == null) {
                    stopForegroundAndSelf()
                    return
                }
                warned = false
                continue
            }
            val c = controller.countdown.value
            if (c == null) {
                stopForegroundAndSelf()
                return
            }
            val remaining = c.remainingSeconds(now)
            if (remaining == 10 && c.kind == TimerKind.REST && !warned) {
                cue(strong = false); warned = true
            }
            if (remaining > 10) warned = false
            notify(buildNotification(c, remaining))
            // Sleep until the next whole-second boundary of this countdown.
            val toNext = ((c.endAtMillis - now) % 1000).let { if (it <= 0) 1000 else it }
            delay(toNext.coerceIn(100, 1000))
        }
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, 0)
        }
    }

    private fun stopForegroundAndSelf() {
        ticker?.cancel(); ticker = null
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        ticker?.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "workouttracker:timer").apply { acquire(30 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun notify(n: Notification) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, n)
    }

    private fun buildNotification(c: Countdown, remaining: Int): Notification {
        val title = when (c.kind) {
            TimerKind.REST -> "Rest · ${Dates.mmss(remaining)}"
            TimerKind.GET_READY -> "Get ready · $remaining"
            TimerKind.WORK -> "Hold · ${Dates.mmss(remaining)}"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val add = PendingIntent.getService(this, 1, Intent(this, TimerService::class.java).setAction(ACTION_ADD), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val skip = PendingIntent.getService(this, 2, Intent(this, TimerService::class.java).setAction(ACTION_SKIP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(c.label)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(c.totalSeconds, (c.totalSeconds - remaining).coerceIn(0, c.totalSeconds), false)
        if (c.kind == TimerKind.REST) {
            builder.addAction(0, "+30 s", add)
        }
        builder.addAction(0, "Skip", skip)
        return builder.build()
    }

    private fun cue(strong: Boolean) {
        if (controller.vibrationEnabled) {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            val pattern = if (strong) longArrayOf(0, 250, 120, 250, 120, 400) else longArrayOf(0, 120)
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        }
        if (controller.soundEnabled) {
            runCatching {
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                val ringtone = RingtoneManager.getRingtone(this, uri)
                ringtone.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (strong) ringtone.play()
            }
        }
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.timer_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.timer_channel_description)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "timers"
        const val NOTIFICATION_ID = 41
        const val ACTION_START = "com.animesh.workouttracker.timer.START"
        const val ACTION_ADD = "com.animesh.workouttracker.timer.ADD"
        const val ACTION_SKIP = "com.animesh.workouttracker.timer.SKIP"
    }
}
