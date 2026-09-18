package com.animesh.fitnesstracker.garmin.workout

import com.animesh.fitnesstracker.garmin.fit.FitField
import com.animesh.fitnesstracker.garmin.fit.FitWriter
import com.animesh.fitnesstracker.garmin.fit.GARMIN_EPOCH_UNIX_SECONDS
import com.animesh.fitnesstracker.garmin.fit.Mesg
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Turns a [WatchWorkoutPlan] into a Garmin workout FIT file (file type 5) that a Forerunner lists
 * under Training, Workouts and guides step by step.
 *
 * Layout, per the research notes: `file_id`, one `workout` (sport 10 training, sub_sport 20
 * strength training), the `workout_step` records in execution order, then one `exercise_title`
 * per distinct catalogue mapping. Steps are built as follows.
 *
 * - Warm-up sets become individual rep steps with intensity 2 (warm-up), each followed by a rest
 *   step capped at [WARMUP_REST_CAP_SECONDS].
 * - Working sets that all share reps, weight and seconds become one block (exercise step, rest
 *   step) followed by a repeat step (duration_type 6 pointing at the block's first index,
 *   target_value = set count). Sets that differ, or a single set, are unrolled one step per set.
 * - An exercise flagged [WatchExercise.supersetWithNext] pairs with the next one into one repeat
 *   block "A, Switch (short transition rest), B, Rest" for the smaller set count; leftover sets of
 *   the longer partner follow as their own block.
 * - Reps use duration_type 29 with the rep count; timed sets duration_type 0 with milliseconds;
 *   weights go to exercise_weight (kg x 100) with weight_display_unit 1 and are omitted for
 *   bodyweight sets. Rest steps are intensity 1, duration_type 0, milliseconds; a rest of 0 s is
 *   skipped, as is a trailing rest at the very end of the workout. target_type is 2 (open) on
 *   every step and message_index runs densely from 0.
 */
object WorkoutFitWriter {
    /** Transition rest between the two halves of a superset round (a 0 s step is not possible). */
    const val SUPERSET_TRANSITION_SECONDS = 15

    /** Rest after a warm-up set is the exercise's rest, but never longer than this. */
    const val WARMUP_REST_CAP_SECONDS = 90

    /** Longest workout name the watch list shows comfortably. */
    const val MAX_WORKOUT_NAME_CHARS = 30

    /** Byte size of the wkt_step_name field (31 characters plus the terminator). */
    const val STEP_NAME_FIELD_SIZE = 32

    const val FILE_TYPE_WORKOUT = 5
    const val MANUFACTURER_DEVELOPMENT = 255
    const val PRODUCT = 1
    const val SPORT_TRAINING = 10
    const val SUB_SPORT_STRENGTH_TRAINING = 20

    const val DURATION_TIME = 0
    const val DURATION_OPEN = 5
    const val DURATION_REPEAT_UNTIL_STEPS_COMPLETE = 6
    const val DURATION_REPS = 29
    const val TARGET_OPEN = 2
    const val INTENSITY_ACTIVE = 0
    const val INTENSITY_REST = 1
    const val INTENSITY_WARMUP = 2
    const val WEIGHT_UNIT_KILOGRAM = 1

    const val REST_STEP_NAME = "Rest"
    const val SWITCH_STEP_NAME = "Switch"

    private const val MAX_U16 = 0xFFFF
    private const val MAX_U32 = 0xFFFFFFFFL
    private val fileDate: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

    /** One planned workout_step before encoding; null fields are omitted from the record. */
    internal data class Step(
        val name: String?,
        val durationType: Int,
        val durationValue: Long,
        val intensity: Int,
        val targetValue: Long? = null,
        val mapping: ExerciseMapping? = null,
        val weightKg: Double? = null
    ) {
        val isRest: Boolean get() = intensity == INTENSITY_REST
        val isRepeat: Boolean get() = durationType == DURATION_REPEAT_UNTIL_STEPS_COMPLETE
    }

    /** Encodes [plan]; [transitionSeconds] is the rest between the two halves of a superset round. */
    fun encode(plan: WatchWorkoutPlan, transitionSeconds: Int = SUPERSET_TRANSITION_SECONDS): EncodedWorkout {
        val mappings = plan.exercises.map { GarminExerciseCatalog.map(it.appExerciseId, it.name) }
        val steps = buildSteps(plan, mappings, transitionSeconds)
        val titles = distinctTitles(plan, mappings)
        val bytes = writeFile(plan, steps, titles)
        return EncodedWorkout(bytes, steps.size, mappings, suggestedFileName(plan))
    }

