package com.animesh.fitnesstracker

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.animesh.fitnesstracker.data.AppDatabase
import com.animesh.fitnesstracker.data.MIGRATION_1_2
import com.animesh.fitnesstracker.data.MIGRATION_2_3
import com.animesh.fitnesstracker.data.MIGRATION_3_4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun migrate1To2CreatesDietTablesAndDefaultSettings() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                "INSERT INTO settings (id, unit, defaultRestSeconds, defaultExerciseRestSeconds, countdownSeconds, soundEnabled, " +
                    "vibrationEnabled, keepScreenAwake, autoStartRest, deloadAfterFailures, deloadPercent, seeded) " +
                    "VALUES (1, 'LB', 75, 120, 5, 1, 1, 1, 1, 3, 10, 1)"
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)
        db.query("SELECT prepReminderMinute, seeded, breakfastStart, dinnerEnd FROM diet_settings").use { c ->
            assertEquals(1, c.count)
            assertTrue(c.moveToFirst())
            assertEquals(1260, c.getInt(0))
            assertEquals(0, c.getInt(1))
            assertEquals(360, c.getInt(2))
            assertEquals(1320, c.getInt(3))
        }
        db.query("SELECT unit, seeded FROM settings WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("LB", c.getString(0))
            assertEquals(1, c.getInt(1))
        }
        for (table in listOf("meals", "ingredients", "meal_steps", "diet_plans", "diet_plan_cells")) {
            db.query("SELECT COUNT(*) FROM $table").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(table, 0, c.getInt(0))
            }
        }
        db.close()
    }

    @Test
    fun migrate2To3CreatesHealthTablesAndKeepsSettings() {
        helper.createDatabase(TEST_DB, 2).apply {
            execSQL(
                "INSERT INTO settings (id, unit, defaultRestSeconds, defaultExerciseRestSeconds, countdownSeconds, soundEnabled, " +
                    "vibrationEnabled, keepScreenAwake, autoStartRest, deloadAfterFailures, deloadPercent, seeded) " +
                    "VALUES (1, 'KG', 90, 120, 5, 1, 1, 1, 1, 3, 10, 1)"
            )
            execSQL(
                "INSERT INTO sessions (id, groupId, groupName, routineId, epochDay, startedAt, endedAt, status, notes) " +
                    "VALUES (7, NULL, 'Push day', NULL, 20710, 5000, 9000, 'COMPLETED', '')"
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_2_3)
        db.query("SELECT unit, seeded, maxHeartRate, stepGoal, birthYear FROM settings WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("KG", c.getString(0))
            assertEquals(1, c.getInt(1))
            assertTrue(c.isNull(2))
            assertEquals(10000, c.getInt(3))
            assertTrue(c.isNull(4))
        }
        val tables = listOf(
            "garmin_files", "health_minutes", "health_stress", "health_spo2", "health_respiration", "health_hrv_values", "health_resting_hr",
            "health_hrv_summary", "health_sleep_stages", "health_sleep_nights", "health_daily_metrics", "health_intensity",
            "activities", "activity_laps", "activity_points"
        )
        for (table in tables) {
            db.query("SELECT COUNT(*) FROM $table").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(table, 0, c.getInt(0))
            }
        }
        // The session link is a real foreign key that clears when the session goes away.
        db.execSQL(
            "INSERT INTO activities (startTimestamp, fitTimeCreated, endTimestamp, sport, subSport, kind, name, timerSeconds, elapsedSeconds, " +
                "hrZoneSeconds, hrZoneBounds, filePath, linkedSessionId) VALUES (100, 100, 200, 10, 20, 'STRENGTH', 'Strength', 90, 100, '', '', 'a.fit', 7)"
        )
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM sessions WHERE id = 7")
        db.query("SELECT linkedSessionId FROM activities").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
        }
        db.close()
    }

    @Test
    fun migrate3To4AddsHealthColumnsAndTheEventsTable() {
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL("INSERT INTO health_minutes (timestamp, epochDay, steps, distanceM, activeKcal, heartRate, activityKind, intensity, worn) VALUES (1000, 20710, 12, 9.5, 1, 70, 6, 0, 1)")
            execSQL(
                "INSERT INTO health_sleep_nights (epochDay, startTimestamp, endTimestamp, score, deepSeconds, lightSeconds, remSeconds, awakeSeconds, source) " +
                    "VALUES (20710, 100, 26000, 81, 5100, 14280, 5400, 1140, 'event')"
            )
            execSQL(
                "INSERT INTO activities (startTimestamp, fitTimeCreated, endTimestamp, sport, subSport, kind, name, timerSeconds, elapsedSeconds, " +
                    "hrZoneSeconds, hrZoneBounds, filePath) VALUES (100, 100, 200, 10, 20, 'STRENGTH', 'Strength', 90, 100, '', '', 'a.fit')"
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)
        db.query("SELECT steps, ascentM, descentM FROM health_minutes").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(12, c.getInt(0))
            assertEquals(0.0, c.getDouble(1), 0.0)
            assertEquals(0.0, c.getDouble(2), 0.0)
        }
        db.query("SELECT score, deepScore, sleepNeedMin, bodyBatteryStart, avgStressDuringSleep, skinTempDeviation FROM health_sleep_nights").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(81, c.getInt(0))
            for (i in 1..5) assertTrue("column $i is null after the migration", c.isNull(i))
        }
        db.query("SELECT performanceCondition, primaryBenefit FROM activities").use { c ->
            assertTrue(c.moveToFirst())
            assertTrue(c.isNull(0))
            assertTrue(c.isNull(1))
        }
        db.execSQL("INSERT INTO health_body_battery_events (startTimestamp, kindRaw, endTimestamp, epochDay, minutes, delta, kind) VALUES (100, 4, 28000, 20710, 465, 51, 'SLEEP')")
        db.query("SELECT COUNT(*) FROM health_body_battery_events WHERE epochDay = 20710").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate1To4ChainsEveryMigration() {
        helper.createDatabase(TEST_DB, 1).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        db.query("SELECT COUNT(*) FROM health_body_battery_events").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.close()
    }

    @Test
    fun migrate1To3ChainsBothMigrations() {
        helper.createDatabase(TEST_DB, 1).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 3, true, MIGRATION_1_2, MIGRATION_2_3)
        db.query("SELECT COUNT(*) FROM diet_settings").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM health_minutes").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(0, c.getInt(0))
        }
        db.close()
    }

    companion object {
        private const val TEST_DB = "migration-test.db"
    }
}
