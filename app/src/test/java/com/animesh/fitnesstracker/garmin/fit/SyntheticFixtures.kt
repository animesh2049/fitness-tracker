package com.animesh.fitnesstracker.garmin.fit

import java.time.Instant
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Deterministic generator for the FIT fixtures under `src/test/resources/fit`.
 *
 * The files stand in for a Forerunner 570's wellness files and reproduce their shapes (message
 * numbers, field layouts, sentinels, unknown messages, `timestamp_16` heart rate samples) with
 * invented content: serial number [SERIAL], September 2026 dates and numbers drawn from
 * `Random(SEED)`. Nothing in them comes from a real person or device. One function per file returns
 * the bytes; [files] maps each committed name to its function.
 *
 * [SyntheticFixturesTest] asserts that every committed file equals its generator output byte for
 * byte. After changing this generator, rewrite the resources with either of
 * ```
 * FIT_REGENERATE=true JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew :app:testDebugUnitTest --tests '*SyntheticFixturesTest' --rerun
 * JAVA_HOME=... ./gradlew :app:testDebugUnitTest --tests '*SyntheticFixturesTest' --rerun -Dfit.regenerate=true
 * ```
 * (the `-D` form needs the property forwarded to the test JVM; the environment variable always
 * reaches it), then commit the changed files together with the generator.
 *
 * Story behind the numbers: the watch lives at UTC-7. Its owner slept from 23:05 on 15 September
 * to 06:32 on the 16th (the SLEEP, HRV and morning METRICS files), took the watch off for a shower
 * at 06:40 and while charging at 13:00, walked twice during the day and reached [WALKING_STEPS]
 * steps by 17:40. MONITOR_M9GL2255 covers 00:00 to 18:30 of the 16th, MONITOR_M9G00000 18:30 to
 * 21:15, MONITOR_M9GL2445 (the golden file, fully hand-specified) 21:20 to 21:30 and
 * MONITOR_M9FM1100 one hour of the 15th.
 */
object SyntheticFixtures {
    const val SEED = 20260917
    const val SERIAL = 1_000_000_001L
    const val MANUFACTURER = 1
    const val PRODUCT = 4570
    const val SOFTWARE_VERSION = 618
    /** Profile version written into the header of the monitoring files; the others use [FitWriter.DEFAULT_PROFILE_VERSION]. */
    const val MONITOR_PROFILE_VERSION = 2049
    const val TZ_OFFSET_MINUTES = -420
    const val TZ_OFFSET_SECONDS = TZ_OFFSET_MINUTES * 60
    const val RESTING_HR = 56
    const val RESTING_METABOLIC_RATE = 2159
    const val SLEEP_SCORE = 83
    const val WALKING_STEPS = 7325L
    const val SPO2_PERCENT = 97
    /** Body Battery at sleep start and the overnight gain, as the daily sleep and event records tell it. */
    const val SLEEP_BATTERY_START = 41
    const val SLEEP_BATTERY_GAIN = 38

    private const val MINUTE = 60L
    private const val WALKING = 6
    private const val GENERIC = 0
    private const val SEDENTARY = 8

    /** Local midnight (UTC-7) of 16 September 2026 as Unix seconds. */
    val DAY_16: Long = Instant.parse("2026-09-16T07:00:00Z").epochSecond
    val DAY_15: Long = DAY_16 - 86_400
    val DAY_05: Long = DAY_16 - 11 * 86_400
    /** The night the SLEEP, HRV and morning METRICS files describe. */
    val SLEEP_START: Long = DAY_15 + hm(23, 5)
    val SLEEP_END: Long = DAY_16 + hm(6, 32)
    /** Start of the hand-specified golden monitoring file (21:20 local on the 16th). */
    val GOLDEN_START: Long = DAY_16 + hm(21, 20)

    val files: Map<String, () -> ByteArray> = linkedMapOf(
        "MONITOR_M9FM1100.fit" to ::monitorM9FM1100,
        "MONITOR_M9G00000.fit" to ::monitorM9G00000,
        "MONITOR_M9GL2255.fit" to ::monitorM9GL2255,
        "MONITOR_M9GL2445.fit" to ::monitorM9GL2445,
        "SLEEP_G9G80120.fit" to ::sleep,
        "HRV_G9G80118.fit" to ::hrv,
        "METRICS_F85H0122.fit" to ::metricsF85H0122,
        "METRICS_G9FM3212.fit" to ::metricsG9FM3212,
        "METRICS_G9FM3213.fit" to ::metricsG9FM3213,
        "METRICS_G9G80119.fit" to ::metricsG9G80119,
        "METRICS_G9GL2318.fit" to ::metricsG9GL2318,
        "SKINTEMP_G9G80127.fit" to ::skinTemp,
        "device.fit" to ::device,
        "Settings.fit" to ::settings,
        "Records.fit" to ::records,
        "Totals.fit" to ::totals
    )

