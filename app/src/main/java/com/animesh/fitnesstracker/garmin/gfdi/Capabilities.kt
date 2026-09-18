package com.animesh.fitnesstracker.garmin.gfdi

/**
 * CONFIGURATION (5050) capability bitmap: bit i of the payload is capability ordinal i in
 * Gadgetbridge's `GarminCapability` enum (120 entries, 15 bytes). [OURS] mirrors Gadgetbridge's
 * `OUR_CAPABILITIES`: every bit set except ordinals 104..111 and 114..119, which is what the reference
 * implementation is known to work with on this watch generation.
 */
object Capabilities {
    const val SYNC = 3
    const val DEVICE_INITIATES_SYNC = 4
    const val HOST_INITIATED_SYNC_REQUESTS = 5
    /** The watch accepts workout files (128/5) pushed by the phone. */
    const val WORKOUT_DOWNLOAD = 18
    const val CURRENT_TIME_REQUEST_SUPPORT = 71
    const val MULTI_LINK_SERVICE = 76
    const val COUNT = 120

    val OURS: ByteArray = bitmap((0 until COUNT).filter { it !in 104..111 && it !in 114..119 })

    fun bitmap(ordinals: Iterable<Int>): ByteArray {
        val out = ByteArray((COUNT + 7) / 8)
        for (o in ordinals) out[o / 8] = (out[o / 8].toInt() or (1 shl (o % 8))).toByte()
        return out
    }

    fun has(bitmap: ByteArray, ordinal: Int): Boolean {
        val i = ordinal / 8
        return i < bitmap.size && bitmap[i].toInt() and (1 shl (ordinal % 8)) != 0
    }
}
