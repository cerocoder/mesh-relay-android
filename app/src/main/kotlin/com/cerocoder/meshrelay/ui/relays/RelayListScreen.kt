package com.cerocoder.meshrelay.ui.relays

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
import com.cerocoder.meshrelay.connection.ConnectionState
import com.cerocoder.meshrelay.settings.GaugeMode
import com.cerocoder.meshrelay.stats.SortMode
import com.cerocoder.meshrelay.stats.model.StatsSnapshot
import com.cerocoder.meshrelay.ui.common.LocalNodeLine
import com.cerocoder.meshrelay.ui.common.LocalRelativeClock
import com.cerocoder.meshrelay.ui.common.ReloadAction
import com.cerocoder.meshrelay.ui.common.SortAction
import com.cerocoder.meshrelay.ui.common.StatsTopBar
import com.cerocoder.meshrelay.ui.preview.SampleData
import com.cerocoder.meshrelay.ui.theme.MeshRelayTheme

/**
 * The relay list - the port of the terminal tool's main view, `render_header`
 * (mesh_stats.py:1661-1719) plus the relay table it introduces
 * (mesh_stats.py:1348-1522). Stateless by design: every value it shows is a
 * parameter, every action it can trigger is a lambda the caller supplies, and
 * it now holds no state of its own at all: the transient UI chrome it used to
 * own - the sort menu and the reset confirmation - moved into
 * [com.cerocoder.meshrelay.ui.common.StatsTopBar] along with the app bar itself.
 *
 * The app bar is shared with the neighbour list rather than written twice; see
 * [com.cerocoder.meshrelay.ui.common.StatsTopBar] for what is in it and why it
 * carries three actions rather than the six this screen used to show.
 */
