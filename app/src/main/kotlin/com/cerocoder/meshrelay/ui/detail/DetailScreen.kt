package com.cerocoder.meshrelay.ui.detail

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.settings.GaugeMode
import com.cerocoder.meshrelay.stats.Geo
import com.cerocoder.meshrelay.stats.NodeId
import com.cerocoder.meshrelay.stats.model.NeighbourStats
import com.cerocoder.meshrelay.stats.model.RelayStats
import com.cerocoder.meshrelay.stats.model.SignalStats
import com.cerocoder.meshrelay.stats.model.StatsSnapshot
import com.cerocoder.meshrelay.ui.common.LocalRelativeClock
import com.cerocoder.meshrelay.ui.nav.DetailSubject
import com.cerocoder.meshrelay.ui.preview.SampleData
import com.cerocoder.meshrelay.ui.theme.MeshRelayTheme

/**
 * One entry in the detail screen's overflow menu.
 *
 * A list rather than a fixed set of parameters, so a second command is one more
 * entry rather than a change to this screen's signature. An empty list draws no
 * button at all - a menu with nothing in it is worse than no menu.
 */
data class DetailMenuItem(@StringRes val labelRes: Int, val onClick: () -> Unit)

/**
 * One shell, two subjects. Ports the terminal tool's single relay-only detail
 * view (`build_detail_lines`, mesh_stats.py:1802-1943) but this is deliberately
 * the point the port stops being literal (spec §10.4): a [DetailSubject.Relay]
 * is a one-byte guess several database nodes can share, so its first tab lets
 * the user rule candidates out; a [DetailSubject.Neighbour] is a node heard
 * directly, its identity already known, so offering to "skip this node as a
 * candidate for itself" would be nonsense and a guessing UI would be a lie.
 * [header] below is where that difference is resolved, once, into the values
 * every other part of this screen only needs to render.
 *
 * **Tab content is a slot, not a call.** [matchingNodesTab] and [remoteNodesTab]
 * are supplied by the caller rather than built here from [MatchingNodesTab] and
 * [RemoteNodesTab] directly, because `MeshRelayNavHost` is the one place that
 * holds everything those two tabs need at once - the snapshot, the Meshview URL
 * and the container's skip commands - and closing over them there is cheaper than
 * threading four more parameters through this shell. Both default to an empty
 * composable, so this screen and its own previews render with the shell alone.
 * [onOpenRemoteNode], [onSkipNode], [onClearSkipped] and [meshviewUrl] are part of
 * this function's contract for the same reason and are deliberately not read in
 * this file's own body.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    subject: DetailSubject,
    snapshot: StatsSnapshot,
    gaugeMode: GaugeMode,
    meshviewUrl: String?,
    onBack: () -> Unit,
    onOpenRemoteNode: (nodeNum: Int) -> Unit,
    onSkipNode: (nodeNum: Int) -> Unit,
    onClearSkipped: () -> Unit,
    modifier: Modifier = Modifier,
    matchingNodesTab: @Composable () -> Unit = {},
    remoteNodesTab: @Composable () -> Unit = {},
    menuItems: List<DetailMenuItem> = emptyList(),
) {
    // Resets to the first tab whenever the subject itself changes (a fresh
    // navigation to a different relay or neighbour), so the previous screen's
    // tab choice never leaks into this one.
    var selectedTab by remember(subject) { mutableIntStateOf(0) }
    var menuExpanded by remember { mutableStateOf(false) }

    val header = resolveHeader(subject, snapshot)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                title = { DetailTitle(primary = header.titlePrimary, secondary = header.titleSecondary) },
                actions = {
                    if (menuItems.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_action_more),
                                    contentDescription = stringResource(R.string.action_more),
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                menuItems.forEach { item ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(item.labelRes)) },
                                        onClick = {
                                            // Dismissed first: the click navigates
                                            // away, and a menu left expanded is
                                            // still expanded on the way back.
                                            menuExpanded = false
                                            item.onClick()
                                        },
                                    )
                                }
                            }
                        }
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
            DetailSummary(
                subject = subject,
                totalPackets = header.totalPackets,
                packetsPerHour = header.packetsPerHour,
                skippedCount = header.skippedCount,
                hopsAway = header.hopsAway,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SignalBlock(
                snr = header.snr,
                rssi = header.rssi,
                gaugeMode = gaugeMode,
                lastPacketAtMillis = header.lastPacketAtMillis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )

            val tab1Title = stringResource(header.tab1TitleRes)
            val tab2Title = stringResource(R.string.detail_tab_remote_nodes)
            val tabTitles = if (header.showRemoteNodesTab) listOf(tab1Title, tab2Title) else listOf(tab1Title)
            // A defensive clamp, not a case this screen expects to hit in
            // practice: if the underlying data changed shape since the last
            // recomposition (e.g. a reset dropped the remote-nodes tab) while
            // it was selected, this keeps the index inside the now-shorter list
            // instead of indexing past it.
            val effectiveTab = selectedTab.coerceIn(0, tabTitles.lastIndex)

            TabRow(selectedTabIndex = effectiveTab) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = effectiveTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when (effectiveTab) {
                    0 -> matchingNodesTab()
                    1 -> remoteNodesTab()
                }
            }
        }
    }
}

/**
 * Everything the shell around [DetailScreen] needs, resolved once from
 * [subject] and [snapshot] rather than re-derived at each of the several
 * places (title, summary, signal block, tab set) that need a piece of it.
 */
