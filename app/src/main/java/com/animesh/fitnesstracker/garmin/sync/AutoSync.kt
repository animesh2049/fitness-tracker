package com.animesh.fitnesstracker.garmin.sync

/**
 * Milestone 22 foreground trigger: when the app comes to the foreground and a watch is paired with
 * "sync on open" enabled, Bluetooth is on and the last successful sync is older than an hour (or there
 * was none), start a sync through the controller. [shouldSync] is the pure decision for tests.
 */
object AutoSync {
    const val MIN_INTERVAL_MS = 60L * 60 * 1000

    fun shouldSync(watch: WatchInfo?, bluetoothOn: Boolean, nowMillis: Long): Boolean {
        if (watch == null || !watch.autoSyncOnOpen || !bluetoothOn) return false
        val last = watch.lastSyncAtMillis ?: return true
        return nowMillis - last >= MIN_INTERVAL_MS
    }

    fun onAppForeground(controller: WatchController, nowMillis: Long = System.currentTimeMillis()) {
        if (shouldSync(controller.currentWatch, controller.isBluetoothOn(), nowMillis)) controller.requestSync()
    }
}
