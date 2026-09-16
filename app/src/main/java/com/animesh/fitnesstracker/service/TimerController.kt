package com.animesh.fitnesstracker.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.animesh.fitnesstracker.domain.timer.Countdown
import com.animesh.fitnesstracker.domain.timer.TimerKind
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide owner of the single running countdown. The UI observes [countdown] and [now];
 * [TimerService] keeps the process alive, ticks [now], shows the notification and plays the cue.
 */
class TimerController(private val context: Context) {
    private val _countdown = MutableStateFlow<Countdown?>(null)
    val countdown: StateFlow<Countdown?> = _countdown.asStateFlow()

    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now.asStateFlow()

    private val _finished = MutableSharedFlow<Countdown>(extraBufferCapacity = 8)
    /** Emitted once when a countdown reaches zero (not when skipped). */
    val finished: SharedFlow<Countdown> = _finished.asSharedFlow()

    var soundEnabled: Boolean = true
    var vibrationEnabled: Boolean = true

    fun start(kind: TimerKind, seconds: Int, label: String = "", tag: Long = 0) {
        val nowMs = System.currentTimeMillis()
        _now.value = nowMs
        if (seconds <= 0) {
            _countdown.value = null
            return
        }
        _countdown.value = Countdown.start(kind, seconds, nowMs, label, tag)
        ContextCompat.startForegroundService(context, Intent(context, TimerService::class.java).setAction(TimerService.ACTION_START))
    }

    fun add(seconds: Int) {
        _countdown.value = _countdown.value?.add(seconds)
    }

    fun skip() {
        _countdown.value = null
    }

    /** Called by the service every tick. Returns the countdown that just finished, if any. */
    internal fun tick(nowMs: Long): Countdown? {
        _now.value = nowMs
        val c = _countdown.value ?: return null
        if (c.isFinished(nowMs)) {
            _countdown.value = null
            _finished.tryEmit(c)
            return c
        }
        return null
    }

    fun remainingSeconds(): Int = _countdown.value?.remainingSeconds(_now.value) ?: 0
}
