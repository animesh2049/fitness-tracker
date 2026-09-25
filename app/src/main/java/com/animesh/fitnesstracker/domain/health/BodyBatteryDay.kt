package com.animesh.fitnesstracker.domain.health

import com.animesh.fitnesstracker.data.model.Activity
import com.animesh.fitnesstracker.data.model.BodyBatteryEvent
import com.animesh.fitnesstracker.data.model.BodyBatteryKind
import com.animesh.fitnesstracker.data.model.SleepNight
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One event of the Body Battery day screen: the row's title ("Sleep", "Workout · Upper A") and time span line. */
data class BodyBatteryEventRow(val event: BodyBatteryEvent, val title: String, val detail: String)

/** The Body Battery day screen's model: the event list, what charged and drained in total, and the night's start and end. */
data class BodyBatteryDay(
    val rows: List<BodyBatteryEventRow>,
    val chargedTotal: Int,
    val drainedTotal: Int,
    val overnightStart: Int?,
    val overnightEnd: Int?
) {
    val overnightGain: Int? get() = if (overnightStart != null && overnightEnd != null) overnightEnd - overnightStart else null
    val hasEvents: Boolean get() = rows.isNotEmpty()

    companion object {
        val EMPTY = BodyBatteryDay(emptyList(), 0, 0, null, null)
    }
}

/**
 * Turns the watch's Body Battery events (FIT message 407) into the day screen's rows. Kind
 * labels follow [BodyBatteryKind], which is provisional until confirmed against the watch's own
 * Body Battery glance; unknown kinds only say whether they charged or drained.
 */
object BodyBatteryEvents {
    private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

    /** "Sleep", "Workout", "Not worn", or for an unknown kind "Charged" or "Drained" by the sign of the delta. */
    fun kindLabel(event: BodyBatteryEvent): String = when (event.kind) {
        BodyBatteryKind.SLEEP -> "Sleep"
        BodyBatteryKind.ACTIVITY -> "Workout"
        BodyBatteryKind.UNMEASURED -> "Not worn"
        BodyBatteryKind.UNKNOWN -> if (event.delta >= 0) "Charged" else "Drained"
    }

    /**
     * @param events the day's events (those that started on the day), in any order.
     * @param activities the day's recorded activities; an activity event that overlaps one by at
     *   least half of the event's own minutes is titled "Workout · name".
     * @param night the night that ended on the day, for the overnight start and end values.
     */
    fun day(events: List<BodyBatteryEvent>, activities: List<Activity>, night: SleepNight?, zone: ZoneId = ZoneId.systemDefault()): BodyBatteryDay {
        val sorted = events.sortedBy { it.startTimestamp }
        val rows = sorted.map { e ->
            val activity = if (e.kind == BodyBatteryKind.ACTIVITY) matchingActivity(e, activities) else null
            val title = if (activity != null) "Workout · ${activity.name}" else kindLabel(e)
            BodyBatteryEventRow(e, title, "${time(e.startTimestamp, zone)} to ${time(e.endTimestamp, zone)} · ${duration(e.minutes * 60)}")
        }
        return BodyBatteryDay(
            rows = rows,
            chargedTotal = sorted.filter { it.delta > 0 }.sumOf { it.delta },
            drainedTotal = sorted.filter { it.delta < 0 }.sumOf { -it.delta },
            overnightStart = night?.bodyBatteryStart,
            overnightEnd = night?.bodyBatteryEnd
        )
    }

    /** The activity the event overlaps most, if that overlap covers at least half of the event. */
    fun matchingActivity(event: BodyBatteryEvent, activities: List<Activity>): Activity? {
        val needed = event.minutes * 60L / 2
        return activities
            .map { a -> a to overlapSeconds(event, a) }
            .filter { (_, overlap) -> overlap > 0 && overlap >= needed }
            .maxByOrNull { (_, overlap) -> overlap }
            ?.first
    }

    private fun overlapSeconds(event: BodyBatteryEvent, activity: Activity): Long =
        (minOf(event.endTimestamp, activity.endTimestamp) - maxOf(event.startTimestamp, activity.startTimestamp)).coerceAtLeast(0L)

    private fun time(seconds: Long, zone: ZoneId): String = Instant.ofEpochSecond(seconds).atZone(zone).format(clock)

    /** "58 min" below an hour, "7 h 12 m" from one hour up, the Health tab's duration rule. */
    fun duration(seconds: Int): String {
        val minutes = seconds.coerceAtLeast(0) / 60
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${"%02d".format(Locale.ENGLISH, minutes % 60)} m"
    }
}
