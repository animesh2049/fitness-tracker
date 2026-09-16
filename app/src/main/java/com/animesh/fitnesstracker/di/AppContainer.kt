package com.animesh.fitnesstracker.di

import android.content.Context
import com.animesh.fitnesstracker.data.AppDatabase
import com.animesh.fitnesstracker.data.Seed
import com.animesh.fitnesstracker.repository.ExerciseRepository
import com.animesh.fitnesstracker.repository.GroupRepository
import com.animesh.fitnesstracker.repository.RoutineRepository
import com.animesh.fitnesstracker.repository.SessionRepository
import com.animesh.fitnesstracker.repository.SettingsRepository
import com.animesh.fitnesstracker.service.TimerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency container. One instance per process, owned by [com.animesh.fitnesstracker.FitnessApplication]. */
class AppContainer(val appContext: Context, val database: AppDatabase = AppDatabase.build(appContext)) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val exercises = ExerciseRepository(database.exerciseDao())
    val groups = GroupRepository(database)
    val routines = RoutineRepository(database)
    val sessions = SessionRepository(database)
    val settings = SettingsRepository(database.settingsDao())
    val timer = TimerController(appContext)

    init {
        appScope.launch { Seed.runIfNeeded(database) }
        appScope.launch {
            settings.observe().collect {
                timer.soundEnabled = it.soundEnabled
                timer.vibrationEnabled = it.vibrationEnabled
            }
        }
    }
}
