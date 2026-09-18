package com.animesh.fitnesstracker.garmin.fit

/**
 * Turns the [RawRecord]s of a [ParsedFit] into the typed lists of [DecodedFit]. Field numbers
 * follow [Profile]; values arrive already scaled and with timestamps in Unix seconds. Records that
 * lack the fields a typed class requires (usually a timestamp) stay in [DecodedFit.raw] only.
 */
internal object FitMapper {
    fun map(parsed: ParsedFit): DecodedFit {
        val builder = TypedRecords()
        parsed.records.forEach(builder::add)
        return builder.build(parsed)
    }
}

/** Read helpers over one record's field map. Numbers are coerced to the requested Kotlin type. */
private class Fields(private val rec: RawRecord) {
    fun long(num: Int): Long? = when (val v = rec.fields[num]) {
        is Long -> v
        is Double -> Math.round(v)
        else -> null
    }

    fun int(num: Int): Int? = long(num)?.toInt()

    fun double(num: Int): Double? = when (val v = rec.fields[num]) {
        is Long -> v.toDouble()
        is Double -> v
        else -> null
    }

    /** First string of the field: a scalar string, or the first element of a string array. */
    fun string(num: Int): String? = when (val v = rec.fields[num]) {
        is String -> v
        is List<*> -> v.firstOrNull { it is String } as? String
        else -> null
    }

    /** Every string of the field; a scalar becomes a one-element list. */
    fun strings(num: Int): List<String> = when (val v = rec.fields[num]) {
        is String -> listOf(v)
        is List<*> -> v.filterIsInstance<String>()
        else -> emptyList()
    }

    /** Array field as doubles; a scalar becomes a one-element list, invalid elements become 0.0 to keep positions. */
    fun doubles(num: Int): List<Double> = when (val v = rec.fields[num]) {
        is List<*> -> v.map { (it as? Number)?.toDouble() ?: 0.0 }
        is Number -> listOf(v.toDouble())
        else -> emptyList()
    }

    fun ints(num: Int): List<Int> = doubles(num).map { it.toInt() }
}

/** Accumulates typed records in file order; one method per global message. */
private class TypedRecords {
    private var fileId: FileIdRec? = null
    private val monitoring = ArrayList<MonitoringRec>()
    private val monitoringInfo = ArrayList<MonitoringInfoRec>()
    private val stress = ArrayList<StressRec>()
    private val restingHr = ArrayList<RestingHrRec>()
    private val spo2 = ArrayList<Spo2Rec>()
    private val respiration = ArrayList<RespirationRec>()
    private val events = ArrayList<EventRec>()
    private val sleepStages = ArrayList<SleepStageRec>()
    private val sleepStats = ArrayList<SleepStatsRec>()
    private val restlessMoments = ArrayList<RestlessMomentsRec>()
    private val naps = ArrayList<NapRec>()
    private val hrvSummary = ArrayList<HrvSummaryRec>()
    private val hrvValues = ArrayList<HrvValueRec>()
    private val trainingLoad = ArrayList<TrainingLoadRec>()
    private val racePredictions = ArrayList<RacePredictionRec>()
    private val hillScores = ArrayList<HillScoreRec>()
    private val enduranceScores = ArrayList<EnduranceScoreRec>()
    private val trainingReadiness = ArrayList<TrainingReadinessRec>()
    private val functionalMetrics = ArrayList<FunctionalMetricsRec>()
    private val recovery = ArrayList<RecoveryRec>()
    private val maxMet = ArrayList<MaxMetRec>()
    private val deviceStatus = ArrayList<DeviceStatusRec>()
    private val sessions = ArrayList<SessionRec>()
    private val laps = ArrayList<LapRec>()
    private val records = ArrayList<RecordRec>()
    private val timeInZone = ArrayList<TimeInZoneRec>()
    private val physiologicalMetrics = ArrayList<PhysiologicalMetricsRec>()
    private val userProfile = ArrayList<UserProfileRec>()
    private val sports = ArrayList<SportRec>()
    private val activity = ArrayList<ActivityRec>()
    private val workouts = ArrayList<WorkoutRec>()
    private val workoutSteps = ArrayList<WorkoutStepRec>()
    private val exerciseTitles = ArrayList<ExerciseTitleRec>()
    private val sets = ArrayList<SetRec>()
    private val capabilities = ArrayList<CapabilitiesRec>()

