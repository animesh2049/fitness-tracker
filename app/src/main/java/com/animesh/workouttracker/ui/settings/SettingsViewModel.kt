package com.animesh.workouttracker.ui.settings

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.animesh.workouttracker.BuildConfig
import com.animesh.workouttracker.backup.BackupCodec
import com.animesh.workouttracker.backup.BackupFormatException
import com.animesh.workouttracker.backup.CsvExport
import com.animesh.workouttracker.backup.ImportMode
import com.animesh.workouttracker.data.model.Settings
import com.animesh.workouttracker.di.AppContainer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsState(
    val settings: Settings = Settings(),
    /** Outcome of the last export or import, shown under the backup buttons. */
    val backupStatus: String? = null,
    val backupBusy: Boolean = false,
    val loaded: Boolean = false
)

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    private val status = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)

    val state: StateFlow<SettingsState> = combine(c.settings.observe(), status, busy) { s, st, b ->
        SettingsState(settings = s, backupStatus = st, backupBusy = b, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsState())

    val appVersion: String = BuildConfig.VERSION_NAME

    fun update(transform: (Settings) -> Settings) {
        viewModelScope.launch { c.settings.update(transform) }
    }

    fun exportJson(uri: Uri) = runBackup {
        val file = BackupCodec.snapshot(c.database, appVersion)
        write(uri, BackupCodec.encode(file))
        "Exported ${count(file.rowCount)} rows to ${displayName(uri)}"
    }

    fun exportCsv(uri: Uri) = runBackup {
        val unit = c.settings.get().unit
        val sessions = c.database.sessionDao().getFinishedSessions()
        write(uri, CsvExport.build(sessions, unit))
        "Exported ${count(CsvExport.rowCount(sessions))} rows to ${displayName(uri)}"
    }

    fun importJson(uri: Uri, mode: ImportMode) = runBackup {
        val text = read(uri)
        val result = BackupCodec.import(c.database, text, mode)
        val verb = if (mode == ImportMode.REPLACE) "Replaced with" else "Merged"
        "$verb ${count(result.total)} rows from ${displayName(uri)}"
    }

    private fun runBackup(block: suspend () -> String) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            status.value = try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: BackupFormatException) {
                e.message ?: "Not a workout backup file"
            } catch (e: Exception) {
                "Failed: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy.value = false
            }
        }
    }

    private fun write(uri: Uri, text: String) {
        val out = c.appContext.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("Could not open file for writing")
        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private fun read(uri: Uri): String {
        val input = c.appContext.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Could not open file")
        return input.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun displayName(uri: Uri): String {
        try {
            c.appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx)?.let { if (it.isNotBlank()) return it }
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun count(n: Int): String = String.format(Locale.US, "%,d", n)
}