private data class DetailHeader(
    val titlePrimary: String,
    val titleSecondary: String,
    val totalPackets: Int,
    val packetsPerHour: Float?,
    val skippedCount: Int,
    val hopsAway: Int?,
    val snr: SignalStats,
    val rssi: SignalStats,
    val lastPacketAtMillis: Long,
    val tab1TitleRes: Int,
    val showRemoteNodesTab: Boolean,
)

/**
 * Resolves [subject] against [snapshot]. A stale navigation target (the relay
 * or neighbour's data was cleared by a reset since the screen was opened)
 * falls back to an all-zero record rather than crashing - every field of
 * [RelayStats] and [NeighbourStats] defaults to a sensible empty value, so the
 * screen still renders, just with nothing to show yet.
 */
@Composable
private fun resolveHeader(subject: DetailSubject, snapshot: StatsSnapshot): DetailHeader = when (subject) {
    is DetailSubject.Relay -> {
        val relay = snapshot.relays.find { it.relayByte == subject.relayByte }
            ?: RelayStats(relayByte = subject.relayByte)
        val matchCount = snapshot.directory.matchingNodeNums(relay.relayByte).size
        val uniqueName = snapshot.directory.uniqueRelayName(relay.relayByte)
        // How many nodes matching this byte the user has explicitly ruled out -
        // StatsSnapshot.skippedRelayNodes is the flat, byte-agnostic skip set
        // every relay's candidate list is filtered through
        // (NodeDirectorySnapshot.matchingNodeNums applies the same set), so
        // this relay's own count is just that set narrowed to its byte.
        val skippedCount = snapshot.skippedRelayNodes.count { Geo.lastByteOfNodeNum(it) == relay.relayByte }
        DetailHeader(
            titlePrimary = stringResource(R.string.detail_relay_title, relay.hexId),
            // A name beside an ambiguous byte would present a guess as a fact -
            // the same honesty rule RelayCard's title line and its position
            // gate both already enforce internally rather than trusting a caller.
            titleSecondary = if (matchCount == 1) uniqueName else "",
            totalPackets = relay.packetCount,
            packetsPerHour = relay.packetsPerHour,
            skippedCount = skippedCount,
            hopsAway = null,
            snr = relay.snr,
            rssi = relay.rssi,
            lastPacketAtMillis = relay.lastPacketAtMillis,
            tab1TitleRes = R.string.detail_tab_matching_nodes,
            // A relay's own carried traffic is always a candidate second tab,
            // even when it turns out to be empty (RemoteNodesTab's own empty
            // state, Task 26, covers that) - unlike a neighbour's conditional
            // tab below, there is no ambiguity here to gate on.
            showRemoteNodesTab = true,
        )
    }
    is DetailSubject.Neighbour -> {
        val neighbour = snapshot.neighbours.find { it.nodeNum == subject.nodeNum }
            ?: NeighbourStats(nodeNum = subject.nodeNum)
        val neighbourByte = Geo.lastByteOfNodeNum(subject.nodeNum)
        DetailHeader(
            titlePrimary = stringResource(R.string.detail_neighbour_title, NodeId.format(subject.nodeNum)),
            titleSecondary = snapshot.directory.shortName(subject.nodeNum),
            totalPackets = neighbour.packetCount,
            // NeighbourStats carries no firstPacketAtMillis the way RelayStats
            // does, so there is no honest duration to divide its packetCount
            // by - see DetailSummary's own KDoc for why this stays null rather
            // than an approximation built from the signal histories' sample
            // timestamps (capped, and skipped for any packet with no decodable
            // signal), which would misrepresent a guess as the exact figure a
            // relay's rate is.
            packetsPerHour = null,
            skippedCount = 0,
            hopsAway = snapshot.directory.node(subject.nodeNum)?.hopsAway,
            snr = neighbour.snr,
            rssi = neighbour.rssi,
            lastPacketAtMillis = neighbour.lastPacketAtMillis,
            tab1TitleRes = R.string.detail_tab_node,
            // The one test this screen runs to decide whether a second tab
            // makes sense at all: does any relay currently being tracked
            // answer to the same byte this neighbour's own node number would
            // produce? Geo.lastByteOfNodeNum is the exact transformation the
            // firmware applies to a NodeNum to get the byte a relay identifies
            // itself by, so this asks "could this neighbour also be one of the
            // candidates behind some relay in the list right now" - the same
            // question RemoteNodesTab (Task 26) would need an actual RelayStats
            // for. When the byte is not currently in snapshot.relays there is
            // nothing behind it to show, and no relay to invent one from.
            showRemoteNodesTab = snapshot.relays.any { it.relayByte == neighbourByte },
        )
    }
}