    fun generate(name: String): ByteArray = files.getValue(name)()

    // Monitoring files

    /** One hour (11:00 to 12:00) of 15 September. */
    fun monitorM9FM1100(): ByteArray = monitorWindow(day(DAY_15, salt = 15), from = 11 * 60, to = 12 * 60, number = 1, withSpo2 = false)

    /** 18:30 to 21:15 of 16 September. */
    fun monitorM9G00000(): ByteArray = monitorWindow(day(DAY_16, salt = 16), from = 18 * 60 + 30, to = 21 * 60 + 15, number = 3, withSpo2 = false)

    /** The big one: 00:00 to 18:30 of 16 September, with the night's sleep end event and the SpO2 reading. */
    fun monitorM9GL2255(): ByteArray = monitorWindow(day(DAY_16, salt = 16), from = 0, to = 18 * 60 + 30, number = 2, withSpo2 = true)

    /**
     * Ten minutes (21:20 to 21:30 of the 16th) with every value spelled out, so [FitGoldenTest] can
     * assert exact record counts and field values.
     */
    fun monitorM9GL2445(): ByteArray {
        val w = FitWriter(MONITOR_PROFILE_VERSION)
        val t = GOLDEN_START
        fileId(w, type = 32, created = t + 45, number = 4)
        fileCreator(w)
        deviceInfo(w, t)
        monitoringInfo(w, t)
        w.write(Mesg.MONITORING_HR_DATA, ts(t), FitField.u8(0, 60), FitField.u8(1, RESTING_HR))
        unknownBlock(w, t, Random(SEED))
        cumulative(w, t, GENERIC, steps = 312, distanceCm = 21_840, activeMs = 1_140_000, kcal = 14, durationMin = 1280)
        cumulative(w, t, WALKING, steps = WALKING_STEPS, distanceCm = 571_350, activeMs = 4_980_000, kcal = 293, durationMin = 1280)
        ascent(w, t, ascentMm = 0, descentMm = 0, totalAscentMm = 21_336, totalDescentMm = 31_301)
        intensity(w, t, activityType = SEDENTARY, level = 0, moderate = 42, vigorous = 3)
        w.write(Mesg.EVENT, ts(t + 45), FitField.enum(0, 74), FitField.enum(1, 0))
        unknown233(w, 200)
        val heartRates = listOf(62, 61, 0, 63, 64, 66, 65, 63, 62, 61)
        val stressAt = mapOf(1 to Triple(-1, 41, 31), 4 to Triple(22, 41, 31), 7 to Triple(-2, 41, 30), 10 to Triple(19, 40, 30))
        val respirationAt = mapOf(1 to -200, 3 to 1520, 5 to 1480, 7 to -100, 9 to 1510)
        for (minute in 1..10) {
            val at = t + minute * MINUTE
            heartRate(w, at, heartRates[minute - 1])
            stressAt[minute]?.let { (stress, average, battery) -> stress(w, at, stress, average, battery, unknown = if (stress == -2) 1 else 0) }
            respirationAt[minute]?.let { respiration(w, at, it) }
            if (minute % 2 == 0) unknown279(w, at)
        }
        unknown233(w, 200)
        w.write(188, ts(t + 10 * MINUTE), FitField.u8(0, 4))
        return w.toByteArray()
    }

    /** One minute of the invented day. Cumulative counters are the day's running totals at the end of the minute. */
    private class Minute(
        val asleep: Boolean,
        val worn: Boolean,
        val walking: Boolean,
        /** 0 when the watch could not measure. */
        val heartRate: Int,
        val stress: Int,
        val averageStress: Int,
        /** 0..100, or 127 in the first minutes after the watch came off. */
        val bodyBattery: Int,
        /** Breaths per minute times 100; -100 and -200 are the watch's sentinels. */
        val respirationRaw: Int,
        val walkSteps: Long,
        val genericSteps: Long,
        val intensity: Int,
        val moderateMinutes: Int,
        val vigorousMinutes: Int,
        val ascentMm: Long,
        val descentMm: Long
    ) {
        val stressRecorded: Boolean get() = worn || bodyBattery == 127
    }

