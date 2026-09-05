package com.cerocoder.meshrelay.ui.common

import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MapAppAvailability.of] itself needs a live `PackageManager`, which this
 * module has no Robolectric or Mockito to fake - the same reason
 * `LocationAvailability` and `BluetoothAvailability` carry no test of their
 * own `PackageManager`-touching methods. `isSameActivityAs` is the one piece
 * that touches neither: both sides are plain `ActivityInfo` field reads, built
 * here by hand exactly the way `LocationAvailabilityTest` builds a plain
 * permission array for its own untestable-`Context` class.
 *
 * This is also the exact comparison that decides `of`'s several-handlers
 * branch: whether `resolveActivity`'s answer is a genuine user default
 * ([MapAppState.One]) or the system's own resolver activity ([MapAppState.Several]).
 */
class MapAppAvailabilityTest {

    private fun resolveInfo(packageName: String, className: String): ResolveInfo =
        ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = className
            }
        }

    @Test
    fun `the same package and class are the same activity`() = with(MapAppAvailability) {
        val a = resolveInfo("com.example.maps", "com.example.maps.MapActivity")
        val b = resolveInfo("com.example.maps", "com.example.maps.MapActivity")
        assertTrue(a.isSameActivityAs(b))
    }

    @Test
    fun `the same package with a different class is not the same activity`() = with(MapAppAvailability) {
        // A mutant that compared packageName alone would pass this: two activities
        // in one application - an in-app chooser between a driving-mode map and a
        // normal one, say - are not interchangeable just because they share a package.
        val a = resolveInfo("com.example.maps", "com.example.maps.MapActivity")
        val b = resolveInfo("com.example.maps", "com.example.maps.CarModeActivity")
        assertFalse(a.isSameActivityAs(b))
    }

    @Test
    fun `different packages are not the same activity`() = with(MapAppAvailability) {
        val a = resolveInfo("com.example.maps", "com.example.maps.MapActivity")
        val b = resolveInfo("com.other.maps", "com.other.maps.MapActivity")
        assertFalse(a.isSameActivityAs(b))
    }
}
