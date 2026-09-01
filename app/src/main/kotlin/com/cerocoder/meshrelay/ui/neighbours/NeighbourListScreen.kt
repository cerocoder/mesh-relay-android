package com.cerocoder.meshrelay.ui.neighbours

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.settings.GaugeMode
import com.cerocoder.meshrelay.stats.AgeText
import com.cerocoder.meshrelay.stats.RelativeAge
import com.cerocoder.meshrelay.stats.SortMode
import com.cerocoder.meshrelay.stats.model.StatsSnapshot
import com.cerocoder.meshrelay.ui.common.LocalNodeLine
import com.cerocoder.meshrelay.ui.common.LocalRelativeClock
import com.cerocoder.meshrelay.ui.common.SortModeLabels
import com.cerocoder.meshrelay.ui.common.StatsTopBar
import com.cerocoder.meshrelay.ui.preview.SampleData
import com.cerocoder.meshrelay.ui.theme.MeshRelayTheme

/**
 * The neighbour list - the port of the terminal tool's `[N]` view: `render_header`
 * in its `neighbours_mode` branch (mesh_stats.py:1661-1719) plus the neighbour
 * table it introduces (mesh_stats.py:1524-1659). The counterpart to
 * [com.cerocoder.meshrelay.ui.relays.RelayListScreen] - together the two answer
 * "who reaches me, and how": this screen is nodes heard directly, with no relay
 * in between, while the relay screen is everything forwarded through one.
 *
 * Stateless by design, on the same terms as the relay screen: every value shown
 * is a parameter, every action a lambda the caller supplies, and no local state
 * at all - the sort menu and the reset confirmation moved into the shared
 * [com.cerocoder.meshrelay.ui.common.StatsTopBar] along with the app bar itself.
 *
 * Differences from the relay screen, all because a neighbour is a known node
 * identity rather than a one-byte guess:
 * - No node-database reload action, no reload spinner and no connection state
 *   parameter - the neighbour list is built from live traffic, not a database
 *   fetch, so there is nothing here for a reload to refresh. That is what the
 *   omitted `reload` argument to [com.cerocoder.meshrelay.ui.common.StatsTopBar]
 *   says: the item is left out of the menu rather than shown disabled.
 * - The header's traffic line reports [com.cerocoder.meshrelay.stats.model.Counters.totalDirectPackets]
 *   under [R.string.neighbours_status_direct] ("Direct"), not the relay
 *   screen's separate Total/Relayed counts (mesh_stats.py:1674-1675's
 *   `neighbours_mode` branch drops the "Total"/"Relayed" split entirely).
 * - [NeighbourCard] is keyed by node identity - id plus short name - with no
 *   match-count bracket and no ambiguity gate on its position.
 */