    private class Day(val midnight: Long, val minutes: List<Minute>)

    private fun day(midnight: Long, salt: Int): Day {
        val rnd = Random(SEED + salt)
        val asleepUntil = (SLEEP_END - DAY_16) / MINUTE
        val asleepFrom = (SLEEP_START - DAY_15) / MINUTE
        val notWorn = listOf(400 until 440, 780 until 840)
        val walks = listOf(490 until 520, 1005 until 1060)
        val lastWalkMinute = walks.last().last

        val walkIncrement = IntArray(1440)
        val genericIncrement = IntArray(1440)
        for (i in 0 until 1440) {
            val asleep = i < asleepUntil || i >= asleepFrom
            val worn = notWorn.none { i in it }
            if (asleep || !worn) continue
            when {
                walks.any { i in it } -> walkIncrement[i] = rnd.nextInt(88, 126)
                i < lastWalkMinute && rnd.nextInt(100) < 18 -> walkIncrement[i] = rnd.nextInt(6, 40)
                rnd.nextInt(100) < 6 -> genericIncrement[i] = rnd.nextInt(3, 16)
            }
        }
        val rawWalkTotal = walkIncrement.sum().toLong()

        val minutes = ArrayList<Minute>(1440)
        var hr = 54.0
        var battery = 42.0
        var stressLevel = 20.0
        var stressSum = 0L
        var stressCount = 0
        var rawWalk = 0L
        var generic = 0L
        var moderate = 0
        var vigorous = 0
        var ascent = 0L
        var descent = 0L
        for (i in 0 until 1440) {
            val asleep = i < asleepUntil || i >= asleepFrom
            val worn = notWorn.none { i in it }
            val walking = walks.any { i in it }
            val target = when {
                !worn -> hr
                walking -> if (i >= walks.last().first) 118.0 else 104.0
                asleep -> 52.0
                else -> 70.0
            }
            hr += (target - hr) * 0.15 + rnd.nextDouble(-3.0, 3.0)
            val heartRate = if (!worn || rnd.nextInt(100) < 3) 0 else hr.roundToInt().coerceIn(45, 160)
            battery += when {
                !worn -> 0.0
                asleep -> 0.11
                walking -> -0.16
                else -> -0.055
            }
            battery = battery.coerceIn(5.0, 100.0)
            val bodyBattery = if (i in 400..402) 127 else battery.roundToInt()
            val stressTarget = if (asleep) 16.0 else 38.0
            stressLevel += (stressTarget - stressLevel) * 0.1 + rnd.nextDouble(-6.0, 6.0)
            val stress = when {
                heartRate == 0 -> -2
                walking || rnd.nextInt(100) < 4 -> -1
                else -> stressLevel.roundToInt().coerceIn(1, 95)
            }
            if (stress >= 0) { stressSum += stress; stressCount++ }
            val averageStress = if (stressCount == 0) 0 else (stressSum / stressCount).toInt()
            val breaths = when {
                walking -> 22.0
                asleep -> 13.5
                else -> 16.0
            } + rnd.nextDouble(-1.2, 1.2)
            val respirationRaw = when {
                i % 47 == 5 -> -100
                i % 113 == 9 -> -200
                else -> (breaths * 100).roundToInt()
            }
            rawWalk += walkIncrement[i]
            generic += genericIncrement[i]
            val walkSteps = if (rawWalkTotal == 0L) 0L else rawWalk * WALKING_STEPS / rawWalkTotal
            val intensity = when {
                walking && heartRate >= 120 -> 3
                walking -> 1
                else -> 0
            }
            if (intensity == 3) vigorous++ else if (intensity == 1) moderate++
            if (walking) {
                ascent += rnd.nextInt(0, 600)
                descent += rnd.nextInt(0, 600)
            }
            minutes += Minute(
                asleep, worn, walking, heartRate, stress, averageStress, bodyBattery, respirationRaw, walkSteps, generic,
                intensity, moderate, vigorous, ascent, descent
            )
        }
        return Day(midnight, minutes)
    }

