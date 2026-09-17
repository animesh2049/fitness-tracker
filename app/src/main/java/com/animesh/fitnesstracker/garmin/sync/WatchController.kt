package com.animesh.fitnesstracker.garmin.sync

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.animesh.fitnesstracker.garmin.ble.GattClient
import com.animesh.fitnesstracker.garmin.gfdi.PhoneInfo
import com.animesh.fitnesstracker.service.GarminSyncService
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The [WatchGateway] the UI talks to. Owns the [WatchPrefs] and the [SyncLog], starts and cancels the
 * foreground sync service, pairs (createBond, waiting for BOND_BONDED and tolerating BOND_NONE for a watch
 * that does not bond) and forgets the watch, and builds the [SyncSession] that the service and the
 * background worker run. Sync state comes from the process-wide [SyncRuntime].
 */
@SuppressLint("MissingPermission")
class WatchController(
    private val context: Context,
    private val store: RawFileStore,
    private val importHook: ImportHook,
    private val scope: CoroutineScope,
    private val prefs: WatchPrefs = WatchPrefs(context),
    val syncLog: SyncLog = SyncLog(File(context.filesDir, "garmin/sync.log"))
) : WatchGateway {
    init {
        SyncRuntime.controller = this
    }

    override val watch: StateFlow<WatchInfo?> = prefs.watch
    override val syncState: StateFlow<SyncState> = SyncRuntime.state
    override val log: StateFlow<List<SyncLogLine>> = syncLog.lines

    val currentWatch: WatchInfo? get() = prefs.current

    override fun requestSync() {
        if (SyncRuntime.isRunning || prefs.current == null) return
        if (!BluetoothPermissions.granted(context)) {
            syncLog.log("Sync not started: Bluetooth permission missing")
            return
        }
        ContextCompat.startForegroundService(
            context, Intent(context, GarminSyncService::class.java).setAction(GarminSyncService.ACTION_SYNC)
        )
    }

    override fun cancelSync() {
        SyncRuntime.activeJob?.cancel()
        runCatching { context.startService(Intent(context, GarminSyncService::class.java).setAction(GarminSyncService.ACTION_CANCEL)) }
    }

    /** Restores a pairing from a JSON backup (no bonding happens; the first sync re-bonds if needed). */
    fun restore(info: WatchInfo) {
        prefs.save(info.copy(firstConnectDone = false))
        scheduleBackgroundSync(context, info.backgroundSyncHours)
    }

    override suspend fun updateWatch(transform: (WatchInfo) -> WatchInfo) {
        val before = prefs.current ?: return
        val after = prefs.update(transform) ?: return
        if (before.backgroundSyncHours != after.backgroundSyncHours) scheduleBackgroundSync(context, after.backgroundSyncHours)
    }

    override suspend fun forgetWatch() {
        cancelSync()
        val w = prefs.current
        if (w != null) {
            runCatching { removeBond(w.macAddress) }
            syncLog.log("Forgot ${w.name}")
        }
        prefs.clear()
        scheduleBackgroundSync(context, 0)
    }

    /**
     * Bonds with the watch (or proceeds without a bond when the watch does not ask for one) and stores it
     * as the paired watch with `firstConnectDone = false`, so the next sync runs the first-connect handshake.
     */
    suspend fun pair(address: String, name: String): WatchInfo {
        val adapter = adapter() ?: throw IllegalStateException("Bluetooth unavailable")
        val device = adapter.getRemoteDevice(address)
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            val started = runCatching { device.createBond() }.getOrDefault(false)
            if (started) {
                val result = withTimeoutOrNull(BOND_TIMEOUT_MS) { awaitBond(device) }
                syncLog.log(
                    when (result) {
                        BluetoothDevice.BOND_BONDED -> "Bonded with $name"
                        null -> "Bonding timed out, continuing without a bond"
                        else -> "Watch did not bond (state $result), continuing without a bond"
                    }
                )
            } else {
                syncLog.log("createBond not started, continuing without a bond")
            }
        }
        val info = WatchInfo(macAddress = address, name = name, pairedAtMillis = System.currentTimeMillis(), firstConnectDone = false)
        prefs.save(info)
        syncLog.log("Paired $name ($address)")
        return info
    }

    fun scan(): Flow<WatchScanner.Hit> = WatchScanner(context).scan()

    fun isBluetoothOn(): Boolean = adapter()?.isEnabled == true

    fun newSession(): SyncSession = SyncSession(
        transportFactory = { address, sessionScope -> GattClient(context, address, sessionScope, syncLog::log) },
        store = store,
        importHook = importHook,
        log = syncLog,
        phone = phoneInfo()
    )

    /**
     * Runs one sync for the paired watch, publishing state to [SyncRuntime]. The first sync after pairing
     * runs with `firstConnect = true`; success records the sync time, battery, unit id and firmware.
     */
    suspend fun runSync(): SyncSession.Outcome? {
        val w = prefs.current ?: return null
        val outcome = newSession().run(w, !w.firstConnectDone) { SyncRuntime.state.value = it }
        prefs.update {
            it.copy(
                firstConnectDone = it.firstConnectDone || outcome.success || outcome.unitId != null,
                lastSyncAtMillis = if (outcome.success) System.currentTimeMillis() else it.lastSyncAtMillis,
                lastBatteryPercent = outcome.batteryPercent ?: it.lastBatteryPercent,
                unitId = outcome.unitId ?: it.unitId,
                firmwareVersion = outcome.firmwareVersion ?: it.firmwareVersion
            )
        }
        return outcome
    }

    private fun phoneInfo(): PhoneInfo {
        val btName = runCatching { adapter()?.name }.getOrNull().orEmpty()
        return PhoneInfo(btName.ifBlank { "Fitness Tracker" }, Build.MANUFACTURER ?: "Android", Build.DEVICE ?: "phone")
    }

    private fun adapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private suspend fun awaitBond(device: BluetoothDevice): Int = suspendCancellableCoroutine { cont ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val d = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                if (d.address != device.address) return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                if (state == BluetoothDevice.BOND_BONDING) return
                runCatching { context.unregisterReceiver(this) }
                if (cont.isActive) cont.resume(state)
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
    }

    private fun removeBond(address: String) {
        val device = adapter()?.getRemoteDevice(address) ?: return
        if (device.bondState == BluetoothDevice.BOND_NONE) return
        // removeBond is hidden API; reflection is the usual way and fails silently on restricted builds.
        val m = BluetoothDevice::class.java.getMethod("removeBond")
        m.invoke(device)
    }

    companion object {
        const val BOND_TIMEOUT_MS = 60_000L
    }
}
