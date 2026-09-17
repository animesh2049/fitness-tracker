package com.animesh.fitnesstracker.garmin.fit

/**
 * Typed view of one decoded FIT file. This is the contract between the decoder ([FitDecoder]) and
 * the importer: every list holds the records of one FIT global message, in file order, with scale
 * and offset already applied and timestamps converted to Unix seconds. Fields the watch did not
 * write are null. Unknown messages and fields are counted, never fatal.
 *
 * Message numbers and field semantics follow the FIT profile as documented in
 * claude-projects/fitness-tracker/assets/garmin-research/research-data.md section 3.
 */
data class DecodedFit(
    val fileId: FileIdRec,
    val monitoring: List<MonitoringRec> = emptyList(),
    val monitoringInfo: List<MonitoringInfoRec> = emptyList(),
    val stress: List<StressRec> = emptyList(),
    val restingHr: List<RestingHrRec> = emptyList(),
    val spo2: List<Spo2Rec> = emptyList(),
    val respiration: List<RespirationRec> = emptyList(),
    val events: List<EventRec> = emptyList(),
    val sleepStages: List<SleepStageRec> = emptyList(),
    val sleepStats: List<SleepStatsRec> = emptyList(),
    val restlessMoments: List<RestlessMomentsRec> = emptyList(),
    val naps: List<NapRec> = emptyList(),
    val hrvSummary: List<HrvSummaryRec> = emptyList(),
    val hrvValues: List<HrvValueRec> = emptyList(),
    val trainingLoad: List<TrainingLoadRec> = emptyList(),
    val racePredictions: List<RacePredictionRec> = emptyList(),
    val hillScores: List<HillScoreRec> = emptyList(),
    val enduranceScores: List<EnduranceScoreRec> = emptyList(),
    val trainingReadiness: List<TrainingReadinessRec> = emptyList(),
    val functionalMetrics: List<FunctionalMetricsRec> = emptyList(),
    val recovery: List<RecoveryRec> = emptyList(),
    val maxMet: List<MaxMetRec> = emptyList(),
    val deviceStatus: List<DeviceStatusRec> = emptyList(),
    val sessions: List<SessionRec> = emptyList(),
    val laps: List<LapRec> = emptyList(),
    val records: List<RecordRec> = emptyList(),
    val timeInZone: List<TimeInZoneRec> = emptyList(),
    val physiologicalMetrics: List<PhysiologicalMetricsRec> = emptyList(),
    val userProfile: List<UserProfileRec> = emptyList(),
    val sports: List<SportRec> = emptyList(),
    val activity: List<ActivityRec> = emptyList(),
    val workouts: List<WorkoutRec> = emptyList(),
    val capabilities: List<CapabilitiesRec> = emptyList(),
    /** Every record in the file, including unknown messages, for diagnostics. */
    val raw: List<RawRecord> = emptyList(),
    val unknownMessageCount: Int = 0,
    val unknownFieldCount: Int = 0
)

/** FIT file_id.type values the app cares about. Unknown numbers decode to null type with the raw number kept. */
enum class FitFileType(val num: Int) {
    DEVICE(1), SETTINGS(2), SPORT(3), ACTIVITY(4), WORKOUT(5), COURSE(6), SCHEDULES(7), WEIGHT(9), TOTALS(10),
    GOALS(11), MONITORING_A(15), MONITORING_DAILY(28), MONITORING_B(32), SEGMENT_LIST(35), CHANGELOG(41),
    METRICS(44), SLEEP(49), ECG(61), HRV_STATUS(68), HSA(70), SKIN_TEMP(73);

    /** True for the three monitoring file kinds (all-day samples). */
    val isMonitoring: Boolean get() = this == MONITORING_A || this == MONITORING_DAILY || this == MONITORING_B

    companion object {
        fun fromNum(num: Int?): FitFileType? = entries.firstOrNull { it.num == num }
    }
}

/** Global message 0. */
data class FileIdRec(
    val typeNum: Int?,
    val type: FitFileType? = FitFileType.fromNum(typeNum),
    /** Unix seconds. */
    val timeCreated: Long?,
    val manufacturer: Int? = null,
    val product: Int? = null,
    val serialNumber: Long? = null,
    val number: Int? = null
)

