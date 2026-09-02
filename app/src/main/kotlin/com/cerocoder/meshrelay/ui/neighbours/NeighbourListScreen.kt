package com.cerocoder.meshrelay.ui.neighbours

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.cerocoder.meshrelay.stats.SortMode
import com.cerocoder.meshrelay.stats.model.StatsSnapshot
import com.cerocoder.meshrelay.ui.common.LocalRelativeClock
import com.cerocoder.meshrelay.ui.common.SortAction
import com.cerocoder.meshrelay.ui.common.StatsTopBar
import com.cerocoder.meshrelay.ui.common.StatusCount
import com.cerocoder.meshrelay.ui.common.StatusStrip
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
    onOpenNeighbour: (nodeNum: Int) -> Unit,
    onSetSortMode: (SortMode) -> Unit,
    onSetGaugeMode: (GaugeMode) -> Unit,
    onTogglePause: () -> Unit,
    onReset: () -> Unit,
    onOpenSettings: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            StatsTopBar(
                title = stringResource(R.string.neighbours_title),
                sort = SortAction(
                    // The mode after forNeighbours(), so the tick in the menu names
                    // the order this list actually applied.
                    mode = snapshot.sortMode.forNeighbours(),
                    available = SortMode.entries - SortMode.KNOWN_NODES,
                    onSet = onSetSortMode,
                ),
                gaugeMode = gaugeMode,
                onSetGaugeMode = onSetGaugeMode,
                paused = snapshot.paused,
                onTogglePause = onTogglePause,
                onReset = onReset,
                onOpenSettings = onOpenSettings,
                onExit = onExit,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            StatusStrip(
                snapshot = snapshot,
                counts = listOf(
                    StatusCount(R.string.neighbours_status_direct, snapshot.counters.totalDirectPackets),
                ),
                // The mode the list actually applied, not the one that was asked
                // for: KNOWN_NODES can reach this screen and degrades to PACKETS.
                sortMode = snapshot.sortMode.forNeighbours(),
            )

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
                onOpenNeighbour = {},
                onSetSortMode = {},
                onSetGaugeMode = {},
                onTogglePause = {},
                onReset = {},
                onOpenSettings = {},
                onExit = {},
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
                onOpenNeighbour = {},
                onSetSortMode = {},
                onSetGaugeMode = {},
                onTogglePause = {},
                onReset = {},
                onOpenSettings = {},
                onExit = {},
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
                onOpenNeighbour = {},
                onSetSortMode = {},
                onSetGaugeMode = {},
                onTogglePause = {},
                onReset = {},
                onOpenSettings = {},
                onExit = {},
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
                onOpenNeighbour = {},
                onSetSortMode = {},
                onSetGaugeMode = {},
                onTogglePause = {},
                onReset = {},
                onOpenSettings = {},
                onExit = {},
            )
        }
    }
}
