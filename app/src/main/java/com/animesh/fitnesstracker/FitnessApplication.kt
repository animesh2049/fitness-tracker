package com.animesh.fitnesstracker

import android.app.Application
import com.animesh.fitnesstracker.di.AppContainer

class FitnessApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
