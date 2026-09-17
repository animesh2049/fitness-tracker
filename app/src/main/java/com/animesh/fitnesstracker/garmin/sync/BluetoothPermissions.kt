package com.animesh.fitnesstracker.garmin.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Which runtime permissions the sync stack needs on this SDK level: BLUETOOTH_SCAN and
 * BLUETOOTH_CONNECT from API 31, ACCESS_FINE_LOCATION before that (BLUETOOTH and BLUETOOTH_ADMIN are
 * install-time permissions on those releases). The UI asks for [missing] before scanning or syncing.
 */
object BluetoothPermissions {
    fun required(sdk: Int = Build.VERSION.SDK_INT): List<String> =
        if (sdk >= Build.VERSION_CODES.S) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun missing(context: Context): List<String> =
        required().filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }

    fun granted(context: Context): Boolean = missing(context).isEmpty()
}
