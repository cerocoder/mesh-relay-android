package com.cerocoder.meshrelay.stats.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StampedPositionTest {

    @Test
    fun `fromDegrees defaults to no altitude`() {
        val position = StampedPosition.fromDegrees(40.3057734, -3.7325611, PositionOrigin.PHONE)
        assertNull(position.altitude)
    }

    @Test
    fun `fromDegrees carries the altitude it is given`() {
        val position = StampedPosition.fromDegrees(40.3057734, -3.7325611, PositionOrigin.NODE, altitude = 612)
        assertEquals(612, position.altitude)
    }

    @Test
    fun `NO_ALTITUDE is outside any real altitude a sample could carry`() {
        assertEquals(Int.MIN_VALUE, StampedPosition.NO_ALTITUDE)
    }

    private fun position(altitude: Int?, held: Boolean = false) =
        StampedPosition.fromDegrees(40.3, -3.7, PositionOrigin.PHONE, altitude, held)

    @Test
    fun `an altitude that is not held is fresh`() {
        assertEquals(AltitudeState.FRESH, position(720).altitudeState)
    }

    @Test
    fun `a held altitude is held`() {
        assertEquals(AltitudeState.HELD, position(720, held = true).altitudeState)
    }

    @Test
    fun `no altitude is missing whether or not it was marked held`() {
        assertEquals(AltitudeState.MISSING, position(null).altitudeState)
        assertEquals(AltitudeState.MISSING, position(null, held = true).altitudeState)
    }
}
