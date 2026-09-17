package com.animesh.fitnesstracker.util

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Dates {
    fun todayEpochDay(): Long = LocalDate.now().toEpochDay()

    fun epochDayOf(millis: Long): Long =
        java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    fun date(epochDay: Long): LocalDate = LocalDate.ofEpochDay(epochDay)

    private val shortDay = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    private val full = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
    private val monthYear = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())

    fun shortDay(epochDay: Long): String = date(epochDay).format(shortDay)
    fun dayMonth(epochDay: Long): String = date(epochDay).format(dayMonth)
    fun full(epochDay: Long): String = date(epochDay).format(full)
    fun monthYear(date: LocalDate): String = date.format(monthYear)

    fun formatDuration(millis: Long): String {
        val minutes = (millis / 60_000).toInt()
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }

    fun mmss(totalSeconds: Int): String {
        val s = totalSeconds.coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    // Unix second helpers for the watch data, which the watch stamps in seconds.

    /** First second of the local day. */
    fun dayStartSeconds(epochDay: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toEpochSecond()

    /** First second of the next local day (exclusive end of [epochDay]). */
    fun dayEndSeconds(epochDay: Long, zone: ZoneId = ZoneId.systemDefault()): Long = dayStartSeconds(epochDay + 1, zone)

    fun epochDayOfSeconds(seconds: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        java.time.Instant.ofEpochSecond(seconds).atZone(zone).toLocalDate().toEpochDay()

    /** The Monday on or before the day (intensity minutes reset on Monday). */
    fun mondayOf(epochDay: Long): Long = LocalDate.ofEpochDay(epochDay).with(java.time.DayOfWeek.MONDAY).toEpochDay()

    /** Zone offset in seconds on the given day, for SQL that groups timestamps by local day. */
    fun zoneOffsetSeconds(epochDay: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).offset.totalSeconds.toLong()

    private val hhmm = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

    /** Local wall clock time of a Unix second, "06:10". */
    fun hhmm(seconds: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        java.time.Instant.ofEpochSecond(seconds).atZone(zone).format(hhmm)

    /** "7 h 12 min", "45 min". */
    fun hoursMinutes(totalSeconds: Int): String {
        val minutes = (totalSeconds.coerceAtLeast(0) / 60)
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }
}
