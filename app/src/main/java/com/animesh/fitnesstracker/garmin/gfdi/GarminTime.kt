package com.animesh.fitnesstracker.garmin.gfdi

import java.time.Instant
import java.time.ZoneId

/**
 * Garmin epoch arithmetic: Garmin timestamps are seconds since 1989-12-31T00:00:00Z (Unix 631065600).
 * [currentTime] computes the CURRENT_TIME_REQUEST reply: now in Garmin seconds, the total UTC offset
 * in seconds including DST, and the next two zone transitions as Garmin seconds (0 when the zone has none).
 */
object GarminTime {
    const val EPOCH_OFFSET = 631_065_600L

    fun toUnix(garmin: Long): Long = garmin + EPOCH_OFFSET
    fun fromUnix(unix: Long): Long = unix - EPOCH_OFFSET

    data class CurrentTime(val garminTime: Long, val tzOffsetSeconds: Int, val nextTransitionEnd: Long, val nextTransitionStart: Long)

    fun currentTime(nowMillis: Long, zone: ZoneId): CurrentTime {
        val now = Instant.ofEpochMilli(nowMillis)
        val rules = zone.rules
        val offset = rules.getOffset(now).totalSeconds
        val next = runCatching { rules.nextTransition(now) }.getOrNull()
        var start = 0L
        var end = 0L
        if (next != null) {
            start = fromUnix(next.instant.epochSecond)
            val after = runCatching { rules.nextTransition(next.instant) }.getOrNull()
            if (after != null) end = fromUnix(after.instant.epochSecond)
        }
        return CurrentTime(fromUnix(now.epochSecond), offset, end, start)
    }
}