    /** Writes minutes [from] until [to] (exclusive) of [day] as one monitoring file. */
    private fun monitorWindow(day: Day, from: Int, to: Int, number: Int, withSpo2: Boolean): ByteArray {
        val rnd = Random(SEED + from)
        val w = FitWriter(MONITOR_PROFILE_VERSION)
        val start = day.midnight + from * MINUTE
        val end = day.midnight + to * MINUTE
        fileId(w, type = 32, created = end + 12, number = number)
        fileCreator(w)
        deviceInfo(w, start)
        monitoringInfo(w, start)
        w.write(Mesg.MONITORING_HR_DATA, ts(start), FitField.u8(0, 60), FitField.u8(1, RESTING_HR))
        unknownBlock(w, start, rnd)
        var lastAscent = 0L
        var lastDescent = 0L
        for (i in from until to) {
            val m = day.minutes[i]
            val at = day.midnight + i * MINUTE
            if (i == from || i % 20 == 0) {
                val activeMinutes = day.minutes.subList(0, i + 1).count { it.walkSteps > 0 && it.walking }
                cumulative(w, at, GENERIC, m.genericSteps, m.genericSteps * 70, (i + 1 - i / 3) * 1_000L, (m.genericSteps * 0.04).roundToInt(), i)
                cumulative(w, at, WALKING, m.walkSteps, m.walkSteps * 78, activeMinutes * 60_000L, (m.walkSteps * 0.04).roundToInt(), i)
            }
            if (i % 60 == 0) {
                ascent(w, at, m.ascentMm - lastAscent, m.descentMm - lastDescent, m.ascentMm, m.descentMm)
                lastAscent = m.ascentMm
                lastDescent = m.descentMm
            }
            if (i % 30 == 0) {
                val type = if (m.walking) WALKING else if (m.asleep) SEDENTARY else GENERIC
                intensity(w, at, type, m.intensity, m.moderateMinutes, m.vigorousMinutes)
            }
            if (m.worn) heartRate(w, at, m.heartRate)
            if (m.stressRecorded) stress(w, at, m.stress, m.averageStress, m.bodyBattery, unknown = if (m.stress < 0) 1 else 0)
            if (m.worn) respiration(w, at, m.respirationRaw)
            if (i % 2 == 0) unknown279(w, at)
            if (i % 10 == 0) unknown233(w, rnd.nextInt(180, 230))
            if (at == SLEEP_END) {
                w.write(Mesg.EVENT, ts(at), FitField.enum(0, 74), FitField.enum(1, 1), FitField.enum(14, 0), FitField.u32(15, garmin(SLEEP_START)))
            }
            if (at == day.midnight + SLEEP_START - DAY_15) {
                w.write(Mesg.EVENT, ts(at), FitField.enum(0, 74), FitField.enum(1, 0))
            }
            if (withSpo2 && at == day.midnight + hm(3, 10)) {
                w.write(Mesg.SPO2_DATA, ts(at), FitField.u8(0, SPO2_PERCENT), FitField.u8(1, 3), FitField.enum(2, 3))
            }
            if (withSpo2 && at == SLEEP_END) bodyBatteryEvent(w, start = SLEEP_START, end = SLEEP_END, kind = 4, delta = SLEEP_BATTERY_GAIN)
            if (withSpo2 && i == 1060) bodyBatteryEvent(w, start = at - 55 * MINUTE, end = at, kind = 0, delta = -9)
        }
        w.write(188, ts(end), FitField.u8(0, 4))
        return w.toByteArray()
    }

    private fun monitoringInfo(w: FitWriter, at: Long) {
        w.write(
            Mesg.MONITORING_INFO,
            ts(at), FitField.u32(0, garmin(at) + TZ_OFFSET_SECONDS), u8s(1, listOf(WALKING, 1)), u16s(3, listOf(8190, 12285)),
            u16s(4, listOf(265, 480)), FitField.u16(5, RESTING_METABOLIC_RATE), u32s(7, listOf(10_000L, 10_000L))
        )
    }

    /** The messages a monitoring file opens with: unknown 24 (twice), 188, 408, 355 and 484, plus a 12 minute unmeasured Body Battery event (407). */
    private fun unknownBlock(w: FitWriter, at: Long, rnd: Random) {
        repeat(2) { n -> w.write(24, bytes(2, List(27) { i -> if (i == 0) n + 1 else (i * 7 + n) % 256 })) }
        w.write(188, ts(at), FitField.u8(0, 3))
        w.write(
            Mesg.BODY_BATTERY_EVENT,
            ts(at), FitField.u8(0, 3), FitField.u16(1, 12), FitField.sint8(2, 0), FitField.u8(3, 3), FitField.u8(6, 2), FitField.u32(7, garmin(at + 12 * MINUTE))
        )
        w.write(408, ts(at), FitField.u8(0, 1))
        w.write(355, ts(at), FitField.u32(0, 1234L + rnd.nextInt(0, 3)), FitField.u8(1, 2))
        w.write(484, ts(at), FitField.u16(0, 7), FitField.u16(1, 42))
    }

