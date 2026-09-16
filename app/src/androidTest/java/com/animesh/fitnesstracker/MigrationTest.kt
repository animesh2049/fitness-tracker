package com.animesh.fitnesstracker

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.animesh.fitnesstracker.data.AppDatabase
import com.animesh.fitnesstracker.data.MIGRATION_1_2
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

    companion object {
        private const val TEST_DB = "migration-test.db"
    }
}
