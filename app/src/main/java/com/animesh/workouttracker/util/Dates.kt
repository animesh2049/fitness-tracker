package com.animesh.workouttracker.util

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
}
