package com.animesh.fitnesstracker.garmin.workout

import com.animesh.fitnesstracker.garmin.fit.DecodedFit
import com.animesh.fitnesstracker.garmin.fit.FitDecoder
import com.animesh.fitnesstracker.garmin.fit.FitDump
import com.animesh.fitnesstracker.garmin.fit.FitFileType
import com.animesh.fitnesstracker.garmin.fit.GARMIN_EPOCH_UNIX_SECONDS
import com.animesh.fitnesstracker.garmin.fit.Mesg
import com.animesh.fitnesstracker.garmin.fit.WorkoutStepRec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The workout encoder against the research's worked example ("Push day A") and the shapes the app
 * produces: timed holds, bodyweight, differing sets, custom exercises, supersets, file naming.
 */
class WorkoutFitWriterTest {
    /** 2026-09-17T10:00:00Z. */
    private val createdAt = 1789639200L

    private fun sets(n: Int, reps: Int, kg: Double? = null) = List(n) { WatchSet(reps = reps, weightKg = kg) }
    private fun timed(n: Int, seconds: Int) = List(n) { WatchSet(seconds = seconds) }

    private fun pushDayA() = WatchWorkoutPlan(
        name = "Push day A",
        serial = 0x5055_5348L,
        createdAtEpochSeconds = createdAt,
        exercises = listOf(
            WatchExercise(1, "Bench press", WatchExerciseKind.WEIGHT, listOf(WatchSet(10, 40.0, warmup = true)) + sets(3, 8, 60.0), restSeconds = 120),
            WatchExercise(2, "Dumbbell row", WatchExerciseKind.WEIGHT, sets(3, 10, 24.0), restSeconds = 20, supersetWithNext = true),
            WatchExercise(3, "Push-up", WatchExerciseKind.BODYWEIGHT, sets(3, 15), restSeconds = 90)
        )
    )

    private fun decode(encoded: EncodedWorkout): DecodedFit = FitDecoder.decode(encoded.bytes)

    private fun WorkoutStepRec.summary(): String = buildString {
        append(messageIndex).append(':')
        append(name ?: "-").append(' ')
        append("d").append(durationType).append('/').append(durationValue)
        append(" t").append(targetType).append('/').append(targetValue ?: 0)
        append(" i").append(intensity)
        if (exerciseCategory != null) append(" ex").append(exerciseCategory).append('/').append(exerciseName)
        if (exerciseWeightKg != null) append(" kg").append(exerciseWeightKg).append(" u").append(weightDisplayUnit)
    }

    @Test
    fun pushDayAMatchesTheWorkedExample() {
        val encoded = WorkoutEncoder.encode(pushDayA())
        assertEquals(10, encoded.stepCount)
        assertEquals(3, encoded.mappings.size)
        assertTrue(encoded.mappings.none { it.isCustom })
        val d = decode(encoded)
        assertEquals(FitFileType.WORKOUT, d.fileId.type)
        assertEquals(255, d.fileId.manufacturer)
        assertEquals(1, d.fileId.product)
        assertEquals(0x5055_5348L, d.fileId.serialNumber)
        assertEquals(createdAt, d.fileId.timeCreated)
        val workout = d.workouts.single()
        assertEquals("Push day A", workout.name)
        assertEquals(10, workout.sport)
        assertEquals(20, workout.subSport)
        assertEquals(10, workout.numValidSteps)
        assertEquals(
            listOf(
                "0:Bench press warm-up d29/10 t2/0 i2 ex0/1 kg40.0 u1",
                "1:Rest d0/90000 t2/0 i1",
                "2:Bench press d29/8 t2/0 i0 ex0/1 kg60.0 u1",
                "3:Rest d0/120000 t2/0 i1",
                "4:- d6/2 t2/3 i0",
                "5:Dumbbell row d29/10 t2/0 i0 ex23/2 kg24.0 u1",
                "6:Switch d0/15000 t2/0 i1",
                "7:Push-up d29/15 t2/0 i0 ex22/77",
                "8:Rest d0/90000 t2/0 i1",
                "9:- d6/5 t2/3 i0"
            ),
            d.workoutSteps.map { it.summary() }
        )
        assertEquals(listOf(0, 1, 2), d.exerciseTitles.map { it.messageIndex })
        assertEquals(listOf(0 to 1, 23 to 2, 22 to 77), d.exerciseTitles.map { it.exerciseCategory to it.exerciseName })
        assertEquals(listOf("Bench press", "Dumbbell row", "Push-up"), d.exerciseTitles.map { it.name })
        // Message order: file_id, workout, steps, titles.
        assertEquals(
            listOf(Mesg.FILE_ID, Mesg.WORKOUT) + List(10) { Mesg.WORKOUT_STEP } + List(3) { Mesg.EXERCISE_TITLE },
            d.raw.map { it.globalMessageNumber }
        )
        assertEquals(0, d.unknownFieldCount)
        assertEquals(0, d.unknownMessageCount)
        assertEquals("push-day-a-2026-09-17.fit", encoded.suggestedFileName)
    }

