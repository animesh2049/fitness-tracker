package com.animesh.fitnesstracker.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.animesh.fitnesstracker.data.model.DailyMetric
import com.animesh.fitnesstracker.data.model.HealthMinute
import com.animesh.fitnesstracker.data.model.HrvSummary
import com.animesh.fitnesstracker.data.model.HrvValue
import com.animesh.fitnesstracker.data.model.IntensityMinute
import com.animesh.fitnesstracker.data.model.MetricType
import com.animesh.fitnesstracker.data.model.RespirationSample
import com.animesh.fitnesstracker.data.model.RestingHrDaily
import com.animesh.fitnesstracker.data.model.SleepNight
import com.animesh.fitnesstracker.data.model.SleepStage
import com.animesh.fitnesstracker.data.model.Spo2Sample
import com.animesh.fitnesstracker.data.model.StressSample
import kotlinx.coroutines.flow.Flow

/** Steps, distance and active calories summed over one day. */
data class DayTotals(val epochDay: Long, val steps: Int, val distanceM: Double, val activeKcal: Int)

/** Heart rate range of one day, from worn minutes with a reading. */
data class DayHeartRate(val epochDay: Long, val hrMin: Int, val hrAvg: Double, val hrMax: Int)

/** Body Battery range and average stress of one day, keyed by the local day passed to the query. */
data class DayStress(val epochDay: Long, val bodyBatteryMin: Int?, val bodyBatteryMax: Int?, val stressAvg: Double?)

/** Intensity minutes summed over one local day. */
data class DayIntensity(val epochDay: Long, val moderate: Int, val vigorous: Int)

@Dao
interface HealthDao {
    // Minutes

    @Query("SELECT * FROM health_minutes WHERE epochDay = :epochDay ORDER BY timestamp")
    fun observeMinutes(epochDay: Long): Flow<List<HealthMinute>>

    @Query("SELECT * FROM health_minutes WHERE timestamp >= :fromTs AND timestamp < :toTs ORDER BY timestamp")
    suspend fun minutesBetween(fromTs: Long, toTs: Long): List<HealthMinute>

    @Query("SELECT COALESCE(SUM(steps), 0) FROM health_minutes WHERE epochDay = :epochDay AND timestamp < :beforeTs")
    suspend fun stepsBefore(epochDay: Long, beforeTs: Long): Int

    @Query("SELECT COALESCE(SUM(distanceM), 0) FROM health_minutes WHERE epochDay = :epochDay AND timestamp < :beforeTs")
    suspend fun distanceBefore(epochDay: Long, beforeTs: Long): Double

    @Query("SELECT COALESCE(SUM(activeKcal), 0) FROM health_minutes WHERE epochDay = :epochDay AND timestamp < :beforeTs")
    suspend fun kcalBefore(epochDay: Long, beforeTs: Long): Int

    @Query(
        "SELECT epochDay, COALESCE(SUM(steps), 0) AS steps, COALESCE(SUM(distanceM), 0) AS distanceM, COALESCE(SUM(activeKcal), 0) AS activeKcal " +
            "FROM health_minutes WHERE epochDay = :epochDay GROUP BY epochDay"
    )
    fun observeDayTotals(epochDay: Long): Flow<DayTotals?>

    @Query(
        "SELECT epochDay, COALESCE(SUM(steps), 0) AS steps, COALESCE(SUM(distanceM), 0) AS distanceM, COALESCE(SUM(activeKcal), 0) AS activeKcal " +
            "FROM health_minutes WHERE epochDay BETWEEN :fromDay AND :toDay GROUP BY epochDay ORDER BY epochDay"
    )
    fun observeDayTotalsBetween(fromDay: Long, toDay: Long): Flow<List<DayTotals>>

    @Query(
        "SELECT epochDay, MIN(heartRate) AS hrMin, AVG(heartRate) AS hrAvg, MAX(heartRate) AS hrMax FROM health_minutes " +
            "WHERE epochDay BETWEEN :fromDay AND :toDay AND heartRate IS NOT NULL AND heartRate > 0 GROUP BY epochDay ORDER BY epochDay"
    )
    fun observeDayHeartRateBetween(fromDay: Long, toDay: Long): Flow<List<DayHeartRate>>

    @Query("SELECT MIN(heartRate) FROM health_minutes WHERE timestamp >= :fromTs AND timestamp < :toTs AND heartRate > 0")
    suspend fun lowestHeartRateBetween(fromTs: Long, toTs: Long): Int?

