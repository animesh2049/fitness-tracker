package com.animesh.workouttracker.di

import android.content.Context
import com.animesh.workouttracker.data.AppDatabase
import com.animesh.workouttracker.data.Seed
import com.animesh.workouttracker.repository.ExerciseRepository
import com.animesh.workouttracker.repository.GroupRepository
import com.animesh.workouttracker.repository.RoutineRepository
import com.animesh.workouttracker.repository.SessionRepository
import com.animesh.workouttracker.repository.SettingsRepository
import com.animesh.workouttracker.service.TimerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency container. One instance per process, owned by [com.animesh.workouttracker.WorkoutApplication]. */
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
