package com.cerocoder.meshrelay.stats.model

import org.meshtastic.proto.Position

/**
 * One position as it was heard. Ports PositionMessage, mesh_stats.py:270-307.
 */
data class PositionReport(
    val atMillis: Long,
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Int?,
    val precisionBits: Int?,
) {
    val hasCoordinates: Boolean get() = latitude != null && longitude != null
    val hasAltitude: Boolean get() = altitude != null

    companion object {
        /** Coordinates travel as integers scaled by ten million. */
        private const val COORD_SCALE = 1e-7

        fun fromProto(position: Position, atMillis: Long): PositionReport = PositionReport(
            atMillis = atMillis,
            // Multiplied in Double: at this scale a Float loses roughly ten metres.
            latitude = position.latitude_i?.let { it * COORD_SCALE },
            longitude = position.longitude_i?.let { it * COORD_SCALE },
            // altitude_hae is height above the WGS84 ellipsoid and is preferred when
            // present; altitude is the older mean-sea-level field.
            altitude = position.altitude_hae ?: position.altitude,
            // precision_bits is not an optional field, so 0 is what "unset" looks
            // like on the wire and must not be read as a real precision.
            precisionBits = position.precision_bits.takeIf { it != 0 },
        )
    }
}
