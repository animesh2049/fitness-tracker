package com.animesh.fitnesstracker.garmin.sync

import com.animesh.fitnesstracker.service.GarminSyncService
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncHelpersTest {
    private val paired = WatchInfo("AA:BB", "Forerunner 570", lastSyncAtMillis = 1_000_000L)

    @Test
    fun `auto sync only when paired, enabled, bluetooth on and an hour has passed`() {
        val hour = AutoSync.MIN_INTERVAL_MS
        assertFalse(AutoSync.shouldSync(null, true, 1_000_000L + hour))
        assertFalse(AutoSync.shouldSync(paired, false, 1_000_000L + hour))
        assertFalse(AutoSync.shouldSync(paired.copy(autoSyncOnOpen = false), true, 1_000_000L + hour))
        assertFalse(AutoSync.shouldSync(paired, true, 1_000_000L + hour - 1))
        assertTrue(AutoSync.shouldSync(paired, true, 1_000_000L + hour))
        assertTrue(AutoSync.shouldSync(paired.copy(lastSyncAtMillis = null), true, 5L))
    }

    @Test
    fun `bluetooth permissions per sdk level`() {
        assertEquals(listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"), BluetoothPermissions.required(31))
        assertEquals(listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"), BluetoothPermissions.required(35))
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), BluetoothPermissions.required(26))
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), BluetoothPermissions.required(30))
    }

    @Test
    fun `notification text follows the sync state`() {
        assertEquals("Connecting to Forerunner 570", GarminSyncService.text(SyncState.Connecting, "Forerunner 570"))
        assertEquals("Downloading file 3 of 7", GarminSyncService.text(SyncState.Downloading(2, 7, "MONITOR 16 Sep"), "Forerunner 570"))
        assertEquals("Synced 2 new file(s)", GarminSyncService.text(SyncState.Done(2, 0L), "Forerunner 570"))
        assertEquals("Up to date", GarminSyncService.text(SyncState.Done(0, 0L), "Forerunner 570"))
        assertEquals("Sync failed: Watch went silent", GarminSyncService.text(SyncState.Failed("Watch went silent", 0L), "Forerunner 570"))
    }

    @Test
    fun `sync log keeps the last lines in memory and on disk`() {
        val file = File(System.getProperty("java.io.tmpdir"), "synclog-" + System.nanoTime() + "/sync.log")
        var t = 0L
        val log = SyncLog(file, maxLines = 5, clock = { ++t })
        repeat(12) { log.log("line $it") }
        assertEquals((7..11).map { "line $it" }, log.lines.value.map { it.text })
        assertEquals(12L, log.lines.value.last().atMillis)
        // Wait for the single writer thread, then reload from disk.
        Thread.sleep(200)
        val reloaded = SyncLog(file, maxLines = 5)
        assertEquals((7..11).map { "line $it" }, reloaded.lines.value.map { it.text })
        file.parentFile!!.deleteRecursively()
    }

    @Test
    fun `sync state running flag`() {
        assertFalse(SyncState.Idle.isRunning)
        assertTrue(SyncState.Connecting.isRunning)
        assertTrue(SyncState.Downloading(0, 1, "x").isRunning)
        assertFalse(SyncState.Done(0, 0).isRunning)
        assertFalse(SyncState.Failed("x", 0).isRunning)
    }
}
