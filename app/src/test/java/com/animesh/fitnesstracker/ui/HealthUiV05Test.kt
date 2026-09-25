package com.animesh.fitnesstracker.ui

import com.animesh.fitnesstracker.domain.health.TrendMetric
import com.animesh.fitnesstracker.ui.health.ActivityDetailViewModel
import com.animesh.fitnesstracker.ui.health.BodyBatteryDayViewModel
import com.animesh.fitnesstracker.ui.health.HealthTodayViewModel
import com.animesh.fitnesstracker.ui.health.SecondaryMode
import com.animesh.fitnesstracker.ui.health.SleepViewModel
import com.animesh.fitnesstracker.ui.health.TrendsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure text helpers behind the version 0.5 Health screens. */
class HealthUiV05Test {
    private val minus = HealthTodayViewModel.MINUS

    @Test
    fun floorsSubtitleNamesTheClimbAndTheDescent() {
        assertEquals("12.4 m up · 2 down", HealthTodayViewModel.floorsSub(12.4, 2))
        assertEquals("3.1 m up", HealthTodayViewModel.floorsSub(3.1, 0))
        assertEquals("no climb recorded", HealthTodayViewModel.floorsSub(0.0, 0))
    }

    @Test
    fun caloriesSubtitleSplitsActiveAndResting() {
        assertEquals("active 412 · resting 1,693 so far", HealthTodayViewModel.caloriesSub(412, 1693, isToday = true))
        assertEquals("active 488 · resting 1,693", HealthTodayViewModel.caloriesSub(488, 1693, isToday = false))
        assertEquals("active kcal, no resting rate yet", HealthTodayViewModel.caloriesSub(412, null, isToday = true))
    }

    @Test
    fun bodyBatteryLineOmitsWhatIsUnknown() {
        assertEquals("+51 charged · ${minus}34 drained · overnight 37 \u2192 88", HealthTodayViewModel.batteryLine(51, 34, 37, 88))
        assertEquals("${minus}10 drained", HealthTodayViewModel.batteryLine(0, 10, null, null))
        assertEquals("overnight 41 \u2192 92", HealthTodayViewModel.batteryLine(0, 0, 41, 92))
        assertNull(HealthTodayViewModel.batteryLine(0, 0, null, 92))
    }

    @Test
    fun needLineSaysMetOrShortBy() {
        assertEquals("Need 8 h 40 m · short by 1 h 28 m", HealthTodayViewModel.needLine(520, met = false, shortByMin = 88))
        assertEquals("Need 8 h 00 m · need met", HealthTodayViewModel.needLine(480, met = true, shortByMin = 0))
    }

    @Test
    fun signedNumbersUseTheUnicodeMinus() {
        assertEquals("+3", ActivityDetailViewModel.signedInt(3))
        assertEquals("${minus}2", ActivityDetailViewModel.signedInt(-2))
        assertEquals("0", ActivityDetailViewModel.signedInt(0))
        assertEquals("+51", BodyBatteryDayViewModel.signed(51))
        assertEquals("${minus}10", BodyBatteryDayViewModel.signed(-10))
        assertEquals("+51 overnight", SleepViewModel.batteryGainSub(51))
        assertEquals("no change overnight", SleepViewModel.batteryGainSub(0))
        assertEquals("${minus}0.4\u00b0", SleepViewModel.skinTempValue(-0.4))
        assertEquals("+0.3\u00b0", SleepViewModel.skinTempValue(0.25))
    }

    @Test
    fun reserveWordsFollowGarminsBands() {
        assertEquals("high reserve", BodyBatteryDayViewModel.reserveWord(88))
        assertEquals("medium reserve", BodyBatteryDayViewModel.reserveWord(51))
        assertEquals("low reserve", BodyBatteryDayViewModel.reserveWord(30))
        assertEquals("very low reserve", BodyBatteryDayViewModel.reserveWord(12))
    }

    @Test
    fun trendFormattingCoversTheNewMetrics() {
        assertEquals("2,105", TrendsViewModel.format(TrendMetric.CALORIES, 2105.4))
        assertEquals("7 h 12 m", TrendsViewModel.format(TrendMetric.SLEEP_NEED, 432.0))
        assertEquals("4", TrendsViewModel.format(TrendMetric.FLOORS, 4.2))
        assertEquals("2.1k", TrendsViewModel.gridLabel(TrendMetric.CALORIES, 2105.0))
        assertEquals("850", TrendsViewModel.gridLabel(TrendMetric.CALORIES, 850.0))
        assertEquals("7.2 h", TrendsViewModel.gridLabel(TrendMetric.SLEEP_NEED, 432.0))
        assertEquals(SecondaryMode.STACK, TrendsViewModel.secondaryMode(TrendMetric.CALORIES))
        assertEquals(SecondaryMode.MARKER, TrendsViewModel.secondaryMode(TrendMetric.SLEEP_NEED))
        assertEquals(SecondaryMode.NONE, TrendsViewModel.secondaryMode(TrendMetric.FLOORS))
        assertEquals(3, listOf(TrendMetric.FLOORS, TrendMetric.CALORIES, TrendMetric.SLEEP_NEED).count { TrendsViewModel.INFO.containsKey(it) })
    }
}
