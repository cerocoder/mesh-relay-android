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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.stats.AgeText
import com.cerocoder.meshrelay.stats.RelativeAge
import com.cerocoder.meshrelay.stats.SortMode
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
 * own; the one derived value ("how long ago was the database loaded") is produced
 * by [AgeText.relativeTo], the same tested function [AgeLabel] uses, so the load
 * time reads as a relative age ("5m ago") rather than the original's absolute
 * timestamp - there is no absolute-date-format string resource to port that
 * literally, and every other age on these screens is already relative to the same
 * clock.
 */
@Composable
fun StatusStrip(
    snapshot: StatsSnapshot,
    counts: List<StatusCount>,
    sortMode: SortMode?,
    modifier: Modifier = Modifier,
) {
    val nowMillis = LocalRelativeClock.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(
                R.string.format_db_header,
                snapshot.directory.count,
                snapshot.directory.airCount,
                dbLoadTimeText(snapshot.directory.loadedAtMillis, nowMillis),
            ),
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
 * How long ago the node database was (re)loaded, or [R.string.common_not_available]
 * before it ever has been. Mirrors the branch [AgeLabel] renders for a packet's
 * age: the subtraction and the never/seconds/minutes/hours split both live in
 * [AgeText.relativeTo], already unit tested there. This function only resolves the
 * matching string resource for each branch.
 */
@Composable
private fun dbLoadTimeText(loadedAtMillis: Long?, nowMillis: Long): String {
    if (loadedAtMillis == null) return stringResource(R.string.common_not_available)
    return when (val age = AgeText.relativeTo(nowMillis, loadedAtMillis)) {
        is RelativeAge.Seconds -> stringResource(R.string.format_ago_seconds, age.seconds)
        is RelativeAge.Minutes -> stringResource(R.string.format_ago_minutes, age.minutes, age.seconds)
        is RelativeAge.Hours -> stringResource(R.string.format_ago_hours, age.hours, age.minutes)
        RelativeAge.Never -> stringResource(R.string.common_never)
    }
}
