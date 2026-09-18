package com.animesh.fitnesstracker.garmin.workout

import com.animesh.fitnesstracker.data.Seed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Catalogue lookups: the numbers from the FIT SDK enums, name normalisation, and coverage of the app's seeded exercises. */
class GarminExerciseCatalogTest {
    private fun pair(name: String): Pair<Int, Int> = GarminExerciseCatalog.map(1, name).let { it.category to it.exerciseName }

    @Test
    fun coreLiftsMapToTheDocumentedNumbers() {
        assertEquals(0 to 1, pair("Bench press"))
        assertEquals(0 to 9, pair("Incline dumbbell press"))
        assertEquals(0 to 6, pair("Dumbbell bench press"))
        assertEquals(24 to 14, pair("Overhead press"))
        assertEquals(24 to 14, pair("Shoulder press"))
        assertEquals(24 to 24, pair("Dumbbell shoulder press"))
        assertEquals(28 to 6, pair("Squat"))
        assertEquals(28 to 6, pair("Back squat"))
        assertEquals(28 to 8, pair("Front squat"))
        assertEquals(8 to 0, pair("Deadlift"))
        assertEquals(8 to 23, pair("Romanian deadlift"))
        assertEquals(28 to 0, pair("Leg press"))
        assertEquals(17 to 21, pair("Lunge"))
        assertEquals(23 to 45, pair("Barbell row"))
        assertEquals(23 to 45, pair("Bent over row"))
        assertEquals(23 to 2, pair("Dumbbell row"))
        assertEquals(23 to 18, pair("Seated cable row"))
        assertEquals(21 to 13, pair("Lat pulldown"))
        assertEquals(21 to 38, pair("Pull-up"))
        assertEquals(21 to 39, pair("Chin-up"))
        assertEquals(7 to 46, pair("Biceps curl"))
        assertEquals(7 to 3, pair("Barbell curl"))
        assertEquals(7 to 46, pair("Dumbbell curl"))
        assertEquals(7 to 16, pair("Hammer curl"))
        assertEquals(30 to 39, pair("Triceps pushdown"))
        assertEquals(30 to 13, pair("Skull crusher"))
        assertEquals(30 to 2, pair("Dips"))
        assertEquals(14 to 34, pair("Lateral raise"))
        assertEquals(23 to 5, pair("Face pull"))
        assertEquals(9 to 11, pair("Rear delt fly"))
        assertEquals(9 to 2, pair("Chest fly"))
        assertEquals(9 to 0, pair("Cable fly"))
        assertEquals(19 to 43, pair("Plank"))
        assertEquals(19 to 66, pair("Side plank"))
        assertEquals(1 to 18, pair("Calf raise"))
        assertEquals(10 to 1, pair("Hip thrust"))
        assertEquals(10 to 11, pair("Glute bridge"))
        assertEquals(22 to 77, pair("Push-up"))
        assertEquals(6 to 83, pair("Crunch"))
        assertEquals(16 to 1, pair("Hanging leg raise"))
        assertEquals(15 to 0, pair("Leg curl"))
    }

    @Test
    fun labelsComeFromGarminAndCustomHasNone() {
        val bench = GarminExerciseCatalog.map(7, "Bench press")
        assertEquals("Barbell Bench Press", bench.catalogueLabel)
        assertEquals("Bench press", bench.appName)
        assertEquals(7L, bench.appExerciseId)
        assertFalse(bench.isCustom)
        val custom = GarminExerciseCatalog.map(21, "Leg extension")
        assertTrue(custom.isCustom)
        assertEquals(ExerciseMapping.CUSTOM_CATEGORY, custom.category)
        assertEquals(21, custom.exerciseName)
        assertNull(custom.catalogueLabel)
    }

    @Test
    fun normalisationFoldsCasePunctuationPluralsAndSynonyms() {
        assertEquals("childs pose", GarminExerciseCatalog.normalise("Child's pose"))
        assertEquals("pull up", GarminExerciseCatalog.normalise("Pull-Ups"))
        assertEquals("pullup", GarminExerciseCatalog.normalise("Pullups"))
        assertEquals("biceps curl", GarminExerciseCatalog.normalise("  Bicep   Curls "))
        assertEquals("dumbbell fly", GarminExerciseCatalog.normalise("Dumbell Flyes"))
        assertEquals(pair("Pull-up"), pair("PULL UPS"))
        assertEquals(pair("Pull-up"), pair("pullups"))
        assertEquals(pair("Chin-up"), pair("chinups"))
        assertEquals(pair("Push-up"), pair("Push Ups"))
        assertEquals(pair("Bench press"), pair("Bench Presses"))
        assertEquals(pair("Romanian deadlift"), pair("RDL"))
        assertEquals(pair("Overhead press"), pair("OHP"))
        assertEquals(pair("Chest fly"), pair("Dumbbell flyes"))
        assertEquals(pair("Hanging leg raise"), pair("Hanging leg raises"))
    }

