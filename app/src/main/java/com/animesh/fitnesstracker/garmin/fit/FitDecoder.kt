package com.animesh.fitnesstracker.garmin.fit

/**
 * Decodes a FIT file (the binary format Garmin watches write) into [DecodedFit].
 *
 * Implementation lives in Milestone 18 (`FitReader`, `Profile`). This entry point is the only thing
 * the importer depends on. It must never throw for unknown messages or fields; it throws
 * [FitDecodeException] only for a corrupt file (bad magic, truncated record, CRC mismatch).
 */
object FitDecoder {
    fun decode(bytes: ByteArray): DecodedFit = FitReader.decode(bytes)
}
