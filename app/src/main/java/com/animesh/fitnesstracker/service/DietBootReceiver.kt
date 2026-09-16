package com.animesh.fitnesstracker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.animesh.fitnesstracker.FitnessApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Alarms do not survive a reboot or an app update, so both events re-arm the diet reminders. */
class DietBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val container = (context.applicationContext as? FitnessApplication)?.container ?: return
        val result = goAsync()
        container.appScope.launch(Dispatchers.IO) {
            try {
                container.dietReminders.reschedule()
            } catch (e: Exception) {
                Log.e("DietReminders", "Failed to reschedule after ${intent.action}", e)
            } finally {
                result.finish()
            }
        }
    }
}
