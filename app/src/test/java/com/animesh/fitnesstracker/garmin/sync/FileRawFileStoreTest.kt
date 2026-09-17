package com.animesh.fitnesstracker.garmin.sync

import java.io.File
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FileRawFileStoreTest {
    private lateinit var root: File
    private lateinit var store: FileRawFileStore

    // 2026-09-16T07:05:09Z
    private val ts = 1789542309L

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "rawstore-" + System.nanoTime()).also { it.mkdirs() }
        store = FileRawFileStore(root, ZoneOffset.UTC)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `save uses the documented path pattern`() {
        val f = store.save(1234, 32, ts, byteArrayOf(1, 2, 3))
        assertEquals(File(root, "MONITOR/2026/MONITOR_2026-09-16_07-05-09_1234.fit"), f.file)
        assertArrayEquals(byteArrayOf(1, 2, 3), f.file.readBytes())
        assertEquals(ts * 1000, f.file.lastModified())
        assertEquals(1234, f.watchIndex)
        assertEquals(32, f.fitType)
        assertEquals(3L, f.sizeBytes)
        assertEquals(File(root, "ACTIVITY/2026/ACTIVITY_2026-09-16_07-05-09_88.fit"), store.save(88, 4, ts, byteArrayOf(9)).file)
        assertEquals(File(root, "HRV_STATUS/2026/HRV_STATUS_2026-09-16_07-05-09_7.fit"), store.save(7, 68, ts, byteArrayOf(9)).file)
        assertEquals(File(root, "OTHER73/2026/OTHER73_2026-09-16_07-05-09_9.fit"), store.save(9, 73, ts, byteArrayOf(9)).file)
    }

    @Test
    fun `unknown timestamp drops the year directory and the date`() {
        val f = store.save(55, 49, null, byteArrayOf(1))
        assertEquals(File(root, "SLEEP/SLEEP_55.fit"), f.file)
        assertNull(f.watchTimestamp)
    }

    @Test
    fun `exists requires a non-empty file with the same coordinates`() {
        assertFalse(store.exists(1, 32, ts))
        store.save(1, 32, ts, byteArrayOf(1))
        assertTrue(store.exists(1, 32, ts))
        assertFalse(store.exists(1, 32, ts + 1))
        assertFalse(store.exists(2, 32, ts))
        assertFalse(store.exists(1, 49, ts))
        store.save(3, 44, null, ByteArray(0))
        assertFalse(store.exists(3, 44, null))
        // Overwrite keeps the same name.
        store.save(1, 32, ts, byteArrayOf(7, 7))
        assertEquals(1, File(root, "MONITOR/2026").listFiles()!!.size)
        assertArrayEquals(byteArrayOf(7, 7), store.fileFor(1, 32, ts).readBytes())
    }

    @Test
    fun `saveImported uses index 0 and the FIT time or a uuid`() {
        val timed = store.saveImported(4, ts, byteArrayOf(1))
        assertEquals(File(root, "ACTIVITY/2026/ACTIVITY_2026-09-16_07-05-09_0.fit"), timed.file)
        assertEquals(0, timed.watchIndex)
        val untimed = store.saveImported(32, null, byteArrayOf(1))
        assertEquals(File(root, "MONITOR"), untimed.file.parentFile)
        assertTrue(untimed.file.name.matches(Regex("MONITOR_[0-9a-f-]{36}\\.fit")))
        assertNull(untimed.watchTimestamp)
    }

    @Test
    fun `listAll parses names back and sorts oldest first`() {
        store.save(20, 32, ts + 100, byteArrayOf(1))
        store.save(10, 49, ts, byteArrayOf(1, 2))
        store.save(30, 68, null, byteArrayOf(1, 2, 3))
        store.saveImported(44, null, byteArrayOf(1, 2, 3, 4))
        File(root, "junk.txt").writeText("ignore me")
        val all = store.listAll()
        assertEquals(4, all.size)
        val timed = all.filter { it.watchTimestamp != null }
        assertEquals(listOf(10, 20), timed.map { it.watchIndex })
        assertEquals(listOf(49, 32), timed.map { it.fitType })
        assertEquals(ts, timed[0].watchTimestamp)
        val hrv = all.first { it.fitType == 68 }
        assertEquals(30, hrv.watchIndex)
        assertNull(hrv.watchTimestamp)
        val imported = all.first { it.fitType == 44 }
        assertEquals(0, imported.watchIndex)
        assertEquals(10L, store.totalBytes() - "ignore me".length)
    }

    @Test
    fun `type names round trip`() {
        for ((num, name) in listOf(4 to "ACTIVITY", 15 to "MONITOR_A", 28 to "MONITOR_DAILY", 32 to "MONITOR", 44 to "METRICS", 49 to "SLEEP", 68 to "HRV_STATUS", 9 to "OTHER9")) {
            assertEquals(name, FileRawFileStore.typeName(num))
            assertEquals(num, FileRawFileStore.fitType(name))
        }
        assertNull(FileRawFileStore.fitType("WHATEVER"))
    }
}