    /** Garmin-epoch seconds of the last monitoring record, the base for `timestamp_16`. */
    private var lastMonitoringGarminSeconds: Long? = null

    fun add(rec: RawRecord) {
        val f = Fields(rec)
        when (rec.globalMessageNumber) {
            Mesg.FILE_ID -> if (fileId == null) fileId = fileId(f)
            Mesg.CAPABILITIES -> capabilities += CapabilitiesRec(f.long(23), packBits(f.ints(1)))
            Mesg.USER_PROFILE -> userProfile += UserProfileRec(f.int(8), f.double(4), f.int(2), f.int(1), f.int(11))
            Mesg.SPORT -> sports += SportRec(f.int(0), f.int(1), f.string(3))
            Mesg.SESSION -> session(f)
            Mesg.LAP -> lap(f)
            Mesg.RECORD -> record(f)
            Mesg.EVENT -> f.long(253)?.let { events += EventRec(it, f.int(0), f.int(1), f.long(3)) }
            Mesg.WORKOUT -> workouts += WorkoutRec(f.string(8), f.int(4), f.int(11), f.int(6), f.long(5), f.int(254))
            Mesg.WORKOUT_STEP -> workoutSteps += WorkoutStepRec(
                messageIndex = f.int(254), name = f.string(0), durationType = f.int(1), durationValue = f.long(2), targetType = f.int(3),
                targetValue = f.long(4), customTargetValueLow = f.long(5), customTargetValueHigh = f.long(6), intensity = f.int(7),
                notes = f.string(8), equipment = f.int(9), exerciseCategory = f.int(10), exerciseName = f.int(11),
                exerciseWeightKg = f.double(12), weightDisplayUnit = f.int(13)
            )
            Mesg.EXERCISE_TITLE -> exerciseTitles += ExerciseTitleRec(f.int(254), f.int(0), f.int(1), f.strings(2))
            Mesg.SET -> sets += SetRec(
                timestamp = f.long(254) ?: f.long(253), durationSeconds = f.double(0), repetitions = f.int(3), weightKg = f.double(4),
                setType = f.int(5), startTime = f.long(6), category = f.ints(7), categorySubtype = f.ints(8), weightDisplayUnit = f.int(9),
                messageIndex = f.int(10), wktStepIndex = f.int(11)
            )
            Mesg.ACTIVITY -> activity += ActivityRec(f.long(253), f.long(5), f.int(1), f.double(0), f.string(8))
            Mesg.MONITORING -> monitoring(f)
            Mesg.MONITORING_INFO -> f.long(253)?.let { monitoringInfo += MonitoringInfoRec(it, f.int(5)) }
            Mesg.DEVICE_STATUS -> f.long(253)?.let { deviceStatus += DeviceStatusRec(it, f.int(2)) }
            Mesg.PHYSIOLOGICAL_METRICS -> physiologicalMetrics += PhysiologicalMetricsRec(
                f.long(253), f.double(4), f.double(20), f.double(7), f.int(9), f.int(14), f.int(63)
            )
            Mesg.MONITORING_HR_DATA -> restingHr += RestingHrRec(f.long(253), f.int(0), f.int(1))
            Mesg.TIME_IN_ZONE -> timeInZone += TimeInZoneRec(f.long(253), f.int(0), f.int(1), f.doubles(2), f.ints(6))
            Mesg.STRESS_LEVEL -> (f.long(1) ?: f.long(253))?.let { stress += StressRec(it, f.int(0), f.int(3), f.int(2)) }
            Mesg.MAX_MET_DATA -> (f.long(253) ?: f.long(0))?.let {
                maxMet += MaxMetRec(it, f.double(2), f.int(8), f.int(5), f.int(6), f.int(3))
            }
            Mesg.SPO2_DATA -> {
                val ts = f.long(253)
                val percent = f.int(0)
                if (ts != null && percent != null) spo2 += Spo2Rec(ts, percent, f.int(2), f.int(1))
            }
            Mesg.SLEEP_LEVEL -> {
                val ts = f.long(253)
                val stage = f.int(0)
                if (ts != null && stage != null) sleepStages += SleepStageRec(ts, stage)
            }
            Mesg.METRIC_RECOVERY -> {
                val ts = f.long(253) ?: f.long(1)
                val minutes = f.int(0)
                if (ts != null && minutes != null) recovery += RecoveryRec(ts, minutes)
            }
            Mesg.RESPIRATION_RATE -> {
                val ts = f.long(253)
                val rate = f.double(0)
                if (ts != null && rate != null) respiration += RespirationRec(ts, rate)
            }
            Mesg.RACE_PREDICTION -> f.long(253)?.let { racePredictions += RacePredictionRec(it, f.int(1), f.int(2), f.int(3), f.int(4)) }
            Mesg.SLEEP_ASSESSMENT -> sleepStats += SleepStatsRec(
                timestamp = f.long(253), overallSleepScore = f.int(6), deepSleepScore = f.int(3), lightSleepScore = f.int(5),
                remSleepScore = f.int(9), sleepDurationScore = f.int(4), sleepQualityScore = f.int(7), sleepRecoveryScore = f.int(8),
                sleepRestlessnessScore = f.int(10), awakeningsCount = f.int(11), interruptionsScore = f.int(14),
                averageStressDuringSleep = f.double(15)
            )
            Mesg.FUNCTIONAL_METRICS -> f.long(253)?.let { functionalMetrics += FunctionalMetricsRec(it, f.int(4), f.int(9), f.int(7), f.int(8)) }
            Mesg.TRAINING_READINESS -> f.long(253)?.let { trainingReadiness += TrainingReadinessRec(it, f.int(0), f.int(1), f.int(4)) }
            Mesg.HRV_STATUS_SUMMARY -> f.long(253)?.let {
                hrvSummary += HrvSummaryRec(it, f.double(0), f.double(1), f.double(2), f.double(3), f.double(4), f.double(5), f.int(6))
            }
            Mesg.HRV_VALUE -> {
                val ts = f.long(253)
                val value = f.double(0)
                if (ts != null && value != null) hrvValues += HrvValueRec(ts, value)
            }
            Mesg.TRAINING_LOAD -> f.long(253)?.let { trainingLoad += TrainingLoadRec(it, f.int(3), f.int(4), f.double(5)) }
            Mesg.SLEEP_RESTLESS_MOMENTS -> f.int(1)?.let { restlessMoments += RestlessMomentsRec(f.long(253), it) }
            Mesg.HILL_SCORE -> f.long(253)?.let { hillScores += HillScoreRec(it, f.int(0), f.int(1), f.int(2), f.int(4)) }
            Mesg.ENDURANCE_SCORE -> f.long(253)?.let { enduranceScores += EnduranceScoreRec(it, f.int(0), f.int(1)) }
            Mesg.NAP -> {
                val start = f.long(0)
                val end = f.long(2)
                if (start != null && end != null) naps += NapRec(start, end)
            }
        }
    }

