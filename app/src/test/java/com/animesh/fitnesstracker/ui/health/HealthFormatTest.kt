package com.animesh.fitnesstracker.ui.health

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthFormatTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private fun seconds(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).toEpochSecond(ZoneOffset.UTC)
    private val wed16Sep = LocalDate.of(2026, 9, 16).toEpochDay()

    @Test
    fun durations() {
        assertEquals("7 h 12 m", HealthFormat.duration(7 * 3600 + 12 * 60))
        assertEquals("1 h 04 m", HealthFormat.duration(64 * 60))
        assertEquals("58 min", HealthFormat.duration(58 * 60 + 30))
        assertEquals("0 min", HealthFormat.duration(0))
        assertEquals("0 min", HealthFormat.duration(-5))
        assertEquals("58 min", HealthFormat.durationMinutes(58))
        assertEquals("0 h 30 m", HealthFormat.hoursMinutes(30 * 60))
        assertEquals("6 h 57 m", HealthFormat.hoursMinutes(417 * 60))
        assertEquals("8.6 h", HealthFormat.hoursShort(516))
        assertEquals("14 h", HealthFormat.hoursRounded(14 * 60 + 10))
        assertEquals("45 min", HealthFormat.hoursRounded(45))
        assertEquals("19:42", HealthFormat.clockDuration(19 * 60 + 42))
        assertEquals("1:04:12", HealthFormat.clockDuration(3600 + 4 * 60 + 12))
    }

    @Test
    fun numbers() {
        assertEquals("8,412", HealthFormat.thousands(8412))
        assertEquals("412", HealthFormat.thousands(412))
        assertEquals("1,284", HealthFormat.thousands(1284))
        assertEquals("6.4 km", HealthFormat.km(6412.0))
        assertEquals("6.1", HealthFormat.kmValue(6100.0))
        assertEquals("14.2", HealthFormat.oneDecimal(14.24))
        assertEquals("54", HealthFormat.oneDecimal(54.0))
        assertEquals("7.3", HealthFormat.oneDecimal(7.25))
        assertEquals("2.0", HealthFormat.fixedOneDecimal(2.0))
        assertEquals("84% of 10,000", HealthFormat.percentOfGoal(8412, 10000))
        assertEquals("129% of 10,000", HealthFormat.percentOfGoal(12904, 10000))
        assertEquals("0% of 0", HealthFormat.percentOfGoal(100, 0))
        assertEquals("9:42", HealthFormat.pace(582))
        assertEquals("10:05", HealthFormat.pace(605))
        assertEquals("+1,204", HealthFormat.signed(1204.0) { HealthFormat.thousands(it.toInt()) })
        assertEquals("-1.5", HealthFormat.signed(-1.5) { HealthFormat.oneDecimal(it) })
    }

    @Test
    fun timesAndDates() {
        assertEquals("07:41", HealthFormat.timeOfDay(seconds(2026, 9, 16, 7, 41), utc))
        assertEquals("WED 16 SEP", HealthFormat.eyebrowDate(wed16Sep))
        assertEquals("Wednesday", HealthFormat.weekdayName(wed16Sep))
        assertEquals("Wednesday 16 Sep", HealthFormat.dayLabel(wed16Sep))
        assertEquals("16 Sep", HealthFormat.dayMonth(wed16Sep))
        assertEquals("16 Sep 2026", HealthFormat.dayMonthYear(wed16Sep))
        assertEquals("Wed 16 Sep", HealthFormat.shortDay(wed16Sep))
        assertEquals("September 2026", HealthFormat.monthYear(YearMonth.of(2026, 9)))
        assertEquals("Sep", HealthFormat.monthName(wed16Sep))
        assertEquals("Sep 2026", HealthFormat.monthShortYear(wed16Sep))
    }

    @Test
    fun nightAndWindowLabels() {
        assertEquals("Tue 15 to Wed 16 Sep", HealthFormat.nightLabel(wed16Sep))
        assertEquals("Wed 30 Sep to Thu 1 Oct", HealthFormat.nightLabel(LocalDate.of(2026, 10, 1).toEpochDay()))
        assertEquals("Tue 23:41 to Wed 06:53", HealthFormat.window(seconds(2026, 9, 15, 23, 41), seconds(2026, 9, 16, 6, 53), utc))
        assertEquals("23:41 to 06:53", HealthFormat.clockWindow(seconds(2026, 9, 15, 23, 41), seconds(2026, 9, 16, 6, 53), utc))
    }

    @Test
    fun ranges() {
        val sep10 = LocalDate.of(2026, 9, 10).toEpochDay()
        assertEquals("10 Sep to 16 Sep", HealthFormat.dayRange(sep10, wed16Sep))
        assertEquals("28 Dec 2025 to 3 Jan 2026", HealthFormat.dayRange(LocalDate.of(2025, 12, 28).toEpochDay(), LocalDate.of(2026, 1, 3).toEpochDay()))
        assertEquals("Apr to Sep 2026", HealthFormat.monthRange(LocalDate.of(2026, 4, 1).toEpochDay(), wed16Sep))
        assertEquals("Oct 2025 to Sep 2026", HealthFormat.monthRange(LocalDate.of(2025, 10, 1).toEpochDay(), wed16Sep))
    }

    @Test
    fun relativeSync() {
        val now = 1_700_000_000_000L
        assertEquals("Never synced", HealthFormat.relativeSync(null, now))
        assertEquals("Synced just now", HealthFormat.relativeSync(now - 20_000, now))
        assertEquals("Synced 12 min ago", HealthFormat.relativeSync(now - 12 * 60_000, now))
        assertEquals("Synced 3 h ago", HealthFormat.relativeSync(now - 3 * 3_600_000 - 5_000, now))
        assertEquals("Synced yesterday", HealthFormat.relativeSync(now - 30 * 3_600_000, now))
        assertEquals("Synced 5 days ago", HealthFormat.relativeSync(now - 5 * 24 * 3_600_000L - 60_000, now))
        assertEquals("Synced just now", HealthFormat.relativeSync(now + 60_000, now))
    }

    @Test
    fun watchLineAndWords() {
        assertEquals("Forerunner 570 · 71%", HealthFormat.watchLine("Forerunner 570", 71))
        assertEquals("Forerunner 570", HealthFormat.watchLine("Forerunner 570", null))
        assertEquals("No benefit", HealthFormat.trainingEffectWord(0.8))
        assertEquals("Minor benefit", HealthFormat.trainingEffectWord(1.6))
        assertEquals("Maintaining", HealthFormat.trainingEffectWord(2.4))
        assertEquals("Improving", HealthFormat.trainingEffectWord(3.1))
        assertEquals("Highly improving", HealthFormat.trainingEffectWord(4.2))
        assertEquals("Overreaching", HealthFormat.trainingEffectWord(5.0))
    }

    @Test
    fun zonesAndAxis() {
        assertEquals(listOf("98 to 117", "118 to 137", "138 to 156", "157 to 176", "177+"), HealthFormat.zoneRanges(listOf(98, 118, 138, 157, 177)))
        assertEquals(listOf("0", "15", "30", "45", "58 min"), HealthFormat.elapsedAxis(58 * 60).map { it.text })
        assertEquals(listOf("0", "16", "32", "48", "64 min"), HealthFormat.elapsedAxis(64 * 60).map { it.text })
        val axis = HealthFormat.elapsedAxis(64 * 60)
        assertEquals(0f, axis[0].fraction, 0.0001f)
        assertEquals(0.5f, axis[2].fraction, 0.0001f)
        assertEquals(1f, axis[4].fraction, 0.0001f)
    }
}