    @Test
    fun pushDayAIsSmallAndSavedAsASampleFile() {
        val encoded = WorkoutEncoder.encode(pushDayA())
        assertTrue("size ${encoded.bytes.size}", encoded.bytes.size in 300..700)
        val moduleDir = File(System.getProperty("user.dir")).let { if (it.name == "app") it else File(it, "app") }
        val out = File(moduleDir, "build/fit-samples/push-day-a.fit")
        out.parentFile.mkdirs()
        out.writeBytes(encoded.bytes)
        assertEquals(encoded.bytes.size.toLong(), out.length())
        val summary = FitDump.summary(FitDecoder.decode(out.readBytes()))
        println("Wrote ${out.absolutePath} (${encoded.bytes.size} bytes)")
        println(summary)
        assertTrue(summary.contains("file_id: type=5 (WORKOUT)"))
        assertTrue(summary, Regex("27 workout_step\\s+10 ").containsMatchIn(summary))
        assertTrue(summary, Regex("264 exercise_title\\s+3 ").containsMatchIn(summary))
        assertTrue(summary, Regex("26 workout\\s+1 ").containsMatchIn(summary))
    }

    @Test
    fun supersetTransitionIsConfigurable() {
        val d = decode(WorkoutFitWriter.encode(pushDayA(), transitionSeconds = 20))
        assertEquals(20_000L, d.workoutSteps[6].durationValue)
        assertEquals("Switch", d.workoutSteps[6].name)
    }

    @Test
    fun timedExerciseUsesMillisecondsAndRepeat() {
        val plan = WatchWorkoutPlan(
            "Core", 9, createdAt,
            listOf(WatchExercise(5, "Plank", WatchExerciseKind.TIMED, timed(3, 45), restSeconds = 45))
        )
        val d = decode(WorkoutEncoder.encode(plan))
        assertEquals(
            listOf("0:Plank d0/45000 t2/0 i0 ex19/43", "1:Rest d0/45000 t2/0 i1", "2:- d6/0 t2/3 i0"),
            d.workoutSteps.map { it.summary() }
        )
        assertEquals(3, d.workouts.single().numValidSteps)
    }

    @Test
    fun bodyweightStepsCarryNoWeightFields() {
        val plan = WatchWorkoutPlan(
            "Pull", 9, createdAt,
            listOf(WatchExercise(5, "Pull-up", WatchExerciseKind.BODYWEIGHT, listOf(WatchSet(8, weightKg = 0.0), WatchSet(6)), restSeconds = 90))
        )
        val d = decode(WorkoutEncoder.encode(plan))
        val steps = d.workoutSteps
        assertEquals(3, steps.size)
        steps.forEach { assertNull(it.exerciseWeightKg); assertNull(it.weightDisplayUnit) }
        val rawSteps = d.raw.filter { it.globalMessageNumber == Mesg.WORKOUT_STEP }
        rawSteps.forEach { assertFalse(it.fields.containsKey(12)); assertFalse(it.fields.containsKey(13)) }
        assertEquals(listOf(8L, 90_000L, 6L), steps.map { it.durationValue })
    }

