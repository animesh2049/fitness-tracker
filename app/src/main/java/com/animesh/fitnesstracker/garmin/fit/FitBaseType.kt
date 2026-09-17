package com.animesh.fitnesstracker.garmin.fit

/**
 * The FIT base types a field can be encoded as. [id] is the byte written in a definition record
 * (bit 7 marks multi-byte types; only the low five bits identify the type), [size] the width of
 * one element in bytes. Each type has an "invalid" sentinel meaning the watch did not write the
 * field; [isInvalid] tests a decoded element for it.
 */
internal enum class FitBaseType(val id: Int, val size: Int, val label: String) {
    ENUM(0x00, 1, "enum"),
    SINT8(0x01, 1, "sint8"),
    UINT8(0x02, 1, "uint8"),
    SINT16(0x83, 2, "sint16"),
    UINT16(0x84, 2, "uint16"),
    SINT32(0x85, 4, "sint32"),
    UINT32(0x86, 4, "uint32"),
    STRING(0x07, 1, "string"),
    FLOAT32(0x88, 4, "float32"),
    FLOAT64(0x89, 8, "float64"),
    UINT8Z(0x0A, 1, "uint8z"),
    UINT16Z(0x8B, 2, "uint16z"),
    UINT32Z(0x8C, 4, "uint32z"),
    BYTE(0x0D, 1, "byte"),
    SINT64(0x8E, 8, "sint64"),
    UINT64(0x8F, 8, "uint64"),
    UINT64Z(0x90, 8, "uint64z");

    val isSigned: Boolean get() = this == SINT8 || this == SINT16 || this == SINT32 || this == SINT64
    val isFloat: Boolean get() = this == FLOAT32 || this == FLOAT64
    val isInteger: Boolean get() = !isFloat && this != STRING

    /**
     * True when [raw] (the unsigned bit pattern of one element, sign-extended for signed types)
     * is this type's invalid sentinel.
     */
    fun isInvalid(raw: Long): Boolean = when (this) {
        ENUM, UINT8, BYTE -> raw == 0xFFL
        SINT8 -> raw == 0x7FL
        SINT16 -> raw == 0x7FFFL
        UINT16 -> raw == 0xFFFFL
        SINT32 -> raw == 0x7FFFFFFFL
        UINT32 -> raw == 0xFFFFFFFFL
        FLOAT32 -> raw == 0xFFFFFFFFL
        FLOAT64 -> raw == -1L
        UINT8Z, UINT16Z, UINT32Z, UINT64Z -> raw == 0L
        SINT64 -> raw == Long.MAX_VALUE
        UINT64 -> raw == -1L
        STRING -> raw == 0L
    }

    companion object {
        private const val TYPE_NUMBER_MASK = 0x1F

        /** Looks a type up by its definition byte, ignoring the endian-ability bit. Null for an unknown number. */
        fun fromId(id: Int): FitBaseType? {
            val num = id and TYPE_NUMBER_MASK
            return entries.firstOrNull { (it.id and TYPE_NUMBER_MASK) == num }
        }
    }
}
