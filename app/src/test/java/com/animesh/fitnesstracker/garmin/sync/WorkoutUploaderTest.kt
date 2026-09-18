package com.animesh.fitnesstracker.garmin.sync

import com.animesh.fitnesstracker.garmin.gfdi.GfdiId
import com.animesh.fitnesstracker.garmin.gfdi.LeReader
import com.animesh.fitnesstracker.garmin.gfdi.PhoneInfo
import com.animesh.fitnesstracker.garmin.gfdi.SystemEvent
import java.io.File
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The workout push end to end: SyncSession.runUpload against the scripted FakeWatch. The encoder is not
 * involved; the bytes are an arbitrary pattern, which is all the transfer layer cares about.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutUploaderTest {
    private val roots = ArrayList<File>()

    @After
    fun tearDown() {
        roots.forEach { it.deleteRecursively() }
    }

    private val workout = ByteArray(1000) { (it * 7 + 3).toByte() }
    private val name = "Push day · Thu 17 Sep"
    private val watchInfo = WatchInfo("AA:BB:CC:DD:EE:FF", "Forerunner 570")

    /** A previously pushed workout at index 12 and an unrelated activity at 30. */
    private fun files() = linkedMapOf(
        12 to FakeWatch.FakeFile(5, 0, ByteArray(300)),
        30 to FakeWatch.FakeFile(4, 0, ByteArray(500))
    )

    private inner class Harness(scope: TestScope, val watch: FakeWatch) {
        val root = File(System.getProperty("java.io.tmpdir"), "upload-" + System.nanoTime()).also { it.mkdirs(); roots.add(it) }
        val log = SyncLog(null, clock = { scope.currentTime })
        val states = ArrayList<SyncState>()
        val session = SyncSession(
            transportFactory = { _, _ -> watch },
            store = FileRawFileStore(root, ZoneOffset.UTC),
            importHook = { files -> ImportSummary(files.size, 0) },
            log = log,
            phone = PhoneInfo("Pixel 7 Pro", "Google", "cheetah"),
            clock = { scope.currentTime },
            zone = ZoneOffset.UTC
        )

        suspend fun upload(previousIndex: Int? = 12, bytes: ByteArray = workout) =
            session.runUpload(watchInfo, false, SyncSession.WorkoutUpload(bytes, name, previousIndex)) { states.add(it) }

        fun logText() = log.lines.value.joinToString("\n") { it.text }
        fun sent(id: Int) = watch.received.filter { it.first == id }
    }

    @Test
    fun `happy path uploads byte for byte, deletes the previous index and finishes with SYNC_COMPLETE`() = runTest {
        val watch = FakeWatch(files(), backgroundScope)
        val h = Harness(this, watch)

        val outcome = h.upload()

        assertTrue(h.logText(), outcome.success)
        assertEquals(77, outcome.uploadedIndex)
        assertEquals(1000, outcome.uploadedBytes)
        assertArrayEquals(workout, watch.uploaded[77])
        assertEquals(listOf(12), watch.deleted.toList())
        assertFalse(watch.directory.containsKey(12))
        assertTrue(watch.directory.containsKey(30))
        assertTrue(watch.archived.isEmpty())

        // Protocol order: delete, create, upload request, chunks, sync complete. No downloads in an upload session.
        val ids = watch.received.map { it.first }
        val flag = ids.indexOf(GfdiId.SET_FILE_FLAG)
        val create = ids.indexOf(GfdiId.CREATE_FILE)
        val request = ids.indexOf(GfdiId.UPLOAD_REQUEST)
        val firstChunk = ids.indexOf(GfdiId.FILE_TRANSFER_DATA)
        assertTrue(flag in 0 until create)
        assertTrue(create < request && request < firstChunk)
        assertTrue(ids.none { it == GfdiId.DOWNLOAD_REQUEST || it == GfdiId.FILTER })

        val flagPayload = LeReader(h.sent(GfdiId.SET_FILE_FLAG).single().second)
        assertEquals(12, flagPayload.u16()); assertEquals(0x20, flagPayload.u8())
        val createPayload = LeReader(h.sent(GfdiId.CREATE_FILE).single().second)
        assertEquals(1000L, createPayload.u32()); assertEquals(128, createPayload.u8()); assertEquals(5, createPayload.u8())
        val requestPayload = LeReader(h.sent(GfdiId.UPLOAD_REQUEST).single().second)
        assertEquals(77, requestPayload.u16()); assertEquals(1000L, requestPayload.u32()); assertEquals(0L, requestPayload.u32()); assertEquals(0, requestPayload.u16())

        // maxPacketSize 375 gives 362-byte chunks.
        assertEquals(listOf(0 to 362, 362 to 362, 724 to 276), watch.uploadChunks)
        assertEquals(listOf(SystemEvent.TIME_UPDATED, SystemEvent.SYNC_READY, SystemEvent.SYNC_COMPLETE), watch.systemEvents)

        assertEquals(SyncState.Connecting, h.states[0])
        assertEquals(SyncState.Handshake, h.states[1])
        val uploading = h.states.filterIsInstance<SyncState.Uploading>()
        assertEquals(SyncState.Uploading(0, 1000, name), uploading.first())
        assertEquals(listOf(0, 0, 362, 724, 1000), uploading.map { it.sentBytes })
        assertEquals(SyncState.Done(0, currentTime, uploadedWorkout = name), h.states.last())

        val log = h.logText()
        for (expected in listOf(
            "Workout push started for Forerunner 570 (AA:BB:CC:DD:EE:FF): $name",
            "Workout upload: $name (1000 bytes)",
            "Watch accepts workout files (128/5)",
            "Deleting previous workout (index 12)",
            "Previous workout index 12 deleted",
            "CREATE_FILE 128/5, 1000 bytes, id ",
            "Watch created file index 77 (type 128/5, number 77)",
            "UPLOAD_REQUEST index 77 accepted: offset 0, max 1000, crc seed 0",
            "Sending 1000 bytes in chunks of 362",
            "Upload done: 1000 bytes to index 77 in ",
            "SYNC_COMPLETE sent",
            "Watch acknowledged SYNC_COMPLETE",
            "Disconnected"
        )) assertTrue("missing '$expected' in:\n$log", log.contains(expected))
        assertTrue(watch.closed)
    }

    @Test
    fun `no previous index means no delete`() = runTest {
        val watch = FakeWatch(files(), backgroundScope)
        val h = Harness(this, watch)
        val outcome = h.upload(previousIndex = null)
        assertTrue(outcome.success)
        assertTrue(watch.deleted.isEmpty())
        assertTrue(h.sent(GfdiId.SET_FILE_FLAG).isEmpty())
        assertArrayEquals(workout, watch.uploaded[77])
    }

    @Test
    fun `DUPLICATE from CREATE_FILE fails with a plain message before any chunk is sent`() = runTest {
        val watch = FakeWatch(files(), backgroundScope, createStatus = 1)
        val h = Harness(this, watch)
        val outcome = h.upload()
        assertFalse(outcome.success)
        assertEquals("The watch already has this workout", outcome.reason)
        assertNull(outcome.uploadedIndex)
        assertTrue(h.sent(GfdiId.FILE_TRANSFER_DATA).isEmpty())
        assertTrue(h.sent(GfdiId.UPLOAD_REQUEST).isEmpty())
        assertEquals(SyncState.Failed("The watch already has this workout", currentTime), h.states.last())
        assertTrue(h.logText().contains("Workout push failed: The watch already has this workout"))
        assertTrue(watch.uploaded.isEmpty())
        assertTrue(watch.closed)
    }

    @Test
    fun `NO_SLOTS and NO_SPACE_FOR_TYPE report a full workout list`() = runTest {
        for (status in listOf(4, 5)) {
            val watch = FakeWatch(files(), backgroundScope, createStatus = status)
            val h = Harness(this, watch)
            val outcome = h.upload()
            assertFalse(outcome.success)
            assertEquals("The watch's workout list is full, delete some workouts on the watch", outcome.reason)
            assertTrue(watch.uploaded.isEmpty())
        }
    }

    @Test
    fun `RESEND in the middle rewinds and the file still arrives intact`() = runTest {
        val watch = FakeWatch(files(), backgroundScope, resendAtChunk = 2)
        val h = Harness(this, watch)
        val outcome = h.upload()
        assertTrue(h.logText(), outcome.success)
        assertArrayEquals(workout, watch.uploaded[77])
        // Chunk 2 (offset 724) is refused with RESEND from 362, so 362 and 724 go out again.
        assertEquals(listOf(0 to 362, 362 to 362, 724 to 276, 362 to 362, 724 to 276), watch.uploadChunks)
        assertTrue(h.logText().contains("Watch asked to resend from offset 362 (RESEND, retry 1 of 5)"))
        assertEquals(SyncState.Done(0, currentTime, uploadedWorkout = name), h.states.last())
    }

    @Test
    fun `CRC_MISMATCH is answered by resending the same chunk`() = runTest {
        val watch = FakeWatch(files(), backgroundScope, crcMismatchAtChunk = 1)
        val h = Harness(this, watch)
        val outcome = h.upload()
        assertTrue(h.logText(), outcome.success)
        assertArrayEquals(workout, watch.uploaded[77])
        assertEquals(listOf(0 to 362, 362 to 362, 362 to 362, 724 to 276), watch.uploadChunks)
        assertTrue(h.logText().contains("CRC mismatch reported at offset 362, resending (retry 1 of 5)"))
    }

    @Test
    fun `missing capability bit refuses before CREATE_FILE`() = runTest {
        val watch = FakeWatch(files(), backgroundScope, supportsWorkouts = false)
        val h = Harness(this, watch)
        val outcome = h.upload()
        assertFalse(outcome.success)
        assertEquals("The watch does not advertise workout download (capability 18 missing)", outcome.reason)
        assertTrue(h.sent(GfdiId.CREATE_FILE).isEmpty())
        assertTrue(h.sent(GfdiId.SET_FILE_FLAG).isEmpty())
        assertTrue(watch.deleted.isEmpty())
        assertTrue(h.states.last() is SyncState.Failed)
        assertTrue(watch.closed)
    }

    @Test
    fun `a small file goes in one chunk`() = runTest {
        val watch = FakeWatch(files(), backgroundScope)
        val h = Harness(this, watch)
        val small = ByteArray(100) { it.toByte() }
        val outcome = h.upload(bytes = small)
        assertTrue(outcome.success)
        assertArrayEquals(small, watch.uploaded[77])
        assertEquals(listOf(0 to 100), watch.uploadChunks)
    }
}
