package com.animesh.fitnesstracker.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.animesh.fitnesstracker.FitnessApplication
import com.animesh.fitnesstracker.MainActivity
import com.animesh.fitnesstracker.R
import com.animesh.fitnesstracker.data.model.MealSlot
import com.animesh.fitnesstracker.di.AppContainer
import com.animesh.fitnesstracker.domain.diet.DayMenu
import com.animesh.fitnesstracker.domain.diet.PrepPlanner
import com.animesh.fitnesstracker.ui.diet.DietRoutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Fired by the alarms [DietReminderScheduler] sets. Posts the prep reminder (FR42) or the meal
 * window reminder (FR43), then schedules the next alarm. Also handles the "Done" action.
 */
class DietReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val container = (context.applicationContext as? FitnessApplication)?.container ?: return
        val app = context.applicationContext
        val result = goAsync()
        container.appScope.launch(Dispatchers.IO) {
            try {
                when (action) {
                    ACTION_PREP -> { postPrep(app, container); container.dietReminders.reschedule() }
                    ACTION_WINDOW -> { postWindow(app, container, intent.getStringExtra(EXTRA_SLOT)); container.dietReminders.reschedule() }
                    ACTION_PREP_DONE -> markPrepDone(app, container)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle $action", e)
            } finally {
                result.finish()
            }
        }
    }

    private suspend fun postPrep(context: Context, container: AppContainer) {
        val plan = container.dietPlans.getActive()
        val items = PrepPlanner.forDate(plan, LocalDate.now())
        val (title, body) = PrepPlanner.notificationText(items) ?: return
        val done = PendingIntent.getBroadcast(
            context, RC_PREP_DONE,
            Intent(context, DietReminderReceiver::class.java).setAction(ACTION_PREP_DONE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val recipe = openApp(context, RC_OPEN_RECIPE, DietRoutes.meal(items.first().meal.id))
        val notification = builder(context, title, body, openApp(context, RC_OPEN_DIET_PREP, DietRoutes.HUB))
            .addAction(0, "Done", done)
            .addAction(0, "Open recipe", recipe)
            .build()
        post(context, NOTIFICATION_ID_PREP, notification)
    }

    private suspend fun postWindow(context: Context, container: AppContainer, slotName: String?) {
        val slot = MealSlot.entries.firstOrNull { it.name == slotName } ?: return
        val plan = container.dietPlans.getActive()
        val entry = DayMenu.entries(plan, DayMenu.dayOfWeek(LocalDate.now())).first { it.slot == slot }
        val title = DietReminderText.windowTitle(entry) ?: return
        val body = DietReminderText.windowBody(entry) ?: return
        post(context, NOTIFICATION_ID_WINDOW, builder(context, title, body, openApp(context, RC_OPEN_DIET_WINDOW, DietRoutes.HUB)).build())
    }

    private suspend fun markPrepDone(context: Context, container: AppContainer) {
        val today = LocalDate.now().toEpochDay()
        container.dietSettings.update { it.copy(prepDoneEpochDay = today) }
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_PREP)
    }

    private fun builder(context: Context, title: String, body: String, open: PendingIntent): NotificationCompat.Builder {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
    }

    private fun openApp(context: Context, requestCode: Int, route: String): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_NAVIGATE_TO, route),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    @SuppressLint("MissingPermission")
    private fun post(context: Context, id: Int, notification: android.app.Notification) {
        if (!canPost(context)) {
            Log.i(TAG, "Notifications not permitted; skipping $id")
            return
        }
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = CHANNEL_DESCRIPTION
            }
        )
    }

    companion object {
        private const val TAG = "DietReminders"
        const val CHANNEL_ID = "diet_reminders"
        const val CHANNEL_NAME = "Diet reminders"
        const val CHANNEL_DESCRIPTION = "Day-before prep reminders and meal window reminders"
        const val NOTIFICATION_ID_PREP = 2001
        const val NOTIFICATION_ID_WINDOW = 2002
        const val ACTION_PREP = "com.animesh.fitnesstracker.diet.PREP"
        const val ACTION_WINDOW = "com.animesh.fitnesstracker.diet.WINDOW"
        const val ACTION_PREP_DONE = "com.animesh.fitnesstracker.diet.PREP_DONE"
        const val EXTRA_SLOT = "slot"
        private const val RC_PREP_DONE = 2010
        private const val RC_OPEN_DIET_PREP = 2011
        private const val RC_OPEN_RECIPE = 2012
        private const val RC_OPEN_DIET_WINDOW = 2013
    }
}
