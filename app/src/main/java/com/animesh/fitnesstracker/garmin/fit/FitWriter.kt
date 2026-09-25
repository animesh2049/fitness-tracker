package com.animesh.fitnesstracker.garmin.fit

import java.io.ByteArrayOutputStream

/**
 * One field value to encode: field number, base type, declared size in bytes and the value.
 *
 * Values are `Long`/`Int` for integers and enums, `String` for text, a `List` of those for arrays,
 * or null for "not written" (the base type's invalid sentinel is emitted). Strings are written
 * NUL-terminated and padded with NUL to [size]; a string longer than `size - 1` bytes is truncated
 * on a UTF-8 character boundary. Build instances through the companion factories so the declared
 * size always matches the base type.
 */
internal class FitField private constructor(val num: Int, val baseType: FitBaseType, val size: Int, val value: Any?) {

    companion object {
        fun enum(num: Int, value: Int?) = FitField(num, FitBaseType.ENUM, 1, value?.toLong())
        fun u8(num: Int, value: Int?) = FitField(num, FitBaseType.UINT8, 1, value?.toLong())
        fun u16(num: Int, value: Int?) = FitField(num, FitBaseType.UINT16, 2, value?.toLong())
        fun u32(num: Int, value: Long?) = FitField(num, FitBaseType.UINT32, 4, value)
        /** uint32z: 0 is the invalid sentinel, so a null and a 0 encode identically. */
        fun u32z(num: Int, value: Long?) = FitField(num, FitBaseType.UINT32Z, 4, value)
        fun float32(num: Int, value: Float?) = FitField(num, FitBaseType.FLOAT32, 4, value)
        fun sint8(num: Int, value: Int?) = FitField(num, FitBaseType.SINT8, 1, value?.toLong())
        fun sint16(num: Int, value: Int?) = FitField(num, FitBaseType.SINT16, 2, value?.toLong())
        fun sint32(num: Int, value: Long?) = FitField(num, FitBaseType.SINT32, 4, value)

        /** A single string in a field of [size] bytes (default: the text's UTF-8 length plus the terminator). */
        fun string(num: Int, value: String?, size: Int = utf8Length(value) + 1) =
            FitField(num, FitBaseType.STRING, maxOf(1, size), value)

        /** Several strings back to back, each NUL-terminated, in a field of [size] bytes (default: exact fit). */
        fun strings(num: Int, values: List<String>, size: Int = values.sumOf { utf8Length(it) + 1 }) =
            FitField(num, FitBaseType.STRING, maxOf(1, size), values)

        /** An array of any integer base type; null elements become the sentinel. */
        fun array(num: Int, baseType: FitBaseType, values: List<Long?>, count: Int = values.size): FitField {
            require(baseType.isInteger) { "array of ${baseType.label} is not supported" }
            return FitField(num, baseType, baseType.size * maxOf(1, count), values)
        }

        fun u16Array(num: Int, values: List<Int?>, count: Int = values.size) = array(num, FitBaseType.UINT16, values.map { it?.toLong() }, count)

        private fun utf8Length(text: String?): Int = text?.toByteArray(Charsets.UTF_8)?.size ?: 0
    }
}

/**
 * Production FIT encoder, the counterpart of [FitReader]. Call [write] once per data record with
 * the global message number and its fields; the writer emits a little-endian definition record
 * whenever the (message, field layout) it needs is not the one currently bound to a local message
 * type, reusing up to 16 local types and evicting the oldest when all are taken. [toByteArray]
 * wraps the records in a 14 byte header (protocol 1.0, profile [profileVersion], data size, ".FIT",
 * header CRC) and appends the CRC-16 trailer, so the output round-trips through [FitReader.parse].
 *
 * Integers are written as their low bytes, little endian; nulls become the base type's invalid
 * sentinel; strings are NUL-terminated and padded to the declared size; arrays shorter than the
 * declared count are padded with sentinels.
 */
internal class FitWriter(private val profileVersion: Int = DEFAULT_PROFILE_VERSION) {
    private val body = ByteArrayOutputStream()
    private val bound = arrayOfNulls<Layout>(LOCAL_TYPE_COUNT)
    private val localByLayout = HashMap<Layout, Int>()
    private var nextEviction = 0
    private var recordCount = 0

    /** Number of data records written so far. */
    val records: Int get() = recordCount

    /** Appends one data record of global message [globalNum], emitting a definition record first when needed. */
    fun write(globalNum: Int, fields: List<FitField>) {
        require(fields.isNotEmpty()) { "a FIT data record needs at least one field" }
        require(fields.size <= 255) { "too many fields (${fields.size}) for one definition record" }
        val layout = Layout(globalNum, fields.map { Slot(it.num, it.size, it.baseType) })
        val local = localByLayout[layout] ?: define(layout)
        body.write(local)
        fields.forEach(::writeValue)
        recordCount++
    }

    fun write(globalNum: Int, vararg fields: FitField) = write(globalNum, fields.toList())

