package com.cerocoder.meshrelay.location

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The permission array is a plain constant with no Context and no
 * `Build.VERSION` branch, so it is testable on the JVM - which is the whole
 * reason it is a constant rather than an instance field the way
 * `BluetoothAvailability`'s is. `BluetoothAvailability` has no test today; this
 * adds one for the new type only.
 */
class LocationAvailabilityTest {

    @Test
    fun `both location permissions are requested, on every API level`() {
        // Fine alone is not enough to ask for: from Android 12 the user can
        // downgrade a fine request to approximate, and a request that names only
        // ACCESS_FINE_LOCATION gives the system no coarse permission to grant
        // instead - the dialog's "Approximate" button then grants nothing at all.
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            LocationAvailability.REQUIRED_PERMISSIONS.toList(),
        )
    }

    @Test
    fun `the array is not the Bluetooth one`() {
        // Guards against the copy-paste this file began life as: BLUETOOTH_SCAN
        // carries neverForLocation in the manifest, and requesting it here would
        // be asking for the wrong thing entirely.
        assertEquals(2, LocationAvailability.REQUIRED_PERMISSIONS.size)
    }
}
