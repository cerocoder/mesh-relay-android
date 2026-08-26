package com.cerocoder.meshrelay.stats

import com.cerocoder.meshrelay.stats.model.Direction
import com.cerocoder.meshrelay.stats.model.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private val MADRID = LatLon(40.4168, -3.7038)
private val GETAFE = LatLon(40.3083, -3.7325)
private val TOLEDO = LatLon(39.8628, -4.0273)

class GeoTest {

    @Test
    fun `haversine matches known distances in the local mesh`() {
        assertEquals(12.30726, Geo.haversineKm(MADRID.lat, MADRID.lon, GETAFE.lat, GETAFE.lon), 0.001)
        assertEquals(67.46109, Geo.haversineKm(MADRID.lat, MADRID.lon, TOLEDO.lat, TOLEDO.lon), 0.001)
    }

    @Test
    fun `haversine matches one degree of longitude at the equator`() {
        assertEquals(111.19493, Geo.haversineKm(0.0, 0.0, 0.0, 1.0), 0.001)
    }

    @Test
    fun `haversine of a point with itself is zero`() {
        assertEquals(0.0, Geo.haversineKm(MADRID.lat, MADRID.lon, MADRID.lat, MADRID.lon), 1e-9)
    }

    @Test
    fun `bearing points south toward Getafe and south west toward Toledo`() {
        assertEquals(191.4047, Geo.bearingDegrees(MADRID, GETAFE), 0.001)
        assertEquals(204.1605, Geo.bearingDegrees(MADRID, TOLEDO), 0.001)
    }

    @Test
    fun `bearing is normalised into zero until three hundred and sixty`() {
        val west = Geo.bearingDegrees(MADRID, LatLon(MADRID.lat, MADRID.lon - 1.0))
        assert(west in 0.0..360.0) { "bearing must be normalised, was $west" }
        assertEquals(270.0, west, 0.5)
    }

    @Test
    fun `direction sectors are centred on their compass point`() {
        assertEquals(Direction.N, Geo.directionOf(0.0))
        assertEquals(Direction.N, Geo.directionOf(22.4))
        assertEquals(Direction.NE, Geo.directionOf(22.5))
        assertEquals(Direction.E, Geo.directionOf(67.5))
        assertEquals(Direction.S, Geo.directionOf(180.0))
        assertEquals(Direction.N, Geo.directionOf(337.5))
        assertEquals(Direction.N, Geo.directionOf(359.9))
    }

    @Test
    fun `direction accepts bearings outside one full turn`() {
        assertEquals(Direction.N, Geo.directionOf(720.0))
        assertEquals(Direction.N, Geo.directionOf(-0.1))
    }

    @Test
    fun `obfuscation radius halves with every bit`() {
        assertEquals(2918.1865234375, Geo.obfuscationRadiusMeters(13)!!, 1e-9)
        assertEquals(364.7733154296875, Geo.obfuscationRadiusMeters(16)!!, 1e-9)
    }

    @Test
    fun `obfuscation radius is unknown when precision is absent or zero`() {
        assertNull(Geo.obfuscationRadiusMeters(null))
        assertNull(Geo.obfuscationRadiusMeters(0))
        assertNull(Geo.obfuscationRadiusMeters(-1))
    }

    @Test
    fun `last byte of a node number is its low byte`() {
        assertEquals(0xa4, Geo.lastByteOfNodeNum(0x9e75f1a4.toInt()))
        assertEquals(0x01, Geo.lastByteOfNodeNum(0x00000001))
    }

    @Test
    fun `a low byte of zero reads as ff`() {
        // Not arithmetic - a firmware convention. The relay field never carries 0
        // to mean a node, so a node whose number ends in 00 identifies itself as ff.
        // This single line decides which nodes are offered as candidates for every
        // relay in the application.
        assertEquals(0xFF, Geo.lastByteOfNodeNum(0x9e75f100.toInt()))
        assertEquals(0xFF, Geo.lastByteOfNodeNum(0))
    }
}
