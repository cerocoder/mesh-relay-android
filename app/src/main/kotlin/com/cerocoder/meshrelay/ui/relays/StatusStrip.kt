package com.cerocoder.meshrelay.ui.relays

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.cerocoder.meshrelay.stats.model.StatsSnapshot
import com.cerocoder.meshrelay.ui.common.LocalRelativeClock

/**
 * The header block above the relay list: the node database line (ports the
 * `DB(...)` corner of `render_header`, mesh_stats.py:1661-1719), then the
 * total/relayed counters, the active sort and - while paused - a badge.
 *
 * Every number here comes straight off [StatsSnapshot] with no arithmetic of
 * its own; the one derived value ("how long ago was the database loaded") is
 * produced by [AgeText.relativeTo], the same tested function [AgeLabel][
 * com.cerocoder.meshrelay.ui.common.AgeLabel] uses, so the load time reads as
 * a relative age ("5m ago") rather than the original's absolute timestamp -
 * there is no absolute-date-format string resource to port that literally,
 * and every other age on this screen is already relative to the same clock.
 */
@Composable
fun StatusStrip(snapshot: StatsSnapshot, modifier: Modifier = Modifier) {
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
                dbLoadTimeText(snapshot.directory.loadedAtMillis, nowMillis),
            ),
            style = MaterialTheme.typography.bodySmall,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LabelledCount(stringResource(R.string.relays_status_total), snapshot.counters.totalPackets)
            LabelledCount(stringResource(R.string.relays_status_relayed), snapshot.counters.totalRelayedPackets)

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.sort_label), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(SortModeLabels.labelOf(snapshot.sortMode)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

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
 * before it ever has been. Mirrors the branch [AgeLabel][
 * com.cerocoder.meshrelay.ui.common.AgeLabel] renders for a packet's age: the
 * subtraction and the never/seconds/minutes/hours split both live in
 * [AgeText.relativeTo], already unit tested there. This function only resolves
 * the matching string resource for each branch.
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