    /** Idempotent: a minute already imported keeps its row. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMinutesIgnore(rows: List<HealthMinute>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMinutesReplace(rows: List<HealthMinute>): List<Long>

    // Stress and Body Battery

    @Query("SELECT * FROM health_stress WHERE timestamp >= :fromTs AND timestamp < :toTs ORDER BY timestamp")
    fun observeStress(fromTs: Long, toTs: Long): Flow<List<StressSample>>

    @Query("SELECT * FROM health_stress WHERE timestamp >= :fromTs AND timestamp < :toTs ORDER BY timestamp")
    suspend fun stressBetween(fromTs: Long, toTs: Long): List<StressSample>

    @Query(
        "SELECT :epochDay AS epochDay, MIN(bodyBattery) AS bodyBatteryMin, MAX(bodyBattery) AS bodyBatteryMax, AVG(stress) AS stressAvg " +
            "FROM health_stress WHERE timestamp >= :fromTs AND timestamp < :toTs"
    )
    fun observeDayStress(epochDay: Long, fromTs: Long, toTs: Long): Flow<DayStress?>

    /**
     * Body Battery range and average stress per local day. [offsetSeconds] is the zone offset that
     * turns a Unix timestamp into a local day number (timestamp plus offset, divided by 86400).
     */
    @Query(
        "SELECT (timestamp + :offsetSeconds) / 86400 AS epochDay, MIN(bodyBattery) AS bodyBatteryMin, MAX(bodyBattery) AS bodyBatteryMax, " +
            "AVG(stress) AS stressAvg FROM health_stress WHERE timestamp >= :fromTs AND timestamp < :toTs " +
            "GROUP BY (timestamp + :offsetSeconds) / 86400 ORDER BY epochDay"
    )
    fun observeDayStressBetween(fromTs: Long, toTs: Long, offsetSeconds: Long): Flow<List<DayStress>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStress(rows: List<StressSample>): List<Long>

    // Point samples

    @Query("SELECT * FROM health_spo2 WHERE timestamp >= :fromTs AND timestamp < :toTs ORDER BY timestamp")
    fun observeSpo2(fromTs: Long, toTs: Long): Flow<List<Spo2Sample>>

    @Query("SELECT AVG(percent) FROM health_spo2 WHERE timestamp >= :fromTs AND timestamp < :toTs")
    suspend fun avgSpo2Between(fromTs: Long, toTs: Long): Double?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpo2(rows: List<Spo2Sample>): List<Long>

    @Query("SELECT * FROM health_respiration WHERE timestamp >= :fromTs AND timestamp < :toTs ORDER BY timestamp")
    fun observeRespiration(fromTs: Long, toTs: Long): Flow<List<RespirationSample>>

    @Query("SELECT AVG(breathsPerMinute) FROM health_respiration WHERE timestamp >= :fromTs AND timestamp < :toTs")
    suspend fun avgRespirationBetween(fromTs: Long, toTs: Long): Double?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRespiration(rows: List<RespirationSample>): List<Long>

    @Query("SELECT * FROM health_hrv_values WHERE timestamp >= :fromTs AND timestamp < :toTs ORDER BY timestamp")
    fun observeHrvValues(fromTs: Long, toTs: Long): Flow<List<HrvValue>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHrvValues(rows: List<HrvValue>): List<Long>

    // Resting heart rate

    @Query("SELECT * FROM health_resting_hr WHERE epochDay = :epochDay")
    fun observeRestingHr(epochDay: Long): Flow<RestingHrDaily?>

    @Query("SELECT * FROM health_resting_hr WHERE epochDay BETWEEN :fromDay AND :toDay ORDER BY epochDay")
    fun observeRestingHrBetween(fromDay: Long, toDay: Long): Flow<List<RestingHrDaily>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRestingHr(rows: List<RestingHrDaily>): List<Long>

    // HRV summary

    @Query("SELECT * FROM health_hrv_summary WHERE epochDay = :epochDay")
    fun observeHrvSummary(epochDay: Long): Flow<HrvSummary?>

    @Query("SELECT * FROM health_hrv_summary WHERE epochDay = :epochDay")
    suspend fun hrvSummary(epochDay: Long): HrvSummary?

    @Query("SELECT * FROM health_hrv_summary WHERE epochDay BETWEEN :fromDay AND :toDay ORDER BY epochDay")
    fun observeHrvSummaryBetween(fromDay: Long, toDay: Long): Flow<List<HrvSummary>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHrvSummaries(rows: List<HrvSummary>): List<Long>

    // Sleep

    @Query("SELECT * FROM health_sleep_stages WHERE nightEpochDay = :epochDay ORDER BY endTimestamp")
    fun observeSleepStages(epochDay: Long): Flow<List<SleepStage>>

