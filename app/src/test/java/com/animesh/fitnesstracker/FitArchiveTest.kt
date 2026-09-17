package com.animesh.fitnesstracker

import com.animesh.fitnesstracker.garmin.fit.DecodedFit
import com.animesh.fitnesstracker.garmin.fit.FileIdRec
import com.animesh.fitnesstracker.garmin.fit.FitDecodeException
import com.animesh.fitnesstracker.garmin.fitimport.FitArchive
import com.animesh.fitnesstracker.garmin.sync.ImportHook
import com.animesh.fitnesstracker.garmin.sync.ImportSummary
import com.animesh.fitnesstracker.garmin.sync.RawFileStore
import com.animesh.fitnesstracker.garmin.sync.StoredFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** In-memory store backed by a temp directory, the shape Milestone 17's real store will have. */
class FakeRawFileStore(override val root: File) : RawFileStore {
    private val files = ArrayList<StoredFile>()
    val importedCalls = ArrayList<Triple<Int, Long?, Int>>()

    override fun exists(watchIndex: Int, fitType: Int, watchTimestamp: Long?): Boolean =
        files.any { it.watchIndex == watchIndex && it.fitType == fitType && it.watchTimestamp == watchTimestamp && it.file.length() > 0 }

    override fun save(watchIndex: Int, fitType: Int, watchTimestamp: Long?, bytes: ByteArray): StoredFile {
        val file = File(root, "$fitType/f_${watchTimestamp ?: 0}_$watchIndex.fit")
        file.parentFile.mkdirs()
        file.writeBytes(bytes)
        files.removeAll { it.file == file }
        return StoredFile(watchIndex, fitType, watchTimestamp, file).also { files += it }
    }

    override fun saveImported(fitType: Int, timeCreated: Long?, bytes: ByteArray): StoredFile {
        importedCalls += Triple(fitType, timeCreated, bytes.size)
        return save(0, fitType, timeCreated, bytes)
    }

    override fun listAll(): List<StoredFile> = files.sortedBy { it.watchTimestamp ?: 0 }
    override fun totalBytes(): Long = files.sumOf { it.sizeBytes }
}

/** Fake decoder: the bytes are "type:timeCreated" as text; anything starting with BAD is corrupt. */
fun fakeDecode(bytes: ByteArray): DecodedFit {
    val text = String(bytes)
    if (text.startsWith("BAD")) throw FitDecodeException("bad magic")
    val (type, time) = text.split(':')
    return DecodedFit(FileIdRec(typeNum = type.toInt(), timeCreated = time.toLong()))
}

class FitArchiveTest {
    private lateinit var root: File
    private lateinit var store: FakeRawFileStore
    private val archive = FitArchive(::fakeDecode)

    private class RecordingHook : ImportHook {
        val batches = ArrayList<List<StoredFile>>()
        override suspend fun importFiles(files: List<StoredFile>): ImportSummary {
            batches += files
            return ImportSummary(filesImported = files.size, filesFailed = 0, minuteSamples = files.size * 10)
        }
    }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("fit-archive").toFile()
        store = FakeRawFileStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun exportZipKeepsPathsRelativeToTheStoreRoot() {
        store.save(1, 32, 1_789_000_000, "32:1789000000".toByteArray())
        store.save(2, 49, 1_789_010_000, "49:1789010000".toByteArray())
        val out = ByteArrayOutputStream()
        assertEquals(2, archive.exportZip(store, out))
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                entries[e.name] = zip.readBytes()
            }
        }
        assertEquals(listOf("32/f_1789000000_1.fit", "49/f_1789010000_2.fit"), entries.keys.toList())
        assertArrayEquals("49:1789010000".toByteArray(), entries["49/f_1789010000_2.fit"])
    }

    @Test
    fun importZipStoresFitEntriesSkipsOthersAndReportsCorruptOnes() = runTest {
        val zipBytes = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { zip ->
                fun add(name: String, content: String) { zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry() }
                zip.putNextEntry(ZipEntry("monitor/")); zip.closeEntry()
                add("monitor/2026/a.fit", "32:1789000000")
                add("notes.txt", "hello")
                add("sleep/b.FIT", "49:1789010000")
                add("broken.fit", "BAD data")
            }
        }.toByteArray()
        val hook = RecordingHook()
        val summary = archive.importZip(ByteArrayInputStream(zipBytes), store, hook)
        assertEquals(1, hook.batches.size)
        assertEquals(2, hook.batches[0].size)
        assertEquals(listOf(Triple(32, 1_789_000_000L, 13), Triple(49, 1_789_010_000L, 13)), store.importedCalls)
        assertEquals(2, summary.filesImported)
        assertEquals(1, summary.filesFailed)
        assertEquals(20, summary.minuteSamples)
        assertEquals(1, summary.errors.size)
        assertTrue(summary.errors[0].startsWith("broken.fit: bad magic"))
        assertTrue(store.listAll().all { it.watchIndex == 0 })
    }

    @Test
    fun importFitStoresOneFileAndAnEmptyOneFails() = runTest {
        val hook = RecordingHook()
        val ok = archive.importFit("METRICS_G9G80119.fit", "44:1789020000".toByteArray(), store, hook)
        assertEquals(1, ok.filesImported)
        assertEquals(0, ok.filesFailed)
        assertEquals(Triple(44, 1_789_020_000L, 13), store.importedCalls.single())
        val empty = archive.importFit("empty.fit", ByteArray(0), store, hook)
        assertEquals(0, empty.filesImported)
        assertEquals(1, empty.filesFailed)
        assertEquals(listOf("empty.fit: empty file"), empty.errors)
        assertEquals(1, hook.batches.size)
    }

    @Test
    fun exportThenImportRoundTripsEveryFile() = runTest {
        store.save(1, 32, 1_789_000_000, "32:1789000000".toByteArray())
        store.save(2, 4, 1_789_030_000, "4:1789030000".toByteArray())
        val out = ByteArrayOutputStream()
        archive.exportZip(store, out)
        val otherRoot = Files.createTempDirectory("fit-archive-2").toFile()
        try {
            val other = FakeRawFileStore(otherRoot)
            val hook = RecordingHook()
            val summary = archive.importZip(ByteArrayInputStream(out.toByteArray()), other, hook)
            assertEquals(2, summary.filesImported)
            assertEquals(listOf(32, 4), other.listAll().map { it.fitType })
            assertEquals(store.totalBytes(), other.totalBytes())
        } finally {
            otherRoot.deleteRecursively()
        }
        assertTrue(FitArchive.isFitName("a/b/C.Fit"))
        assertTrue(!FitArchive.isFitName("a/b/c.fit.txt"))
    }
}
