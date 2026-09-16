package com.animesh.fitnesstracker.domain.diet

import com.animesh.fitnesstracker.data.model.Meal
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Calories and macros for some number of servings. */
data class Macros(val kcal: Double, val proteinG: Double, val carbsG: Double, val fatG: Double) {
    fun plus(other: Macros): Macros = Macros(kcal + other.kcal, proteinG + other.proteinG, carbsG + other.carbsG, fatG + other.fatG)

    /** "560 kcal · 20 g protein". */
    fun summary(): String = "${kcal.roundToInt()} kcal · ${proteinG.roundToInt()} g protein"
}

/** Scales per-serving recipe quantities and macros by a serving multiplier (requirement FR39). */
object Scaling {

    /** [amountPerServing] times [servings], rounded to the nearest quarter. */
    fun scale(amountPerServing: Double, servings: Double): Double = (amountPerServing * servings * 4).roundToLong() / 4.0

    /** "0.5", "1", "1.5", "2.25": at most two decimals, no trailing zeros, leading zero kept. */
    fun format(amount: Double): String = BigDecimal(amount).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /** "0.5 cup", or just the number when [unit] is blank. */
    fun formatQuantity(amount: Double, unit: String): String {
        val u = unit.trim()
        return if (u.isEmpty()) format(amount) else "${format(amount)} $u"
    }

    /** The meal's macros for [servings] servings, or null when the meal has no complete macros. */
    fun macros(meal: Meal, servings: Double): Macros? {
        if (!meal.hasMacros) return null
        return Macros(meal.kcal!! * servings, meal.proteinG!! * servings, meal.carbsG!! * servings, meal.fatG!! * servings)
    }
}