    private fun fileId(f: Fields) = FileIdRec(
        typeNum = f.int(0), timeCreated = f.long(4), manufacturer = f.int(1), product = f.int(2),
        serialNumber = f.long(3), number = f.int(5)
    )

    /**
     * Monitoring records carry either a full timestamp (253) or `timestamp_16` (26), the low 16 bits
     * of the Garmin-epoch timestamp relative to the previous monitoring record. Activity type and
     * intensity come from field 5 or from the packed field 24 (low 5 bits type, high 3 bits intensity).
     * A heart rate of 0 means "not measured" and becomes null.
     */
    private fun monitoring(f: Fields) {
        val full = f.long(253)
        val garminSeconds = when {
            full != null -> full - GARMIN_EPOCH_UNIX_SECONDS
            else -> {
                val t16 = f.long(26)
                val base = lastMonitoringGarminSeconds
                if (t16 == null || base == null) null else base + ((t16 - (base and 0xFFFF)) and 0xFFFF)
            }
        } ?: return
        lastMonitoringGarminSeconds = garminSeconds
        val packed = f.int(24)
        monitoring += MonitoringRec(
            timestamp = garminSeconds + GARMIN_EPOCH_UNIX_SECONDS,
            heartRate = f.int(27)?.takeIf { it > 0 },
            cumulativeSteps = f.long(3),
            cumulativeDistanceM = f.double(2),
            cumulativeActiveKcal = f.int(19),
            activityType = f.int(5) ?: packed?.let { it and 0x1F },
            intensity = packed?.let { it shr 5 },
            moderateActivityMinutes = f.int(33) ?: f.int(37),
            vigorousActivityMinutes = f.int(34) ?: f.int(38),
            ascentM = f.double(31),
            descentM = f.double(32)
        )
    }

