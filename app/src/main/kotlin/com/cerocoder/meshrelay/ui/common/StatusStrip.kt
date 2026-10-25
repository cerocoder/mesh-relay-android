package com.cerocoder.meshrelay.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.stats.SortMode
import com.cerocoder.meshrelay.stats.model.AltitudeState
import com.cerocoder.meshrelay.stats.model.StatsSnapshot

/** One counter in the strip: its label and the number the snapshot reports for it. */
data class StatusCount(@StringRes val label: Int, val value: Int)

/**
 * The header block above a main screen: the node database line (ports the
 * `DB(...)` corner of `render_header`, mesh_stats.py:1661-1719), then whichever
 * counters that screen reports, the active sort and - while paused - a badge.
 *
 * One composable for all three screens. It began as a private one on the relay
 * list, was copied into the neighbour list, and a third copy for My node is what
 * this move prevents - F-1 was exactly a duplicated header left to grow. The
 * screens differ only in [counts] and in whether they sort anything:
 *
 * | Screen     | Counts                   | Sort |
 * |------------|--------------------------|------|
 * | Relays     | Total, Relayed           | yes  |
 * | Neighbours | Direct                   | yes  |
 * | My node    | Total, Relayed, Direct   | no   |
 *
 * [sortMode] is `null` on a screen that sorts nothing, the same shape and for
 * the same reason as [SortAction] in [StatsTopBar]. Callers with a list pass the
 * mode the list actually applied - the neighbour list passes
 * [SortMode.forNeighbours]'s result - so the strip never names an order that did
 * not run.
 *
 * The counters row is a [FlowRow], not a [Row]: three counts plus the sort pair
 * plus the paused badge, in Spanish and at a large font scale, is more content
 * than a phone's width, and F-3 was that shape wrapping badly rather than at all.
 *
 * Every number here comes straight off [StatsSnapshot] with no arithmetic of its
 * own. The right end of the first line is `LOAD(n)`, the number of nodes a node
 * file supplied, where the original showed when the database was loaded. The
 * altitude is coloured by how it was got: green when the latest fix gave a good
 * measurement, yellow when an earlier one is being held, red when there is none.
 */
@Composable
fun StatusStrip(
    snapshot: StatsSnapshot,
    counts: List<StatusCount>,
    sortMode: SortMode?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val altitudeField = stringResource(R.string.format_altitude_field, myAltitudeText(snapshot))
        val header = stringResource(
            R.string.format_db_header,
            snapshot.directory.count,
            snapshot.directory.airCount,
            altitudeField,
            snapshot.directory.loadedCount,
        )
        val altitudeColor = altitudeColor(snapshot.observer?.altitudeState ?: AltitudeState.MISSING)
        Text(
            text = buildAnnotatedString {
                append(header)
                // The field is a whole substituted argument, so it is found as a whole.
                val start = header.indexOf(altitudeField)
                if (start >= 0) {
                    addStyle(SpanStyle(color = altitudeColor, fontWeight = FontWeight.Bold), start, start + altitudeField.length)
                }
            },
            style = MaterialTheme.typography.bodySmall,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            counts.forEach { count ->
                LabelledCount(stringResource(count.label), count.value)
            }

            if (sortMode != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.sort_label), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = stringResource(SortModeLabels.labelOf(sortMode)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // On every screen, not only the lists: pausing is engine-wide, and a
            // screen that hid the badge would let a user forget the whole app is
            // frozen. relays_status_paused's text ("Paused") is generic; there is no
            // per-screen key, and inventing one for identical text would only fork a
            // translation.
            if (snapshot.paused) {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Text(
                        text = stringResource(R.string.relays_status_paused),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

/** One "label value" pair, kept as two `Text` nodes rather than one interpolated
 *  string - [count] is a plain digit sequence, not a locale-sensitive number, so
 *  there is nothing here for a pure formatter to own. */
@Composable
private fun LabelledCount(label: String, count: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(count.toString(), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * The observer's current altitude - the phone's when *Use phone location* is on and
 * a fix has arrived, the node's otherwise - or [R.string.common_not_available] when
 * there is none. Resolved once by the engine as [StatsSnapshot.observer], the same
 * position that anchors every distance and the Graph's pins.
 */
@Composable
private fun myAltitudeText(snapshot: StatsSnapshot): String {
    val altitude = snapshot.observer?.altitude
        ?: return stringResource(R.string.common_not_available)
    return stringResource(R.string.format_altitude_m, altitude)
}

/**
 * Light on a dark background and deep on a light one, chosen by the background's
 * luminance so the green, yellow and red each stay readable in both themes.
 */
@Composable
private fun altitudeColor(state: AltitudeState): Color {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return when (state) {
        AltitudeState.FRESH -> if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
        AltitudeState.HELD -> if (dark) Color(0xFFFFEB3B) else Color(0xFFB26A00)
        AltitudeState.MISSING -> if (dark) Color(0xFFFF8A80) else Color(0xFFC62828)
    }
}
