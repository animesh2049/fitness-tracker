package com.animesh.fitnesstracker.garmin.fit

/** The FIT file header (12 or 14 bytes). [headerCrc] is null for the 12-byte form and 0 when the writer skipped it. */
internal data class FitHeader(
    val headerSize: Int,
    val protocolVersion: Int,
    val profileVersion: Int,
    val dataSize: Int,
    val headerCrc: Int?
)

/** Result of the byte-level pass: every record in file order plus the unknown counts, before mapping to [DecodedFit]. */
internal class ParsedFit(
    val header: FitHeader,
    val records: List<RawRecord>,
    val unknownMessageCount: Int,
    val unknownFieldCount: Int
)

/**
 * Pure-Kotlin FIT decoder: validates the header and CRC, walks definition and data records
 * (normal and compressed-timestamp headers, both byte orders, arrays, strings, invalid sentinels,
 * developer fields) into [RawRecord]s, then hands them to [FitMapper] for the typed contract.
 *
 * Throws [FitDecodeException] only for a corrupt file: bad magic, unsupported header, truncation,
 * CRC mismatch, or a data record whose local message type was never defined. Unknown messages and
 * fields are counted and kept, never fatal.
 */
internal object FitReader {
    private const val MAGIC = ".FIT"
    private const val MIN_HEADER_SIZE = 12
    private const val HEADER_WITH_CRC_SIZE = 14

    fun decode(bytes: ByteArray): DecodedFit = FitMapper.map(parse(bytes))

    fun parse(bytes: ByteArray): ParsedFit {
        val header = readHeader(bytes)
        val dataEnd = header.headerSize + header.dataSize
        verifyFileCrc(bytes, dataEnd)
        return RecordPass(bytes, header.headerSize, dataEnd).run(header)
    }

    private fun readHeader(bytes: ByteArray): FitHeader {
        if (bytes.size < MIN_HEADER_SIZE + 2) {
            throw FitDecodeException("Not a FIT file: only ${bytes.size} bytes, a header needs at least $MIN_HEADER_SIZE")
        }
        val r = FitByteReader(bytes)
        val headerSize = r.u8("header size")
        if (headerSize != MIN_HEADER_SIZE && headerSize != HEADER_WITH_CRC_SIZE) {
            throw FitDecodeException("Unsupported FIT header size $headerSize (expected 12 or 14)")
        }
        val protocol = r.u8("protocol version")
        val profile = r.u16(bigEndian = false, what = "profile version")
        val declaredDataSize = r.u32(bigEndian = false, what = "data size")
        val magic = String(r.slice(4, "magic"), Charsets.US_ASCII)
        if (magic != MAGIC) throw FitDecodeException("Not a FIT file: magic is '$magic' instead of '$MAGIC'")
        if (bytes.size < headerSize + 2) throw FitDecodeException("Truncated FIT file: header of $headerSize bytes does not fit in ${bytes.size}")
        val headerCrc = if (headerSize == HEADER_WITH_CRC_SIZE) r.u16(bigEndian = false, what = "header CRC") else null
        if (headerCrc != null && headerCrc != 0) {
            val computed = FitCrc.compute(bytes, 0, MIN_HEADER_SIZE)
            if (computed != headerCrc) {
                throw FitDecodeException("Header CRC mismatch: file says ${hex(headerCrc)}, computed ${hex(computed)}")
            }
        }
        // A data size of 0 is written by some encoders that stream; take everything up to the trailing CRC.
        val available = bytes.size - headerSize - 2
        val dataSize: Long = if (declaredDataSize == 0L) available.toLong() else declaredDataSize
        if (dataSize > available) {
            throw FitDecodeException("Truncated FIT file: header declares $dataSize data bytes but only $available are present before the CRC")
        }
        return FitHeader(headerSize, protocol, profile, dataSize.toInt(), headerCrc)
    }

    private fun hex(value: Int): String = "0x" + value.toString(16).uppercase().padStart(4, '0')

    private fun verifyFileCrc(bytes: ByteArray, dataEnd: Int) {
        val expected = (bytes[dataEnd].toInt() and 0xFF) or ((bytes[dataEnd + 1].toInt() and 0xFF) shl 8)
        val computed = FitCrc.compute(bytes, 0, dataEnd)
        if (expected != computed) {
            throw FitDecodeException("File CRC mismatch: file says ${hex(expected)}, computed ${hex(computed)}")
        }
    }
}

/** One field slot of a definition record. [baseType] is null for a base type number this decoder does not know. */
private class FieldDef(val num: Int, val size: Int, baseTypeId: Int) {
    val baseType: FitBaseType? = FitBaseType.fromId(baseTypeId)
}