    @Test
    fun differingSetsAreUnrolledAndTheTrailingRestIsDropped() {
        val plan = WatchWorkoutPlan(
            "Legs", 9, createdAt,
            listOf(
                WatchExercise(5, "Back squat", WatchExerciseKind.WEIGHT, listOf(WatchSet(5, 80.0), WatchSet(5, 85.0), WatchSet(3, 90.0)), restSeconds = 180)
            )
        )
        val encoded = WorkoutEncoder.encode(plan)
        assertEquals(5, encoded.stepCount)
        val d = decode(encoded)
        assertEquals(
            listOf(
                "0:Back squat d29/5 t2/0 i0 ex28/6 kg80.0 u1", "1:Rest d0/180000 t2/0 i1",
                "2:Back squat d29/5 t2/0 i0 ex28/6 kg85.0 u1", "3:Rest d0/180000 t2/0 i1",
                "4:Back squat d29/3 t2/0 i0 ex28/6 kg90.0 u1"
            ),
            d.workoutSteps.map { it.summary() }
        )
        assertTrue(d.workoutSteps.none { it.durationType == 6 })
        assertEquals(1, d.exerciseTitles.size)
    }

    @Test
    fun singleSetAndZeroRestProduceNoRepeatAndNoRestSteps() {
        val plan = WatchWorkoutPlan(
            "Quick", 9, createdAt,
            listOf(
                WatchExercise(1, "Deadlift", WatchExerciseKind.WEIGHT, sets(1, 5, 100.0), restSeconds = 0),
                WatchExercise(2, "Plank", WatchExerciseKind.TIMED, timed(2, 30), restSeconds = 0)
            )
        )
        val d = decode(WorkoutEncoder.encode(plan))
        assertEquals(
            listOf("0:Deadlift d29/5 t2/0 i0 ex8/0 kg100.0 u1", "1:Plank d0/30000 t2/0 i0 ex19/43", "2:- d6/1 t2/2 i0"),
            d.workoutSteps.map { it.summary() }
        )
    }

    @Test
    fun customExerciseGetsCategory65534AndATitle() {
        val plan = WatchWorkoutPlan(
            "Legs", 9, createdAt,
            listOf(WatchExercise(1001, "Leg extension", WatchExerciseKind.WEIGHT, sets(2, 12, 45.0), restSeconds = 60))
        )
        val encoded = WorkoutEncoder.encode(plan)
        assertTrue(encoded.mappings.single().isCustom)
        val d = decode(encoded)
        assertEquals(65534, d.workoutSteps[0].exerciseCategory)
        assertEquals(1001, d.workoutSteps[0].exerciseName)
        val title = d.exerciseTitles.single()
        assertEquals(65534, title.exerciseCategory)
        assertEquals(1001, title.exerciseName)
        assertEquals("Leg extension", title.name)
    }

    @Test
    fun oneTitlePerDistinctPairEvenWhenExercisesRepeat() {
        val plan = WatchWorkoutPlan(
            "Arms", 9, createdAt,
            listOf(
                WatchExercise(1, "Bicep curl", WatchExerciseKind.WEIGHT, sets(2, 12, 10.0), restSeconds = 60),
                WatchExercise(2, "Dumbbell curl", WatchExerciseKind.WEIGHT, sets(2, 12, 10.0), restSeconds = 60),
                WatchExercise(3, "Hammer curl", WatchExerciseKind.WEIGHT, sets(2, 12, 12.0), restSeconds = 60)
            )
        )
        val d = decode(WorkoutEncoder.encode(plan))
        assertEquals(listOf(7 to 46, 7 to 16), d.exerciseTitles.map { it.exerciseCategory to it.exerciseName })
        assertEquals(listOf("Bicep curl", "Hammer curl"), d.exerciseTitles.map { it.name })
    }

    @Test
    fun unevenSupersetPutsLeftoverSetsAfterTheBlock() {
        val plan = WatchWorkoutPlan(
            "Upper", 9, createdAt,
            listOf(
                WatchExercise(1, "Incline dumbbell press", WatchExerciseKind.WEIGHT, sets(3, 10, 22.5), restSeconds = 60, supersetWithNext = true),
                WatchExercise(2, "Triceps pushdown", WatchExerciseKind.WEIGHT, sets(2, 12, 25.0), restSeconds = 60)
            )
        )
        val d = decode(WorkoutEncoder.encode(plan))
        assertEquals(
            listOf(
                "0:Incline dumbbell press d29/10 t2/0 i0 ex0/9 kg22.5 u1",
                "1:Switch d0/15000 t2/0 i1",
                "2:Triceps pushdown d29/12 t2/0 i0 ex30/39 kg25.0 u1",
                "3:Rest d0/60000 t2/0 i1",
                "4:- d6/0 t2/2 i0",
                "5:Incline dumbbell press d29/10 t2/0 i0 ex0/9 kg22.5 u1"
            ),
            d.workoutSteps.map { it.summary() }
        )
    }

