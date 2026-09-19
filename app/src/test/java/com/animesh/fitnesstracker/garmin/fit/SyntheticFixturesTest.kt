package com.animesh.fitnesstracker.garmin.fit

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the committed fixtures to [SyntheticFixtures]: every file under `src/test/resources/fit`
 * must equal the generator's output byte for byte, so the fixtures cannot drift from the code that
 * documents them. With `-Dfit.regenerate=true` (or `FIT_REGENERATE=true` in the environment, which
 * reaches the forked test JVM without build script changes) the test first rewrites the files; see
 * the KDoc of [SyntheticFixtures] for the full command.
 */
class SyntheticFixturesTest {
    private val regenerate = System.getProperty("fit.regenerate") == "true" || System.getenv("FIT_REGENERATE") == "true"

    private fun resourceDir(): File {
        val candidates = listOf(File("src/test/resources/fit"), File("app/src/test/resources/fit"))
        return candidates.firstOrNull { it.isDirectory } ?: candidates.first().also { it.mkdirs() }
    }

    @Test
    fun generatorListsExactlyTheFixturesTheTestsLoad() {
        assertEquals(FitFixtures.all.toSortedSet(), SyntheticFixtures.files.keys.toSortedSet())
    }

    @Test
    fun committedFixturesEqualTheGeneratorOutput() {
        if (regenerate) {
            val dir = resourceDir()
            for ((name, generator) in SyntheticFixtures.files) File(dir, name).writeBytes(generator())
            println("Regenerated ${SyntheticFixtures.files.size} fixtures under ${dir.absolutePath}")
        }
        for (name in FitFixtures.all) {
            assertArrayEquals("$name differs from SyntheticFixtures; regenerate the resources", SyntheticFixtures.generate(name), FitFixtures.bytes(name))
        }
    }

    @Test
    fun generatorIsDeterministic() {
        for (name in FitFixtures.all) assertArrayEquals(name, SyntheticFixtures.generate(name), SyntheticFixtures.generate(name))
    }

    @Test
    fun fixturesStaySmall() {
        val sizes = FitFixtures.all.associateWith { SyntheticFixtures.generate(it).size }
        assertTrue("big monitor file is ${sizes["MONITOR_M9GL2255.fit"]} bytes", sizes.getValue("MONITOR_M9GL2255.fit") < 60_000)
        assertTrue("all fixtures total ${sizes.values.sum()} bytes", sizes.values.sum() < 100_000)
        assertTrue("SLEEP file must span more than one 200 byte sync chunk", sizes.getValue("SLEEP_G9G80120.fit") > 400)
    }

    @Test
    fun everyGeneratedFileRoundTripsThroughTheDecoder() {
        for (name in FitFixtures.all) {
            val decoded = FitDecoder.decode(SyntheticFixtures.generate(name))
            assertEquals(name, SyntheticFixtures.SERIAL, decoded.fileId.serialNumber)
            assertEquals(name, SyntheticFixtures.MANUFACTURER, decoded.fileId.manufacturer)
            assertEquals(name, SyntheticFixtures.PRODUCT, decoded.fileId.product)
        }
    }
}
