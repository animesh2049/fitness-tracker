package com.animesh.fitnesstracker.garmin.fit

/**
 * Placeholder for the Milestone 18 decoder so the contract compiles. Replaced by the real reader.
 */
internal object FitReader {
    fun decode(bytes: ByteArray): DecodedFit {
        throw FitDecodeException("FIT decoder not implemented yet (Milestone 18)")
    }
}
