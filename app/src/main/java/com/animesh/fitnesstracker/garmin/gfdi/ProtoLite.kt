package com.animesh.fitnesstracker.garmin.gfdi

import java.io.ByteArrayOutputStream

/**
 * Minimal protobuf wire codec (varint and length-delimited fields only; fixed32/fixed64 are skipped on
 * read), enough for the GDI "Smart" messages this app exchanges: the battery request and response in
 * DeviceStatusService (Smart field 8) and the ConnectedNotification ping in CoreService (Smart field 13).
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): ProtoWriter {
        tag(field, 0)
        writeVarint(value)
        return this
    }

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, 2)
        writeVarint(value.size.toLong())
        out.write(value, 0, value.size)
        return this
    }

    fun message(field: Int, inner: ProtoWriter): ProtoWriter = bytes(field, inner.toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wireType: Int) = writeVarint(((field shl 3) or wireType).toLong())

    private fun writeVarint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }
}

class ProtoReader(private val data: ByteArray) {
    data class Field(val number: Int, val wireType: Int, val varint: Long, val bytes: ByteArray?)

    /** All top-level fields in order; throws [IllegalArgumentException] on malformed input. */
    fun fields(): List<Field> {
        val out = ArrayList<Field>()
        var pos = 0
        fun readVarint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                if (pos >= data.size) throw IllegalArgumentException("truncated varint")
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IllegalArgumentException("varint too long")
            }
        }
        while (pos < data.size) {
            val key = readVarint()
            val number = (key ushr 3).toInt()
            val wireType = (key and 7).toInt()
            when (wireType) {
                0 -> out.add(Field(number, wireType, readVarint(), null))
                1 -> { if (pos + 8 > data.size) throw IllegalArgumentException("truncated fixed64"); pos += 8; out.add(Field(number, wireType, 0, null)) }
                2 -> {
                    val len = readVarint().toInt()
                    if (len < 0 || pos + len > data.size) throw IllegalArgumentException("truncated bytes")
                    out.add(Field(number, wireType, 0, data.copyOfRange(pos, pos + len)))
                    pos += len
                }
                5 -> { if (pos + 4 > data.size) throw IllegalArgumentException("truncated fixed32"); pos += 4; out.add(Field(number, wireType, 0, null)) }
                else -> throw IllegalArgumentException("unsupported wire type $wireType")
            }
        }
        return out
    }

    fun first(number: Int): Field? = fields().firstOrNull { it.number == number }
}

/** What a watch-initiated protobuf turned out to be, and the canned Smart messages we send. */
object GarminProto {
    const val SMART_DEVICE_STATUS = 8
    const val SMART_CORE = 13

    const val DS_BATTERY_REQUEST = 2
    const val DS_BATTERY_RESPONSE = 3
    const val DS_BATTERY_LEVEL = 2

    const val CORE_SYNC_REQUEST = 1
    const val CORE_GET_LOCATION_REQUEST = 3
    const val CORE_GET_LOCATION_RESPONSE = 4
    const val CORE_LOCATION_SET_ENABLED_REQUEST = 5
    const val CORE_LOCATION_SET_ENABLED_RESPONSE = 6
    const val CORE_CONNECTED_NOTIFICATION = 14

    sealed class Incoming {
        data class BatteryResponse(val level: Int?) : Incoming()
        data object ConnectedNotification : Incoming()
        data object GetLocationRequest : Incoming()
        data object LocationUpdatesRequest : Incoming()
        data class Other(val smartField: Int?) : Incoming()
    }

    /** `Smart { device_status_service = 8 { remote_device_battery_status_request = 2 {} } }` = 42 02 12 00. */
    fun batteryRequest(): ByteArray =
        ProtoWriter().message(SMART_DEVICE_STATUS, ProtoWriter().bytes(DS_BATTERY_REQUEST, ByteArray(0))).toByteArray()

    /** `Smart { core_service = 13 { connected_notification = 14 {} } }` = 6A 02 72 00. */
    fun connectedNotification(): ByteArray =
        ProtoWriter().message(SMART_CORE, ProtoWriter().bytes(CORE_CONNECTED_NOTIFICATION, ByteArray(0))).toByteArray()

    /** GetLocationResponse { status = LOCATION_SERVICES_DISABLED (4) }: we never hand the watch a GPS fix. */
    fun locationDeclined(): ByteArray =
        ProtoWriter().message(SMART_CORE, ProtoWriter().message(CORE_GET_LOCATION_RESPONSE, ProtoWriter().varint(1, 4))).toByteArray()

    /** LocationUpdatedSetEnabledResponse { status = UNAVAILABLE (2) }. */
    fun locationUpdatesDeclined(): ByteArray =
        ProtoWriter().message(SMART_CORE, ProtoWriter().message(CORE_LOCATION_SET_ENABLED_RESPONSE, ProtoWriter().varint(1, 2))).toByteArray()

    fun classify(smart: ByteArray): Incoming {
        val top = try { ProtoReader(smart).fields() } catch (e: IllegalArgumentException) { return Incoming.Other(null) }
        val first = top.firstOrNull() ?: return Incoming.Other(null)
        val inner = first.bytes ?: return Incoming.Other(first.number)
        return when (first.number) {
            SMART_DEVICE_STATUS -> {
                val response = runCatching { ProtoReader(inner).first(DS_BATTERY_RESPONSE) }.getOrNull()
                if (response?.bytes != null) {
                    val level = runCatching { ProtoReader(response.bytes).first(DS_BATTERY_LEVEL)?.varint?.toInt() }.getOrNull()
                    Incoming.BatteryResponse(level)
                } else Incoming.Other(first.number)
            }
            SMART_CORE -> {
                val fields = runCatching { ProtoReader(inner).fields() }.getOrElse { return Incoming.Other(first.number) }
                when {
                    fields.any { it.number == CORE_CONNECTED_NOTIFICATION } -> Incoming.ConnectedNotification
                    fields.any { it.number == CORE_GET_LOCATION_REQUEST } -> Incoming.GetLocationRequest
                    fields.any { it.number == CORE_LOCATION_SET_ENABLED_REQUEST } -> Incoming.LocationUpdatesRequest
                    else -> Incoming.Other(first.number)
                }
            }
            else -> Incoming.Other(first.number)
        }
    }
}
