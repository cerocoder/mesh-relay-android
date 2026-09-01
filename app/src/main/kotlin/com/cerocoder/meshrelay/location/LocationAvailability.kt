package com.cerocoder.meshrelay.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Whether this app may ask the platform where the phone is.
 *
 * The shape `BluetoothAvailability` established, with one difference: the
 * permission array does not vary by API level, so it is a constant rather than an
 * instance field - which also makes it testable without a Context.
 */
class LocationAvailability(private val context: Context) {

    /**
     * `any`, not `all`.
     *
     * From Android 12 the permission dialog offers "Precise" and "Approximate",
     * and the user choosing Approximate grants COARSE while denying FINE. That is
     * a working grant: `LocationManager`'s NETWORK_PROVIDER still delivers fixes,
     * and a coarse pin on a hillside is worth more than no pin. Requiring both
     * would treat the user's own choice as a refusal.
     */
    fun granted(): Boolean = REQUIRED_PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        /**
         * Both, and in this order. Naming only FINE would leave the system with no
         * coarse permission to grant when the user picks Approximate.
         */
        val REQUIRED_PERMISSIONS: Array<String> = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}
