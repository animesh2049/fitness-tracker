package com.animesh.fitnesstracker.garmin.fitimport

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.animesh.fitnesstracker.garmin.sync.ImportSummary
import com.animesh.fitnesstracker.garmin.sync.RawFileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Rebuilds the health tables from the stored raw files once after a schema change that added
 * columns the importer fills (version 4 added floors, the sleep score breakdown, Sleep Coach need,
 * Body Battery at sleep start and end, events and activity benefit). The rebuild is the same
 * `reimportAll` the Watch screen offers; this class only decides whether it is due and remembers
 * that it ran, in a small SharedPreferences file, so the watch is never needed for an upgrade.
 */
class HealthRebuild(private val prefs: SharedPreferences, private val store: RawFileStore, private val importer: FitImporter) {
    constructor(context: Context, store: RawFileStore, importer: FitImporter) :
        this(context.getSharedPreferences("health_rebuild", Context.MODE_PRIVATE), store, importer)

    sealed class State {
        data object Idle : State()
        data object Running : State()
        data class Done(val summary: ImportSummary) : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /** True when the stored files were imported by an older schema and the rows lack the new columns. */
    val isDue: Boolean get() = prefs.getInt(KEY_VERSION, 0) < HEALTH_SCHEMA

    /** Runs the rebuild when due. A store without files just records the version. */
    suspend fun runIfNeeded() {
        if (!isDue) return
        _state.value = State.Running
        try {
            if (store.listAll().isEmpty()) {
                markDone()
                _state.value = State.Idle
                return
            }
            val summary = importer.reimportAll()
            markDone()
            _state.value = State.Done(summary)
            Log.i(TAG, "Rebuilt health rows from ${summary.filesImported} files (${summary.filesFailed} failed)")
        } catch (e: Exception) {
            Log.e(TAG, "Health rebuild failed", e)
            _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** The Watch screen's own re-import also satisfies the rebuild. */
    fun markDone() {
        prefs.edit().putInt(KEY_VERSION, HEALTH_SCHEMA).apply()
    }

    companion object {
        private const val TAG = "HealthRebuild"
        const val KEY_VERSION = "health_schema_version"
        /** Bump when the importer starts filling columns that existing rows do not have. */
        const val HEALTH_SCHEMA = 4
    }
}
