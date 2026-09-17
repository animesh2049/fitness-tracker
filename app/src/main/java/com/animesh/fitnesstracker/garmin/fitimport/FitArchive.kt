package com.animesh.fitnesstracker.garmin.fitimport

import com.animesh.fitnesstracker.garmin.fit.DecodedFit
import com.animesh.fitnesstracker.garmin.fit.FitDecoder
import com.animesh.fitnesstracker.garmin.sync.ImportHook
import com.animesh.fitnesstracker.garmin.sync.ImportSummary
import com.animesh.fitnesstracker.garmin.sync.RawFileStore
import com.animesh.fitnesstracker.garmin.sync.StoredFile
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Export of the raw file store as a zip and import of a zip or single FIT files (FR47). Pure JVM.
 * Imported files are stored through [RawFileStore.saveImported] with the type and time from
 * their own file_id, then handed to the [ImportHook].
 *
 * @param decode reads the file_id of an imported file; tests pass a fake.
 */
class FitArchive(private val decode: (ByteArray) -> DecodedFit = FitDecoder::decode) {

    /** Writes every stored file into the zip, keeping its path relative to the store root. Returns the file count. */
    fun exportZip(store: RawFileStore, output: OutputStream): Int {
        var count = 0
        ZipOutputStream(output.buffered()).use { zip ->
            for (stored in store.listAll()) {
                val file = stored.file
                if (!file.isFile) continue
                val entry = ZipEntry(relativePath(store, file))
                entry.time = file.lastModified()
                zip.putNextEntry(entry)
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                count++
            }
        }
        return count
    }

    /** Stores every .fit entry of the zip and imports them in one batch. Unreadable entries count as failed. */
    suspend fun importZip(input: InputStream, store: RawFileStore, hook: ImportHook): ImportSummary {
        val stored = ArrayList<StoredFile>()
        val errors = ArrayList<String>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || !isFitName(entry.name)) { zip.closeEntry(); continue }
                val bytes = zip.readBytes()
                zip.closeEntry()
                stage(entry.name, bytes, store, stored, errors)
            }
        }
        return importStaged(stored, errors, hook)
    }

    /** Stores one FIT file (from the file picker or a USB copy) and imports it. */
    suspend fun importFit(name: String, bytes: ByteArray, store: RawFileStore, hook: ImportHook): ImportSummary {
        val stored = ArrayList<StoredFile>()
        val errors = ArrayList<String>()
        stage(name, bytes, store, stored, errors)
        return importStaged(stored, errors, hook)
    }

    /** Stores several FIT files (a multi select in the picker) and imports them together. */
    suspend fun importFits(files: List<Pair<String, ByteArray>>, store: RawFileStore, hook: ImportHook): ImportSummary {
        val stored = ArrayList<StoredFile>()
        val errors = ArrayList<String>()
        for ((name, bytes) in files) stage(name, bytes, store, stored, errors)
        return importStaged(stored, errors, hook)
    }

    private fun stage(name: String, bytes: ByteArray, store: RawFileStore, stored: MutableList<StoredFile>, errors: MutableList<String>) {
        if (bytes.isEmpty()) { errors += "$name: empty file"; return }
        val fit = try {
            decode(bytes)
        } catch (e: Exception) {
            errors += "$name: ${e.message?.lineSequence()?.firstOrNull() ?: "not a FIT file"}"
            return
        }
        val type = fit.fileId.typeNum ?: 0
        stored += store.saveImported(type, fit.fileId.timeCreated, bytes)
    }

    private suspend fun importStaged(stored: List<StoredFile>, errors: List<String>, hook: ImportHook): ImportSummary {
        val summary = if (stored.isEmpty()) ImportSummary(0, 0) else hook.importFiles(stored)
        return summary.copy(filesFailed = summary.filesFailed + errors.size, errors = errors + summary.errors)
    }

    companion object {
        fun isFitName(name: String): Boolean = name.substringAfterLast('/').substringAfterLast('\\').endsWith(".fit", ignoreCase = true)

        fun relativePath(store: RawFileStore, file: File): String =
            file.relativeToOrNull(store.root)?.path?.replace(File.separatorChar, '/') ?: file.name
    }
}
