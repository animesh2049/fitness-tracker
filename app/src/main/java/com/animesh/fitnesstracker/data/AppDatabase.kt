package com.animesh.fitnesstracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.animesh.fitnesstracker.data.dao.ActivityDao
import com.animesh.fitnesstracker.data.dao.DietPlanDao
import com.animesh.fitnesstracker.data.dao.DietSettingsDao
import com.animesh.fitnesstracker.data.dao.ExerciseDao
import com.animesh.fitnesstracker.data.dao.GroupDao
import com.animesh.fitnesstracker.data.dao.HealthDao
import com.animesh.fitnesstracker.data.dao.MealDao
import com.animesh.fitnesstracker.data.dao.RoutineDao
import com.animesh.fitnesstracker.data.dao.SessionDao
import com.animesh.fitnesstracker.data.dao.SettingsDao
import com.animesh.fitnesstracker.data.dao.SyncedFileDao
import com.animesh.fitnesstracker.data.model.Activity
import com.animesh.fitnesstracker.data.model.ActivityLap
import com.animesh.fitnesstracker.data.model.ActivityPoint
import com.animesh.fitnesstracker.data.model.DailyMetric
import com.animesh.fitnesstracker.data.model.DayLog
import com.animesh.fitnesstracker.data.model.HealthMinute
import com.animesh.fitnesstracker.data.model.HrvSummary
import com.animesh.fitnesstracker.data.model.HrvValue
import com.animesh.fitnesstracker.data.model.IntensityMinute
import com.animesh.fitnesstracker.data.model.RespirationSample
import com.animesh.fitnesstracker.data.model.RestingHrDaily
import com.animesh.fitnesstracker.data.model.SleepNight
import com.animesh.fitnesstracker.data.model.SleepStage
import com.animesh.fitnesstracker.data.model.Spo2Sample
import com.animesh.fitnesstracker.data.model.StressSample
import com.animesh.fitnesstracker.data.model.SyncedFile
import com.animesh.fitnesstracker.data.model.DietPlan
import com.animesh.fitnesstracker.data.model.DietPlanCell
import com.animesh.fitnesstracker.data.model.DietSettings
import com.animesh.fitnesstracker.data.model.Exercise
import com.animesh.fitnesstracker.data.model.GroupExercise
import com.animesh.fitnesstracker.data.model.Ingredient
import com.animesh.fitnesstracker.data.model.Meal
import com.animesh.fitnesstracker.data.model.MealStep
import com.animesh.fitnesstracker.data.model.Routine
import com.animesh.fitnesstracker.data.model.RoutineSlot
import com.animesh.fitnesstracker.data.model.Session
import com.animesh.fitnesstracker.data.model.SessionExercise
import com.animesh.fitnesstracker.data.model.SessionSet
import com.animesh.fitnesstracker.data.model.SetPrescription
import com.animesh.fitnesstracker.data.model.Settings
import com.animesh.fitnesstracker.data.model.WorkoutGroup

@Database(
    entities = [
        Exercise::class, WorkoutGroup::class, GroupExercise::class, SetPrescription::class,
        Routine::class, RoutineSlot::class,
        Session::class, SessionExercise::class, SessionSet::class, DayLog::class,
        Settings::class,
        Meal::class, Ingredient::class, MealStep::class, DietPlan::class, DietPlanCell::class, DietSettings::class,
        SyncedFile::class, HealthMinute::class, StressSample::class, Spo2Sample::class, RespirationSample::class,
        HrvValue::class, RestingHrDaily::class, HrvSummary::class, SleepStage::class, SleepNight::class,
        DailyMetric::class, IntensityMinute::class, Activity::class, ActivityLap::class, ActivityPoint::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun exerciseDao(): ExerciseDao
    abstract fun groupDao(): GroupDao
    abstract fun routineDao(): RoutineDao
    abstract fun sessionDao(): SessionDao
    abstract fun settingsDao(): SettingsDao
    abstract fun mealDao(): MealDao
    abstract fun dietPlanDao(): DietPlanDao
    abstract fun dietSettingsDao(): DietSettingsDao
    abstract fun healthDao(): HealthDao
    abstract fun activityDao(): ActivityDao
    abstract fun syncedFileDao(): SyncedFileDao

    companion object {
        const val NAME = "workout-tracker.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                // Migrations are added here as the schema version grows. Never fall back to destructive migration.
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
