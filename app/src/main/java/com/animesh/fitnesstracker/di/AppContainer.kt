package com.animesh.fitnesstracker.di

import android.content.Context
import android.util.Log
import com.animesh.fitnesstracker.data.AppDatabase
import com.animesh.fitnesstracker.data.Seed
import com.animesh.fitnesstracker.repository.DietPlanRepository
import com.animesh.fitnesstracker.repository.DietSettingsRepository
import com.animesh.fitnesstracker.repository.ExerciseRepository
import com.animesh.fitnesstracker.repository.MealRepository
import com.animesh.fitnesstracker.repository.GroupRepository
import com.animesh.fitnesstracker.repository.RoutineRepository
import com.animesh.fitnesstracker.repository.SessionRepository
import com.animesh.fitnesstracker.repository.SettingsRepository
import com.animesh.fitnesstracker.service.DietReminderScheduler
import com.animesh.fitnesstracker.service.TimerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** Manual dependency container. One instance per process, owned by [com.animesh.fitnesstracker.FitnessApplication]. */
class AppContainer(val appContext: Context, val database: AppDatabase = AppDatabase.build(appContext)) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val exercises = ExerciseRepository(database.exerciseDao())
    val groups = GroupRepository(database)
    val routines = RoutineRepository(database)
    val sessions = SessionRepository(database)
    val settings = SettingsRepository(database.settingsDao())
    val meals = MealRepository(database)
    val dietPlans = DietPlanRepository(database)
    val dietSettings = DietSettingsRepository(database.dietSettingsDao())
    val timer = TimerController(appContext)
    val dietReminders = DietReminderScheduler(appContext, dietSettings, dietPlans)

    init {
        appScope.launch { Seed.runIfNeeded(database) }
        appScope.launch {
            settings.observe().collect {
                timer.soundEnabled = it.soundEnabled
                timer.vibrationEnabled = it.vibrationEnabled
            }
        }
        appScope.launch { rescheduleDietRemindersOnChange() }
    }

    /** Re-arms the diet alarms whenever the settings or the active plan change; cheap because the alarms are inexact. */
    @OptIn(FlowPreview::class)
    private suspend fun rescheduleDietRemindersOnChange() {
        combine(dietSettings.observe(), dietPlans.observeActive()) { _, _ -> Unit }
            .debounce(500)
            .collect {
                try {
                    dietReminders.reschedule()
                } catch (e: Exception) {
                    Log.e("AppContainer", "Diet reminder reschedule failed", e)
                }
            }
    }
}