    private fun session(f: Fields) {
        val ts = f.long(253) ?: return
        sessions += SessionRec(
            timestamp = ts, startTime = f.long(2), sport = f.int(5), subSport = f.int(6), sportProfileName = f.string(110),
            totalElapsedTime = f.double(7), totalTimerTime = f.double(8), totalDistance = f.double(9), totalCalories = f.int(11),
            restingCalories = f.int(196), avgHeartRate = f.int(16), maxHeartRate = f.int(17), minHeartRate = f.int(64),
            avgCadence = f.int(18), maxCadence = f.int(19), avgSpeed = f.double(124) ?: f.double(14), maxSpeed = f.double(125) ?: f.double(15),
            totalAscent = f.int(22), totalDescent = f.int(23), totalTrainingEffect = f.double(24), totalAnaerobicTrainingEffect = f.double(137),
            beginningBodyBattery = f.int(215), endingBodyBattery = f.int(216), trainingLoadPeak = f.double(168), avgStress = f.int(195),
            totalCycles = f.long(10), avgStepLength = f.double(134), avgTemperature = f.int(57), numLaps = f.int(26)
        )
    }

    private fun lap(f: Fields) {
        val ts = f.long(253) ?: return
        laps += LapRec(
            timestamp = ts, startTime = f.long(2), totalElapsedTime = f.double(7), totalTimerTime = f.double(8), totalDistance = f.double(9),
            avgHeartRate = f.int(15), maxHeartRate = f.int(16), avgSpeed = f.double(110) ?: f.double(13), avgCadence = f.int(17),
            totalAscent = f.int(21), totalCalories = f.int(11), lapTrigger = f.int(24)
        )
    }

    private fun record(f: Fields) {
        val ts = f.long(253) ?: return
        records += RecordRec(
            timestamp = ts, latitude = f.double(0), longitude = f.double(1), altitude = f.double(78) ?: f.double(2), distance = f.double(5),
            speed = f.double(73) ?: f.double(6), heartRate = f.int(3), cadence = f.int(4), power = f.int(7), temperature = f.int(13),
            bodyBattery = f.int(143)
        )
    }

    /** Packs up to eight bitmask bytes (little-endian order) into one Long; null when the field is absent. */
    private fun packBits(bytes: List<Int>): Long? {
        if (bytes.isEmpty()) return null
        var out = 0L
        bytes.take(8).forEachIndexed { i, b -> out = out or ((b.toLong() and 0xFF) shl (8 * i)) }
        return out
    }

    fun build(parsed: ParsedFit) = DecodedFit(
        fileId = fileId ?: FileIdRec(typeNum = null, timeCreated = null),
        monitoring = monitoring, monitoringInfo = monitoringInfo, stress = stress, restingHr = restingHr, spo2 = spo2,
        respiration = respiration, events = events, sleepStages = sleepStages, sleepStats = sleepStats, restlessMoments = restlessMoments,
        naps = naps, hrvSummary = hrvSummary, hrvValues = hrvValues, trainingLoad = trainingLoad, racePredictions = racePredictions,
        hillScores = hillScores, enduranceScores = enduranceScores, trainingReadiness = trainingReadiness,
        functionalMetrics = functionalMetrics, recovery = recovery, maxMet = maxMet, deviceStatus = deviceStatus, sessions = sessions,
        laps = laps, records = records, timeInZone = timeInZone, physiologicalMetrics = physiologicalMetrics, userProfile = userProfile,
        sports = sports, activity = activity, workouts = workouts, workoutSteps = workoutSteps, exerciseTitles = exerciseTitles,
        sets = sets, capabilities = capabilities, raw = parsed.records,
        unknownMessageCount = parsed.unknownMessageCount, unknownFieldCount = parsed.unknownFieldCount
    )
}