    private fun cumulative(w: FitWriter, at: Long, activityType: Int, steps: Long, distanceCm: Long, activeMs: Long, kcal: Int, durationMin: Int) {
        w.write(
            Mesg.MONITORING,
            ts(at), FitField.u32(3, steps), FitField.u32(2, distanceCm), FitField.u32(4, activeMs), FitField.u16(19, kcal),
            FitField.u16(29, durationMin), FitField.enum(5, activityType)
        )
    }

    private fun ascent(w: FitWriter, at: Long, ascentMm: Long, descentMm: Long, totalAscentMm: Long, totalDescentMm: Long) {
        w.write(Mesg.MONITORING, ts(at), FitField.u32(31, ascentMm), FitField.u32(32, descentMm), FitField.u32(35, totalAscentMm), FitField.u32(36, totalDescentMm))
    }

    private fun intensity(w: FitWriter, at: Long, activityType: Int, level: Int, moderate: Int, vigorous: Int) {
        w.write(Mesg.MONITORING, ts(at), FitField.u8(24, (level shl 5) or activityType), FitField.u16(37, moderate), FitField.u16(38, vigorous))
    }

    private fun heartRate(w: FitWriter, at: Long, bpm: Int) {
        w.write(Mesg.MONITORING, FitField.u16(26, (garmin(at) and 0xFFFF).toInt()), FitField.u8(27, bpm))
    }

    private fun stress(w: FitWriter, at: Long, stress: Int, average: Int, bodyBattery: Int, unknown: Int) {
        w.write(Mesg.STRESS_LEVEL, FitField.u32(1, garmin(at)), FitField.sint16(0, stress), FitField.sint16(2, average), FitField.u8(3, bodyBattery), FitField.u8(4, unknown))
    }

    private fun respiration(w: FitWriter, at: Long, raw: Int) {
        w.write(Mesg.RESPIRATION_RATE, ts(at), FitField.sint16(0, raw))
    }

    /** The watch's altitude record: 2897 raw is 79.4 m with the enhanced_altitude scale (value / 5 - 500). */
    private fun unknown279(w: FitWriter, at: Long) = w.write(Mesg.MONITORING_ALTITUDE, ts(at), FitField.u16(0, 2897))

    /** A Body Battery event as the watch writes it: the record timestamp is the start, field 7 the end. */
    private fun bodyBatteryEvent(w: FitWriter, start: Long, end: Long, kind: Int, delta: Int) {
        val minutes = ((end - start) / MINUTE).toInt()
        w.write(
            Mesg.BODY_BATTERY_EVENT,
            ts(start), FitField.u8(0, kind), FitField.u16(1, minutes), FitField.sint8(2, delta), FitField.u8(3, if (kind == 0) 28 else 0),
            FitField.u8(6, if (kind == 0) 35 else 0), FitField.u32(7, garmin(end))
        )
    }

    private fun unknown233(w: FitWriter, last: Int) = w.write(233, bytes(2, listOf(10, 0, 0, last)))

    // Sleep, HRV and skin temperature

    fun sleep(): ByteArray {
        val rnd = Random(SEED + 49)
        val w = FitWriter()
        fileId(w, type = 49, created = SLEEP_END + 300, number = 1)
        fileCreator(w)
        deviceInfo(w, SLEEP_END)
        w.write(273, ts(SLEEP_END), FitField.u8(0, 1), FitField.u16(1, 300), FitField.string(4, "6.24.0.6-Q225", 16))
        w.write(Mesg.EVENT, ts(SLEEP_START), FitField.enum(0, 74), FitField.enum(1, 0))
        val stages = listOf(2, 3, 3, 2, 4, 2, 1, 2, 3, 2, 4, 2, 0, 2, 3, 2, 4, 4, 2, 1, 2, 4, 2, 4, 2)
        val weights = List(stages.size) { rnd.nextInt(10, 29) }
        val totalMinutes = (SLEEP_END - SLEEP_START) / MINUTE
        var elapsed = 0L
        for ((index, stage) in stages.withIndex()) {
            elapsed += weights[index] * totalMinutes / weights.sum()
            val end = if (index == stages.lastIndex) SLEEP_END else SLEEP_START + elapsed * MINUTE
            w.write(Mesg.SLEEP_LEVEL, ts(end), FitField.enum(0, stage))
        }
        w.write(Mesg.EVENT, ts(SLEEP_END), FitField.enum(0, 74), FitField.enum(1, 1), FitField.enum(14, 0), FitField.u32(15, garmin(SLEEP_START)))
        w.write(
            Mesg.SLEEP_ASSESSMENT,
            FitField.u8(0, 78), FitField.u8(1, 80), FitField.u8(2, 90), FitField.u8(3, 74), FitField.u8(4, 85), FitField.u8(5, 88),
            FitField.u8(6, SLEEP_SCORE), FitField.u8(7, 82), FitField.u8(8, 79), FitField.u8(9, 86), FitField.u8(10, 81), FitField.u8(11, 2),
            FitField.u8(12, 3), FitField.u8(13, 0), FitField.u8(14, 84), FitField.u16(15, 867), FitField.u8(16, 1)
        )
        w.write(Mesg.SLEEP_RESTLESS_MOMENTS, FitField.u32(0, garmin(SLEEP_START)), FitField.u16(1, 50), u16s(2, listOf(30, 60, 45, 120)))
        return w.toByteArray()
    }

