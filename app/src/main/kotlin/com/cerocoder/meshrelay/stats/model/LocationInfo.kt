package com.cerocoder.meshrelay.stats.model

/**
 * The rendered geography of one node relative to a reference position: where it
 * is, how far away, in which compass direction, and how much of that to trust
 * given the position's obfuscation radius. Ports the dict built by
 * get_node_location_info, mesh_stats.py:830-919.
 */
data class LocationInfo(
    val lat: Double?,
    val lon: Double?,
    val altitude: Int?,
    val distanceKm: Double?,
    val obfuscationRadiusMeters: Double?,
    val direction: Direction,
    val source: PositionSource?,
    val atMillis: Long?,
) {
    companion object {
        /** No position was available at all. */
        val EMPTY = LocationInfo(
            lat = null,
            lon = null,
            altitude = null,
            distanceKm = null,
            obfuscationRadiusMeters = null,
            direction = Direction.UNKNOWN,
            source = null,
            atMillis = null,
        )
    }
}
