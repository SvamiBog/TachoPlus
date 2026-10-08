package com.example.data.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat

enum class BluetoothStatus {
    READY,
    PERMISSIONS_MISSING,
    BLUETOOTH_DISABLED,
    LOCATION_DISABLED_LEGACY
}

object BluetoothPermissionManager {

    /**
     * Returns list of required permissions for the current Android SDK level.
     */
    fun getRequiredPermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    /**
     * Checks if all required permissions are granted by user.
     */
    fun arePermissionsGranted(context: Context): Boolean {
        val permissions = getRequiredPermissions()
        return permissions.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Checks if Bluetooth adapter is present and powered on.
     */
    fun isBluetoothEnabled(context: Context): Boolean {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bm?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        return adapter != null && adapter.isEnabled
    }

    /**
     * On Android 11 and lower, location services (GPS) had to be enabled
     * for Bluetooth discovery to return results.
     */
    fun isLocationEnabledLegacy(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Returns general ready-state of Bluetooth scanning.
     */
    fun getOverallStatus(context: Context): BluetoothStatus {
        if (!arePermissionsGranted(context)) {
            return BluetoothStatus.PERMISSIONS_MISSING
        }
        if (!isBluetoothEnabled(context)) {
            return BluetoothStatus.BLUETOOTH_DISABLED
        }
        if (!isLocationEnabledLegacy(context)) {
            return BluetoothStatus.LOCATION_DISABLED_LEGACY
        }
        return BluetoothStatus.READY
    }
}
