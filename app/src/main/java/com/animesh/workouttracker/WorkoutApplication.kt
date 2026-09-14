package com.animesh.workouttracker

import android.app.Application
import com.animesh.workouttracker.di.AppContainer

class WorkoutApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