/** One developer field slot of a definition record. */
private class DevFieldDef(val num: Int, val size: Int, val developerDataIndex: Int)

/** The layout a local message type currently maps to. */
private class MessageDef(val globalNum: Int, val bigEndian: Boolean, val fields: List<FieldDef>, val devFields: List<DevFieldDef>)

/** What a field_description record declared about a developer field. */
private class DevFieldInfo(val spec: FieldSpec, val baseType: FitBaseType)

/**
 * Walks the record stream once, keeping the per-local-type definitions, the last timestamp for
 * compressed headers, developer field descriptions and the unknown counters.
 */
private class RecordPass(bytes: ByteArray, start: Int, end: Int) {
    private val reader = FitByteReader(bytes, start, end)
    private val definitions = arrayOfNulls<MessageDef>(16)
    private val devFieldInfos = HashMap<Int, DevFieldInfo>()
    private val records = ArrayList<RawRecord>()
    private var lastTimestamp: Long? = null
    private var unknownMessageCount = 0
    private var unknownFieldCount = 0

    fun run(header: FitHeader): ParsedFit {
        while (reader.remaining > 0) {
            val recordStart = reader.position
            val h = reader.u8("record header")
            when {
                h and COMPRESSED_HEADER_BIT != 0 -> readData((h shr 5) and 0x03, h and COMPRESSED_OFFSET_MASK, recordStart)
                h and DEFINITION_BIT != 0 -> readDefinition(h and LOCAL_TYPE_MASK, h and DEVELOPER_DATA_BIT != 0)
                else -> readData(h and LOCAL_TYPE_MASK, null, recordStart)
            }
        }
        return ParsedFit(header, records, unknownMessageCount, unknownFieldCount)
    }

    private fun readDefinition(localType: Int, hasDeveloperFields: Boolean) {
        reader.u8("reserved byte")
        val architecture = reader.u8("architecture")
        if (architecture > 1) throw FitDecodeException("Invalid architecture byte $architecture in definition at offset ${reader.position - 1}")
        val bigEndian = architecture == 1
        val globalNum = reader.u16(bigEndian, "global message number")
        val fieldCount = reader.u8("field count")
        val fields = List(fieldCount) { FieldDef(reader.u8("field number"), reader.u8("field size"), reader.u8("base type")) }
        val devFields = if (hasDeveloperFields) {
            val devCount = reader.u8("developer field count")
            List(devCount) { DevFieldDef(reader.u8("developer field number"), reader.u8("developer field size"), reader.u8("developer data index")) }
        } else {
            emptyList()
        }
        definitions[localType] = MessageDef(globalNum, bigEndian, fields, devFields)
    }

    private fun readData(localType: Int, compressedOffset: Int?, recordStart: Int) {
        val def = definitions[localType]
            ?: throw FitDecodeException("Data record at offset $recordStart uses local message type $localType before any definition")
        val known = Profile.isKnownMessage(def.globalNum)
        val fields = LinkedHashMap<Int, Any?>()
        for (fd in def.fields) {
            val spec = Profile.field(def.globalNum, fd.num)
            val value = FitFieldDecoder.read(reader, fd.size, fd.baseType, def.bigEndian, spec)
            if (known && Profile.isUnknownField(def.globalNum, fd.num)) unknownFieldCount++
            if (value != null) fields[fd.num] = value
        }
        val developerFields = readDeveloperFields(def)
        if (compressedOffset != null) applyCompressedTimestamp(fields, compressedOffset)
        (fields[TIMESTAMP_FIELD] as? Long)?.let { lastTimestamp = it - GARMIN_EPOCH_UNIX_SECONDS }
        if (!known) unknownMessageCount++
        if (def.globalNum == Mesg.FIELD_DESCRIPTION) registerDeveloperField(fields)
        records += RawRecord(def.globalNum, fields, developerFields)
    }

