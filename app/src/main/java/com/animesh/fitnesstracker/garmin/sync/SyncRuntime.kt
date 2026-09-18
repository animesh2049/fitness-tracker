package com.animesh.fitnesstracker.garmin.sync

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Process-wide sync state shared by the foreground service, the WorkManager worker and the
 * [WatchController]. The service and the worker publish [state] while a session runs and register
 * their job in [activeJob] so [WatchController.cancelSync] can stop it; the controller registers
 * itself so the service can build sessions without a dependency container.
 */
object SyncRuntime {
    val state = MutableStateFlow<SyncState>(SyncState.Idle)

    @Volatile var controller: WatchController? = null

    @Volatile var activeJob: Job? = null

    /** The workout waiting for the upload service, set by [WatchController.requestWorkoutUpload]; see [WorkoutOutbox]. */
    @Volatile var pendingUpload: PendingWorkoutUpload? = null

    val isRunning: Boolean get() = state.value.isRunning || activeJob?.isActive == true
}
