package com.animesh.fitnesstracker.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.animesh.fitnesstracker.data.model.MealSlot
import com.animesh.fitnesstracker.domain.diet.PrepPlanner
import com.animesh.fitnesstracker.repository.DietPlanRepository
import com.animesh.fitnesstracker.repository.DietSettingsRepository
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Keeps two inexact alarms in [AlarmManager]: the next prep reminder (FR42) and the next meal
 * window opening (FR43). Each fires [DietReminderReceiver], which posts the notification and
 * calls [reschedule] for the following one. Windowed alarms need no exact alarm permission.
 */
class DietReminderScheduler(
    private val context: Context,
    private val dietSettings: DietSettingsRepository,
    private val dietPlans: DietPlanRepository
) {
    private val alarms: AlarmManager get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Recomputes both triggers from the current settings and plan, replacing whatever was scheduled. */
    suspend fun reschedule() {
        val settings = dietSettings.get()
        val plan = dietPlans.getActive()
        val now = LocalDateTime.now()

        // No prep alarm at all while no meal in the active plan needs day-before prep.
        val anyPrep = plan?.cells?.any { it.meal?.prepDayBefore == true } == true
        val prepAt = PrepPlanner.nextPrepTrigger(settings, now)?.takeIf { anyPrep }
        set(prepIntent(), prepAt)

        val window = PrepPlanner.nextWindowTrigger(settings, now)
        set(windowIntent(window?.first ?: MealSlot.BREAKFAST), window?.second)
        Log.d(TAG, "rescheduled prep=$prepAt window=$window")
    }

    fun cancelAll() {
        alarms.cancel(prepIntent())
        alarms.cancel(windowIntent(MealSlot.BREAKFAST))
    }

    private fun set(pending: PendingIntent, at: LocalDateTime?) {
        alarms.cancel(pending)
        if (at == null) return
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarms.setWindow(AlarmManager.RTC_WAKEUP, millis, WINDOW_MILLIS, pending)
    }

    private fun prepIntent(): PendingIntent = PendingIntent.getBroadcast(
        context, RC_PREP,
        Intent(context, DietReminderReceiver::class.java).setAction(DietReminderReceiver.ACTION_PREP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** One request code for all slots: the extra is replaced on each schedule and the same intent cancels it. */
    private fun windowIntent(slot: MealSlot): PendingIntent = PendingIntent.getBroadcast(
        context, RC_WINDOW,
        Intent(context, DietReminderReceiver::class.java)
            .setAction(DietReminderReceiver.ACTION_WINDOW)
            .putExtra(DietReminderReceiver.EXTRA_SLOT, slot.name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        private const val TAG = "DietReminders"
        const val RC_PREP = 2001
        const val RC_WINDOW = 2002
        const val WINDOW_MILLIS = 10 * 60 * 1000L
    }
}
