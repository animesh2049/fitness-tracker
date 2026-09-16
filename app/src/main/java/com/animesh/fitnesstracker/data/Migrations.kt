package com.animesh.fitnesstracker.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 2 adds the diet planner: meals with ingredients and steps, week plans with cells,
 * and diet settings. The statements are copied from the exported schema
 * (app/schemas/.../2.json) so the migrated database validates against it exactly.
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `meals` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`slots` TEXT NOT NULL, `servings` INTEGER NOT NULL, `cookMinutes` INTEGER, `kcal` REAL, `proteinG` REAL, " +
                "`carbsG` REAL, `fatG` REAL, `prepDayBefore` INTEGER NOT NULL, `prepInstruction` TEXT NOT NULL, " +
                "`notes` TEXT NOT NULL, `archived` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_meals_name` ON `meals` (`name`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ingredients` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `mealId` INTEGER NOT NULL, " +
                "`position` INTEGER NOT NULL, `name` TEXT NOT NULL, `amount` REAL NOT NULL, `unit` TEXT NOT NULL, " +
                "FOREIGN KEY(`mealId`) REFERENCES `meals`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_ingredients_mealId` ON `ingredients` (`mealId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `meal_steps` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `mealId` INTEGER NOT NULL, " +
                "`position` INTEGER NOT NULL, `text` TEXT NOT NULL, " +
                "FOREIGN KEY(`mealId`) REFERENCES `meals`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_meal_steps_mealId` ON `meal_steps` (`mealId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `diet_plans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                "`isActive` INTEGER NOT NULL, `isTemplate` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `diet_plan_cells` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `planId` INTEGER NOT NULL, " +
                "`dayOfWeek` INTEGER NOT NULL, `slot` TEXT NOT NULL, `mealId` INTEGER, `servings` REAL NOT NULL, " +
                "FOREIGN KEY(`planId`) REFERENCES `diet_plans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                "FOREIGN KEY(`mealId`) REFERENCES `meals`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_diet_plan_cells_planId` ON `diet_plan_cells` (`planId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_diet_plan_cells_mealId` ON `diet_plan_cells` (`mealId`)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_diet_plan_cells_planId_dayOfWeek_slot` ON `diet_plan_cells` " +
                "(`planId`, `dayOfWeek`, `slot`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `diet_settings` (`id` INTEGER NOT NULL, `breakfastStart` INTEGER NOT NULL, " +
                "`breakfastEnd` INTEGER NOT NULL, `lunchStart` INTEGER NOT NULL, `lunchEnd` INTEGER NOT NULL, " +
                "`dinnerStart` INTEGER NOT NULL, `dinnerEnd` INTEGER NOT NULL, `prepReminderEnabled` INTEGER NOT NULL, " +
                "`prepReminderMinute` INTEGER NOT NULL, `mealReminderEnabled` INTEGER NOT NULL, `prepDoneEpochDay` INTEGER, " +
                "`seeded` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))"
        )
        // Default diet settings (the DietSettings data class defaults), not yet seeded so the diet seed runs on next launch.
        db.execSQL(
            "INSERT INTO `diet_settings` (`id`, `breakfastStart`, `breakfastEnd`, `lunchStart`, `lunchEnd`, `dinnerStart`, `dinnerEnd`, " +
                "`prepReminderEnabled`, `prepReminderMinute`, `mealReminderEnabled`, `prepDoneEpochDay`, `seeded`) " +
                "VALUES (1, 360, 630, 690, 930, 1110, 1320, 1, 1260, 1, NULL, 0)"
        )
    }
}
