package com.cerocoder.meshrelay.ui.relays

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.connection.ConnectionState
import com.cerocoder.meshrelay.settings.GaugeMode
import com.cerocoder.meshrelay.stats.SortMode
import com.cerocoder.meshrelay.stats.model.NodeDirectorySnapshot
import com.cerocoder.meshrelay.stats.model.StatsSnapshot
import com.cerocoder.meshrelay.ui.common.LocalRelativeClock
import com.cerocoder.meshrelay.ui.common.PositionLine
import com.cerocoder.meshrelay.ui.preview.SampleData
import com.cerocoder.meshrelay.ui.theme.MeshRelayTheme

/**
 * The relay list - the port of the terminal tool's main view, `render_header`
 * (mesh_stats.py:1661-1719) plus the relay table it introduces
 * (mesh_stats.py:1348-1522). Stateless by design: every value it shows is a
 * parameter, every action it can trigger is a lambda the caller supplies, and
 * it holds no state of its own beyond the two transient bits of local UI chrome
 * (whether the sort menu or the reset confirmation is open) that never need to
 * survive a recomposition elsewhere.
 *
 * Bundled icon set note: this build only depends on `androidx.compose.material3`,
 * whose actual Gradle module (verified against the exact `material3-android`
 * version this project's Compose BOM resolves to) does not pull in
 * `androidx.compose.material:material-icons-core` - so `Icons.Filled.*` is not
 * actually on the compile classpath despite `material3` using a handful of
 * icons internally under its own `internal` package. Rather than adding a new
 * dependency (forbidden by this task's brief) or guessing at an unverifiable
 * import, every app bar action here is a labelled [TextButton] instead of an
 * icon button. See this task's report for the full finding.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var resetDialogVisible by remember { mutableStateOf(false) }

    // The reload flag is allowed to outlive a dropped connection by design (so a
    // reconnect can pick the same reload back up); gating on the flag alone
    // would spin the indicator over a dead link.
    val isReloading = nodeDbReloading && connection == ConnectionState.Connected

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.relays_title)) },
                actions = {
                    Box {
                        TextButton(onClick = { sortMenuExpanded = true }) {
                            Text(stringResource(R.string.action_sort))
                        }
                        DropdownMenu(
                            expanded = sortMenuExpanded,
                            onDismissRequest = { sortMenuExpanded = false },
                        ) {
                            SortMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = stringResource(SortModeLabels.labelOf(mode)),
                                            fontWeight = if (mode == snapshot.sortMode) {
                                                FontWeight.Bold
                                            } else {
                                                FontWeight.Normal
                                            },
                                        )
                                    },
                                    onClick = {
                                        sortMenuExpanded = false
                                        onSetSortMode(mode)
                                    },
                                )
                            }
                        }
                    }

                    TextButton(
                        onClick = {
                            val next = if (gaugeMode == GaugeMode.SIMPLE) GaugeMode.COMPLEX else GaugeMode.SIMPLE
                            onSetGaugeMode(next)
                        },
                    ) {
                        Text(
                            stringResource(
                                if (gaugeMode == GaugeMode.SIMPLE) R.string.gauge_simple else R.string.gauge_complex,
                            ),
                        )
                    }

                    TextButton(onClick = onTogglePause) {
                        Text(stringResource(if (snapshot.paused) R.string.action_resume else R.string.action_pause))
                    }

                    TextButton(onClick = { resetDialogVisible = true }) {
                        Text(stringResource(R.string.action_reset))
                    }

                    TextButton(onClick = onReloadNodeDb, enabled = !isReloading) {
                        if (isReloading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.action_reload_db))
                        }
                    }

                    TextButton(onClick = onOpenSettings) {
                        Text(stringResource(R.string.action_settings))
                    }
                },
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

    if (resetDialogVisible) {
        AlertDialog(
            onDismissRequest = { resetDialogVisible = false },
            title = { Text(stringResource(R.string.action_reset_confirm_title)) },
            text = { Text(stringResource(R.string.action_reset_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        resetDialogVisible = false
                        onReset()
                    },
                ) {
                    Text(stringResource(R.string.action_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { resetDialogVisible = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/**
 * This device's own line, above the relay list: short name plus its position
 * with no distance figure (there is no "self" to measure a distance from), or
 * [R.string.relays_local_node_unknown] before the node database has told this
 * app its own node number at all. Ports `render_my_info`, mesh_stats.py:1319-1346.
 */
@Composable
private fun LocalNodeLine(
    directory: NodeDirectorySnapshot,
    meshviewUrl: String?,
    modifier: Modifier = Modifier,
) {
    val localNodeNum = directory.localNodeNum
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        if (localNodeNum == null) {
            Text(
                text = stringResource(R.string.relays_local_node_unknown),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Text(
                text = stringResource(R.string.relays_local_node),
                style = MaterialTheme.typography.labelMedium,
            )
            // The node database can know this node's number without ever having
            // heard its own User message - shortName is "" then, not null, and
            // isNotEmpty() (rather than a null check) is what hides it correctly.
            val shortName = directory.shortName(localNodeNum)
            if (shortName.isNotEmpty()) {
                Text(text = shortName, style = MaterialTheme.typography.bodyMedium)
            }
            PositionLine(
                info = directory.locationInfo(localNodeNum, from = null),
                nodeNum = localNodeNum,
                meshviewUrl = meshviewUrl,
            )
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