/** Global message 55. Steps, distance and calories are the watch's running totals for [activityType] within the day. */
data class MonitoringRec(
    val timestamp: Long,
    val heartRate: Int?,
    val cumulativeSteps: Long?,
    val cumulativeDistanceM: Double?,
    val cumulativeActiveKcal: Int?,
    val activityType: Int?,
    val intensity: Int?,
    val moderateActivityMinutes: Int?,
    val vigorousActivityMinutes: Int?,
    val ascentM: Double? = null,
    val descentM: Double? = null
)

/** Global message 103. */
data class MonitoringInfoRec(val timestamp: Long, val restingMetabolicRate: Int?)

/** Global message 227. [stress] is -1 or -2 when the watch could not measure; [bodyBattery] 0..100. */
data class StressRec(val timestamp: Long, val stress: Int?, val bodyBattery: Int?, val averageStress: Int? = null)

/** Global message 211. */
data class RestingHrRec(val timestamp: Long?, val restingHeartRate: Int?, val currentDayRestingHeartRate: Int?)

/** Global message 269. [mode] 1 manual, 3 automatic. */
data class Spo2Rec(val timestamp: Long, val spo2Percent: Int, val mode: Int?, val confidence: Int?)

/** Global message 297. */
data class RespirationRec(val timestamp: Long, val breathsPerMinute: Double)

/** Global message 21. Event 74 with type 0 / 1 marks sleep start / end. */
data class EventRec(val timestamp: Long, val event: Int?, val eventType: Int?, val data: Long?)

/** Global message 275. Stage 0 unmeasurable, 1 awake, 2 light, 3 deep, 4 REM; [timestamp] is the stage's upper bound. */
data class SleepStageRec(val timestamp: Long, val stage: Int)

/** Global message 346. Scores 0..100, null when absent. */
data class SleepStatsRec(
    val timestamp: Long?,
    val overallSleepScore: Int?,
    val deepSleepScore: Int? = null,
    val lightSleepScore: Int? = null,
    val remSleepScore: Int? = null,
    val sleepDurationScore: Int? = null,
    val sleepQualityScore: Int? = null,
    val sleepRecoveryScore: Int? = null,
    val sleepRestlessnessScore: Int? = null,
    val awakeningsCount: Int? = null,
    val interruptionsScore: Int? = null,
    val averageStressDuringSleep: Double? = null
)

/** Global message 382. */
data class RestlessMomentsRec(val timestamp: Long?, val count: Int)

/** Global message 412. Unix seconds. */
data class NapRec(val startTimestamp: Long, val endTimestamp: Long)

/** Global message 370. Values in milliseconds. Status 0 none, 1 poor, 2 low, 3 unbalanced, 4 balanced. */
data class HrvSummaryRec(
    val timestamp: Long,
    val weeklyAverageMs: Double?,
    val lastNightAverageMs: Double?,
    val lastNight5MinHighMs: Double?,
    val baselineLowUpperMs: Double?,
    val baselineBalancedLowerMs: Double?,
    val baselineBalancedUpperMs: Double?,
    val status: Int?
)

/** Global message 371. Five-minute overnight values in milliseconds. */
data class HrvValueRec(val timestamp: Long, val valueMs: Double)

/** Global message 378. */
data class TrainingLoadRec(val timestamp: Long, val acute: Int?, val chronic: Int?, val acuteChronicRatio: Double?)

/** Global message 339. Seconds. */
data class RacePredictionRec(val timestamp: Long, val time5k: Int?, val time10k: Int?, val timeHalf: Int?, val timeFull: Int?)

/** Global message 402. */
data class HillScoreRec(val timestamp: Long, val score: Int?, val strength: Int?, val endurance: Int?, val level: Int?)

/** Global message 403. */
data class EnduranceScoreRec(val timestamp: Long, val score: Int?, val level: Int?)

/** Global message 369. */
data class TrainingReadinessRec(val timestamp: Long, val readiness: Int?, val level: Int?, val sleepScore: Int? = null)

/** Global message 356. */
data class FunctionalMetricsRec(
    val timestamp: Long,
    val functionalThresholdPower: Int?,
    val cyclingLactateThresholdHr: Int?,
    val runningLactateThresholdPower: Int?,
    val runningLactateThresholdHr: Int?
)

