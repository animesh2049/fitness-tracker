package com.animesh.fitnesstracker

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.animesh.fitnesstracker.data.AppDatabase
import com.animesh.fitnesstracker.data.model.HealthMinute
import com.animesh.fitnesstracker.garmin.fit.DecodedFit
import com.animesh.fitnesstracker.garmin.fit.FileIdRec
import com.animesh.fitnesstracker.garmin.fit.MonitoringRec
import com.animesh.fitnesstracker.garmin.fitimport.FitImporter
import com.animesh.fitnesstracker.garmin.fitimport.HealthRebuild
import com.animesh.fitnesstracker.garmin.sync.RawFileStore
import com.animesh.fitnesstracker.garmin.sync.StoredFile
import com.animesh.fitnesstracker.repository.HealthRepository
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one-time rebuild of the health rows after the version 4 schema change: due on a fresh
 * preferences file, satisfied by an empty store or by the Watch screen's own re-import, and a
 * real `reimportAll` when stored files exist. Same fakes as [HealthDaoTest]: an in-memory Room
 * database, files under a temp root, and a decoder that returns hand built [DecodedFit] values.
 */
@RunWith(AndroidJUnit4::class)
class HealthRebuildTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val wed: LocalDate = LocalDate.of(2026, 9, 16)
    private val wedDay = wed.toEpochDay()

    private lateinit var db: AppDatabase
    private lateinit var root: File
    private lateinit var store: TestStore
    private lateinit var importer: FitImporter
    private lateinit var health: HealthRepository
    private lateinit var prefs: SharedPreferences
    private val fits = HashMap<String, DecodedFit>()

    private fun at(time: String): Long = LocalDateTime.of(wed, LocalTime.parse(time)).atZone(zone).toEpochSecond()

    private open inner class TestStore(override val root: File) : RawFileStore {
        private val list = ArrayList<StoredFile>()
        override fun exists(watchIndex: Int, fitType: Int, watchTimestamp: Long?) = list.any { it.watchIndex == watchIndex && it.fitType == fitType && it.watchTimestamp == watchTimestamp }
        override fun save(watchIndex: Int, fitType: Int, watchTimestamp: Long?, bytes: ByteArray): StoredFile {
            val file = File(root, "$fitType/f_${watchTimestamp}_$watchIndex.fit")
            file.parentFile.mkdirs()
            file.writeBytes(bytes)
            return StoredFile(watchIndex, fitType, watchTimestamp, file).also { list += it }
        }
        override fun saveImported(fitType: Int, timeCreated: Long?, bytes: ByteArray) = save(0, fitType, timeCreated, bytes)
        override fun listAll(): List<StoredFile> = list.sortedBy { it.watchTimestamp ?: 0 }
        override fun totalBytes(): Long = list.sumOf { it.sizeBytes }
    }

    private fun stored(key: String, fit: DecodedFit, index: Int, ts: Long): StoredFile {
        fits[key] = fit
        return store.save(index, 32, ts, key.toByteArray())
    }

    private fun mon(ts: Long, steps: Long) = MonitoringRec(ts, 60, steps, null, null, 6, null, null, null)

    private fun monitoringFit(created: Long, vararg recs: MonitoringRec) = DecodedFit(FileIdRec(32, timeCreated = created), monitoring = recs.toList())

    /** Two monitoring files of the same day whose counters end at 130 and 300 steps. */
    private fun storeTwoFiles(): List<StoredFile> = listOf(
        stored("a", monitoringFit(at("12:00"), mon(at("07:00"), 100), mon(at("07:01"), 130)), 1, at("12:00")),
        stored("b", monitoringFit(at("21:22"), mon(at("12:05"), 250), mon(at("12:06"), 300)), 2, at("21:22"))
    )

    private suspend fun steps(): Int = health.observeDayTotals(wedDay).first()?.steps ?: 0

    private suspend fun insertStrayRow() {
        db.healthDao().insertMinutesReplace(listOf(HealthMinute(at("06:00"), wedDay, steps = 999)))
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        root = File(context.cacheDir, "rebuild-test-${System.nanoTime()}").apply { mkdirs() }
        store = TestStore(root)
        importer = FitImporter(db, store, decode = { bytes -> fits.getValue(String(bytes)) }, zone = zone, now = { 1_758_000_000_000 })
        health = HealthRepository(db, zone)
        prefs = context.getSharedPreferences("health_rebuild_test_${System.nanoTime()}", Context.MODE_PRIVATE)
    }

    @After
    fun tearDown() {
        db.close()
        root.deleteRecursively()
        prefs.edit().clear().commit()
    }

    @Test
    fun isDueOnAFreshInstall() = runTest {
        val rebuild = HealthRebuild(prefs, store, importer)
        assertTrue(rebuild.isDue)
        assertFalse(prefs.contains(HealthRebuild.KEY_VERSION))

        rebuild.runIfNeeded()

        assertEquals("an empty store only records the version", HealthRebuild.State.Idle, rebuild.state.value)
        assertFalse(rebuild.isDue)
        assertEquals(HealthRebuild.HEALTH_SCHEMA, prefs.getInt(HealthRebuild.KEY_VERSION, 0))
    }

    @Test
    fun rebuildsFromStoredFilesWhenDue() = runTest {
        val files = storeTwoFiles()
        importer.importFiles(files)
        assertEquals(300, steps())
        insertStrayRow()
        assertEquals(1_299, steps())

        val rebuild = HealthRebuild(prefs, store, importer)
        assertTrue(rebuild.isDue)
        rebuild.runIfNeeded()

        val state = rebuild.state.value
        assertTrue("expected Done, got $state", state is HealthRebuild.State.Done)
        assertEquals(2, (state as HealthRebuild.State.Done).summary.filesImported)
        assertEquals(0, state.summary.filesFailed)
        assertEquals("the stray row is gone and the files' counters are back", 300, steps())
        assertFalse(rebuild.isDue)
        assertEquals(HealthRebuild.HEALTH_SCHEMA, prefs.getInt(HealthRebuild.KEY_VERSION, 0))
    }

    @Test
    fun doesNotRunTwice() = runTest {
        importer.importFiles(storeTwoFiles())
        val rebuild = HealthRebuild(prefs, store, importer)
        rebuild.runIfNeeded()
        val first = rebuild.state.value
        assertTrue(first is HealthRebuild.State.Done)

        insertStrayRow()
        rebuild.runIfNeeded()

        assertEquals("state is untouched by a second call", first, rebuild.state.value)
        assertEquals("the stray row survives because nothing was rebuilt", 1_299, steps())
        assertFalse(rebuild.isDue)
    }

    @Test
    fun markDoneSatisfiesTheRebuild() = runTest {
        importer.importFiles(storeTwoFiles())
        insertStrayRow()
        val rebuild = HealthRebuild(prefs, store, importer)
        assertTrue(rebuild.isDue)

        rebuild.markDone()
        assertFalse(rebuild.isDue)
        rebuild.runIfNeeded()

        assertEquals(HealthRebuild.State.Idle, rebuild.state.value)
        assertEquals("nothing was rebuilt, the stray row is still there", 1_299, steps())
    }

    /**
     * Documents the current behaviour: `runIfNeeded` lists the store before its try block, so a
     * store that cannot be listed throws out of the call instead of ending in [HealthRebuild.State.Failed].
     * The rebuild stays due, so the next launch tries again.
     */
    @Test
    fun aFailingStoreReportsFailedAndStaysDue() = runTest {
        val broken = object : TestStore(root) {
            override fun listAll(): List<StoredFile> = throw IllegalStateException("store unreadable")
        }
        val rebuild = HealthRebuild(prefs, broken, importer)
        assertTrue(rebuild.isDue)

        rebuild.runIfNeeded()

        val state = rebuild.state.value
        assertTrue("a store that cannot be listed becomes Failed, got $state", state is HealthRebuild.State.Failed)
        assertEquals("store unreadable", (state as HealthRebuild.State.Failed).message)
        assertTrue("the rebuild is still due for the next launch", rebuild.isDue)
        assertFalse(prefs.contains(HealthRebuild.KEY_VERSION))
    }
}
