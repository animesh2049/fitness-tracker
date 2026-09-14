package com.animesh.workouttracker.domain.timer

enum class TimerKind { REST, GET_READY, WORK }

/**
 * A wall-clock countdown. Remaining time is derived from [endAtMillis] so it stays correct across
 * process death, Doze, and delayed ticks. [totalSeconds] is what the progress bar is relative to.
 */
data class Countdown(
    val kind: TimerKind,
    val totalSeconds: Int,
    val endAtMillis: Long,
    /** Free text shown under the timer, for example "Set 3 · 8 × 62.5 kg". */
    val label: String = "",
    /** Opaque tag the UI uses to know what the timer belongs to (set id, exercise id). */
    val tag: Long = 0
) {
    fun remainingSeconds(nowMillis: Long): Int = (((endAtMillis - nowMillis) + 999) / 1000).toInt().coerceAtLeast(0)
    fun isFinished(nowMillis: Long): Boolean = nowMillis >= endAtMillis
    fun elapsedSeconds(nowMillis: Long): Int = (totalSeconds - remainingSeconds(nowMillis)).coerceIn(0, totalSeconds)
    fun fraction(nowMillis: Long): Float = if (totalSeconds <= 0) 1f else (remainingSeconds(nowMillis).toFloat() / totalSeconds).coerceIn(0f, 1f)

    fun add(seconds: Int): Countdown = copy(endAtMillis = endAtMillis + seconds * 1000L, totalSeconds = totalSeconds + seconds)

    companion object {
        fun start(kind: TimerKind, seconds: Int, nowMillis: Long, label: String = "", tag: Long = 0) =
            Countdown(kind, seconds.coerceAtLeast(0), nowMillis + seconds.coerceAtLeast(0) * 1000L, label, tag)
    }
}
