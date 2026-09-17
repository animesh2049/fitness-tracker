package com.animesh.fitnesstracker.garmin.sync

import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Rolling sync log: the last [maxLines] lines in memory (exposed as a [StateFlow] for the Watch
 * screen) and mirrored to [file] (`<filesDir>/garmin/sync.log`, one `millis<TAB>text` line each) so a
 * failed background sync can still be inspected. File writes happen on a single background thread.
 */
class SyncLog(
    private val file: File?,
    private val maxLines: Int = 300,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val _lines = MutableStateFlow<List<SyncLogLine>>(emptyList())
    val lines: StateFlow<List<SyncLogLine>> = _lines
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "garmin-sync-log").apply { isDaemon = true } }
    private var sinceRewrite = 0

    init {
        val f = file
        if (f != null && f.exists()) {
            val loaded = runCatching {
                f.readLines().mapNotNull { line ->
                    val tab = line.indexOf('\t')
                    if (tab <= 0) null else line.substring(0, tab).toLongOrNull()?.let { SyncLogLine(it, line.substring(tab + 1)) }
                }.takeLast(maxLines)
            }.getOrDefault(emptyList())
            _lines.value = loaded
        }
    }

    fun log(text: String) {
        val line = SyncLogLine(clock(), text.replace('\n', ' '))
        val next = (_lines.value + line).takeLast(maxLines)
        _lines.value = next
        val f = file
        if (f != null) {
            val rewrite = ++sinceRewrite >= maxLines
            if (rewrite) sinceRewrite = 0
            io.execute {
                runCatching {
                    f.parentFile?.mkdirs()
                    if (rewrite) f.writeText(next.joinToString("") { "${it.atMillis}\t${it.text}\n" })
                    else f.appendText("${line.atMillis}\t${line.text}\n")
                }
            }
        }
    }

    fun clear() {
        _lines.value = emptyList()
        val f = file
        if (f != null) io.execute { runCatching { f.delete() } }
    }
}
