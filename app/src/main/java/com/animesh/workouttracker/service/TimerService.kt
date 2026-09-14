package com.animesh.workouttracker.service

import android.content.Intent
import android.os.IBinder
import androidx.lifecycle.LifecycleService

/** Foreground countdown service. Filled in during Milestone 4. */
class TimerService : LifecycleService() {
    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
}
