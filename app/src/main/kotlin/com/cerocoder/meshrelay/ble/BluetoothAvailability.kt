package com.cerocoder.meshrelay.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Readiness of the Bluetooth subsystem. */
enum class BleReadiness {
    READY,
    PERMISSIONS_MISSING,
    ADAPTER_OFF,
    UNSUPPORTED,
}

/**
 * Answers the question "can we scan and connect right now".
 *
 * The spec requires that missing permissions and a disabled adapter be states, not
 * exceptions: the user must see an understandable reason, not an empty device list.
 */
class BluetoothAvailability(private val context: Context) {

    /** Permissions to request on this version of Android. */
    val requiredPermissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun check(): BleReadiness {
        val manager = context.getSystemService(BluetoothManager::class.java)
            ?: return BleReadiness.UNSUPPORTED
        val adapter = manager.adapter ?: return BleReadiness.UNSUPPORTED

        val granted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!granted) return BleReadiness.PERMISSIONS_MISSING

        return if (adapter.isEnabled) BleReadiness.READY else BleReadiness.ADAPTER_OFF
    }
}