    @Test
    fun supersetWithDifferingSetsIsUnrolledRoundByRound() {
        val plan = WatchWorkoutPlan(
            "Upper", 9, createdAt,
            listOf(
                WatchExercise(1, "Dumbbell row", WatchExerciseKind.WEIGHT, listOf(WatchSet(10, 24.0), WatchSet(8, 26.0)), restSeconds = 60, supersetWithNext = true),
                WatchExercise(2, "Push-up", WatchExerciseKind.BODYWEIGHT, sets(2, 15), restSeconds = 60)
            )
        )
        val d = decode(WorkoutEncoder.encode(plan))
        assertEquals(listOf(24.0, null, null, null, 26.0, null, null), d.workoutSteps.map { it.exerciseWeightKg })
        assertEquals(7, d.workoutSteps.size)
        assertTrue(d.workoutSteps.none { it.durationType == 6 })
    }

    @Test
    fun supersetFlagOnTheLastExerciseIsIgnored() {
        val plan = WatchWorkoutPlan(
            "Solo", 9, createdAt,
            listOf(WatchExercise(1, "Push-up", WatchExerciseKind.BODYWEIGHT, sets(2, 15), restSeconds = 60, supersetWithNext = true))
        )
        val d = decode(WorkoutEncoder.encode(plan))
        assertEquals(listOf("0:Push-up d29/15 t2/0 i0 ex22/77", "1:Rest d0/60000 t2/0 i1", "2:- d6/0 t2/2 i0"), d.workoutSteps.map { it.summary() })
    }

    @Test
    fun upperLowerRoutineEncodesWithoutUnexpectedCustomFallbacks() {
        val upper = WatchWorkoutPlan(
            "Upper A", 11, createdAt,
            listOf(
                WatchExercise(1, "Bench press", WatchExerciseKind.WEIGHT, listOf(WatchSet(8, 40.0, warmup = true)) + sets(3, 8, 60.0), 120),
                WatchExercise(2, "Barbell row", WatchExerciseKind.WEIGHT, sets(3, 10, 50.0), 120),
                WatchExercise(3, "Overhead press", WatchExerciseKind.WEIGHT, sets(3, 10, 30.0), 120),
                WatchExercise(4, "Lat pulldown", WatchExerciseKind.WEIGHT, sets(3, 12, 45.0), 90),
                WatchExercise(5, "Lateral raise", WatchExerciseKind.WEIGHT, sets(3, 15, 8.0), 60, supersetWithNext = true),
                WatchExercise(6, "Face pull", WatchExerciseKind.WEIGHT, sets(3, 15, 15.0), 60),
                WatchExercise(7, "Bicep curl", WatchExerciseKind.WEIGHT, sets(3, 12, 10.0), 60, supersetWithNext = true),
                WatchExercise(8, "Triceps pushdown", WatchExerciseKind.WEIGHT, sets(3, 12, 25.0), 60)
            )
        )
        val lower = WatchWorkoutPlan(
            "Lower A", 12, createdAt,
            listOf(
                WatchExercise(9, "Back squat", WatchExerciseKind.WEIGHT, sets(3, 5, 80.0), 180),
                WatchExercise(10, "Romanian deadlift", WatchExerciseKind.WEIGHT, sets(3, 8, 70.0), 120),
                WatchExercise(11, "Leg press", WatchExerciseKind.WEIGHT, sets(3, 12, 120.0), 90),
                WatchExercise(12, "Leg curl", WatchExerciseKind.WEIGHT, sets(3, 12, 40.0), 60),
                WatchExercise(13, "Leg extension", WatchExerciseKind.WEIGHT, sets(3, 12, 40.0), 60),
                WatchExercise(14, "Calf raise", WatchExerciseKind.WEIGHT, sets(3, 15, 40.0), 60),
                WatchExercise(15, "Plank", WatchExerciseKind.TIMED, timed(3, 45), 45)
            )
        )
        val upperEncoded = WorkoutEncoder.encode(upper)
        assertTrue(upperEncoded.mappings.none { it.isCustom })
        val du = decode(upperEncoded)
        assertEquals(upperEncoded.stepCount, du.workouts.single().numValidSteps)
        assertEquals(8, du.exerciseTitles.size)
        assertEquals("upper-a-2026-09-17.fit", upperEncoded.suggestedFileName)
        // Warm-up, 3 sets of bench in a block, five plain blocks, two superset blocks: 2 + 3 + 3 + 3 + 3 + 5 + 5 = 24 steps.
        assertEquals(24, upperEncoded.stepCount)

        val lowerEncoded = WorkoutEncoder.encode(lower)
        assertEquals(listOf("Leg extension"), lowerEncoded.mappings.filter { it.isCustom }.map { it.appName })
        val dl = decode(lowerEncoded)
        assertEquals(7, dl.exerciseTitles.size)
        assertEquals(1, dl.exerciseTitles.count { it.exerciseCategory == 65534 })
        assertEquals(lowerEncoded.stepCount, dl.workoutSteps.size)
        assertEquals(dl.workoutSteps.indices.toList(), dl.workoutSteps.map { it.messageIndex })
        // Every repeat step points back inside the file, at an active step.
        dl.workoutSteps.filter { it.durationType == 6 }.forEach { repeat ->
            val target = dl.workoutSteps[repeat.durationValue!!.toInt()]
            assertEquals(0, target.intensity)
            assertTrue(repeat.durationValue!! < repeat.messageIndex!!)
        }
    }

