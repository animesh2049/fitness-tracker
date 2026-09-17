package com.animesh.fitnesstracker.domain.health

import com.animesh.fitnesstracker.data.model.SleepNight
import com.animesh.fitnesstracker.data.model.SleepStage

/** Seconds per stage for one night. Percentages are of the whole night including awake time. */
data class StageTotals(val deepSeconds: Int, val lightSeconds: Int, val remSeconds: Int, val awakeSeconds: Int) {
    val asleepSeconds: Int get() = deepSeconds + lightSeconds + remSeconds
    val totalSeconds: Int get() = asleepSeconds + awakeSeconds

    /** Seconds for a FIT stage number (1 awake, 2 light, 3 deep, 4 REM); 0 for anything else. */
    fun seconds(stage: Int): Int = when (stage) {
        SleepStage.AWAKE -> awakeSeconds
        SleepStage.LIGHT -> lightSeconds
        SleepStage.DEEP -> deepSeconds
        SleepStage.REM -> remSeconds
        else -> 0
    }

    /** Whole percent of the night for a stage, 0 when the night is empty. */
    fun percent(stage: Int): Int = if (totalSeconds == 0) 0 else Math.round(seconds(stage) * 100.0 / totalSeconds).toInt()

    /** True when at least one stage is real sleep, the rule the importer uses to keep a night. */
    val hasSleep: Boolean get() = asleepSeconds > 0

    companion object {
        val EMPTY = StageTotals(0, 0, 0, 0)
    }
}

/** One bar of the hypnogram. */
data class HypnogramSegment(val startTimestamp: Long, val endTimestamp: Long, val stage: Int) {
    val seconds: Int get() = (endTimestamp - startTimestamp).toInt()
}

/**
 * Night selection and stage arithmetic for the sleep screen and the Health tab's sleep card.
 * Stages come pre-classified from the watch; each row's [SleepStage.endTimestamp] is the upper
 * bound of that stage and its start is the previous row's end.
 */
object SleepNights {
    /** Stage order of the hypnogram lanes, top to bottom: awake, REM, light, deep. */
    val LANE_ORDER = listOf(SleepStage.AWAKE, SleepStage.REM, SleepStage.LIGHT, SleepStage.DEEP)
    val STAGE_NAMES = mapOf(SleepStage.AWAKE to "Awake", SleepStage.REM to "REM", SleepStage.LIGHT to "Light", SleepStage.DEEP to "Deep")

    /**
     * The night shown for a day: the one that ended on that day. When there is none (the watch
     * has not synced this morning yet) the most recent earlier night is offered only if it
     * ended the day before, so an old night never masquerades as last night.
     */
    fun nightFor(day: Long, nights: List<SleepNight>): SleepNight? =
        nights.firstOrNull { it.epochDay == day } ?: nights.filter { it.epochDay == day - 1 }.maxByOrNull { it.endTimestamp }

    fun totals(night: SleepNight): StageTotals =
        StageTotals(night.deepSeconds, night.lightSeconds, night.remSeconds, night.awakeSeconds)

    /** Sums stage rows; unmeasurable (0) time is not counted anywhere. */
    fun totalsFromStages(stages: List<SleepStage>): StageTotals {
        var deep = 0; var light = 0; var rem = 0; var awake = 0
        for (s in stages) {
            when (s.stage) {
                SleepStage.DEEP -> deep += s.seconds
                SleepStage.LIGHT -> light += s.seconds
                SleepStage.REM -> rem += s.seconds
                SleepStage.AWAKE -> awake += s.seconds
            }
        }
        return StageTotals(deep, light, rem, awake)
    }

    /**
     * Segments for the hypnogram: stage rows sorted, clipped to [start, end], with consecutive
     * rows of the same stage merged and empty or unmeasurable pieces dropped.
     */
    fun hypnogram(stages: List<SleepStage>, start: Long? = null, end: Long? = null): List<HypnogramSegment> {
        val result = ArrayList<HypnogramSegment>()
        for (s in stages.sortedBy { it.endTimestamp }) {
            if (s.stage == SleepStage.UNMEASURABLE) continue
            val from = if (start != null) maxOf(s.startTimestamp, start) else s.startTimestamp
            val to = if (end != null) minOf(s.endTimestamp, end) else s.endTimestamp
            if (to <= from) continue
            val last = result.lastOrNull()
            if (last != null && last.stage == s.stage && last.endTimestamp == from) {
                result[result.size - 1] = last.copy(endTimestamp = to)
            } else {
                result += HypnogramSegment(from, to, s.stage)
            }
        }
        return result
    }

    /** Words the design uses for the sleep score. */
    fun scoreWord(score: Int?): String = when {
        score == null -> ""
        score >= 90 -> "Excellent"
        score >= 80 -> "Good"
        score >= 60 -> "Fair"
        else -> "Poor"
    }
}