/**
 * The app bar's title. [secondary] is a resolved name/short-name, already
 * decided by the caller to be safe to show - for a relay this is empty unless
 * exactly one candidate matched ([DetailHeader.titleSecondary]'s own
 * comment), and for a neighbour it is simply
 * [com.cerocoder.meshrelay.stats.model.NodeDirectorySnapshot.shortName], which
 * is `""`, never null, before a `User` message has been heard.
 */
@Composable
private fun DetailTitle(primary: String, secondary: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (secondary.isNotEmpty()) {
            Text(
                text = secondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Supplies a stable "now" to [LocalRelativeClock] for previews. Mirrors the
 * identical private helper every other screen carries -
 * [com.cerocoder.meshrelay.ui.relays.RelayListScreen]'s copy documents the
 * full reasoning.
 */
@Composable
private fun PreviewClock(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRelativeClock provides System.currentTimeMillis()) {
        content()
    }
}

/**
 * A neighbour fixture whose low byte matches none of [SampleData]'s relay
 * bytes, for the "tab 2 absent" preview below - [SampleData] itself has no
 * such case among its two ready-made neighbours (both, by construction, share
 * a byte with a relay fixture already: [SampleData.NUM_GETAFE_ROUTER] with
 * [SampleData.RELAY_ONE_MATCH_BYTE], [SampleData.NUM_ILLESCAS_MUDO] with
 * [SampleData.RELAY_SKIPPED_CANDIDATE_BYTE]) - and this task's file allowance
 * does not extend to editing `SampleData.kt` to add one. Reuses
 * [SampleData.NUM_YUNCOS_REINICIO] (low byte `0x63`, not among
 * [SampleData]'s seven relay bytes) rather than inventing a new node number,
 * so the directory entry, telemetry and short name it already carries stay
 * consistent with the rest of the fixture.
 */
private val previewNeighbourNoRelayByte = NeighbourStats(
    nodeNum = SampleData.NUM_YUNCOS_REINICIO,
    snr = SignalStats.EMPTY.plus(2.0f),
    rssi = SignalStats.EMPTY.plus(-91f),
    packetCount = 3,
    lastPacketAtMillis = System.currentTimeMillis() - 40_000L,
)

private val previewSnapshotNoRelayByteNeighbour = SampleData.snapshot.copy(
    neighbours = SampleData.snapshot.neighbours + previewNeighbourNoRelayByte,
)

@Preview(showBackground = true, name = "Relay - several candidates")
@Composable
private fun DetailScreenRelayThreeMatchPreview() {
    MeshRelayTheme {
        PreviewClock {
            DetailScreen(
                subject = DetailSubject.Relay(SampleData.RELAY_THREE_MATCH_BYTE),
                snapshot = SampleData.snapshot,
                gaugeMode = GaugeMode.COMPLEX,
                meshviewUrl = "https://meshview.meshtastic.es",
                onBack = {},
                onOpenRemoteNode = {},
                onSkipNode = {},
                onClearSkipped = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Relay - one candidate (named)")
@Composable
private fun DetailScreenRelayOneMatchPreview() {
    MeshRelayTheme {
        PreviewClock {
            DetailScreen(
                subject = DetailSubject.Relay(SampleData.RELAY_ONE_MATCH_BYTE),
                snapshot = SampleData.snapshot,
                gaugeMode = GaugeMode.COMPLEX,
                meshviewUrl = "https://meshview.meshtastic.es",
                onBack = {},
                onOpenRemoteNode = {},
                onSkipNode = {},
                onClearSkipped = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Relay - with the overflow menu")
@Composable
private fun DetailScreenWithMenuPreview() {
    MeshRelayTheme {
        PreviewClock {
            DetailScreen(
                subject = DetailSubject.Relay(SampleData.RELAY_ONE_MATCH_BYTE),
                snapshot = SampleData.snapshot,
                gaugeMode = GaugeMode.COMPLEX,
                meshviewUrl = "https://meshview.meshtastic.es",
                onBack = {},
                onOpenRemoteNode = {},
                onSkipNode = {},
                onClearSkipped = {},
                menuItems = listOf(DetailMenuItem(R.string.action_graph) {}),
            )
        }
    }
}

@Preview(showBackground = true, name = "Relay - skipped candidate")
@Composable
private fun DetailScreenRelaySkippedPreview() {
    MeshRelayTheme {
        PreviewClock {
            DetailScreen(
                subject = DetailSubject.Relay(SampleData.RELAY_SKIPPED_CANDIDATE_BYTE),
                snapshot = SampleData.snapshot,
                gaugeMode = GaugeMode.COMPLEX,
                meshviewUrl = "https://meshview.meshtastic.es",
                onBack = {},
                onOpenRemoteNode = {},
                onSkipNode = {},
                onClearSkipped = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Relay - no candidates")
@Composable
private fun DetailScreenRelayNoMatchPreview() {
    MeshRelayTheme {
        PreviewClock {
            DetailScreen(
                subject = DetailSubject.Relay(SampleData.RELAY_NO_MATCH_BYTE),
                snapshot = SampleData.snapshot,
                gaugeMode = GaugeMode.COMPLEX,
                meshviewUrl = "https://meshview.meshtastic.es",
                onBack = {},
                onOpenRemoteNode = {},
                onSkipNode = {},
                onClearSkipped = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Relay - no signal data")
@Composable
private fun DetailScreenRelayNoSignalPreview() {
    MeshRelayTheme {
        PreviewClock {
            DetailScreen(
                subject = DetailSubject.Relay(SampleData.RELAY_NO_SIGNAL_BYTE),
                snapshot = SampleData.snapshot,
                gaugeMode = GaugeMode.COMPLEX,
                meshviewUrl = null,
                onBack = {},
                onOpenRemoteNode = {},
                onSkipNode = {},
                onClearSkipped = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Neighbour - byte also a relay (tab 2 present)")
@Composable
private fun DetailScreenNeighbourWithRelayTabPreview() {
    MeshRelayTheme {
        PreviewClock {
            DetailScreen(
                subject = DetailSubject.Neighbour(SampleData.NUM_GETAFE_ROUTER),
                snapshot = SampleData.snapshot,
                gaugeMode = GaugeMode.COMPLEX,
                meshviewUrl = "https://meshview.meshtastic.es",
                onBack = {},
                onOpenRemoteNode = {},
                onSkipNode = {},
                onClearSkipped = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Neighbour - byte not a relay (tab 2 absent)")
@Composable
private fun DetailScreenNeighbourWithoutRelayTabPreview() {
    MeshRelayTheme {
        PreviewClock {
            DetailScreen(
                subject = DetailSubject.Neighbour(SampleData.NUM_YUNCOS_REINICIO),
                snapshot = previewSnapshotNoRelayByteNeighbour,
                gaugeMode = GaugeMode.COMPLEX,
                meshviewUrl = "https://meshview.meshtastic.es",
                onBack = {},
                onOpenRemoteNode = {},
                onSkipNode = {},
                onClearSkipped = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Dark theme", uiMode = 0x20)
@Composable
private fun DetailScreenDarkPreview() {
    MeshRelayTheme(darkTheme = true) {
        PreviewClock {
            DetailScreen(
                subject = DetailSubject.Neighbour(SampleData.NUM_GETAFE_ROUTER),
                snapshot = SampleData.snapshot,
                gaugeMode = GaugeMode.COMPLEX,
                meshviewUrl = "https://meshview.meshtastic.es",
                onBack = {},
                onOpenRemoteNode = {},
                onSkipNode = {},
                onClearSkipped = {},
            )
        }
    }
}