    fun hrv(): ByteArray {
        val rnd = Random(SEED + 68)
        val w = FitWriter()
        val created = DAY_16 + hm(6, 40)
        fileId(w, type = 68, created = created, number = 1)
        fileCreator(w)
        deviceInfo(w, created)
        w.write(22, ts(created), FitField.u16(0, 1))
        w.write(162, ts(created), FitField.u32(1, garmin(created)), FitField.u32(3, garmin(created) + TZ_OFFSET_SECONDS), FitField.u16(5, 0))
        w.write(Mesg.HRV_STATUS_SUMMARY, ts(created), FitField.u16(1, 50 * 128), FitField.u16(2, 87 * 128), FitField.enum(6, 0), FitField.u8(7, 1))
        var ms = 48.0
        for (k in 0 until 96) {
            ms = (ms + rnd.nextDouble(-7.0, 7.0)).coerceIn(32.0, 86.0)
            val value = if (k == 40) 87.0 else ms
            w.write(Mesg.HRV_VALUE, ts(SLEEP_START - 300 + k * 300L), FitField.u16(0, (value * 128).roundToInt()))
        }
        return w.toByteArray()
    }

    fun skinTemp(): ByteArray {
        val w = FitWriter()
        val created = DAY_16 + hm(6, 41)
        fileId(w, type = 73, created = created, number = 1)
        fileCreator(w)
        deviceInfo(w, created)
        w.write(Mesg.SKIN_TEMP_OVERNIGHT, ts(SLEEP_END), FitField.u32(0, garmin(SLEEP_END) + TZ_OFFSET_SECONDS), FitField.u32(1, null), FitField.u32(2, null), FitField.u8(3, 1), FitField.u32(4, null))
        w.write(Mesg.SKIN_TEMP_OVERNIGHT, ts(created), FitField.u32(0, garmin(created) + TZ_OFFSET_SECONDS), FitField.u32(1, null), FitField.u32(2, null), FitField.u8(3, 1), FitField.u32(4, null))
        return w.toByteArray()
    }

    // Metrics files

    fun metricsF85H0122(): ByteArray = metrics(created = DAY_05 + hm(7, 30), number = 122, sleepScore = 78, sleepMinutes = 471, readiness = null, recovery = false)
    fun metricsG9FM3212(): ByteArray = metrics(created = DAY_15 + hm(6, 45), number = 212, sleepScore = 80, sleepMinutes = 452, readiness = null, recovery = false)
    fun metricsG9FM3213(): ByteArray = metrics(created = DAY_15 + hm(6, 46), number = 213, sleepScore = null, sleepMinutes = null, readiness = ReadinessShape(sleepScore = null), recovery = false)
    fun metricsG9G80119(): ByteArray = metrics(created = DAY_16 + hm(6, 40), number = 119, sleepScore = SLEEP_SCORE, sleepMinutes = 480, readiness = ReadinessShape(sleepScore = SLEEP_SCORE), recovery = true)
    fun metricsG9GL2318(): ByteArray = metrics(created = DAY_16 + hm(18, 40), number = 318, sleepScore = SLEEP_SCORE, sleepMinutes = null, readiness = null, recovery = false)

    private class ReadinessShape(val sleepScore: Int?)