    @Test
    fun longNamesAreTruncatedAndStillDecode() {
        val longName = "An extremely long workout name that goes on and on"
        val plan = WatchWorkoutPlan(
            longName, 9, createdAt,
            listOf(WatchExercise(1, "Chest supported single arm dumbbell row", WatchExerciseKind.WEIGHT, sets(2, 10, 24.0), 60))
        )
        val d = decode(WorkoutEncoder.encode(plan))
        assertEquals(longName.take(30), d.workouts.single().name)
        assertEquals("Chest supported single arm dumb", d.workoutSteps[0].name)
        assertEquals(23, d.workoutSteps[0].exerciseCategory)
        assertEquals(2, d.workoutSteps[0].exerciseName)
    }

    @Test
    fun suggestedFileNameSlugsTheNameAndUsesUtcDate() {
        fun name(title: String, at: Long) = WorkoutFitWriter.suggestedFileName(WatchWorkoutPlan(title, 1, at, emptyList()))
        assertEquals("upper-a-2026-09-17.fit", name("Upper A", createdAt))
        assertEquals("push-pull-legs-day-2-2026-09-17.fit", name("  Push/Pull & Legs: day 2! ", createdAt))
        assertEquals("workout-1989-12-31.fit", name("***", GARMIN_EPOCH_UNIX_SECONDS))
        // 23:30 UTC stays on the UTC date, whatever the local zone.
        assertEquals("late-2026-09-17.fit", name("Late", createdAt + 13 * 3600 + 30 * 60))
    }

    @Test
    fun zeroSerialIsReplacedByANonzeroOne() {
        val plan = WatchWorkoutPlan("Zero", 0, createdAt, listOf(WatchExercise(1, "Plank", WatchExerciseKind.TIMED, timed(1, 30), 0)))
        val d = decode(WorkoutEncoder.encode(plan))
        assertTrue(d.fileId.serialNumber != null && d.fileId.serialNumber!! > 0L)
        val big = WatchWorkoutPlan("Big", 0x1_0000_0005L, createdAt, plan.exercises)
        assertEquals(5L, decode(WorkoutEncoder.encode(big)).fileId.serialNumber)
    }

    @Test
    fun emptyPlanStillProducesAValidFile() {
        val encoded = WorkoutEncoder.encode(WatchWorkoutPlan("Empty", 3, createdAt, emptyList()))
        assertEquals(0, encoded.stepCount)
        val d = decode(encoded)
        assertEquals(0, d.workouts.single().numValidSteps)
        assertTrue(d.workoutSteps.isEmpty())
        assertTrue(d.exerciseTitles.isEmpty())
    }
}