@Composable
fun NeighbourListScreen(
    snapshot: StatsSnapshot,
    gaugeMode: GaugeMode,
    meshviewUrl: String?,
    onOpenNeighbour: (nodeNum: Int) -> Unit,
    onSetSortMode: (SortMode) -> Unit,
    onSetGaugeMode: (GaugeMode) -> Unit,
    onTogglePause: () -> Unit,
    onReset: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            StatsTopBar(
                title = stringResource(R.string.neighbours_title),
                sortMode = snapshot.sortMode,
                onSetSortMode = onSetSortMode,
                gaugeMode = gaugeMode,
                onSetGaugeMode = onSetGaugeMode,
                paused = snapshot.paused,
                onTogglePause = onTogglePause,
                onReset = onReset,
                onOpenSettings = onOpenSettings,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            NeighbourStatusStrip(snapshot = snapshot)
            LocalNodeLine(directory = snapshot.directory, meshviewUrl = meshviewUrl)

            if (snapshot.neighbours.isEmpty()) {
                EmptyNeighboursState(modifier = Modifier.weight(1f))
            } else {
                // Computed once, not per row: unlike a relay's matching-node
                // lookup, this device's own position does not depend on which
                // neighbour a given row is showing.
                val localPosition = snapshot.directory.localPosition()
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Keyed on nodeNum, not list index, so re-sorting animates
                    // items into their new position instead of rebuilding every row.
                    items(items = snapshot.neighbours, key = { it.nodeNum }) { neighbour ->
                        NeighbourCard(
                            neighbour = neighbour,
                            shortName = snapshot.directory.shortName(neighbour.nodeNum),
                            location = snapshot.directory.locationInfo(neighbour.nodeNum, localPosition),
                            totalDirect = snapshot.counters.totalDirectPackets,
                            gaugeMode = gaugeMode,
                            onClick = { onOpenNeighbour(neighbour.nodeNum) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The header block above the neighbour list: the node database line (ports the
 * `DB(...)` corner of `render_header`, mesh_stats.py:1661-1719), then the direct
 * packet count, the active sort and - while paused - a badge. The `neighbours_mode`
 * branch of the original (mesh_stats.py:1673-1675) reports one number, `total_direct`
 * under a "Neighbours:"/"direct" label, rather than the Total/Relayed pair the
 * non-neighbours branch shows - [R.string.neighbours_status_direct] is that label.
 *
 * Kept private to this file rather than shared with
 * [com.cerocoder.meshrelay.ui.relays.StatusStrip] - that composable is private to
 * its own package and this task does not touch it - so the DB-line and paused-badge
 * shape is a second, independent copy, not a shared one.
 */
@Composable
private fun NeighbourStatusStrip(snapshot: StatsSnapshot, modifier: Modifier = Modifier) {
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
            LabelledCount(stringResource(R.string.neighbours_status_direct), snapshot.counters.totalDirectPackets)

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.sort_label), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(SortModeLabels.labelOf(snapshot.sortMode)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (snapshot.paused) {
                // relays_status_paused's text ("Paused") is generic; there is
                // no separate neighbours_status_paused key, and inventing one
                // for identical text would only fork a translation.
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
 *  there is nothing here for a pure formatter to own. Mirrors
 *  [com.cerocoder.meshrelay.ui.relays.StatusStrip]'s private helper of the same name. */
@Composable
private fun LabelledCount(label: String, count: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(count.toString(), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * How long ago the node database was (re)loaded, or [R.string.common_not_available]
 * before it ever has been. Mirrors [com.cerocoder.meshrelay.ui.relays.StatusStrip]'s
 * private function of the same name: the subtraction and the never/seconds/minutes/hours
 * split both live in [AgeText.relativeTo], already unit tested there; this function
 * only resolves the matching string resource for each branch.
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

/** No directly received packet has ever arrived. Points at the Relays tab, where
 *  traffic that reached this device through a relay is shown instead. */
@Composable
private fun EmptyNeighboursState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.neighbours_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.neighbours_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * Supplies a stable "now" to [LocalRelativeClock] for previews, so every
 * [com.cerocoder.meshrelay.ui.common.AgeLabel] and the database's own load
 * time render a believable "n ago" instead of falling through to "Never"
 * against the composition local's default of `0L`.
 */
@Composable
private fun PreviewClock(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRelativeClock provides System.currentTimeMillis()) {
        content()
    }
}

@Preview(showBackground = true, name = "Populated")
@Composable
private fun NeighbourListScreenPopulatedPreview() {
    MeshRelayTheme {
        PreviewClock {
            NeighbourListScreen(
                snapshot = SampleData.snapshot,
                gaugeMode = GaugeMode.COMPLEX,
                meshviewUrl = "https://meshview.meshtastic.es",
                onOpenNeighbour = {},
                onSetSortMode = {},
                onSetGaugeMode = {},
                onTogglePause = {},
                onReset = {},
                onOpenSettings = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Empty")
@Composable
private fun NeighbourListScreenEmptyPreview() {
    MeshRelayTheme {
        PreviewClock {
            NeighbourListScreen(
                snapshot = SampleData.emptySnapshot,
                gaugeMode = GaugeMode.COMPLEX,
                meshviewUrl = null,
                onOpenNeighbour = {},
                onSetSortMode = {},
                onSetGaugeMode = {},
                onTogglePause = {},
                onReset = {},
                onOpenSettings = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Paused")
@Composable
private fun NeighbourListScreenPausedPreview() {
    MeshRelayTheme {
        PreviewClock {
            NeighbourListScreen(
                snapshot = SampleData.pausedSnapshot,
                gaugeMode = GaugeMode.SIMPLE,
                meshviewUrl = "https://meshview.meshtastic.es",
                onOpenNeighbour = {},
                onSetSortMode = {},
                onSetGaugeMode = {},
                onTogglePause = {},
                onReset = {},
                onOpenSettings = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Dark theme", uiMode = 0x20)
@Composable
private fun NeighbourListScreenDarkPreview() {
    MeshRelayTheme(darkTheme = true) {
        PreviewClock {
            NeighbourListScreen(
                snapshot = SampleData.snapshot,
                gaugeMode = GaugeMode.COMPLEX,
                meshviewUrl = "https://meshview.meshtastic.es",
                onOpenNeighbour = {},
                onSetSortMode = {},
                onSetGaugeMode = {},
                onTogglePause = {},
                onReset = {},
                onOpenSettings = {},
            )
        }
    }
}
