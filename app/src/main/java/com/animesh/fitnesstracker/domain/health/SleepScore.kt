package com.animesh.fitnesstracker.domain.health

import com.animesh.fitnesstracker.data.model.SleepNight
import java.util.Locale

/** One line of the sleep score breakdown: the watch's sub-score, its word band and an optional detail ("2 times", "19 m"). */
data class ScoreRow(val name: String, val score: Int, val band: String, val detail: String?)

/** The sleep score breakdown the watch writes in sleep_assessment, in a fixed reading order. */
object SleepScoreBreakdown {
    fun rows(night: SleepNight): List<ScoreRow> {
        val rows = ArrayList<ScoreRow>()
        fun add(name: String, score: Int?, detail: String? = null) {
            if (score != null) rows += ScoreRow(name, score, SleepNights.scoreWord(score), detail)
        }
        add("Duration", night.durationScore)
        add("Quality", night.qualityScore)
        add("Deep", night.deepScore)
        add("Light", night.lightScore)
        add("REM", night.remScore)
        add("Restlessness", night.restlessnessScore, night.restlessMoments?.let { "$it moments" })
        add("Interruptions", night.interruptionsScore)
        add("Awake time", night.awakeScore, night.awakeSeconds.takeIf { it > 0 }?.let { awakeDuration(it) })
        add("Awakenings", night.awakeningsScore, night.awakeningsCount?.let { if (it == 1) "1 time" else "$it times" })
        add("Recovery", night.recoveryScore)
        return rows
    }

    /** "19 m" below an hour, "1 h 05 m" from one hour up. */
    fun awakeDuration(seconds: Int): String {
        val minutes = seconds.coerceAtLeast(0) / 60
        return if (minutes < 60) "$minutes m" else "${minutes / 60} h ${"%02d".format(Locale.ENGLISH, minutes % 60)} m"
    }
}

/** Sleep Coach's need for a night against what was slept, all in minutes. */
data class SleepNeed(val needMin: Int, val baselineMin: Int?, val sleptMin: Int) {
    val shortByMin: Int get() = (needMin - sleptMin).coerceAtLeast(0)
    val met: Boolean get() = sleptMin >= needMin
    val fraction: Float get() = if (needMin <= 0) 1f else (sleptMin.toFloat() / needMin).coerceIn(0f, 1f)
}

object SleepNeeds {
    /** Null when the watch gave no need for the night. Slept time is the asleep total, else the night's length. */
    fun of(night: SleepNight): SleepNeed? {
        val need = night.sleepNeedMin ?: return null
        val slept = if (night.asleepSeconds > 0) night.asleepSeconds / 60 else night.durationSeconds / 60
        return SleepNeed(need, night.sleepBaselineMin, slept)
    }
}
