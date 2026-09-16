package com.animesh.fitnesstracker.util

import com.animesh.fitnesstracker.data.model.WeightUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

object Weights {
    private const val LB_PER_KG = 2.2046226218

    fun toDisplay(kg: Double, unit: WeightUnit): Double = if (unit == WeightUnit.KG) kg else kg * LB_PER_KG
    fun toKg(display: Double, unit: WeightUnit): Double = if (unit == WeightUnit.KG) display else display / LB_PER_KG

    fun roundTo(value: Double, increment: Double): Double {
        if (increment <= 0) return value
        return round(value / increment) * increment
    }

    /** "60", "62.5", "22.25" without trailing zeros. */
    fun format(value: Double): String {
        val r = round(value * 100) / 100
        return if (abs(r - round(r)) < 1e-9) round(r).toLong().toString() else String.format(Locale.US, "%.2f", r).trimEnd('0').trimEnd('.')
    }

    fun formatWithUnit(kg: Double, unit: WeightUnit): String =
        format(toDisplay(kg, unit)) + " " + unit.name.lowercase()

    fun formatVolume(kg: Double, unit: WeightUnit): String {
        val v = toDisplay(kg, unit)
        return if (v >= 1000) String.format(Locale.US, "%.1f t", v / 1000) else format(v) + " " + unit.name.lowercase()
    }
}