@Composable
fun RelayListScreen(
    snapshot: StatsSnapshot,
    connection: ConnectionState,
    gaugeMode: GaugeMode,
    meshviewUrl: String?,
    nodeDbReloading: Boolean,
    onOpenRelay: (relayByte: Int) -> Unit,
    onSetSortMode: (SortMode) -> Unit,
    onSetGaugeMode: (GaugeMode) -> Unit,
    onTogglePause: () -> Unit,
    onReset: () -> Unit,
    onReloadNodeDb: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The reload flag is allowed to outlive a dropped connection by design (so a
    // reconnect can pick the same reload back up); gating on the flag alone
    // would spin the indicator over a dead link.
    val isReloading = nodeDbReloading && connection == ConnectionState.Connected

    Scaffold(
        modifier = modifier,
        topBar = {
            StatsTopBar(
                title = stringResource(R.string.relays_title),
                sort = SortAction(snapshot.sortMode, SortMode.entries, onSetSortMode),
                gaugeMode = gaugeMode,
                onSetGaugeMode = onSetGaugeMode,
                paused = snapshot.paused,
                onTogglePause = onTogglePause,
                onReset = onReset,
                onOpenSettings = onOpenSettings,
                reload = ReloadAction(inProgress = isReloading, onReload = onReloadNodeDb),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            StatusStrip(snapshot = snapshot)
            LocalNodeLine(directory = snapshot.directory, meshviewUrl = meshviewUrl)

            if (snapshot.relays.isEmpty()) {
                EmptyRelaysState(modifier = Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Keyed on relayByte, not list index, so re-sorting animates
                    // items into their new position instead of rebuilding every row.
                    items(items = snapshot.relays, key = { it.relayByte }) { relay ->
                        val matchingNodeNums = snapshot.directory.matchingNodeNums(relay.relayByte)
                        val matchCount = matchingNodeNums.size
                        val uniqueName = snapshot.directory.uniqueRelayName(relay.relayByte)
                        // A position is only ever attached to a single, unambiguous
                        // candidate - see RelayCard's own honesty rule about matchCount.
                        val location = if (matchCount == 1) {
                            snapshot.directory.locationInfo(
                                matchingNodeNums[0],
                                snapshot.directory.localPosition(),
                            )
                        } else {
                            null
                        }
                        RelayCard(
                            relay = relay,
                            matchCount = matchCount,
                            uniqueName = uniqueName,
                            location = location,
                            totalRelayed = snapshot.counters.totalRelayedPackets,
                            gaugeMode = gaugeMode,
                            onClick = { onOpenRelay(relay.relayByte) },
                        )
                    }
                }
            }
        }
    }
}

/** No relayed packet has ever arrived. Points at the Neighbours tab, where
 *  direct (unrelayed) traffic is shown instead. */
@Composable
private fun EmptyRelaysState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.relays_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.relays_empty_body),
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
private fun RelayListScreenPopulatedPreview() {
    MeshRelayTheme {
        PreviewClock {
            RelayListScreen(
                snapshot = SampleData.snapshot,
                connection = ConnectionState.Connected,
                gaugeMode = GaugeMode.COMPLEX,
                meshviewUrl = "https://meshview.meshtastic.es",
                nodeDbReloading = false,
                onOpenRelay = {},
                onSetSortMode = {},
                onSetGaugeMode = {},
                onTogglePause = {},
                onReset = {},
                onReloadNodeDb = {},
                onOpenSettings = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Empty")
@Composable
private fun RelayListScreenEmptyPreview() {
    MeshRelayTheme {
        PreviewClock {
            RelayListScreen(
                snapshot = SampleData.emptySnapshot,
                connection = ConnectionState.Connected,
                gaugeMode = GaugeMode.COMPLEX,
                meshviewUrl = null,
                nodeDbReloading = false,
                onOpenRelay = {},
                onSetSortMode = {},
                onSetGaugeMode = {},
                onTogglePause = {},
                onReset = {},
                onReloadNodeDb = {},
                onOpenSettings = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Paused")
@Composable
private fun RelayListScreenPausedPreview() {
    MeshRelayTheme {
        PreviewClock {
            RelayListScreen(
                snapshot = SampleData.pausedSnapshot,
                connection = ConnectionState.Connected,
                gaugeMode = GaugeMode.SIMPLE,
                meshviewUrl = "https://meshview.meshtastic.es",
                nodeDbReloading = false,
                onOpenRelay = {},
                onSetSortMode = {},
                onSetGaugeMode = {},
                onTogglePause = {},
                onReset = {},
                onReloadNodeDb = {},
                onOpenSettings = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Dark theme", uiMode = 0x20)
@Composable
private fun RelayListScreenDarkPreview() {
    MeshRelayTheme(darkTheme = true) {
        PreviewClock {
            RelayListScreen(
                snapshot = SampleData.snapshot,
                connection = ConnectionState.Connected,
                gaugeMode = GaugeMode.COMPLEX,
                meshviewUrl = "https://meshview.meshtastic.es",
                nodeDbReloading = false,
                onOpenRelay = {},
                onSetSortMode = {},
                onSetGaugeMode = {},
                onTogglePause = {},
                onReset = {},
                onReloadNodeDb = {},
                onOpenSettings = {},
            )
        }
    }
}

/** [nodeDbReloading] with a live connection: the reload action shows its spinner. */
@Preview(showBackground = true, name = "Reloading (connected)")
@Composable
private fun RelayListScreenReloadingPreview() {
    MeshRelayTheme {
        PreviewClock {
            RelayListScreen(
                snapshot = SampleData.snapshot,
                connection = ConnectionState.Connected,
                gaugeMode = GaugeMode.COMPLEX,
                meshviewUrl = "https://meshview.meshtastic.es",
                nodeDbReloading = true,
                onOpenRelay = {},
                onSetSortMode = {},
                onSetGaugeMode = {},
                onTogglePause = {},
                onReset = {},
                onReloadNodeDb = {},
                onOpenSettings = {},
            )
        }
    }
}

/**
 * [nodeDbReloading] left over from before the link dropped: the spinner must
 * NOT show, since there is no live reload in progress to report on - only the
 * label. This is the exact case the reload-spinner rule exists to cover.
 */
@Preview(showBackground = true, name = "Reload flag stale after disconnect")
@Composable
private fun RelayListScreenReloadFlagStaleWhileDisconnectedPreview() {
    MeshRelayTheme {
        PreviewClock {
            RelayListScreen(
                snapshot = SampleData.snapshot,
                connection = ConnectionState.Disconnected(retrying = true),
                gaugeMode = GaugeMode.COMPLEX,
                meshviewUrl = "https://meshview.meshtastic.es",
                nodeDbReloading = true,
                onOpenRelay = {},
                onSetSortMode = {},
                onSetGaugeMode = {},
                onTogglePause = {},
                onReset = {},
                onReloadNodeDb = {},
                onOpenSettings = {},
            )
        }
    }
}
