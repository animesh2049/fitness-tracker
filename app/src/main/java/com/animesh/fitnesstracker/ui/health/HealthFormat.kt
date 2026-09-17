package com.animesh.fitnesstracker.ui.health

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** One x axis label and where it sits, 0 at the left edge of the plot and 1 at the right. */
data class AxisLabel(val fraction: Float, val text: String)

/**
 * Pure string helpers for the Health tab. Everything here is deterministic (English labels,
 * explicit zone parameters) so the unit tests do not depend on the device locale.
 */
object HealthFormat {
    private val locale = Locale.ENGLISH
    private val eyebrowDay = DateTimeFormatter.ofPattern("EEE d MMM", locale)
    private val weekday = DateTimeFormatter.ofPattern("EEEE", locale)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", locale)
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
    private val month = DateTimeFormatter.ofPattern("MMM", locale)
    private val monthYear = DateTimeFormatter.ofPattern("MMMM yyyy", locale)
    private val monthShortYear = DateTimeFormatter.ofPattern("MMM yyyy", locale)
    private val shortWeekday = DateTimeFormatter.ofPattern("EEE", locale)
    private val clock = DateTimeFormatter.ofPattern("HH:mm", locale)

    // Durations

    /** "7 h 12 m" from one hour up, "58 min" below; "0 min" for nothing. Minutes are zero padded after an hour. */
    fun duration(seconds: Int): String {
        val minutes = seconds.coerceAtLeast(0) / 60
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${"%02d".format(locale, minutes % 60)} m"
    }

    fun durationMinutes(minutes: Int): String = duration(minutes * 60)

    /** Always with hours: "6 h 57 m", "0 h 30 m". Used for month totals. */
    fun hoursMinutes(seconds: Int): String {
        val minutes = seconds.coerceAtLeast(0) / 60
        return "${minutes / 60} h ${"%02d".format(locale, minutes % 60)} m"
    }

    /** Hours with one decimal, "8.6 h". */
    fun hoursShort(minutes: Int): String = "${oneDecimal(minutes / 60.0)} h"

    /** Whole hours for recovery time, "14 h"; minutes below an hour. */
    fun hoursRounded(minutes: Int): String = if (minutes < 60) "$minutes min" else "${(minutes / 60.0).roundToInt()} h"

    /** "19:42" or "1:04:12" for cumulative lap times. */
    fun clockDuration(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(locale, h, m, sec) else "%d:%02d".format(locale, m, sec)
    }

    // Numbers

    /** "8,412". */
    fun thousands(value: Int): String = "%,d".format(locale, value)

    /** "6.4 km" from metres. */
    fun km(metres: Double): String = "${kmValue(metres)} km"

    /** "6.4" without the unit; always one decimal so "6.0 km" does not read as a rounded guess. */
    fun kmValue(metres: Double): String = "%.1f".format(locale, metres / 1000.0)

    /** One decimal, no trailing ".0": "14.2", "54". */
    fun oneDecimal(value: Double): String {
        val rounded = (value * 10).roundToInt() / 10.0
        return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else "%.1f".format(locale, rounded)
    }

    /** Always one decimal, "2.0", for training effects. */
    fun fixedOneDecimal(value: Double): String = "%.1f".format(locale, value)

    /** "84% of 10,000". */
    fun percentOfGoal(value: Int, goal: Int): String {
        val pct = if (goal <= 0) 0 else (value * 100.0 / goal).roundToInt()
        return "$pct% of ${thousands(goal)}"
    }

    /** "9:42" from seconds per kilometre. */
    fun pace(secondsPerKm: Int): String = "%d:%02d".format(locale, secondsPerKm / 60, secondsPerKm % 60)

    /** "+1,204" or "-12" with the metric's own value formatting applied to the magnitude. */
    fun signed(value: Double, format: (Double) -> String): String =
        (if (value < 0) "-" else "+") + format(abs(value))

    // Times and dates

