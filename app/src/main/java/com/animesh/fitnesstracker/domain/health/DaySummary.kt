package com.animesh.fitnesstracker.domain.health

import com.animesh.fitnesstracker.data.model.HealthMinute
import com.animesh.fitnesstracker.data.model.IntensityMinute
import com.animesh.fitnesstracker.data.model.StressSample
import com.animesh.fitnesstracker.util.Dates
import java.time.ZoneId

/** A value with the Unix second it was observed at. */
data class TimedValue(val value: Int, val timestamp: Long)

/** Minutes spent in each stress band: rest 1..25, low 26..50, medium 51..75, high 76..100. */
data class StressBands(val restMinutes: Int, val lowMinutes: Int, val mediumMinutes: Int, val highMinutes: Int) {
    val totalMinutes: Int get() = restMinutes + lowMinutes + mediumMinutes + highMinutes
    val minutes: List<Int> get() = listOf(restMinutes, lowMinutes, mediumMinutes, highMinutes)

    /** Whole percent per band, in the same order as [minutes]; zeros when nothing was measured. */
    val percent: List<Int>
        get() {
            val total = totalMinutes
            return if (total == 0) listOf(0, 0, 0, 0) else minutes.map { Math.round(it * 100.0 / total).toInt() }
        }

    companion object {
        val EMPTY = StressBands(0, 0, 0, 0)
        val NAMES = listOf("Rest", "Low", "Medium", "High")
    }
}

