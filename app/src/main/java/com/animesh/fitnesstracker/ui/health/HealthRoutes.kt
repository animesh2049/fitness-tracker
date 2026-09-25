package com.animesh.fitnesstracker.ui.health

/** Routes of the Health tab. The hub route must stay "health" (see TopLevel.Health). */
object HealthRoutes {
    const val HUB = "health"
    const val SLEEP = "health/sleep/{epochDay}"
    fun sleep(epochDay: Long) = "health/sleep/$epochDay"
    const val BODY_BATTERY = "health/body-battery/{epochDay}"
    fun bodyBattery(epochDay: Long) = "health/body-battery/$epochDay"
    const val TRENDS = "health/trends"
    const val ACTIVITIES = "health/activities"
    const val ACTIVITY = "health/activity/{activityId}"
    fun activity(id: Long) = "health/activity/$id"
    const val WATCH = "health/watch"
}
