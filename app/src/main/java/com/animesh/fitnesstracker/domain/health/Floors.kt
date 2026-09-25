package com.animesh.fitnesstracker.domain.health

/** Garmin's floors: one floor per 3 m of barometric ascent, rounded down. */
object Floors {
    const val METRES_PER_FLOOR = 3.0

    fun of(metres: Double): Int = if (metres <= 0.0) 0 else (metres / METRES_PER_FLOOR).toInt()
}