/** Global message 284. */
data class RecoveryRec(val timestamp: Long, val recoveryMinutes: Int)

/** Global message 229. [vo2Max] in ml/kg/min. */
data class MaxMetRec(val timestamp: Long, val vo2Max: Double?, val category: Int?, val sport: Int?, val subSport: Int?, val fitnessAge: Int? = null)

/** Global message 104. */
data class DeviceStatusRec(val timestamp: Long, val batteryLevel: Int?)

/** Global message 18. Times in seconds, distance in metres, speed in m/s. */
data class SessionRec(
    val timestamp: Long,
    val startTime: Long?,
    val sport: Int?,
    val subSport: Int?,
    val sportProfileName: String? = null,
    val totalElapsedTime: Double?,
    val totalTimerTime: Double?,
    val totalDistance: Double?,
    val totalCalories: Int?,
    val restingCalories: Int? = null,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val minHeartRate: Int? = null,
    val avgCadence: Int? = null,
    val maxCadence: Int? = null,
    val avgSpeed: Double? = null,
    val maxSpeed: Double? = null,
    val totalAscent: Int? = null,
    val totalDescent: Int? = null,
    val totalTrainingEffect: Double? = null,
    val totalAnaerobicTrainingEffect: Double? = null,
    val beginningBodyBattery: Int? = null,
    val endingBodyBattery: Int? = null,
    val trainingLoadPeak: Double? = null,
    val avgStress: Int? = null,
    val totalCycles: Long? = null,
    val avgStepLength: Double? = null,
    val avgTemperature: Int? = null,
    val numLaps: Int? = null
)

/** Global message 19. */
data class LapRec(
    val timestamp: Long,
    val startTime: Long?,
    val totalElapsedTime: Double?,
    val totalTimerTime: Double?,
    val totalDistance: Double?,
    val avgHeartRate: Int?,
    val maxHeartRate: Int?,
    val avgSpeed: Double?,
    val avgCadence: Int?,
    val totalAscent: Int?,
    val totalCalories: Int?,
    val lapTrigger: Int?
)

/** Global message 20. Position in degrees, altitude in metres, speed in m/s. */
data class RecordRec(
    val timestamp: Long,
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Double?,
    val distance: Double?,
    val speed: Double?,
    val heartRate: Int?,
    val cadence: Int?,
    val power: Int?,
    val temperature: Int?,
    val bodyBattery: Int? = null
)

/** Global message 216. [referenceMesg] 18 means the session, 19 a lap. Zone times in seconds. */
data class TimeInZoneRec(
    val timestamp: Long?,
    val referenceMesg: Int?,
    val referenceIndex: Int?,
    val timeInHrZone: List<Double>,
    val hrZoneHighBoundary: List<Int>
)

/** Global message 140. */
data class PhysiologicalMetricsRec(
    val timestamp: Long?,
    val aerobicEffect: Double?,
    val anaerobicEffect: Double?,
    val metMax: Double?,
    val recoveryTimeMinutes: Int?,
    val lactateThresholdHeartRate: Int?,
    val averageHeartRate: Int?
)

/** Global message 3. */
data class UserProfileRec(val restingHeartRate: Int?, val weightKg: Double?, val age: Int?, val gender: Int?, val defaultMaxHeartRate: Int?)

/** Global message 12. */
data class SportRec(val sport: Int?, val subSport: Int?, val name: String?)

/** Global message 34. */
data class ActivityRec(val timestamp: Long?, val localTimestamp: Long?, val numSessions: Int?, val totalTimerTime: Double?, val name: String? = null)

/** Global message 26. */
data class WorkoutRec(val name: String?, val sport: Int?)

/** Global message 1 (sent over the GFDI link during the handshake, also present in device.fit). */
data class CapabilitiesRec(val connectivitySupported: Long?, val sportsSupported: Long? = null)

/** One record as decoded, for diagnostics and tests. Values are already scaled; arrays are lists. */
data class RawRecord(val globalMessageNumber: Int, val fields: Map<Int, Any?>, val developerFields: Map<String, Any?> = emptyMap())

class FitDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Garmin's FIT epoch, 1989-12-31T00:00:00Z, as Unix seconds. */
const val GARMIN_EPOCH_UNIX_SECONDS = 631065600L