    private fun metrics(created: Long, number: Int, sleepScore: Int?, sleepMinutes: Int?, readiness: ReadinessShape?, recovery: Boolean): ByteArray {
        val rnd = Random(SEED + number)
        val w = FitWriter()
        fileId(w, type = 44, created = created, number = number)
        fileCreator(w)
        deviceInfo(w, created)
        if (recovery) {
            w.write(Mesg.METRIC_RECOVERY, FitField.u16(0, 1), FitField.u32(1, garmin(created)), FitField.u16(2, 0), FitField.u8(3, 1), FitField.u8(4, 0), FitField.u16(5, 30))
        }
        w.write(
            Mesg.FUNCTIONAL_METRICS,
            ts(created), FitField.u16(2, 180 + rnd.nextInt(0, 20)), FitField.u16(3, 160 + rnd.nextInt(0, 10)), FitField.u8(5, 2), FitField.u8(6, 1),
            FitField.u16(11, 1000 + rnd.nextInt(0, 50)), FitField.u16(12, 3)
        )
        if (readiness != null) {
            w.write(
                Mesg.TRAINING_READINESS,
                ts(created), FitField.u8(0, null), FitField.enum(1, 0), FitField.u8(2, 1), FitField.u8(3, 0), FitField.u8(4, readiness.sleepScore),
                FitField.u8(5, 20), FitField.u8(6, 1), FitField.u8(7, 0), FitField.u16(8, 0), FitField.u8(9, 1), FitField.u8(10, 60),
                FitField.u16(11, 0), FitField.u32(20, garmin(created) + TZ_OFFSET_SECONDS), FitField.u16(21, 65408), FitField.u16(22, 120)
            )
        }
        w.write(241, ts(created), FitField.u8(0, 1))
        w.write(330, ts(created), FitField.u16(0, 12), FitField.u16(1, 7))
        if (sleepScore != null) {
            val nightEnd = created - hm(0, 8)
            val nightStart = nightEnd - hm(7, 27)
            w.write(
                Mesg.DAILY_SLEEP,
                ts(created), FitField.u8(0, 54), FitField.u8(1, 100), FitField.u8(2, sleepScore), FitField.u16(3, 960),
                FitField.u32(8, garmin(nightEnd) + TZ_OFFSET_SECONDS), FitField.u32(9, garmin(nightStart)), FitField.sint16(10, TZ_OFFSET_MINUTES),
                FitField.u32(11, garmin(nightEnd)), FitField.sint16(12, TZ_OFFSET_MINUTES), FitField.u8(14, SLEEP_BATTERY_START),
                FitField.u8(16, SLEEP_BATTERY_START + SLEEP_BATTERY_GAIN), FitField.sint8(22, -15), FitField.u8(24, 30)
            )
        }
        if (sleepMinutes != null) {
            w.write(
                Mesg.SLEEP_DEMAND,
                ts(created), FitField.u16(0, sleepMinutes), FitField.u16(1, sleepMinutes + (number % 3) * 20), FitField.u8(2, 3), FitField.u8(3, 0),
                FitField.u8(4, 3), FitField.u8(5, 3), FitField.u8(6, 0)
            )
        }
        return w.toByteArray()
    }

    // Device, settings, records and totals

    fun device(): ByteArray {
        val rnd = Random(SEED + 1)
        val w = FitWriter()
        val created = DAY_16 + hm(7, 0)
        fileId(w, type = 1, created = created, number = 0)
        fileCreator(w)
        w.write(
            Mesg.CAPABILITIES,
            FitField.array(1, FitBaseType.UINT8Z, listOf(0x7BL, 0x1FL, 0L, 0x30L, 0L, 0L, 0L, 0x02L)), FitField.u32z(21, 0x0FL), FitField.u32z(22, 0x03L),
            FitField.u32z(23, 0x0F3FL), FitField.enum(24, 1), FitField.enum(26, 1)
        )
        repeat(99) { i -> w.write(35, FitField.u16(3, 100 + i), FitField.string(5, "006-B4570-" + (10 + i % 90), 13)) }
        repeat(3) { i -> w.write(37, FitField.u8(0, i), FitField.u16(1, rnd.nextInt(0, 500))) }
        repeat(2) { i -> w.write(38, FitField.u8(0, i), FitField.u8(1, 1)) }
        repeat(2) { i -> w.write(39, FitField.u16(0, i), FitField.u32(1, rnd.nextInt(0, 100_000).toLong())) }
        repeat(4) { i -> w.write(138, FitField.u16(0, i), FitField.u8(1, rnd.nextInt(0, 4))) }
        return w.toByteArray()
    }

