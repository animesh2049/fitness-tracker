package com.animesh.fitnesstracker.garmin.fit

import java.time.Instant

/**
 * Human-readable summary of a [DecodedFit] for tests and the app's diagnostics screen: file_id,
 * record totals, first and last timestamps, and per message the record count and which fields
 * carried a value in how many records. Unknown messages and fields show their bare numbers.
 */
object FitDump {
    fun summary(decoded: DecodedFit): String {
        val out = StringBuilder()
        appendFileId(out, decoded.fileId)
        appendTotals(out, decoded)
        appendTimestamps(out, decoded)
        appendMessages(out, decoded)
        return out.toString()
    }

    private fun appendFileId(out: StringBuilder, id: FileIdRec) {
        out.append("file_id: type=").append(id.typeNum).append(" (").append(id.type?.name ?: "unknown").append(")")
        out.append(" time_created=").append(formatTime(id.timeCreated))
        out.append(" manufacturer=").append(id.manufacturer).append(" product=").append(id.product)
        out.append(" serial=").append(id.serialNumber).append(" number=").append(id.number).append('\n')
    }

    private fun appendTotals(out: StringBuilder, decoded: DecodedFit) {
        val numbers = decoded.raw.map { it.globalMessageNumber }.toSet()
        val unknownNumbers = numbers.filterNot(Profile::isKnownMessage).sorted()
        out.append("records: ").append(decoded.raw.size).append(" in ").append(numbers.size).append(" message types; ")
        out.append("unknown messages: ").append(decoded.unknownMessageCount).append(" records of types ").append(unknownNumbers)
        out.append("; unknown field slots in known messages: ").append(decoded.unknownFieldCount).append('\n')
    }

    private fun appendTimestamps(out: StringBuilder, decoded: DecodedFit) {
        val stamps = ArrayList<Long>()
        decoded.raw.forEach { rec -> (rec.fields[253] as? Long)?.let(stamps::add) }
        decoded.monitoring.forEach { stamps += it.timestamp }
        decoded.stress.forEach { stamps += it.timestamp }
        val first = stamps.minOrNull()
        val last = stamps.maxOrNull()
        out.append("timestamps: first=").append(formatTime(first)).append(" last=").append(formatTime(last)).append('\n')
    }

    private fun appendMessages(out: StringBuilder, decoded: DecodedFit) {
        val counts = LinkedHashMap<Int, Int>()
        val coverage = HashMap<Int, LinkedHashMap<String, Int>>()
        for (rec in decoded.raw) {
            val num = rec.globalMessageNumber
            counts[num] = (counts[num] ?: 0) + 1
            val fields = coverage.getOrPut(num) { LinkedHashMap() }
            for ((fieldNum, value) in rec.fields) {
                if (value == null) continue
                val key = fieldLabel(num, fieldNum)
                fields[key] = (fields[key] ?: 0) + 1
            }
            for ((name, value) in rec.developerFields) {
                if (value == null) continue
                val key = "dev:$name"
                fields[key] = (fields[key] ?: 0) + 1
            }
        }
        out.append("messages (num name count: field=records with a value):\n")
        for (num in counts.keys.sorted()) {
            val name = Profile.message(num)?.name ?: "?"
            out.append(String.format("  %4d %-24s %6d  ", num, name, counts[num]))
            out.append(coverage[num].orEmpty().entries.joinToString(" ") { "${it.key}=${it.value}" })
            out.append('\n')
        }
    }

    private fun fieldLabel(messageNum: Int, fieldNum: Int): String {
        val spec = Profile.field(messageNum, fieldNum)
        return if (spec != null) "$fieldNum:${spec.name}" else "$fieldNum"
    }

    private fun formatTime(unixSeconds: Long?): String =
        if (unixSeconds == null) "null" else "$unixSeconds (${Instant.ofEpochSecond(unixSeconds)})"
}
