package com.cerocoder.meshrelay.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalUriHandler
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
 * map links [PositionLineText] cannot open itself, being a plain function with
 * no composition to reach a uri handler from. Ports
 * `render_position_oneline` (mesh_stats.py:1747-1800) and
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
    // LocalUriHandler, not LocalContext.startActivity. Compose builds
    // AndroidUriHandler from the ComposeView's own context, so it opens links
    // against the activity whatever LocalContext has been overridden with further
    // down the tree - which LocalizedApp does override, once a language other than
    // the system one is chosen. That override is a ContextWrapper around the
    // activity now (see LocalizedContext), so startActivity would in fact work
    // again; the uri handler stays because it is the narrower tool for the job and
    // does not depend on that being true.
    val uriHandler = LocalUriHandler.current

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // One row per field, each marked by its own glyph, rather than the four
        // parts joined into a single sentence. Joined, "697 m Src: current:30min"
        // put a distance in metres directly against an age in minutes and left
        // the reader to work out which unit belonged to which number. A row per
        // field also cannot overflow the way one long line does.
        //
        // bodySmall is this app's monospace style (see Type.kt). Node ids, short
        // names and the per-node counts beside this line all use it, and a
        // position rendered in the default proportional body style reads as a
        // different kind of content sitting inside the same card.
        val coordinates = listOfNotNull(parts.coordinates, parts.distance)
            .joinToString(separator = " ")
        if (coordinates.isNotEmpty()) {
            FieldRow(
                icon = R.drawable.ic_field_coordinates,
                contentDescription = stringResource(R.string.node_coordinates),
                text = coordinates,
            )
        }
        parts.altitude?.let { altitude ->
            FieldRow(
                icon = R.drawable.ic_field_altitude,
                contentDescription = stringResource(R.string.node_altitude),
                text = altitude,
            )
        }
        // The same globe as the coordinates: this names where those coordinates
        // came from, so it belongs with them rather than in a category of its own.
        parts.source?.let { source ->
            FieldRow(
                icon = R.drawable.ic_field_coordinates,
                contentDescription = stringResource(R.string.node_position_source),
                text = source,
            )
        }

        val lat = info.lat
        val lon = info.lon
        if ((lat != null && lon != null) || meshviewUrl != null) {
            // FlowRow, not Row, and this is field issue F-3 rather than a
            // preference. A positioned node offers three links at once, and a plain
            // Row hands each child its measured width until the space runs out and
            // then squeezes whatever is left: on a 1080 px phone the first two took
            // 901 px between them and Meshview was measured at 111 px, which is
            // narrower than one of its words. It did not clip - it wrapped, to about
            // a character a line, and grew 958 px tall. On the relay screen that
            // pushed the list (the weight(1f) sibling) down to a 128 px slit, and on
            // a node card it rendered no label glyph at all: an invisible but still
            // clickable strip where the action should have been. The links are also
            // the widest text this app lays out and the ones that grow most in
            // Spanish, so they need a container that can take a second line.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (lat != null && lon != null) {
                    TextButton(onClick = { uriHandler.openUri(MapLinks.googleMaps(lat, lon)) }) {
                        Text(stringResource(R.string.node_open_google_maps))
                    }
                    TextButton(onClick = { uriHandler.openUri(MapLinks.openStreetMap(lat, lon)) }) {
                        Text(stringResource(R.string.node_open_osm))
                    }
                }
                if (meshviewUrl != null) {
                    TextButton(onClick = { uriHandler.openUri(MapLinks.meshview(meshviewUrl, nodeNum)) }) {
                        Text(stringResource(R.string.node_open_meshview))
                    }
                }
            }
        }
    }
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

/** One position field: its glyph, then its value. */
@Composable
private fun FieldRow(
    @DrawableRes icon: Int,
    contentDescription: String?,
    text: String,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FieldIcon(icon = icon, contentDescription = contentDescription)
        Text(text = text, style = MaterialTheme.typography.bodySmall)
    }
}
