package com.animesh.fitnesstracker.garmin.sync

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * SharedPreferences-backed store of the paired [WatchInfo] (file `garmin_watch`), exposed as a
 * [StateFlow]. A null value means no watch is paired. Every field of the contract is persisted.
 */
class WatchPrefs(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("garmin_watch", Context.MODE_PRIVATE))

    private val _watch = MutableStateFlow(load())
    val watch: StateFlow<WatchInfo?> = _watch

    val current: WatchInfo? get() = _watch.value

    @Synchronized
    fun save(info: WatchInfo) {
        prefs.edit()
            .putString(KEY_MAC, info.macAddress)
            .putString(KEY_NAME, info.name)
            .apply { if (info.unitId != null) putLong(KEY_UNIT, info.unitId) else remove(KEY_UNIT) }
            .apply { if (info.firmwareVersion != null) putString(KEY_FW, info.firmwareVersion) else remove(KEY_FW) }
            .putBoolean(KEY_FIRST_DONE, info.firstConnectDone)
            .putLong(KEY_PAIRED_AT, info.pairedAtMillis)
            .apply { if (info.lastSyncAtMillis != null) putLong(KEY_LAST_SYNC, info.lastSyncAtMillis) else remove(KEY_LAST_SYNC) }
            .apply { if (info.lastBatteryPercent != null) putInt(KEY_BATTERY, info.lastBatteryPercent) else remove(KEY_BATTERY) }
            .putBoolean(KEY_AUTO_SYNC, info.autoSyncOnOpen)
            .putInt(KEY_BG_HOURS, info.backgroundSyncHours)
            .putBoolean(KEY_KEEP_CONNECTED, info.keepConnectedDuringSessions)
            .apply()
        _watch.value = info
    }

    @Synchronized
    fun update(transform: (WatchInfo) -> WatchInfo): WatchInfo? {
        val current = _watch.value ?: return null
        val next = transform(current)
        save(next)
        return next
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
        _watch.value = null
    }

    private fun load(): WatchInfo? {
        val mac = prefs.getString(KEY_MAC, null) ?: return null
        return WatchInfo(
            macAddress = mac,
            name = prefs.getString(KEY_NAME, null) ?: "Garmin",
            unitId = if (prefs.contains(KEY_UNIT)) prefs.getLong(KEY_UNIT, 0) else null,
            firmwareVersion = prefs.getString(KEY_FW, null),
            firstConnectDone = prefs.getBoolean(KEY_FIRST_DONE, false),
            pairedAtMillis = prefs.getLong(KEY_PAIRED_AT, 0L),
            lastSyncAtMillis = if (prefs.contains(KEY_LAST_SYNC)) prefs.getLong(KEY_LAST_SYNC, 0) else null,
            lastBatteryPercent = if (prefs.contains(KEY_BATTERY)) prefs.getInt(KEY_BATTERY, 0) else null,
            autoSyncOnOpen = prefs.getBoolean(KEY_AUTO_SYNC, true),
            backgroundSyncHours = prefs.getInt(KEY_BG_HOURS, 0),
            keepConnectedDuringSessions = prefs.getBoolean(KEY_KEEP_CONNECTED, false)
        )
    }

    companion object {
        const val KEY_MAC = "mac"
        const val KEY_NAME = "name"
        const val KEY_UNIT = "unit_id"
        const val KEY_FW = "firmware"
        const val KEY_FIRST_DONE = "first_connect_done"
        const val KEY_PAIRED_AT = "paired_at"
        const val KEY_LAST_SYNC = "last_sync_at"
        const val KEY_BATTERY = "battery"
        const val KEY_AUTO_SYNC = "auto_sync_on_open"
        const val KEY_BG_HOURS = "background_sync_hours"
        const val KEY_KEEP_CONNECTED = "keep_connected"
    }
}