    /** "07:41" local wall clock of a Unix second. */
    fun timeOfDay(seconds: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochSecond(seconds).atZone(zone).format(clock)

    /** "WED 16 SEP" style date piece for header eyebrows (callers add the section prefix). */
    fun eyebrowDate(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(eyebrowDay).uppercase(locale)

    /** "Wednesday". */
    fun weekdayName(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(weekday)

    /** "Wednesday 16 Sep" for the day switcher pill. */
    fun dayLabel(epochDay: Long): String {
        val d = LocalDate.ofEpochDay(epochDay)
        return "${d.format(weekday)} ${d.format(dayMonth)}"
    }

    /** "16 Sep". */
    fun dayMonth(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(dayMonth)

    /** "16 Sep 2026". */
    fun dayMonthYear(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(dayMonthYear)

    /** "Wed 16 Sep". */
    fun shortDay(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(eyebrowDay)

    /** "September 2026". */
    fun monthYear(month: YearMonth): String = month.atDay(1).format(monthYear)

    /** "Sep 2026". */
    fun monthShortYear(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(monthShortYear)

    /** "Sep". */
    fun monthName(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(month)

    /** "Tue 15 to Wed 16 Sep": the night that ends on [endDay]. */
    fun nightLabel(endDay: Long): String {
        val end = LocalDate.ofEpochDay(endDay)
        val start = end.minusDays(1)
        val startText = if (start.month == end.month) "${start.format(shortWeekday)} ${start.dayOfMonth}" else start.format(eyebrowDay)
        return "$startText to ${end.format(eyebrowDay)}"
    }

    /** "Tue 23:41 to Wed 06:53". */
    fun window(startSeconds: Long, endSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val s = Instant.ofEpochSecond(startSeconds).atZone(zone)
        val e = Instant.ofEpochSecond(endSeconds).atZone(zone)
        return "${s.format(shortWeekday)} ${s.format(clock)} to ${e.format(shortWeekday)} ${e.format(clock)}"
    }

    /** "23:41 to 06:53". */
    fun clockWindow(startSeconds: Long, endSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        "${timeOfDay(startSeconds, zone)} to ${timeOfDay(endSeconds, zone)}"

    /** "10 Sep to 16 Sep", or "10 Sep 2025 to 16 Sep 2026" when the years differ. */
    fun dayRange(fromDay: Long, toDay: Long): String {
        val a = LocalDate.ofEpochDay(fromDay)
        val b = LocalDate.ofEpochDay(toDay)
        return if (a.year == b.year) "${a.format(dayMonth)} to ${b.format(dayMonth)}" else "${a.format(dayMonthYear)} to ${b.format(dayMonthYear)}"
    }

    /** "Apr to Sep 2026", or "Oct 2025 to Sep 2026" across a year boundary. */
    fun monthRange(fromDay: Long, toDay: Long): String {
        val a = LocalDate.ofEpochDay(fromDay)
        val b = LocalDate.ofEpochDay(toDay)
        return if (a.year == b.year) "${a.format(month)} to ${b.format(monthShortYear)}" else "${a.format(monthShortYear)} to ${b.format(monthShortYear)}"
    }

    /**
     * "Synced just now", "Synced 12 min ago", "Synced 3 h ago", "Synced yesterday", "Synced 5 days ago",
     * or "Never synced" without a timestamp.
     */
    fun relativeSync(lastSyncMillis: Long?, nowMillis: Long): String {
        if (lastSyncMillis == null) return "Never synced"
        val minutes = ((nowMillis - lastSyncMillis) / 60_000L).coerceAtLeast(0)
        return when {
            minutes < 1 -> "Synced just now"
            minutes < 60 -> "Synced $minutes min ago"
            minutes < 24 * 60 -> "Synced ${minutes / 60} h ago"
            minutes < 48 * 60 -> "Synced yesterday"
            else -> "Synced ${minutes / (24 * 60)} days ago"
        }
    }

    /** "Forerunner 570 · 71%", or just the name without a battery reading. */
    fun watchLine(name: String, batteryPercent: Int?): String = if (batteryPercent == null) name else "$name · $batteryPercent%"

    /** Garmin's words for a training effect value. */
    fun trainingEffectWord(effect: Double): String = when {
        effect < 1.0 -> "No benefit"
        effect < 2.0 -> "Minor benefit"
        effect < 3.0 -> "Maintaining"
        effect < 4.0 -> "Improving"
        effect < 5.0 -> "Highly improving"
        else -> "Overreaching"
    }

    /** Zone ranges from the lower bounds of zones 1 to 5: "98 to 117", the last one "177+". */
    fun zoneRanges(lowerBounds: List<Int>): List<String> = lowerBounds.mapIndexed { i, low ->
        if (i == lowerBounds.lastIndex) "$low+" else "$low to ${lowerBounds[i + 1] - 1}"
    }

    /**
     * Elapsed axis labels: four marks one rounded quarter apart plus the total with its unit, each
     * with its position as a fraction of the total: "0", "15", "30", "45", "58 min" for 58 minutes.
     */
    fun elapsedAxis(totalSeconds: Int): List<AxisLabel> {
        val total = totalSeconds.coerceAtLeast(60) / 60.0
        val step = (total / 4).roundToInt().coerceAtLeast(1)
        val marks = (0..3).map { i -> AxisLabel((i * step / total).toFloat().coerceIn(0f, 1f), (i * step).toString()) }
        return marks + AxisLabel(1f, "${total.roundToInt()} min")
    }
}
