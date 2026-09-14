package com.animesh.workouttracker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.animesh.workouttracker.data.dao.ExerciseDao
import com.animesh.workouttracker.data.dao.GroupDao
import com.animesh.workouttracker.data.dao.RoutineDao
import com.animesh.workouttracker.data.dao.SessionDao
import com.animesh.workouttracker.data.dao.SettingsDao
import com.animesh.workouttracker.data.model.DayLog
import com.animesh.workouttracker.data.model.Exercise
import com.animesh.workouttracker.data.model.GroupExercise
import com.animesh.workouttracker.data.model.Routine
import com.animesh.workouttracker.data.model.RoutineSlot
import com.animesh.workouttracker.data.model.Session
import com.animesh.workouttracker.data.model.SessionExercise
import com.animesh.workouttracker.data.model.SessionSet
import com.animesh.workouttracker.data.model.SetPrescription
import com.animesh.workouttracker.data.model.Settings
import com.animesh.workouttracker.data.model.WorkoutGroup

@Database(
    entities = [
        Exercise::class, WorkoutGroup::class, GroupExercise::class, SetPrescription::class,
        Routine::class, RoutineSlot::class,
        Session::class, SessionExercise::class, SessionSet::class, DayLog::class,
        Settings::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun exerciseDao(): ExerciseDao
    abstract fun groupDao(): GroupDao
    abstract fun routineDao(): RoutineDao
    abstract fun sessionDao(): SessionDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        const val NAME = "workout-tracker.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                // Migrations are added here as the schema version grows. Never fall back to destructive migration.
                .build()
    }
}
