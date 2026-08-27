package com.cerocoder.meshrelay.ui.common

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.stats.AgeBucket
import com.cerocoder.meshrelay.stats.model.Direction
import com.cerocoder.meshrelay.stats.model.LocationInfo
import com.cerocoder.meshrelay.stats.model.PositionSource
import java.util.Locale

/**
 * One line describing where a node is, plus small buttons for the external
 * map links [PositionLineText] can't build itself since it never sees a
 * `Context`. Ports `render_position_oneline` (mesh_stats.py:1747-1800) and
 * the map links appended after it (mesh_stats.py:1868-1872, plus the
 * Meshview link at mesh_stats.py:1793-1794) as one flowing text line with
 * the links underneath as buttons, rather than more text appended to it.
 *
 * Nothing here computes an age, a distance or a URL: [PositionLineText]
 * does the display formatting and [MapLinks] builds the link targets. This
 * function only resolves resources, decides what to show, and lays it out.
 */
@Composable
fun PositionLine(
    info: LocationInfo,
    nodeNum: Int,
    meshviewUrl: String?,
    modifier: Modifier = Modifier,
) {
    val strings = resolvePositionStrings()
    val nowMillis = LocalRelativeClock.current
    val parts = remember(info, nowMillis, strings) { PositionLineText.parts(info, nowMillis, strings) }
    val context = LocalContext.current

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val line = listOfNotNull(parts.coordinates, parts.distance, parts.altitude, parts.source)
            .joinToString(separator = " ")
        if (line.isNotEmpty()) {
            Text(text = line)
        }

        val lat = info.lat
        val lon = info.lon
        if ((lat != null && lon != null) || meshviewUrl != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (lat != null && lon != null) {
                    TextButton(onClick = { openUrl(context, MapLinks.googleMaps(lat, lon)) }) {
                        Text(stringResource(R.string.node_open_google_maps))
                    }
                    TextButton(onClick = { openUrl(context, MapLinks.openStreetMap(lat, lon)) }) {
                        Text(stringResource(R.string.node_open_osm))
                    }
                }
                if (meshviewUrl != null) {
                    TextButton(onClick = { openUrl(context, MapLinks.meshview(meshviewUrl, nodeNum)) }) {
                        Text(stringResource(R.string.node_open_meshview))
                    }
                }
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

/**
 * Resolves every format string and label [PositionLineText] needs, once per
 * recomposition, so [PositionLineText] itself never touches `Resources`.
 * [Direction.UNKNOWN] and [AgeBucket.UNKNOWN] are included for a complete,
 * crash-proof map even though the current logic never surfaces them - an
 * unknown direction is omitted rather than printed, and an unknown age only
 * shows up paired with a known source.
 *
 * Internal rather than private so other screens in this module that need a
 * fragment of [PositionLineText.parts] without the rest of [PositionLine] -
 * `ui/relays/RelayCard.kt` today - resolve the same strings this function
 * does instead of keeping a second, driftable copy.
 */
@Composable
internal fun resolvePositionStrings(): PositionStrings {
    val locales = LocalConfiguration.current.locales
    val locale = if (locales.isEmpty()) Locale.getDefault() else locales.get(0)
    return PositionStrings(
        locale = locale,
        coordinatesFormat = stringResource(R.string.format_coordinates),
        distanceFormat = stringResource(R.string.format_distance_km),
        distanceUncertainFormat = stringResource(R.string.format_distance_km_uncertain),
        altitudeFormat = stringResource(R.string.format_altitude_m),
        sourceFormat = stringResource(R.string.format_source),
        sourceAgedFormat = stringResource(R.string.format_source_aged),
        ageLabels = mapOf(
            AgeBucket.M1 to stringResource(R.string.age_1m),
            AgeBucket.M5 to stringResource(R.string.age_5m),
            AgeBucket.M30 to stringResource(R.string.age_30m),
            AgeBucket.H1 to stringResource(R.string.age_1h),
            AgeBucket.H12 to stringResource(R.string.age_12h),
            AgeBucket.D1 to stringResource(R.string.age_1d),
            AgeBucket.W1 to stringResource(R.string.age_1w),
            AgeBucket.Y1 to stringResource(R.string.age_1y),
            AgeBucket.UNKNOWN to stringResource(R.string.age_unknown),
        ),
        directionLabels = mapOf(
            Direction.N to stringResource(R.string.direction_n),
            Direction.NE to stringResource(R.string.direction_ne),
            Direction.E to stringResource(R.string.direction_e),
            Direction.SE to stringResource(R.string.direction_se),
            Direction.S to stringResource(R.string.direction_s),
            Direction.SW to stringResource(R.string.direction_sw),
            Direction.W to stringResource(R.string.direction_w),
            Direction.NW to stringResource(R.string.direction_nw),
            Direction.UNKNOWN to stringResource(R.string.direction_unknown),
        ),
        sourceLabels = mapOf(
            PositionSource.DB to stringResource(R.string.source_db),
            PositionSource.CURRENT to stringResource(R.string.source_current),
        ),
    )
}
