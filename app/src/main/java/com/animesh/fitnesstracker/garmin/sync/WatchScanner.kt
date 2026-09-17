package com.animesh.fitnesstracker.garmin.sync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * BLE scan for Garmin watches: low latency, [timeoutMs] long, keeping advertisements whose
 * manufacturer-specific data carries company id 0x0087 (Garmin International) or whose name starts with a
 * known Garmin family. Each address is emitted once as a [Hit].
 */
class WatchScanner(private val context: Context) {
    data class Hit(val name: String, val address: String, val rssi: Int)

    @SuppressLint("MissingPermission")
    fun scan(timeoutMs: Long = 20_000): Flow<Hit> = callbackFlow {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter
        val scanner = adapter?.bluetoothLeScanner
        if (adapter == null || !adapter.isEnabled || scanner == null) {
            close(IllegalStateException("Bluetooth is off"))
            return@callbackFlow
        }
        val seen = HashSet<String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val name = record?.deviceName ?: result.device.name
                val garminData = record?.manufacturerSpecificData?.get(GARMIN_COMPANY_ID) != null
                if (!garminData && !isGarminName(name)) return
                if (!seen.add(result.device.address)) return
                trySend(Hit(name ?: "Garmin", result.device.address, result.rssi))
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Scan failed ($errorCode)"))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        launch {
            delay(timeoutMs)
            close()
        }
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }

    companion object {
        const val GARMIN_COMPANY_ID = 0x0087
        val NAME_PREFIXES = listOf("Forerunner", "fenix", "Fenix", "Venu", "Instinct", "vivoactive", "Vivoactive")

        fun isGarminName(name: String?): Boolean = name != null && NAME_PREFIXES.any { name.startsWith(it) }
    }
}
