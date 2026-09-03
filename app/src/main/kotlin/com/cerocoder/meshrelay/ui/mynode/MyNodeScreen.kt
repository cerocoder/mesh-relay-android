package com.cerocoder.meshrelay.ui.mynode

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.settings.GaugeMode
import com.cerocoder.meshrelay.stats.model.NodeDirectorySnapshot
import com.cerocoder.meshrelay.stats.model.StatsSnapshot
import com.cerocoder.meshrelay.ui.common.LocalRelativeClock
import com.cerocoder.meshrelay.ui.common.StatsTopBar
import com.cerocoder.meshrelay.ui.common.StatusCount
import com.cerocoder.meshrelay.ui.common.StatusStrip
import com.cerocoder.meshrelay.ui.detail.NodeCard
import com.cerocoder.meshrelay.ui.preview.SampleData
import com.cerocoder.meshrelay.ui.theme.MeshRelayTheme

/**
 * Everything about the device this app is connected to, on its own screen.
 *
 * Ports `render_my_info`, mesh_stats.py:1319-1346, which the terminal tool drew
 * into the header of whichever view was open. On a phone that block cost a line
 * of the screen with the least vertical room in the app (field issues F-3 and
 * F-4 were both about it), so it moves here and the two lists become lists.
 *
 * It draws the same [NodeCard] the detail screens draw, rather than a card of its
 * own: long name, short name, role, hardware, position, altitude, Src, the map
 * links, the database's last-heard, uptime, restarts, telemetry and public key
 * are all already in it. [NodeCard.index] and its `onSkip` are null - there is
 * nothing here to number and nothing to rule out as a relay candidate, which is
 * exactly the second caller that card was written for.
 *
 * Two of its fields read a little oddly for one's own node ("Last heard" is the
 * database's record of itself). They are left as they are rather than forked into
 * a near-copy of the card; if they read badly on hardware that is a field issue,
 * not a reason to duplicate two hundred lines.
 *
 * The strip carries all three counters, because this screen is not about a list
 * and has no reason to show one screen's half of them.
 */
@Composable
fun MyNodeScreen(
    snapshot: StatsSnapshot,
    meshviewUrl: String?,
    gaugeMode: GaugeMode,
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
                title = stringResource(R.string.nav_my_node),
                // Nothing on this screen is a list, so there is no order to choose.
                sort = null,
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
                .fillMaxSize()
                // verticalScroll rather than a LazyColumn: this is one card, and
                // the card is taller than the screen once telemetry arrives.
                .verticalScroll(rememberScrollState()),
        ) {
            StatusStrip(
                snapshot = snapshot,
                counts = listOf(
                    StatusCount(R.string.relays_status_total, snapshot.counters.totalPackets),
                    StatusCount(R.string.relays_status_relayed, snapshot.counters.totalRelayedPackets),
                    StatusCount(R.string.neighbours_status_direct, snapshot.counters.totalDirectPackets),
                ),
                sortMode = null,
            )

            val localNodeNum = snapshot.directory.localNodeNum
            val record = localNodeNum?.let { snapshot.directory.node(it) }
            when {
                // Before the handshake has said which node this is.
                localNodeNum == null -> Message(R.string.relays_local_node_unknown)

                // Real and reachable, not a defensive branch: the directory learns
                // this node's *number* from the my_node_info handshake, and its
                // NodeInfo arrives separately. It is the state that made shortName
                // return "" in F-4.
                record == null -> Message(R.string.my_node_no_info)

                else -> NodeCard(
                    index = null,
                    record = record,
                    // from = null: there is no "self" to measure a distance from,
                    // so PositionLine draws coordinates, altitude and Src with no
                    // distance figure.
                    location = snapshot.directory.locationInfo(localNodeNum, from = null),
                    telemetry = snapshot.directory.telemetry(localNodeNum),
                    meshviewUrl = meshviewUrl,
                    onSkip = null,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}

/** One explanatory line, for the two states that have no card to draw. */
@Composable
private fun Message(@StringRes text: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * Supplies a stable "now" to [LocalRelativeClock] for previews, so the database's
 * load time and every age on the card render a believable "n ago" instead of
 * falling through to "Never" against the composition local's default of `0L`.
 */
@Composable
private fun PreviewClock(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRelativeClock provides System.currentTimeMillis()) {
        content()
    }
}

@Preview(showBackground = true, name = "Populated")
@Composable
private fun MyNodeScreenPopulatedPreview() {
    MeshRelayTheme {
        PreviewClock {
            MyNodeScreen(
                snapshot = SampleData.snapshot,
                meshviewUrl = "https://meshview.meshtastic.es",
                gaugeMode = GaugeMode.COMPLEX,
                onSetGaugeMode = {},
                onTogglePause = {},
                onReset = {},
                onOpenSettings = {},
                onExit = {},
            )
        }
    }
}

/** The handshake has named this node's number; its NodeInfo has not arrived. */
@Preview(showBackground = true, name = "No node info")
@Composable
private fun MyNodeScreenNoInfoPreview() {
    val directory = SampleData.snapshot.directory
    MeshRelayTheme {
        PreviewClock {
            MyNodeScreen(
                snapshot = SampleData.snapshot.copy(
                    directory = NodeDirectorySnapshot(
                        nodes = emptyMap(),
                        airNodes = emptyMap(),
                        loadedAtMillis = directory.loadedAtMillis,
                        localNodeNum = directory.localNodeNum,
                        positions = emptyMap(),
                        telemetry = emptyMap(),
                        skipped = emptySet(),
                    ),
                ),
                meshviewUrl = "https://meshview.meshtastic.es",
                gaugeMode = GaugeMode.COMPLEX,
                onSetGaugeMode = {},
                onTogglePause = {},
                onReset = {},
                onOpenSettings = {},
                onExit = {},
            )
        }
    }
}

/** Connected, but the handshake has not said which node this is. */
@Preview(showBackground = true, name = "No local node")
@Composable
private fun MyNodeScreenNoLocalNodePreview() {
    MeshRelayTheme {
        PreviewClock {
            MyNodeScreen(
                snapshot = SampleData.emptySnapshot,
                meshviewUrl = null,
                gaugeMode = GaugeMode.COMPLEX,
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
private fun MyNodeScreenDarkPreview() {
    MeshRelayTheme(darkTheme = true) {
        PreviewClock {
            MyNodeScreen(
                snapshot = SampleData.snapshot,
                meshviewUrl = "https://meshview.meshtastic.es",
                gaugeMode = GaugeMode.COMPLEX,
                onSetGaugeMode = {},
                onTogglePause = {},
                onReset = {},
                onOpenSettings = {},
                onExit = {},
            )
        }
    }
}