    /** "upper-a-2026-09-17.fit": slug of the name plus the creation date in UTC. */
    fun suggestedFileName(plan: WatchWorkoutPlan): String {
        val slug = plan.name.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")
            .replace(Regex("-+"), "-").trim('-').ifEmpty { "workout" }
        return "$slug-${fileDate.format(Instant.ofEpochSecond(plan.createdAtEpochSeconds))}.fit"
    }

    /** The step list alone, for tests and the preview sheet. */
    internal fun buildSteps(plan: WatchWorkoutPlan, mappings: List<ExerciseMapping>, transitionSeconds: Int): List<Step> {
        val steps = ArrayList<Step>()
        val exercises = plan.exercises
        var i = 0
        while (i < exercises.size) {
            val exercise = exercises[i]
            val partner = exercises.getOrNull(i + 1)?.takeIf { exercise.supersetWithNext }
            if (partner != null) {
                appendSuperset(steps, exercise, mappings[i], partner, mappings[i + 1], transitionSeconds)
                i += 2
            } else {
                appendWarmups(steps, exercise, mappings[i])
                appendWorkingSets(steps, exercise, mappings[i], exercise.sets.filterNot { it.warmup })
                i += 1
            }
        }
        if (steps.isNotEmpty() && steps.last().isRest) steps.removeAt(steps.size - 1)
        return steps
    }

    private fun appendWarmups(steps: MutableList<Step>, exercise: WatchExercise, mapping: ExerciseMapping) {
        for (set in exercise.sets.filter { it.warmup }) {
            steps += exerciseStep("${exercise.name} warm-up", exercise, set, mapping, INTENSITY_WARMUP)
            appendRest(steps, minOf(exercise.restSeconds, WARMUP_REST_CAP_SECONDS))
        }
    }

    private fun appendWorkingSets(steps: MutableList<Step>, exercise: WatchExercise, mapping: ExerciseMapping, sets: List<WatchSet>) {
        if (sets.isEmpty()) return
        if (sets.size > 1 && allAlike(sets)) {
            val first = steps.size
            steps += exerciseStep(exercise.name, exercise, sets[0], mapping, INTENSITY_ACTIVE)
            appendRest(steps, exercise.restSeconds)
            steps += repeatStep(first, sets.size)
        } else {
            for (set in sets) {
                steps += exerciseStep(exercise.name, exercise, set, mapping, INTENSITY_ACTIVE)
                appendRest(steps, exercise.restSeconds)
            }
        }
    }

    private fun appendSuperset(
        steps: MutableList<Step>,
        a: WatchExercise, aMapping: ExerciseMapping,
        b: WatchExercise, bMapping: ExerciseMapping,
        transitionSeconds: Int
    ) {
        appendWarmups(steps, a, aMapping)
        appendWarmups(steps, b, bMapping)
        val aSets = a.sets.filterNot { it.warmup }
        val bSets = b.sets.filterNot { it.warmup }
        val rounds = minOf(aSets.size, bSets.size)
        if (rounds > 0) {
            val alike = allAlike(aSets.take(rounds)) && allAlike(bSets.take(rounds))
            val unrolled = if (alike && rounds > 1) 1 else rounds
            val first = steps.size
            for (round in 0 until unrolled) {
                steps += exerciseStep(a.name, a, aSets[round], aMapping, INTENSITY_ACTIVE)
                appendRest(steps, transitionSeconds, SWITCH_STEP_NAME)
                steps += exerciseStep(b.name, b, bSets[round], bMapping, INTENSITY_ACTIVE)
                appendRest(steps, b.restSeconds)
            }
            if (alike && rounds > 1) steps += repeatStep(first, rounds)
        }
        appendWorkingSets(steps, a, aMapping, aSets.drop(rounds))
        appendWorkingSets(steps, b, bMapping, bSets.drop(rounds))
    }

    private fun appendRest(steps: MutableList<Step>, seconds: Int, name: String = REST_STEP_NAME) {
        if (seconds <= 0) return
        steps += Step(name, DURATION_TIME, seconds * 1000L, INTENSITY_REST)
    }

    private fun repeatStep(firstIndex: Int, times: Int) =
        Step(null, DURATION_REPEAT_UNTIL_STEPS_COMPLETE, firstIndex.toLong(), INTENSITY_ACTIVE, targetValue = times.toLong())

    private fun exerciseStep(name: String, exercise: WatchExercise, set: WatchSet, mapping: ExerciseMapping, intensity: Int): Step {
        val timed = set.seconds != null && (exercise.kind == WatchExerciseKind.TIMED || set.reps == null)
        val (type, value) = when {
            timed -> DURATION_TIME to set.seconds!! * 1000L
            set.reps != null -> DURATION_REPS to set.reps.toLong()
            else -> DURATION_OPEN to 0L
        }
        val weight = set.weightKg?.takeIf { it > 0.0 && exercise.kind != WatchExerciseKind.BODYWEIGHT }
        return Step(name, type, value, intensity, mapping = mapping, weightKg = weight)
    }

