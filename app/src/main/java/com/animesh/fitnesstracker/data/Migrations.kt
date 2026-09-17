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

/**
 * Version 3 adds the Garmin health data: raw file registry, per-minute health samples, stress,
 * point samples, sleep, HRV, daily metrics, intensity minutes and recorded activities, plus the
 * health settings columns. Statements are copied from app/schemas/.../3.json.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `garmin_files` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `watchIndex` INTEGER NOT NULL, " +
                "`fitType` INTEGER NOT NULL, `watchTimestamp` INTEGER, `path` TEXT NOT NULL, `sizeBytes` INTEGER NOT NULL, " +
                "`importedAt` INTEGER, `importError` TEXT, `minuteSamples` INTEGER NOT NULL, `activities` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_garmin_files_path` ON `garmin_files` (`path`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_garmin_files_watchTimestamp` ON `garmin_files` (`watchTimestamp`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `health_minutes` (`timestamp` INTEGER NOT NULL, `epochDay` INTEGER NOT NULL, `steps` INTEGER NOT NULL, " +
                "`distanceM` REAL NOT NULL, `activeKcal` INTEGER NOT NULL, `heartRate` INTEGER, `activityKind` INTEGER NOT NULL, " +
                "`intensity` INTEGER NOT NULL, `worn` INTEGER NOT NULL, PRIMARY KEY(`timestamp`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_health_minutes_epochDay` ON `health_minutes` (`epochDay`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `health_stress` (`timestamp` INTEGER NOT NULL, `stress` INTEGER, `bodyBattery` INTEGER, PRIMARY KEY(`timestamp`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `health_spo2` (`timestamp` INTEGER NOT NULL, `percent` INTEGER NOT NULL, `mode` INTEGER, PRIMARY KEY(`timestamp`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `health_respiration` (`timestamp` INTEGER NOT NULL, `breathsPerMinute` REAL NOT NULL, PRIMARY KEY(`timestamp`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `health_hrv_values` (`timestamp` INTEGER NOT NULL, `valueMs` REAL NOT NULL, PRIMARY KEY(`timestamp`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `health_resting_hr` (`epochDay` INTEGER NOT NULL, `bpm` INTEGER NOT NULL, PRIMARY KEY(`epochDay`))")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `health_hrv_summary` (`epochDay` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `weeklyAvg` REAL, " +
                "`lastNightAvg` REAL, `fiveMinHigh` REAL, `baselineLowUpper` REAL, `baselineBalancedLower` REAL, `baselineBalancedUpper` REAL, " +
                "`status` INTEGER, PRIMARY KEY(`epochDay`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `health_sleep_stages` (`endTimestamp` INTEGER NOT NULL, `startTimestamp` INTEGER NOT NULL, " +
                "`stage` INTEGER NOT NULL, `nightEpochDay` INTEGER NOT NULL, PRIMARY KEY(`endTimestamp`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_health_sleep_stages_nightEpochDay` ON `health_sleep_stages` (`nightEpochDay`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `health_sleep_nights` (`epochDay` INTEGER NOT NULL, `startTimestamp` INTEGER NOT NULL, " +
                "`endTimestamp` INTEGER NOT NULL, `score` INTEGER, `deepSeconds` INTEGER NOT NULL, `lightSeconds` INTEGER NOT NULL, " +
                "`remSeconds` INTEGER NOT NULL, `awakeSeconds` INTEGER NOT NULL, `restlessMoments` INTEGER, `avgHrvMs` REAL, `hrvStatus` INTEGER, " +
                "`avgRespiration` REAL, `avgSpo2` REAL, `lowestHr` INTEGER, `source` TEXT NOT NULL, PRIMARY KEY(`epochDay`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_health_sleep_nights_startTimestamp` ON `health_sleep_nights` (`startTimestamp`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `health_daily_metrics` (`epochDay` INTEGER NOT NULL, `type` TEXT NOT NULL, `value` REAL NOT NULL, " +
                "`extra` INTEGER, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`epochDay`, `type`))"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_health_daily_metrics_type_epochDay` ON `health_daily_metrics` (`type`, `epochDay`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `health_intensity` (`timestamp` INTEGER NOT NULL, `moderate` INTEGER NOT NULL, `vigorous` INTEGER NOT NULL, " +
                "PRIMARY KEY(`timestamp`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `activities` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startTimestamp` INTEGER NOT NULL, " +
                "`fitTimeCreated` INTEGER NOT NULL, `endTimestamp` INTEGER NOT NULL, `sport` INTEGER NOT NULL, `subSport` INTEGER NOT NULL, " +
                "`kind` TEXT NOT NULL, `name` TEXT NOT NULL, `timerSeconds` INTEGER NOT NULL, `elapsedSeconds` INTEGER NOT NULL, `distanceM` REAL, " +
                "`calories` INTEGER, `avgHr` INTEGER, `maxHr` INTEGER, `minHr` INTEGER, `avgCadence` INTEGER, `avgSpeedMps` REAL, `maxSpeedMps` REAL, " +
                "`totalAscent` INTEGER, `totalDescent` INTEGER, `aerobicEffect` REAL, `anaerobicEffect` REAL, `recoveryMinutes` INTEGER, " +
                "`bodyBatteryStart` INTEGER, `bodyBatteryEnd` INTEGER, `trainingLoad` REAL, `vo2max` REAL, `hrZoneSeconds` TEXT NOT NULL, " +
                "`hrZoneBounds` TEXT NOT NULL, `filePath` TEXT NOT NULL, `linkedSessionId` INTEGER, " +
                "FOREIGN KEY(`linkedSessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_activities_startTimestamp_fitTimeCreated` ON `activities` (`startTimestamp`, `fitTimeCreated`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_activities_linkedSessionId` ON `activities` (`linkedSessionId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_activities_kind` ON `activities` (`kind`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `activity_laps` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `activityId` INTEGER NOT NULL, " +
                "`index` INTEGER NOT NULL, `startTimestamp` INTEGER NOT NULL, `timerSeconds` INTEGER NOT NULL, `distanceM` REAL, `avgHr` INTEGER, " +
                "`maxHr` INTEGER, `avgSpeedMps` REAL, `avgCadence` INTEGER, `calories` INTEGER, " +
                "FOREIGN KEY(`activityId`) REFERENCES `activities`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_activity_laps_activityId` ON `activity_laps` (`activityId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `activity_points` (`activityId` INTEGER NOT NULL, `timestamp` INTEGER NOT NULL, `lat` REAL, `lon` REAL, " +
                "`altitude` REAL, `distanceM` REAL, `speedMps` REAL, `heartRate` INTEGER, `cadence` INTEGER, `power` INTEGER, `temperature` INTEGER, " +
                "PRIMARY KEY(`activityId`, `timestamp`), " +
                "FOREIGN KEY(`activityId`) REFERENCES `activities`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        // Health settings. Existing rows get the defaults of the Settings data class.
        db.execSQL("ALTER TABLE `settings` ADD COLUMN `maxHeartRate` INTEGER")
        db.execSQL("ALTER TABLE `settings` ADD COLUMN `stepGoal` INTEGER NOT NULL DEFAULT 10000")
        db.execSQL("ALTER TABLE `settings` ADD COLUMN `birthYear` INTEGER")
    }
}
