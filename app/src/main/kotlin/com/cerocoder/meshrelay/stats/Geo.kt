package com.cerocoder.meshrelay.stats

import com.cerocoder.meshrelay.stats.model.Direction
import com.cerocoder.meshrelay.stats.model.LatLon
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Ports haversine_distance, obfuscation_radius_meters, bearing_to_direction and
 *  get_last_byte_of_node_num, mesh_stats.py:184-222 and :481-487. */
object Geo {

    private const val EARTH_RADIUS_KM = 6371.0

    /**
     * Meshtastic's position obfuscation: the radius halves with every bit of
     * precision the sender chose to keep. The constant is the firmware's.
     */
    private const val OBFUSCATION_BASE_METERS = 23905784.0

    private val SECTORS = arrayOf(
        Direction.N, Direction.NE, Direction.E, Direction.SE,
        Direction.S, Direction.SW, Direction.W, Direction.NW,
    )

    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(lat1Rad) * cos(lat2Rad) * sin(dLon / 2).pow(2)
        return EARTH_RADIUS_KM * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing from [from] to [to], degrees clockwise from north. */
    fun bearingDegrees(from: LatLon, to: LatLon): Double {
        val dLon = Math.toRadians(to.lon - from.lon)
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return normalise(Math.toDegrees(atan2(y, x)))
    }

    fun directionOf(bearingDeg: Double): Direction {
        val sector = ((normalise(bearingDeg) + 22.5) % 360.0 / 45.0).toInt()
        return SECTORS[sector]
    }

    fun obfuscationRadiusMeters(precisionBits: Int?): Double? {
        if (precisionBits == null || precisionBits <= 0) return null
        return OBFUSCATION_BASE_METERS / 2.0.pow(precisionBits)
    }

    /**
     * The relay field carries only the low byte of a node number, and never 0 -
     * the firmware substitutes ff. Reproduced exactly: this line decides which
     * database nodes are offered as candidates for every relay in the application.
     */
    fun lastByteOfNodeNum(nodeNum: Int): Int {
        val lastByte = nodeNum and 0xFF
        return if (lastByte == 0) 0xFF else lastByte
    }

    private fun normalise(degrees: Double): Double = (degrees % 360.0 + 360.0) % 360.0
}
