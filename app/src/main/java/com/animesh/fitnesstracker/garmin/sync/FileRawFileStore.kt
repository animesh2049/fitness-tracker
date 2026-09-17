package com.animesh.fitnesstracker.garmin.sync

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * [RawFileStore] on the file system with Gadgetbridge's layout:
 * `<root>/<TYPE>/<yyyy>/<TYPE>_<yyyy-MM-dd_HH-mm-ss>_<index>.fit`, or `<root>/<TYPE>/<TYPE>_<index>.fit`
 * when the watch had no timestamp. TYPE is ACTIVITY (4), MONITOR_A (15), MONITOR_DAILY (28), MONITOR (32),
 * METRICS (44), SLEEP (49), HRV_STATUS (68) or `OTHER<subtype>`. Imported files use index 0 and the FIT
 * `time_created`, or a uuid suffix when that is unknown. Timestamps are rendered in [zone].
 */
class FileRawFileStore(override val root: File, private val zone: ZoneId = ZoneId.systemDefault()) : RawFileStore {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    override fun exists(watchIndex: Int, fitType: Int, watchTimestamp: Long?): Boolean {
        val f = fileFor(watchIndex, fitType, watchTimestamp)
        return f.exists() && f.length() > 0
    }

    override fun save(watchIndex: Int, fitType: Int, watchTimestamp: Long?, bytes: ByteArray): StoredFile {
        val f = fileFor(watchIndex, fitType, watchTimestamp)
        write(f, bytes)
        if (watchTimestamp != null) f.setLastModified(watchTimestamp * 1000)
        return StoredFile(watchIndex, fitType, watchTimestamp, f)
    }

    override fun saveImported(fitType: Int, timeCreated: Long?, bytes: ByteArray): StoredFile {
        val type = typeName(fitType)
        val f = if (timeCreated != null) fileFor(0, fitType, timeCreated)
        else File(File(root, type), "${type}_${UUID.randomUUID()}.fit")
        write(f, bytes)
        if (timeCreated != null) f.setLastModified(timeCreated * 1000)
        return StoredFile(0, fitType, timeCreated, f)
    }

    override fun listAll(): List<StoredFile> {
        if (!root.isDirectory) return emptyList()
        val out = ArrayList<StoredFile>()
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".fit") }.forEach { f -> parse(f)?.let { out.add(it) } }
        out.sortWith(compareBy({ it.watchTimestamp ?: (it.file.lastModified() / 1000) }, { it.file.name }))
        return out
    }

    override fun totalBytes(): Long =
        if (!root.isDirectory) 0 else root.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** The path a file with these coordinates lives at, whether or not it exists. */
    fun fileFor(watchIndex: Int, fitType: Int, watchTimestamp: Long?): File {
        val type = typeName(fitType)
        val typeDir = File(root, type)
        if (watchTimestamp == null) return File(typeDir, "${type}_$watchIndex.fit")
        val t = Instant.ofEpochSecond(watchTimestamp).atZone(zone)
        return File(File(typeDir, "%04d".format(t.year)), "${type}_${formatter.format(t)}_$watchIndex.fit")
    }

    private fun write(f: File, bytes: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(f)) {
            f.writeBytes(bytes)
            tmp.delete()
        }
    }

    private fun parse(f: File): StoredFile? {
        val parent = f.parentFile ?: return null
        val typeDir = if (parent.name.matches(YEAR)) parent.parentFile ?: return null else parent
        val type = typeDir.name
        val fitType = fitType(type) ?: return null
        val rest = f.name.removeSuffix(".fit").removePrefix(type + "_")
        TIMED.matchEntire(rest)?.let { m ->
            val ts = runCatching {
                java.time.LocalDateTime.parse(m.groupValues[1], formatter).atZone(zone).toEpochSecond()
            }.getOrNull()
            return StoredFile(m.groupValues[2].toInt(), fitType, ts, f)
        }
        rest.toIntOrNull()?.let { return StoredFile(it, fitType, null, f) }
        return StoredFile(0, fitType, null, f)
    }

    companion object {
        private val YEAR = Regex("\\d{4}")
        private val TIMED = Regex("(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})_(\\d+)")
        private val NAMES = mapOf(
            4 to "ACTIVITY", 15 to "MONITOR_A", 28 to "MONITOR_DAILY", 32 to "MONITOR",
            44 to "METRICS", 49 to "SLEEP", 68 to "HRV_STATUS"
        )

        fun typeName(fitType: Int): String = NAMES[fitType] ?: "OTHER$fitType"

        fun fitType(typeName: String): Int? =
            NAMES.entries.firstOrNull { it.value == typeName }?.key
                ?: typeName.removePrefix("OTHER").takeIf { typeName.startsWith("OTHER") }?.toIntOrNull()
    }
}