    /** The complete file: header, records and trailing CRC. The writer can keep being used afterwards. */
    fun toByteArray(): ByteArray {
        val data = body.toByteArray()
        val out = ByteArrayOutputStream(HEADER_SIZE + data.size + 2)
        out.write(HEADER_SIZE)
        out.write(PROTOCOL_VERSION_1_0)
        writeLittleEndian(out, profileVersion.toLong(), 2)
        writeLittleEndian(out, data.size.toLong(), 4)
        out.write(MAGIC)
        val headerCrc = FitCrc.compute(out.toByteArray(), 0, HEADER_SIZE - 2)
        writeLittleEndian(out, headerCrc.toLong(), 2)
        out.write(data)
        val fileCrc = FitCrc.compute(out.toByteArray())
        writeLittleEndian(out, fileCrc.toLong(), 2)
        return out.toByteArray()
    }

    private fun define(layout: Layout): Int {
        val local = freeLocalType()
        bound[local]?.let(localByLayout::remove)
        bound[local] = layout
        localByLayout[layout] = local
        body.write(DEFINITION_BIT or local)
        body.write(0)
        body.write(0)
        writeLittleEndian(body, layout.globalNum.toLong(), 2)
        body.write(layout.slots.size)
        for (slot in layout.slots) {
            body.write(slot.num)
            body.write(slot.size)
            body.write(slot.baseType.id)
        }
        return local
    }

    private fun freeLocalType(): Int {
        val free = bound.indexOfFirst { it == null }
        if (free >= 0) return free
        val victim = nextEviction
        nextEviction = (nextEviction + 1) % LOCAL_TYPE_COUNT
        return victim
    }

    private fun writeValue(field: FitField) {
        val type = field.baseType
        if (type == FitBaseType.STRING) {
            writeString(field)
            return
        }
        val elementSize = type.size
        val count = field.size / elementSize
        val values: List<Any?> = when (val v = field.value) {
            null -> emptyList()
            is List<*> -> v
            else -> listOf(v)
        }
        require(values.size <= count) { "field ${field.num}: ${values.size} elements do not fit in ${field.size} bytes" }
        for (i in 0 until count) {
            val element = values.getOrNull(i)
            val raw = if (element == null) sentinel(type) else bits(element, type)
            writeLittleEndian(body, raw, elementSize)
        }
    }

    private fun writeString(field: FitField) {
        val buffer = ByteArray(field.size)
        val texts: List<String> = when (val v = field.value) {
            null -> emptyList()
            is String -> listOf(v)
            is List<*> -> v.map { it.toString() }
            else -> listOf(v.toString())
        }
        var offset = 0
        for (text in texts) {
            val room = field.size - offset - 1
            if (room <= 0) break
            val bytes = truncateUtf8(text, room)
            bytes.copyInto(buffer, offset)
            offset += bytes.size + 1
        }
        body.write(buffer)
    }

    private fun bits(value: Any, type: FitBaseType): Long = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is Boolean -> if (value) 1L else 0L
        is Float -> if (type == FitBaseType.FLOAT32) value.toBits().toLong() and 0xFFFFFFFFL else value.toLong()
        is Double -> when (type) {
            FitBaseType.FLOAT64 -> value.toBits()
            FitBaseType.FLOAT32 -> value.toFloat().toBits().toLong() and 0xFFFFFFFFL
            else -> Math.round(value)
        }
        else -> throw IllegalArgumentException("cannot encode ${value::class.java.simpleName} as ${type.label}")
    }

    private fun sentinel(type: FitBaseType): Long = when (type) {
        FitBaseType.ENUM, FitBaseType.UINT8, FitBaseType.BYTE -> 0xFFL
        FitBaseType.SINT8 -> 0x7FL
        FitBaseType.SINT16 -> 0x7FFFL
        FitBaseType.UINT16 -> 0xFFFFL
        FitBaseType.SINT32 -> 0x7FFFFFFFL
        FitBaseType.UINT32, FitBaseType.FLOAT32 -> 0xFFFFFFFFL
        FitBaseType.UINT8Z, FitBaseType.UINT16Z, FitBaseType.UINT32Z, FitBaseType.UINT64Z -> 0L
        FitBaseType.SINT64 -> Long.MAX_VALUE
        FitBaseType.UINT64, FitBaseType.FLOAT64 -> -1L
        FitBaseType.STRING -> 0L
    }

    /** Field layout of one message as it appears in a definition record; equal layouts share a local type. */
    private data class Layout(val globalNum: Int, val slots: List<Slot>)

    private data class Slot(val num: Int, val size: Int, val baseType: FitBaseType)

    companion object {
        const val DEFAULT_PROFILE_VERSION = 2172
        private const val HEADER_SIZE = 14
        private const val PROTOCOL_VERSION_1_0 = 0x10
        private const val DEFINITION_BIT = 0x40
        private const val LOCAL_TYPE_COUNT = 16
        private val MAGIC = ".FIT".toByteArray(Charsets.US_ASCII)

        private fun writeLittleEndian(out: ByteArrayOutputStream, value: Long, size: Int) {
            for (i in 0 until size) out.write(((value shr (8 * i)) and 0xFF).toInt())
        }

        /** The longest prefix of [text] whose UTF-8 form fits in [maxBytes] without splitting a character. */
        fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size <= maxBytes) return bytes
            var end = maxBytes
            while (end > 0 && (bytes[end].toInt() and 0xC0) == 0x80) end -= 1
            return bytes.copyOfRange(0, end)
        }
    }
}
