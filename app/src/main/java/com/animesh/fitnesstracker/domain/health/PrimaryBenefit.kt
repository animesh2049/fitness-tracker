package com.animesh.fitnesstracker.domain.health

/**
 * Garmin's training effect labels for the activity file's primary_benefit code. The numbering is
 * provisional until confirmed on the watch (open question Q18); 0 and unknown codes have no label.
 */
object PrimaryBenefit {
    fun label(code: Int?): String? = when (code) {
        1 -> "Recovery"
        2 -> "Base"
        3 -> "Tempo"
        4 -> "Threshold"
        5 -> "VO2 max"
        6 -> "Anaerobic capacity"
        7 -> "Sprint"
        else -> null
    }
}