    fun settings(): ByteArray {
        val w = FitWriter()
        val created = DAY_16 + hm(7, 1)
        fileId(w, type = 2, created = created, number = 0)
        fileCreator(w)
        w.write(
            2,
            FitField.u8(0, 0), FitField.u32(1, (TZ_OFFSET_SECONDS + 86_400).toLong()), FitField.enum(4, 0), FitField.sint8(5, TZ_OFFSET_SECONDS / 900),
            FitField.enum(12, 2), FitField.enum(36, 1), FitField.u32(39, garmin(created)), FitField.u8(46, 5)
        )
        w.write(
            Mesg.USER_PROFILE,
            FitField.string(0, "athlete", 16), FitField.enum(1, 1), FitField.u8(2, 34), FitField.u16(3, 17_800), FitField.u16(4, 720),
            FitField.u8(8, RESTING_HR), FitField.u8(11, 186), FitField.u8(24, 92), FitField.u32(28, 6 * 3600 + 1800), FitField.u32(29, 23 * 3600),
            FitField.u16(31, 1100), FitField.u16(32, 780), FitField.u16(254, 0)
        )
        w.write(13, FitField.u8(0, 1), FitField.u16(1, 8))
        w.write(22, FitField.u16(0, 3), FitField.u16(1, 25))
        return w.toByteArray()
    }

    fun records(): ByteArray {
        val rnd = Random(SEED + 29)
        val w = FitWriter()
        val created = DAY_16 + hm(7, 2)
        fileId(w, type = 29, created = created, number = 0)
        fileCreator(w)
        repeat(20) { i ->
            w.write(
                114,
                ts(DAY_05 + i * 3600L), FitField.u16(0, i), FitField.enum(1, listOf(1, 2, 11)[i % 3]), FitField.enum(2, i % 5),
                FitField.u32(3, 1000L + rnd.nextInt(0, 50_000)), FitField.u32(4, garmin(DAY_05 - i * 86_400L))
            )
        }
        return w.toByteArray()
    }

    fun totals(): ByteArray {
        val rnd = Random(SEED + 10)
        val w = FitWriter()
        val created = DAY_16 + hm(7, 3)
        fileId(w, type = 10, created = created, number = 0)
        fileCreator(w)
        repeat(12) { i ->
            val timer = 1800L + rnd.nextInt(0, 36_000)
            w.write(
                33,
                ts(created), FitField.u32(0, timer), FitField.u32(1, rnd.nextInt(0, 500_000).toLong()), FitField.u32(2, rnd.nextInt(0, 9000).toLong()),
                FitField.enum(3, listOf(0, 1, 2, 10, 11)[i % 5]), FitField.u32(4, timer + rnd.nextInt(0, 600)), FitField.u16(5, rnd.nextInt(1, 40)),
                FitField.u32(6, timer - rnd.nextInt(0, 600)), FitField.u16(254, i)
            )
        }
        return w.toByteArray()
    }

    // Shared records and encoding helpers

    private fun fileId(w: FitWriter, type: Int, created: Long, number: Int) {
        w.write(
            Mesg.FILE_ID,
            FitField.enum(0, type), FitField.u16(1, MANUFACTURER), FitField.u16(2, PRODUCT), FitField.u32z(3, SERIAL), FitField.u32(4, garmin(created)),
            FitField.u16(5, number)
        )
    }

    private fun fileCreator(w: FitWriter) = w.write(49, FitField.u16(0, SOFTWARE_VERSION))

    private fun deviceInfo(w: FitWriter, at: Long) {
        w.write(23, ts(at), FitField.u16(2, MANUFACTURER), FitField.u32z(3, SERIAL), FitField.u16(4, PRODUCT), FitField.u16(5, SOFTWARE_VERSION))
    }

    /** Seconds since the Garmin epoch for a Unix time. */
    fun garmin(unix: Long): Long = unix - GARMIN_EPOCH_UNIX_SECONDS

    private fun ts(unix: Long) = FitField.u32(253, garmin(unix))

    private fun hm(hours: Int, minutes: Int): Long = hours * 3600L + minutes * 60L

    private fun bytes(num: Int, values: List<Int>) = FitField.array(num, FitBaseType.BYTE, values.map { it.toLong() })
    private fun u8s(num: Int, values: List<Int>) = FitField.array(num, FitBaseType.UINT8, values.map { it.toLong() })
    private fun u16s(num: Int, values: List<Int>) = FitField.u16Array(num, values)
    private fun u32s(num: Int, values: List<Long>) = FitField.array(num, FitBaseType.UINT32, values)
}