    @Query("SELECT * FROM health_sleep_stages WHERE nightEpochDay = :epochDay ORDER BY endTimestamp")
    suspend fun sleepStages(epochDay: Long): List<SleepStage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSleepStages(rows: List<SleepStage>): List<Long>

    @Query("SELECT * FROM health_sleep_nights WHERE epochDay = :epochDay")
    fun observeSleepNight(epochDay: Long): Flow<SleepNight?>

    @Query("SELECT * FROM health_sleep_nights WHERE epochDay = :epochDay")
    suspend fun sleepNight(epochDay: Long): SleepNight?

    @Query("SELECT * FROM health_sleep_nights WHERE epochDay BETWEEN :fromDay AND :toDay ORDER BY epochDay")
    fun observeSleepNightsBetween(fromDay: Long, toDay: Long): Flow<List<SleepNight>>

    @Query("SELECT * FROM health_sleep_nights WHERE epochDay BETWEEN :fromDay AND :toDay ORDER BY epochDay")
    suspend fun sleepNightsBetween(fromDay: Long, toDay: Long): List<SleepNight>

    @Query("SELECT epochDay FROM health_sleep_nights ORDER BY epochDay")
    fun observeSleepNightDays(): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSleepNight(night: SleepNight): Long

    @Update
    suspend fun updateSleepNight(night: SleepNight)

    // Daily metrics

    @Query("SELECT * FROM health_daily_metrics WHERE type = :type AND epochDay BETWEEN :fromDay AND :toDay ORDER BY epochDay")
    fun observeMetrics(type: MetricType, fromDay: Long, toDay: Long): Flow<List<DailyMetric>>

    @Query("SELECT * FROM health_daily_metrics WHERE epochDay = :epochDay")
    fun observeMetricsForDay(epochDay: Long): Flow<List<DailyMetric>>

    /** The most recent value of a metric on or before the day. */
    @Query("SELECT * FROM health_daily_metrics WHERE type = :type AND epochDay <= :epochDay ORDER BY epochDay DESC LIMIT 1")
    fun observeLatestMetric(type: MetricType, epochDay: Long): Flow<DailyMetric?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMetrics(rows: List<DailyMetric>): List<Long>

    // Intensity minutes

    @Query("SELECT * FROM health_intensity WHERE timestamp >= :fromTs AND timestamp < :toTs ORDER BY timestamp")
    fun observeIntensity(fromTs: Long, toTs: Long): Flow<List<IntensityMinute>>

    @Query("SELECT * FROM health_intensity WHERE timestamp >= :fromTs AND timestamp < :toTs ORDER BY timestamp")
    suspend fun intensityBetween(fromTs: Long, toTs: Long): List<IntensityMinute>

    /** Intensity minutes per local day; see [observeDayStressBetween] for [offsetSeconds]. */
    @Query(
        "SELECT (timestamp + :offsetSeconds) / 86400 AS epochDay, COALESCE(SUM(moderate), 0) AS moderate, COALESCE(SUM(vigorous), 0) AS vigorous " +
            "FROM health_intensity WHERE timestamp >= :fromTs AND timestamp < :toTs GROUP BY (timestamp + :offsetSeconds) / 86400 ORDER BY epochDay"
    )
    fun observeDayIntensityBetween(fromTs: Long, toTs: Long, offsetSeconds: Long): Flow<List<DayIntensity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertIntensity(rows: List<IntensityMinute>): List<Long>

    // Re-import support

    @Query("DELETE FROM health_minutes")
    suspend fun deleteAllMinutes()

    @Query("DELETE FROM health_stress")
    suspend fun deleteAllStress()

    @Query("DELETE FROM health_spo2")
    suspend fun deleteAllSpo2()

    @Query("DELETE FROM health_respiration")
    suspend fun deleteAllRespiration()

    @Query("DELETE FROM health_hrv_values")
    suspend fun deleteAllHrvValues()

    @Query("DELETE FROM health_resting_hr")
    suspend fun deleteAllRestingHr()

    @Query("DELETE FROM health_hrv_summary")
    suspend fun deleteAllHrvSummaries()

    @Query("DELETE FROM health_sleep_stages")
    suspend fun deleteAllSleepStages()

    @Query("DELETE FROM health_sleep_nights")
    suspend fun deleteAllSleepNights()

    @Query("DELETE FROM health_daily_metrics")
    suspend fun deleteAllMetrics()

    @Query("DELETE FROM health_intensity")
    suspend fun deleteAllIntensity()

    @Query("SELECT COUNT(*) FROM health_minutes")
    suspend fun countMinutes(): Int

    @Query("SELECT COUNT(*) FROM health_stress")
    suspend fun countStress(): Int
}
