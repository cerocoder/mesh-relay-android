package com.cerocoder.meshrelay.stats.model

import kotlin.math.roundToInt

/**
 * Which device produced a measurement's coordinates.
 *
 * Deliberately not [PositionSource], which already exists and means something
 * else entirely: `CURRENT` vs `DB` is how *fresh a node's own* position is, not
 * whose receiver measured it.
 *
 * [code] is what the series buffer stores, one byte per sample. `0` is reserved
 * for "this sample has no position" and is therefore not an origin - which is
 * what lets the latitude and longitude arrays keep their default `0` with no
 * sentinel value and no ambiguity with the Gulf of Guinea.
 */
enum class PositionOrigin(val code: Byte) {
    NODE(1),
    PHONE(2);

    companion object {
        /** The stored code for a sample that carries no position at all. */
        const val NONE: Byte = 0

        fun ofCode(code: Byte): PositionOrigin? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Where the observer was standing, as one measurement recorded it.
 *
 * Coordinates are scaled integers at 1e-7 degrees - the protobuf's own
 * representation (`Position.latitude_i`). Four bytes, lossless at the
 * resolution the mesh transmits, and it respects the standing rule against
 * `Float` coordinates recorded at [PositionReport]: a `Float` loses roughly ten
 * metres at these magnitudes.
 */
data class StampedPosition(val latI: Int, val lonI: Int, val origin: PositionOrigin) {

    /** Multiplied in `Double`, the conversion [PositionReport] already documents. */
    val latitude: Double get() = latI * COORD_SCALE
    val longitude: Double get() = lonI * COORD_SCALE

    companion object {
        /** Coordinates travel as integers scaled by ten million. */
        const val COORD_SCALE = 1e-7

        /**
         * Rounded, not truncated: truncation biases every coordinate toward the
         * equator and the prime meridian by up to 1.1 cm, which is meaningless in
         * itself but is a bias rather than a wobble, and there is no reason to
         * introduce one.
         *
         * The full coordinate system fits: 180 degrees scales to 1.8e9 and `Int`
         * holds 2.147e9. `roundToInt` saturates rather than wrapping, so even a
         * nonsense value from a broken provider clamps instead of appearing on the
         * other side of the world.
         */
        fun fromDegrees(lat: Double, lon: Double, origin: PositionOrigin): StampedPosition =
            StampedPosition(
                latI = (lat / COORD_SCALE).roundToInt(),
                lonI = (lon / COORD_SCALE).roundToInt(),
                origin = origin,
            )
    }
}