    private fun readDeveloperFields(def: MessageDef): Map<String, Any?> {
        if (def.devFields.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        for (dd in def.devFields) {
            val info = devFieldInfos[devKey(dd.developerDataIndex, dd.num)]
            val spec = info?.spec ?: FieldSpec(dd.num, "dev_${dd.developerDataIndex}_${dd.num}")
            val value = FitFieldDecoder.read(reader, dd.size, info?.baseType ?: FitBaseType.BYTE, def.bigEndian, spec)
            out[spec.name] = value
        }
        return out
    }

    /** Compressed headers carry the low five bits of the timestamp; the rest comes from the last full timestamp seen. */
    private fun applyCompressedTimestamp(fields: MutableMap<Int, Any?>, offset: Int) {
        val base = lastTimestamp ?: return
        var timestamp = (base and COMPRESSED_OFFSET_MASK.toLong().inv()) or offset.toLong()
        if (timestamp < base) timestamp += COMPRESSED_ROLLOVER
        if (!fields.containsKey(TIMESTAMP_FIELD)) fields[TIMESTAMP_FIELD] = timestamp + GARMIN_EPOCH_UNIX_SECONDS
    }

    private fun registerDeveloperField(fields: Map<Int, Any?>) {
        val index = (fields[0] as? Long)?.toInt() ?: return
        val num = (fields[1] as? Long)?.toInt() ?: return
        val baseType = (fields[2] as? Long)?.let { FitBaseType.fromId(it.toInt()) } ?: FitBaseType.BYTE
        val name = (fields[3] as? String) ?: "dev_${index}_$num"
        val scale = (fields[6] as? Long)?.toDouble()?.takeIf { it != 0.0 } ?: 1.0
        val offset = (fields[7] as? Long)?.toDouble() ?: 0.0
        val units = fields[8] as? String
        devFieldInfos[devKey(index, num)] = DevFieldInfo(FieldSpec(num, name, scale, offset, units), baseType)
    }

    private fun devKey(index: Int, num: Int) = (index shl 8) or num

    private companion object {
        const val COMPRESSED_HEADER_BIT = 0x80
        const val DEFINITION_BIT = 0x40
        const val DEVELOPER_DATA_BIT = 0x20
        const val LOCAL_TYPE_MASK = 0x0F
        const val COMPRESSED_OFFSET_MASK = 0x1F
        const val COMPRESSED_ROLLOVER = 0x20L
        const val TIMESTAMP_FIELD = 253
    }
}

/**
 * Decodes one field's bytes into its value: a Long for plain integers, a Double when a scale,
 * offset, coordinate or float is involved, a Long of Unix seconds for timestamps, a String for
 * text, a List of those for arrays, or null when every element is the base type's invalid sentinel.
 */
internal object FitFieldDecoder {
    private const val SEMICIRCLES_TO_DEGREES = 180.0 / 2147483648.0

    fun read(reader: FitByteReader, size: Int, baseType: FitBaseType?, bigEndian: Boolean, spec: FieldSpec?): Any? {
        val type = baseType ?: FitBaseType.BYTE
        if (type == FitBaseType.STRING) return readString(reader, size)
        if (type == FitBaseType.BYTE || size % type.size != 0) return readBytes(reader, size)
        val count = size / type.size
        val values = ArrayList<Any?>(count)
        var anyValid = false
        repeat(count) {
            val raw = readRaw(reader, type, bigEndian)
            if (type.isInvalid(raw)) {
                values += null
            } else {
                values += convert(raw, type, spec)
                anyValid = true
            }
        }
        if (!anyValid) return null
        return if (count == 1) values[0] else values
    }

    private fun readString(reader: FitByteReader, size: Int): String? {
        val bytes = reader.slice(size, "string")
        val end = bytes.indexOf(0).let { if (it < 0) bytes.size else it }
        if (end == 0) return null
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    /** Byte fields are opaque: invalid only when every byte is 0xFF, otherwise all bytes are kept. */
    private fun readBytes(reader: FitByteReader, size: Int): Any? {
        val bytes = reader.slice(size, "byte field")
        if (bytes.all { it == 0xFF.toByte() }) return null
        val values = bytes.map { it.toLong() and 0xFF }
        return if (values.size == 1) values[0] else values
    }

    private fun readRaw(reader: FitByteReader, type: FitBaseType, bigEndian: Boolean): Long {
        val bits = reader.unsigned(type.size, bigEndian, type.label)
        if (!type.isSigned) return bits
        return when (type.size) {
            1 -> bits.toByte().toLong()
            2 -> bits.toShort().toLong()
            4 -> bits.toInt().toLong()
            else -> bits
        }
    }

    private fun convert(raw: Long, type: FitBaseType, spec: FieldSpec?): Any {
        if (type == FitBaseType.FLOAT32) return scaled(Float.fromBits(raw.toInt()).toDouble(), spec)
        if (type == FitBaseType.FLOAT64) return scaled(Double.fromBits(raw), spec)
        return when (spec?.kind) {
            FieldKind.TIMESTAMP, FieldKind.LOCAL_TIMESTAMP -> raw + GARMIN_EPOCH_UNIX_SECONDS
            FieldKind.COORDINATE -> raw * SEMICIRCLES_TO_DEGREES
            else -> if (spec != null && spec.isScaled) raw / spec.scale - spec.offset else raw
        }
    }

    private fun scaled(value: Double, spec: FieldSpec?): Double =
        if (spec != null && spec.isScaled) value / spec.scale - spec.offset else value
}
