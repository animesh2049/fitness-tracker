package com.animesh.fitnesstracker.garmin.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Android [Transport]: `connectGatt(TRANSPORT_LE, autoConnect = false)`, service discovery,
 * `requestMtu(515)`, write chunk `min(512, mtu - 3)`, CCCD subscription on the receive characteristic and
 * one serialized write queue (the next write starts only after `onCharacteristicWrite`). It probes the
 * Multi-Link pair first (service 6A4E2800, receive 6A4E2810..2814, send = receive + 0x10) and falls back
 * to the V1 pair (service 6A4E2401, receive 6A4ECD28, send 6A4E4C80).
 */
@SuppressLint("MissingPermission")
class GattClient(
    private val context: Context,
    private val address: String,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {}
) : Transport {
    private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected(null))
    override val state: StateFlow<TransportState> = _state

    private val inbox = Channel<ByteArray>(Channel.UNLIMITED)
    override val incoming: Flow<ByteArray> = inbox.receiveAsFlow()

    private val writes = Channel<ByteArray>(Channel.UNLIMITED)
    private var writer: Job? = null
    private var gatt: BluetoothGatt? = null
    private var sendChar: BluetoothGattCharacteristic? = null
    private var recvChar: BluetoothGattCharacteristic? = null
    private var kind: LinkKind = LinkKind.MULTI_LINK
    private var mtu = 23
    private var connected: CompletableDeferred<Unit>? = null
    @Volatile private var writeDone: CompletableDeferred<Boolean>? = null
    private var mtuFallback: Job? = null

    override suspend fun connect() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = manager.adapter ?: throw IllegalStateException("Bluetooth unavailable")
        if (!adapter.isEnabled) throw IllegalStateException("Bluetooth is off")
        val device = adapter.getRemoteDevice(address)
        val done = CompletableDeferred<Unit>()
        connected = done
        _state.value = TransportState.Connecting
        log("GATT: connecting to $address")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IllegalStateException("connectGatt returned null")
        try {
            done.await()
        } catch (e: Exception) {
            close()
            throw e
        }
        writer = scope.launch { drainWrites() }
    }

    override fun send(bytes: ByteArray) {
        writes.trySend(bytes)
    }

    override fun close() {
        writer?.cancel(); writer = null
        mtuFallback?.cancel(); mtuFallback = null
        val g = gatt
        gatt = null
        if (g != null) {
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        if (_state.value !is TransportState.Disconnected) _state.value = TransportState.Disconnected("closed")
        connected?.let { if (!it.isCompleted) it.completeExceptionally(IllegalStateException("closed")) }
        inbox.close()
    }

    private suspend fun drainWrites() {
        for (bytes in writes) {
            val g = gatt ?: return
            val c = sendChar ?: return
            val done = CompletableDeferred<Boolean>()
            writeDone = done
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, bytes, writeTypeOf(c)) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    c.writeType = writeTypeOf(c)
                    c.value = bytes
                    g.writeCharacteristic(c)
                }
            }
            if (!started) {
                fail("write failed to start")
                return
            }
            val ok = withTimeoutOrNull(WRITE_TIMEOUT_MS) { done.await() }
            if (ok != true) {
                fail(if (ok == null) "write timed out" else "write rejected")
                return
            }
        }
    }

    private fun writeTypeOf(c: BluetoothGattCharacteristic): Int =
        if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

    private fun fail(reason: String) {
        log("GATT: $reason")
        _state.value = TransportState.Disconnected(reason)
        connected?.let { if (!it.isCompleted) it.completeExceptionally(IllegalStateException(reason)) }
        inbox.close()
    }

    private fun probeCharacteristics(g: BluetoothGatt): Boolean {
        val ml = g.getService(UUID.fromString(ML_SERVICE))
        if (ml != null) {
            for (i in 0x2810..0x2814) {
                val recv = ml.getCharacteristic(uuid(i))
                val send = ml.getCharacteristic(uuid(i + 0x10))
                if (recv != null && send != null) {
                    recvChar = recv; sendChar = send; kind = LinkKind.MULTI_LINK
                    log("GATT: Multi-Link pair ${recv.uuid} / ${send.uuid}")
                    return true
                }
            }
        }
        val v1 = g.getService(UUID.fromString(V1_SERVICE))
        if (v1 != null) {
            val recv = v1.getCharacteristic(UUID.fromString(V1_RECEIVE))
            val send = v1.getCharacteristic(UUID.fromString(V1_SEND))
            if (recv != null && send != null) {
                recvChar = recv; sendChar = send; kind = LinkKind.V1
                log("GATT: V1 pair")
                return true
            }
        }
        return false
    }

    private fun subscribe(g: BluetoothGatt) {
        val recv = recvChar ?: return fail("no receive characteristic")
        if (!g.setCharacteristicNotification(recv, true)) return fail("setCharacteristicNotification failed")
        val cccd = recv.getDescriptor(UUID.fromString(CCCD)) ?: return fail("no CCCD on receive characteristic")
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = value
                g.writeDescriptor(cccd)
            }
        }
        if (!started) fail("CCCD write failed to start")
    }

    private fun maxWrite(): Int = minOf(512, maxOf(23, mtu) - 3)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                log("GATT: connected, discovering services")
                if (!g.discoverServices()) fail("discoverServices failed to start")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                fail("disconnected (status $status)")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return fail("service discovery failed ($status)")
            if (!probeCharacteristics(g)) return fail("not a Garmin GFDI device")
            if (g.requestMtu(REQUESTED_MTU)) {
                // Some stacks never deliver onMtuChanged; continue with the default after a grace period.
                mtuFallback = scope.launch {
                    kotlinx.coroutines.delay(MTU_TIMEOUT_MS)
                    if (connected?.isCompleted == false) {
                        log("GATT: no MTU callback, continuing with $mtu")
                        subscribe(g)
                    }
                }
            } else {
                subscribe(g)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            mtuFallback?.cancel(); mtuFallback = null
            if (status == BluetoothGatt.GATT_SUCCESS && newMtu >= 23) mtu = newMtu
            log("GATT: mtu $mtu, write chunk ${maxWrite()}")
            if (connected?.isCompleted == false) subscribe(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return fail("CCCD write failed ($status)")
            _state.value = TransportState.Connected(kind, maxWrite())
            connected?.complete(Unit)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeDone?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == recvChar?.uuid) inbox.trySend(value.copyOf())
        }

        @Deprecated("Pre-API 33 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            if (characteristic.uuid == recvChar?.uuid) inbox.trySend(value.copyOf())
        }
    }

    companion object {
        const val ML_SERVICE = "6A4E2800-667B-11E3-949A-0800200C9A66"
        const val V1_SERVICE = "6A4E2401-667B-11E3-949A-0800200C9A66"
        const val V1_RECEIVE = "6A4ECD28-667B-11E3-949A-0800200C9A66"
        const val V1_SEND = "6A4E4C80-667B-11E3-949A-0800200C9A66"
        const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"
        const val REQUESTED_MTU = 515
        const val WRITE_TIMEOUT_MS = 8_000L
        const val MTU_TIMEOUT_MS = 3_000L

        fun uuid(short16: Int): UUID = UUID.fromString(String.format("6A4E%04X-667B-11E3-949A-0800200C9A66", short16))
    }
}
