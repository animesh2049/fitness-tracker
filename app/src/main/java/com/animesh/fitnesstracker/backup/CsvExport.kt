package com.animesh.fitnesstracker.backup

import com.animesh.fitnesstracker.data.AppDatabase
import com.animesh.fitnesstracker.data.model.SessionWithExercises
import com.animesh.fitnesstracker.data.model.WeightUnit
import com.animesh.fitnesstracker.util.Dates
import com.animesh.fitnesstracker.util.Weights

/** Flat, spreadsheet friendly history: one row per logged set. */
object CsvExport {
    const val HEADER = "date,session_id,group,exercise,set,warmup,target_reps,target_weight,target_seconds,actual_reps,actual_weight,actual_seconds,completed,rpe"

    /** Every set of every completed or abandoned session, oldest first. Weights in [unit]. */
    suspend fun sessionsCsv(db: AppDatabase, unit: WeightUnit): String =
        build(db.sessionDao().getFinishedSessions(), unit)

    /** Pure builder so the format can be unit tested without a database. */
    fun build(sessions: List<SessionWithExercises>, unit: WeightUnit): String {
        val sb = StringBuilder(HEADER).append('\n')
        for (s in sessions) {
            val date = Dates.date(s.session.epochDay).toString()
            for (se in s.sortedExercises) {
                for (set in se.sortedSets) {
                    sb.append(
                        row(
                            date, s.session.id.toString(), s.session.groupName, se.exercise.exerciseName,
                            (set.position + 1).toString(), set.isWarmup.toString(),
                            set.targetReps?.toString(), weight(set.targetWeightKg, unit), set.targetSeconds?.toString(),
                            set.actualReps?.toString(), weight(set.actualWeightKg, unit), set.actualSeconds?.toString(),
                            set.completed.toString(), set.rpe?.toString()
                        )
                    ).append('\n')
                }
            }
        }
        return sb.toString()
    }

    /** Number of data rows (sets) the CSV for [sessions] would contain. */
    fun rowCount(sessions: List<SessionWithExercises>): Int = sessions.sumOf { s -> s.exercises.sumOf { it.sets.size } }

    private fun weight(kg: Double?, unit: WeightUnit): String? = kg?.let { Weights.format(Weights.toDisplay(it, unit)) }

    fun row(vararg fields: String?): String = fields.joinToString(",") { escape(it ?: "") }

    /** RFC 4180 quoting: wrap in quotes when the value has a comma, quote, or line break; double inner quotes. */
    fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
}