    private fun allAlike(sets: List<WatchSet>): Boolean =
        sets.all { it.reps == sets[0].reps && it.weightKg == sets[0].weightKg && it.seconds == sets[0].seconds }

    /** One title per distinct (category, exercise_name) pair, in first-use order, labelled with the app's name. */
    internal fun distinctTitles(plan: WatchWorkoutPlan, mappings: List<ExerciseMapping>): List<ExerciseMapping> {
        val seen = LinkedHashMap<Pair<Int, Int>, ExerciseMapping>()
        mappings.forEachIndexed { i, m -> if (plan.exercises[i].sets.isNotEmpty()) seen.putIfAbsent(m.category to m.exerciseName, m) }
        return seen.values.toList()
    }

    private fun writeFile(plan: WatchWorkoutPlan, steps: List<Step>, titles: List<ExerciseMapping>): ByteArray {
        val w = FitWriter()
        w.write(
            Mesg.FILE_ID,
            FitField.enum(0, FILE_TYPE_WORKOUT),
            FitField.u16(1, MANUFACTURER_DEVELOPMENT),
            FitField.u16(2, PRODUCT),
            FitField.u32z(3, serialNumber(plan)),
            FitField.u32(4, garminSeconds(plan.createdAtEpochSeconds))
        )
        val name = FitWriter.truncateUtf8(plan.name.take(MAX_WORKOUT_NAME_CHARS), MAX_WORKOUT_NAME_CHARS * 4)
        w.write(
            Mesg.WORKOUT,
            FitField.enum(4, SPORT_TRAINING),
            FitField.enum(11, SUB_SPORT_STRENGTH_TRAINING),
            FitField.u16(6, steps.size),
            FitField.string(8, String(name, Charsets.UTF_8))
        )
        val stepNameSize = stepNameSize(steps.mapNotNull { it.name } + titles.map { it.appName })
        steps.forEachIndexed { index, step -> w.write(Mesg.WORKOUT_STEP, stepFields(index, step, stepNameSize)) }
        titles.forEachIndexed { index, title ->
            w.write(
                Mesg.EXERCISE_TITLE,
                FitField.u16(254, index),
                FitField.u16(0, title.category),
                FitField.u16(1, title.exerciseName),
                FitField.string(2, title.appName, stepNameSize)
            )
        }
        return w.toByteArray()
    }

    private fun stepFields(index: Int, step: Step, nameSize: Int): List<FitField> {
        val fields = ArrayList<FitField>(12)
        fields += FitField.u16(254, index)
        if (step.name != null) fields += FitField.string(0, step.name, nameSize)
        fields += FitField.enum(1, step.durationType)
        fields += FitField.u32(2, step.durationValue.coerceIn(0L, MAX_U32))
        fields += FitField.enum(3, TARGET_OPEN)
        if (step.targetValue != null) fields += FitField.u32(4, step.targetValue.coerceIn(0L, MAX_U32))
        fields += FitField.enum(7, step.intensity)
        if (step.mapping != null) {
            fields += FitField.u16(10, step.mapping.category)
            fields += FitField.u16(11, step.mapping.exerciseName)
        }
        if (step.weightKg != null) {
            fields += FitField.u16(12, Math.round(step.weightKg * 100).toInt().coerceIn(0, MAX_U16 - 1))
            fields += FitField.u16(13, WEIGHT_UNIT_KILOGRAM)
        }
        return fields
    }

    /** One string size for every step name and title so the definitions stay stable: longest name plus NUL, capped. */
    private fun stepNameSize(names: List<String>): Int {
        val longest = names.maxOfOrNull { it.toByteArray(Charsets.UTF_8).size } ?: 0
        return (longest + 1).coerceIn(2, STEP_NAME_FIELD_SIZE)
    }

    /** The plan's serial folded to a nonzero u32 (uint32z treats 0 as absent). */
    internal fun serialNumber(plan: WatchWorkoutPlan): Long {
        val masked = plan.serial and MAX_U32
        return if (masked != 0L) masked else (plan.createdAtEpochSeconds and MAX_U32).coerceAtLeast(1L)
    }

    internal fun garminSeconds(unixSeconds: Long): Long = (unixSeconds - GARMIN_EPOCH_UNIX_SECONDS).coerceIn(0L, MAX_U32 - 1)
}