/** Everything the Health tab shows for one day, computed from the stored samples. */
data class DaySummary(
    val epochDay: Long,
    val steps: Int,
    val distanceM: Double,
    val activeKcal: Int,
    val stepGoal: Int,
    val hrMin: Int?,
    val hrAvg: Int?,
    val hrMax: TimedValue?,
    val restingHr: Int?,
    val bodyBatteryCurrent: TimedValue?,
    val bodyBatteryHigh: TimedValue?,
    val bodyBatteryLow: TimedValue?,
    val stressAvg: Int?,
    val stressBands: StressBands,
    /** Week to date, moderate plus twice vigorous. */
    val intensityMinutes: Int,
    val intensityModerate: Int,
    val intensityVigorous: Int,
    val intensityTarget: Int,
    val wornMinutes: Int,
    /** Metres climbed and descended over the day (version 0.5). */
    val ascentM: Double = 0.0,
    val descentM: Double = 0.0,
    /** Resting calories so far: the watch's resting metabolic rate prorated through the current day; null without an RMR. */
    val restingKcal: Int? = null
) {
    /** Floors climbed, Garmin's rule of 3 m per floor. */
    val floors: Int get() = Floors.of(ascentM)
    val descentFloors: Int get() = Floors.of(descentM)
    /** Resting plus active calories, null when the resting side is unknown. */
    val totalKcal: Int? get() = restingKcal?.let { it + activeKcal }
    val stepProgress: Float get() = if (stepGoal <= 0) 0f else (steps.toFloat() / stepGoal).coerceIn(0f, 1f)
    val intensityProgress: Float get() = if (intensityTarget <= 0) 0f else (intensityMinutes.toFloat() / intensityTarget).coerceIn(0f, 1f)
    val hasData: Boolean get() = wornMinutes > 0 || bodyBatteryCurrent != null || stressAvg != null

    companion object {
        const val INTENSITY_TARGET = 150
        const val METRES_PER_FLOOR = Floors.METRES_PER_FLOOR

        /**
         * Resting calories for a day from a kcal/day rate: the whole rate for a finished day, the
         * elapsed fraction for the day [nowSeconds] falls in, nothing for a day still to come.
         */
        fun restingCalories(restingMetabolicRate: Int?, dayStart: Long, dayEnd: Long, nowSeconds: Long?): Int? {
            val rmr = restingMetabolicRate ?: return null
            if (nowSeconds == null || nowSeconds >= dayEnd) return rmr
            if (nowSeconds < dayStart) return 0
            return Math.round(rmr * (nowSeconds - dayStart).toDouble() / (dayEnd - dayStart)).toInt()
        }

        /** Stress sample cadence: a sample counts for the time to the next one, at most this long. */
        private const val STRESS_SAMPLE_SECONDS = 180L

        /** 0 rest, 1 low, 2 medium, 3 high; null for 0 or values outside 1..100. */
        fun stressBand(stress: Int): Int? = when (stress) {
            in 1..25 -> 0
            in 26..50 -> 1
            in 51..75 -> 2
            in 76..100 -> 3
            else -> null
        }

        /**
         * @param minutes the day's minute rows.
         * @param stress the day's stress and Body Battery samples.
         * @param restingHr the watch's resting heart rate for the day.
         * @param intensityWeek intensity rows from the Monday of the day's week onwards; rows outside
         *   Monday 00:00 to the end of the day are ignored so the total resets on Monday.
         * @param restingMetabolicRate the watch's resting metabolic rate in kcal/day (the latest RMR
         *   metric on or before the day), for resting and total calories.
         * @param nowSeconds the current time, so today's resting calories are prorated; null treats the day as finished.
         */
        fun compute(
            epochDay: Long,
            minutes: List<HealthMinute>,
            stress: List<StressSample>,
            restingHr: Int?,
            intensityWeek: List<IntensityMinute>,
            stepGoal: Int,
            zone: ZoneId = ZoneId.systemDefault(),
            restingMetabolicRate: Int? = null,
            nowSeconds: Long? = null
        ): DaySummary {
            val worn = minutes.filter { it.worn }
            val hrRows = worn.filter { (it.heartRate ?: 0) > 0 }
            val hrMaxRow = hrRows.maxByOrNull { it.heartRate!! }

            val battery = stress.filter { it.bodyBattery != null }.sortedBy { it.timestamp }
            val high = battery.maxByOrNull { it.bodyBattery!! }
            val low = battery.minByOrNull { it.bodyBattery!! }
            val current = battery.lastOrNull()

            val stressed = stress.sortedBy { it.timestamp }
            val bandMinutes = IntArray(4)
            var stressSum = 0L
            var stressCount = 0
            for ((i, s) in stressed.withIndex()) {
                val value = s.stress ?: continue
                val band = stressBand(value) ?: continue
                val gap = if (i + 1 < stressed.size) (stressed[i + 1].timestamp - s.timestamp) else STRESS_SAMPLE_SECONDS
                val seconds = gap.coerceIn(60L, STRESS_SAMPLE_SECONDS)
                bandMinutes[band] += (seconds / 60).toInt()
                stressSum += value
                stressCount++
            }

            val weekStart = Dates.dayStartSeconds(Dates.mondayOf(epochDay), zone)
            val dayStart = Dates.dayStartSeconds(epochDay, zone)
            val dayEnd = Dates.dayEndSeconds(epochDay, zone)
            val week = intensityWeek.filter { it.timestamp >= weekStart && it.timestamp < dayEnd }
            val moderate = week.sumOf { it.moderate }
            val vigorous = week.sumOf { it.vigorous }

            return DaySummary(
                epochDay = epochDay,
                steps = minutes.sumOf { it.steps },
                distanceM = minutes.sumOf { it.distanceM },
                activeKcal = minutes.sumOf { it.activeKcal },
                stepGoal = stepGoal,
                hrMin = hrRows.minOfOrNull { it.heartRate!! },
                hrAvg = if (hrRows.isEmpty()) null else Math.round(hrRows.map { it.heartRate!! }.average()).toInt(),
                hrMax = hrMaxRow?.let { TimedValue(it.heartRate!!, it.timestamp) },
                restingHr = restingHr,
                bodyBatteryCurrent = current?.let { TimedValue(it.bodyBattery!!, it.timestamp) },
                bodyBatteryHigh = high?.let { TimedValue(it.bodyBattery!!, it.timestamp) },
                bodyBatteryLow = low?.let { TimedValue(it.bodyBattery!!, it.timestamp) },
                stressAvg = if (stressCount == 0) null else Math.round(stressSum.toDouble() / stressCount).toInt(),
                stressBands = StressBands(bandMinutes[0], bandMinutes[1], bandMinutes[2], bandMinutes[3]),
                intensityMinutes = moderate + 2 * vigorous,
                intensityModerate = moderate,
                intensityVigorous = vigorous,
                intensityTarget = INTENSITY_TARGET,
                wornMinutes = worn.size,
                ascentM = minutes.sumOf { it.ascentM },
                descentM = minutes.sumOf { it.descentM },
                restingKcal = restingCalories(restingMetabolicRate, dayStart, dayEnd, nowSeconds)
            )
        }
    }
}
