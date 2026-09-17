package com.animesh.fitnesstracker.garmin.sync

import com.animesh.fitnesstracker.garmin.gfdi.GarminTime
import com.animesh.fitnesstracker.garmin.gfdi.GfdiId
import com.animesh.fitnesstracker.garmin.gfdi.GfdiStatus
import com.animesh.fitnesstracker.garmin.gfdi.LeReader
import com.animesh.fitnesstracker.garmin.gfdi.PhoneInfo
import com.animesh.fitnesstracker.garmin.gfdi.SystemEvent
import java.io.File
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** End to end: SyncSession against the scripted FakeWatch, byte for byte against the FIT fixtures. */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncSessionTest {
    private val roots = ArrayList<File>()

    @After
    fun tearDown() {
        roots.forEach { it.deleteRecursively() }
    }

    private fun fixture(name: String): ByteArray = File(javaClass.classLoader!!.getResource("fit/$name")!!.toURI()).readBytes()

    // Garmin timestamps: 2026-09-16T07:05:09Z and a few neighbours.
    private val g1 = GarminTime.fromUnix(1789542309L)
    private val g2 = g1 + 3600
    private val g3 = g1 + 7200
    private val g4 = g1 - 86400
    private val g5 = g1 + 10000

    private val monitor = fixture("MONITOR_M9GL2445.fit")
    private val sleep = fixture("SLEEP_G9G80120.fit")
    private val metrics = fixture("METRICS_G9G80119.fit")
    private val hrv = fixture("HRV_G9G80118.fit")
    private val older = fixture("MONITOR_M9GL2255.fit")
    private val late = fixture("MONITOR_M9G00000.fit")

    private fun files() = linkedMapOf(
        10 to FakeWatch.FakeFile(32, g1, monitor),
        11 to FakeWatch.FakeFile(49, g2, sleep),
        12 to FakeWatch.FakeFile(44, g3, metrics),
        13 to FakeWatch.FakeFile(68, 0, hrv),
        14 to FakeWatch.FakeFile(0, g1, ByteArray(100), dataType = 255),
        15 to FakeWatch.FakeFile(9, g1, ByteArray(64)),
        16 to FakeWatch.FakeFile(32, g4, older)
    )

    private val watchInfo = WatchInfo("AA:BB:CC:DD:EE:FF", "Forerunner 570")

    private inner class Harness(scope: TestScope, val watch: FakeWatch) {
        val root = File(System.getProperty("java.io.tmpdir"), "sync-" + System.nanoTime()).also { it.mkdirs(); roots.add(it) }
        val store = FileRawFileStore(root, ZoneOffset.UTC)
        val imported = ArrayList<List<StoredFile>>()
        val log = SyncLog(null, clock = { scope.currentTime })
        val states = ArrayList<SyncState>()
        val session = SyncSession(
            transportFactory = { _, _ -> watch },
            store = store,
            importHook = { files -> imported.add(files); ImportSummary(files.size, 0) },
            log = log,
            phone = PhoneInfo("Pixel 7 Pro", "Google", "cheetah"),
            clock = { scope.currentTime },
            zone = ZoneOffset.UTC
        )

        suspend fun run(firstConnect: Boolean) = session.run(watchInfo, firstConnect) { states.add(it) }
        fun logText() = log.lines.value.joinToString("\n") { it.text }
    }

    @Test
    fun `full first sync downloads new files byte for byte, archives them and survives a bad chunk and a mid-session sync request`() = runTest {
        val watch = FakeWatch(
            files(), backgroundScope,
            corruptChunk = 11 to 1,
            syncPushAfterArchiveOf = 12,
            lateFiles = mapOf(20 to FakeWatch.FakeFile(32, g5, late))
        )
        val h = Harness(this, watch)
        h.store.save(16, 32, GarminTime.toUnix(g4), older)

        val outcome = h.run(firstConnect = true)

        assertTrue(h.logText(), outcome.success)
        assertEquals(listOf(10, 11, 12, 13, 20), outcome.newFiles.map { it.watchIndex })
        assertArrayEquals(monitor, h.store.fileFor(10, 32, GarminTime.toUnix(g1)).readBytes())
        assertArrayEquals(sleep, h.store.fileFor(11, 49, GarminTime.toUnix(g2)).readBytes())
        assertArrayEquals(metrics, h.store.fileFor(12, 44, GarminTime.toUnix(g3)).readBytes())
        assertArrayEquals(hrv, File(h.root, "HRV_STATUS/HRV_STATUS_13.fit").readBytes())
        assertArrayEquals(late, h.store.fileFor(20, 32, GarminTime.toUnix(g5)).readBytes())
        assertEquals(File(h.root, "MONITOR/2026/MONITOR_2026-09-16_07-05-09_10.fit"), outcome.newFiles[0].file)

        assertEquals(listOf(10, 11, 12, 13, 16, 20), watch.archived.toList())
        assertFalse(watch.archived.contains(14))
        assertFalse(watch.archived.contains(15))
        val requested = watch.received.filter { it.first == GfdiId.DOWNLOAD_REQUEST }.map { LeReader(it.second).u16() }
        assertEquals(listOf(0, 10, 11, 12, 13, 0, 20), requested)

        assertEquals(1, h.imported.size)
        assertEquals(5, h.imported[0].size)
        assertEquals(5, outcome.importSummary!!.filesImported)

        assertEquals(SyncState.Connecting, h.states.first())
        assertTrue(h.states.contains(SyncState.Handshake))
        assertTrue(h.states.contains(SyncState.Listing))
        val downloading = h.states.filterIsInstance<SyncState.Downloading>()
        assertEquals("MONITOR 16 Sep", downloading.first().fileLabel)
        assertTrue(downloading.any { it.fileLabel == "HRV_STATUS #13" })
        assertTrue(downloading.any { it.fileLabel == "SLEEP 16 Sep" && it.done == 1 && it.total == 5 })
        assertTrue(h.states.contains(SyncState.Importing))
        assertEquals(5, (h.states.last() as SyncState.Done).newFiles)

        val log = h.logText()
        assertTrue(log, log.contains("CRC mismatch on SLEEP 16 Sep at offset 200"))
        assertTrue(log, log.contains("Watch requested a sync mid-session"))
        assertTrue(log, log.contains("Already have MONITOR 15 Sep"))
        assertTrue(log, log.contains("Unsupported message 5039"))
        assertTrue(log, log.contains("Battery 77%"))

        assertEquals(
            listOf(SystemEvent.TIME_UPDATED, SystemEvent.SYNC_READY, SystemEvent.PAIR_COMPLETE, SystemEvent.SYNC_COMPLETE, SystemEvent.SETUP_WIZARD_COMPLETE, SystemEvent.SYNC_COMPLETE),
            watch.systemEvents
        )
        val time = LeReader(watch.currentTimeReply!!)
        assertEquals(GfdiId.CURRENT_TIME_REQUEST, time.u16()); assertEquals(GfdiStatus.ACK, time.u8()); assertEquals(0x11223344L, time.u32())
        val info = watch.deviceInfoReply!!
        assertEquals(1, info.last().toInt())
        assertTrue(watch.connectedNotificationEchoed)
        assertTrue(watch.received.any { it.first == GfdiId.RESPONSE && LeReader(it.second).let { r -> r.u16() == 5039 && r.u8() == GfdiStatus.UNSUPPORTED } })
        val ids = watch.received.map { it.first }
        assertTrue(ids.indexOf(GfdiId.FILTER) in 1 until ids.indexOf(GfdiId.DOWNLOAD_REQUEST))
        assertTrue(ids.indexOf(GfdiId.CONFIGURATION) < ids.indexOf(GfdiId.SUPPORTED_FILE_TYPES_REQUEST))

        assertEquals(77, outcome.batteryPercent)
        assertEquals(3999999999L, outcome.unitId)
        assertEquals("18.24", outcome.firmwareVersion)
        assertTrue(watch.closed)
    }

    @Test
    fun `reliable handle and a later sync without pairing events`() = runTest {
        val watch = FakeWatch(files(), backgroundScope, maxWrite = 23, reliable = true, corruptChunk = 10 to 0)
        val h = Harness(this, watch)
        val outcome = h.run(firstConnect = false)
        assertTrue(h.logText(), outcome.success)
        assertEquals(listOf(10, 11, 12, 13, 16), outcome.newFiles.map { it.watchIndex })
        assertArrayEquals(monitor, h.store.fileFor(10, 32, GarminTime.toUnix(g1)).readBytes())
        assertArrayEquals(older, h.store.fileFor(16, 32, GarminTime.toUnix(g4)).readBytes())
        assertEquals(listOf(SystemEvent.TIME_UPDATED, SystemEvent.SYNC_READY, SystemEvent.SYNC_COMPLETE), watch.systemEvents)
        assertTrue(h.logText().contains("(reliable)"))
        assertTrue(h.logText().contains("CRC mismatch on MONITOR 16 Sep at offset 0"))
    }

    @Test
    fun `FILE_AVAILABLE pushed during the session is downloaded too`() = runTest {
        val watch = FakeWatch(
            files(), backgroundScope,
            fileAvailableAfterArchiveOf = 10,
            availableFile = 21 to FakeWatch.FakeFile(4, g5, late)
        )
        val h = Harness(this, watch)
        val outcome = h.run(firstConnect = false)
        assertTrue(h.logText(), outcome.success)
        assertTrue(outcome.newFiles.any { it.watchIndex == 21 && it.fitType == 4 })
        assertArrayEquals(late, h.store.fileFor(21, 4, GarminTime.toUnix(g5)).readBytes())
        assertTrue(watch.archived.contains(21))
        assertTrue(h.logText().contains("Watch announced ACTIVITY 16 Sep (index 21)"))
    }

    @Test
    fun `nothing new leaves the store untouched and still reports done`() = runTest {
        val watch = FakeWatch(emptyMap(), backgroundScope)
        val h = Harness(this, watch)
        val outcome = h.run(firstConnect = false)
        assertTrue(outcome.success)
        assertTrue(outcome.newFiles.isEmpty())
        assertTrue(h.imported.isEmpty())
        assertNull(outcome.importSummary)
        assertEquals(0, (h.states.last() as SyncState.Done).newFiles)
        assertEquals(listOf(SystemEvent.SYNC_COMPLETE), watch.systemEvents.filter { it == SystemEvent.SYNC_COMPLETE })
    }

    @Test
    fun `silent watch fails the handshake after 20 s`() = runTest {
        val watch = FakeWatch(files(), backgroundScope, silent = true)
        val h = Harness(this, watch)
        val outcome = h.run(firstConnect = true)
        assertFalse(outcome.success)
        assertEquals("Handshake timed out (no DEVICE_INFORMATION)", outcome.reason)
        val failed = h.states.last() as SyncState.Failed
        assertEquals(outcome.reason, failed.reason)
        assertEquals(20_000L, currentTime)
        assertTrue(watch.closed)
    }

    @Test
    fun `link drop during a download fails cleanly with the transport reason`() = runTest {
        val watch = FakeWatch(files(), backgroundScope, dropAfterBytes = 6_000)
        val h = Harness(this, watch)
        val outcome = h.run(firstConnect = false)
        assertFalse(outcome.success)
        assertEquals("connection lost", outcome.reason)
        assertTrue(h.states.last() is SyncState.Failed)
        assertNotNull(outcome.unitId)
        assertTrue(watch.closed)
    }

    @Test
    fun `a watch that stops sending chunks trips the 60 s silence timeout`() = runTest {
        val watch = FakeWatch(files(), backgroundScope, stallDownloads = true)
        val h = Harness(this, watch)
        val outcome = h.run(firstConnect = false)
        assertFalse(outcome.success)
        assertEquals("Watch went silent for 60 s while waiting for data for directory", outcome.reason)
        assertTrue(watch.closed)
    }

    @Test
    fun `cancellation disconnects and reports idle`() = runTest {
        val watch = FakeWatch(files(), backgroundScope, stallDownloads = true)
        val h = Harness(this, watch)
        val job = launch { runCatching { h.run(firstConnect = false) } }
        runCurrent()
        assertEquals(SyncState.Listing, h.states.last())
        job.cancel()
        advanceUntilIdle()
        assertEquals(SyncState.Idle, h.states.last())
        assertTrue(watch.closed)
        assertTrue(h.logText().contains("Sync cancelled"))
    }
}
