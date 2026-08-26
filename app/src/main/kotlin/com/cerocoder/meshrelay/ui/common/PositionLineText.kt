package com.cerocoder.meshrelay.ui.common

import com.cerocoder.meshrelay.stats.AgeBucket
import com.cerocoder.meshrelay.stats.model.Direction
import com.cerocoder.meshrelay.stats.model.LocationInfo
import com.cerocoder.meshrelay.stats.model.PositionSource
import java.util.Locale

/**
 * The independent fragments of a rendered position line. Kept apart rather
 * than pre-joined into one string so [PositionLine] can lay them out - and
 * skip the ones that are `null` - without doing any string work of its own.
 *
 * A [LocationInfo] with no position at all (`LocationInfo.EMPTY`) produces
 * every field `null`: a node that never published a position renders as
 * nothing, not as a line of dashes.
 */
data class PositionParts(
    val coordinates: String?,
    val distance: String?,
    val altitude: String?,
    val source: String?,
)

/**
 * Every format string and label [PositionLineText] needs, already resolved
 * from string resources by the caller - a `@Composable` in production - so
 * this stays a plain object [PositionLineText] can take without touching
 * Android, and so the formatter is unit-testable on the JVM.
 *
 * [locale] is the *display* locale, deliberately distinct from the
 * [Locale.ROOT] [MapLinks] uses: this text is prose read by a person, and a
 * Spanish reader expects a decimal comma, not a period.
 */
data class PositionStrings(
    val locale: Locale,
    val coordinatesFormat: String,
    val distanceFormat: String,
    val distanceUncertainFormat: String,
    val altitudeFormat: String,
    val sourceFormat: String,
    val sourceAgedFormat: String,
    val ageLabels: Map<AgeBucket, String>,
    val directionLabels: Map<Direction, String>,
    val sourceLabels: Map<PositionSource, String>,
)

/**
 * Ports `render_position_oneline`, mesh_stats.py:1747-1800.
 *
 * The `"(Dist: ...)"` / `"(Alt: ...)"` labelling and the trailing Meshview
 * link from the original are not reproduced here: [PositionLine] supplies
 * its own layout instead of parenthesised labels, and the Meshview link is
 * one of [MapLinks]' buttons rather than text appended to the line.
 *
 * Pure and Android-free by construction: every resource string and label
 * arrives already resolved through [PositionStrings], so this object never
 * touches `Context` or `Resources` and can be exercised on the JVM without a
 * Composable host.
 */
object PositionLineText {

    /** mesh_stats.py:1749 formats both coordinates to six decimal places. */
    private const val COORDINATE_PATTERN = "%.6f"

    /** mesh_stats.py:1761 and :1766 format distance and its delta to one decimal place. */
    private const val DISTANCE_PATTERN = "%.1f"

    /**
     * mesh_stats.py:1764. Below this, the obfuscation radius is narrower than
     * the one-decimal precision the distance figure itself is printed to, so
     * showing it would claim more certainty about the uncertainty than the
     * number actually carries.
     */
    private const val UNCERTAINTY_FLOOR_KM = 0.1

    /**
     * mesh_stats.py:1761's `f"/{direction}"`. This is glue punctuation, not
     * translated content - the direction letters themselves are already
     * localised through [PositionStrings.directionLabels] - so it is a
     * literal here rather than a string resource.
     */
    private const val DIRECTION_SEPARATOR = "/"

    fun parts(info: LocationInfo, nowMillis: Long, res: PositionStrings): PositionParts {
        val lat = info.lat
        val lon = info.lon

        val coordinates = if (lat != null && lon != null) {
            String.format(
                res.locale,
                res.coordinatesFormat,
                String.format(res.locale, COORDINATE_PATTERN, lat),
                String.format(res.locale, COORDINATE_PATTERN, lon),
            )
        } else {
            null
        }

        val distanceKm = info.distanceKm
        val distance = if (lat != null && lon != null && distanceKm != null) {
            val distanceText = String.format(res.locale, DISTANCE_PATTERN, distanceKm)
            val obfuscationKm = info.obfuscationRadiusMeters?.div(1000.0)
            val withoutDirection = if (obfuscationKm != null && obfuscationKm >= UNCERTAINTY_FLOOR_KM) {
                val obfuscationText = String.format(res.locale, DISTANCE_PATTERN, obfuscationKm)
                String.format(res.locale, res.distanceUncertainFormat, distanceText, obfuscationText)
            } else {
                String.format(res.locale, res.distanceFormat, distanceText)
            }
            if (info.direction == Direction.UNKNOWN) {
                withoutDirection
            } else {
                withoutDirection + DIRECTION_SEPARATOR + res.directionLabels.getValue(info.direction)
            }
        } else {
            null
        }

        // Absent (null) is "no reading was ever taken"; 0 is a real altitude
        // at sea level. Only the former is omitted - `Int?.let` already draws
        // that line without an extra branch.
        val altitude = info.altitude?.let { String.format(res.locale, res.altitudeFormat, it) }

        val source = info.source?.let { source ->
            val sourceLabel = res.sourceLabels.getValue(source)
            val atMillis = info.atMillis
            if (atMillis == null) {
                String.format(res.locale, res.sourceFormat, sourceLabel)
            } else {
                val bucket = AgeBucket.of(nowMillis - atMillis)
                val ageLabel = res.ageLabels.getValue(bucket)
                String.format(res.locale, res.sourceAgedFormat, sourceLabel, ageLabel)
            }
        }

        return PositionParts(coordinates = coordinates, distance = distance, altitude = altitude, source = source)
    }
}