    @Test
    fun suffixMatchingHandlesQualifiers() {
        assertEquals(pair("Bench press"), pair("Smith machine bench press"))
        assertEquals(pair("Lat pulldown"), pair("Close grip lat pulldown"))
        assertEquals(pair("Leg curl"), pair("Nautilus lying leg curl"))
        assertTrue(GarminExerciseCatalog.map(3, "Something entirely new").isCustom)
        assertTrue(GarminExerciseCatalog.map(3, "").isCustom)
        assertTrue(GarminExerciseCatalog.map(3, "Leg extension").isCustom)
    }

    @Test
    fun customNameNumbersStayInTheU16RangeAndAreStable() {
        assertEquals(0, GarminExerciseCatalog.customNameNumber(0))
        assertEquals(65533, GarminExerciseCatalog.customNameNumber(65533))
        val big = GarminExerciseCatalog.customNameNumber(65534)
        assertTrue(big in 1000..65000)
        assertEquals(big, GarminExerciseCatalog.customNameNumber(65534))
        val huge = GarminExerciseCatalog.customNameNumber(9_876_543_210_123L)
        assertTrue(huge in 1000..65000)
        val negative = GarminExerciseCatalog.customNameNumber(-5)
        assertTrue(negative in 1000..65000)
        assertEquals(GarminExerciseCatalog.map(70_000, "Weird machine").exerciseName, GarminExerciseCatalog.customNameNumber(70_000))
    }

    @Test
    fun everySeededExerciseMapsExceptTheDeliberateCustoms() {
        val mappings = Seed.exercises.mapIndexed { i, e -> GarminExerciseCatalog.map(i + 1L, e.name) }
        val custom = mappings.filter { it.isCustom }.map { it.appName }.toSet()
        assertEquals(setOf("Leg extension", "Dead hang"), custom)
        val mapped = mappings.filterNot { it.isCustom }
        assertEquals(Seed.exercises.size - 2, mapped.size)
        mapped.forEach { m ->
            assertTrue("${m.appName} has no label", !m.catalogueLabel.isNullOrBlank())
            assertTrue("${m.appName} category out of range", m.category in 0..53)
            assertTrue("${m.appName} name out of range", m.exerciseName in 0..65000)
        }
        // Distinct app exercises map to distinct catalogue pairs, so one title per pair stays unambiguous.
        val pairs = mapped.map { it.category to it.exerciseName }
        assertEquals(pairs.size, pairs.toSet().size)
    }

    @Test
    fun seededStretchesAndHoldsMapToWarmUpAndSquatEntries() {
        assertEquals(31 to 44, pair("Hamstring stretch"))
        assertEquals(31 to 45, pair("Couch stretch"))
        assertEquals(31 to 49, pair("Hip flexor stretch"))
        assertEquals(31 to 39, pair("Child's pose"))
        assertEquals(28 to 20, pair("Wall sit"))
        assertEquals(17 to 18, pair("Bulgarian split squat"))
        assertEquals(23 to 35, pair("Inverted row"))
        assertEquals(6 to 1, pair("Cable crunch"))
        assertEquals(30 to 2, pair("Dip"))
    }

    @Test
    fun workoutEncoderMappingsUseTheCatalogue() {
        val plan = WatchWorkoutPlan(
            "Test", 1, 0,
            listOf(
                WatchExercise(1, "Bench press", WatchExerciseKind.WEIGHT, emptyList(), 60),
                WatchExercise(2, "Leg extension", WatchExerciseKind.WEIGHT, emptyList(), 60)
            )
        )
        val mappings = WorkoutEncoder.mappings(plan)
        assertEquals(listOf(false, true), mappings.map { it.isCustom })
        assertEquals(2, mappings[1].exerciseName)
    }
}
