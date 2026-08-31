package com.cerocoder.meshrelay.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.stats.RelayIndex
import com.cerocoder.meshrelay.stats.model.NodeRecord
import com.cerocoder.meshrelay.stats.model.RelayStats
import com.cerocoder.meshrelay.stats.model.RemoteNodeStats
import com.cerocoder.meshrelay.stats.model.StatsSnapshot
import com.cerocoder.meshrelay.ui.common.LocalRelativeClock
import com.cerocoder.meshrelay.ui.common.NodeIdText
import com.cerocoder.meshrelay.ui.preview.SampleData
import com.cerocoder.meshrelay.ui.theme.MeshRelayTheme

/**
 * One remote node, every relay that carries its traffic. This screen has no
 * counterpart in mesh_stats: the terminal tool's own detail view
 * (`build_detail_lines`, mesh_stats.py:1802-1943) is reached *from* a relay and
 * can only ever show one at a time, so a node relayed by three different
 * relays would take three separate visits to see - and nothing to compare them
 * with once there. [RelayIndex.relaysCarrying] inverts the relay table to
 * answer exactly that, and this screen is its only caller.
 *
 * [NodeCard] here gets `index = null` and `onSkip = null` - this node is not
 * a guess behind an ambiguous byte the way [MatchingNodesTab]'s cards are, it
 * is the subject the whole screen is about, on the same terms
 * [MatchingNodesTab]'s KDoc already describes for a neighbour's identity tab.
 *
 * [viaRelayByte] is the relay the caller navigated from - typically a row in
 * [RemoteNodesTab], reached by tapping a remote node listed there - and is
 * marked with [R.string.remote_via_relay_current] among the rows below so the
 * user does not lose track of where they came from. It is nullable because a
 * node can also be opened from a context with no such relay at all (a
 * neighbour heard directly), and `null` simply marks nothing.
 *
 * The relay rows repeat this file's honesty rule from [RemoteNodesTab] and
 * [NodeCard]: a relay byte is one byte, so
 * [com.cerocoder.meshrelay.stats.model.NodeDirectorySnapshot.uniqueRelayName]
 * is trusted as-is and a row's name is shown only when it is non-empty -
 * never worked around, even for the row this node itself arrived through.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteNodeScreen(
    nodeNum: Int,
    viaRelayByte: Int?,
    snapshot: StatsSnapshot,
    meshviewUrl: String?,
    onBack: () -> Unit,
    onOpenRelay: (relayByte: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val directory = snapshot.directory
    // A stale navigation target (the node left the directory since this
    // screen was opened) falls back to an all-absent record rather than
    // crashing - the same defensive shape DetailScreen's own resolveHeader
    // applies to a stale relay/neighbour subject.
    val record = directory.node(nodeNum) ?: NodeRecord(
        num = nodeNum,
        longName = null,
        shortName = null,
        hwModel = null,
        role = null,
        dbPosition = null,
        dbSnr = null,
        lastHeardEpochSeconds = null,
        hopsAway = null,
        hasPublicKey = false,
    )
    val shortName = directory.shortName(nodeNum)
    val localPosition = directory.localPosition()
    val location = directory.locationInfo(nodeNum, localPosition)
    val telemetry = directory.telemetry(nodeNum)
    val relaysCarrying = RelayIndex.relaysCarrying(nodeNum, snapshot.relays)

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
                title = { RemoteNodeTitle(nodeNum = nodeNum, shortName = shortName) },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "node-card") {
                NodeCard(
                    index = null,
                    record = record,
                    location = location,
                    telemetry = telemetry,
                    meshviewUrl = meshviewUrl,
                    onSkip = null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "relays-heading") {
                Text(
                    text = stringResource(R.string.remote_via_relays),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                )
            }
            if (relaysCarrying.isEmpty()) {
                // This node is in the directory but no relay currently tracked
                // has forwarded a packet on its behalf - either it was heard
                // directly, or the relay that carried it was never identified.
                // A bare heading with nothing under it would read as broken;
                // this says so explicitly instead.
                item(key = "relays-empty") {
                    Text(
                        text = stringResource(R.string.common_none),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            } else {
                // Keyed on the relay byte, not list position, so the arrival
                // relay's own count updating live does not rebuild every row -
                // same reasoning as this screen's sibling tabs' own keyed items.
                items(items = relaysCarrying, key = { it.relayByte }) { relay ->
                    RelayCarryingRow(
                        relay = relay,
                        uniqueName = directory.uniqueRelayName(relay.relayByte),
                        // relaysCarrying's own contract guarantees this key is
                        // present: it only returns relays whose fromNodeStats
                        // contains nodeNum.
                        packetCount = relay.fromNodeStats.getValue(nodeNum).packetCount,
                        isCurrent = viaRelayByte != null && relay.relayByte == viaRelayByte,
                        onClick = { onOpenRelay(relay.relayByte) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * The app bar's title: the node's own id, plus its short name when the
 * directory has one - the same primary/secondary shape
 * [com.cerocoder.meshrelay.ui.detail.DetailScreen]'s own title uses, rebuilt
 * here rather than reused because that one is private to its file. Needs no
 * [R.string] of its own: [NodeIdText] is the same bare technical notation
 * [NodeCard]'s header already renders with no wrapping label, and a
 * directory short name that is `""` (never heard) is simply omitted, on the
 * same [String.isNotEmpty] terms every other card in this screen's family
 * already applies.
 */
@Composable
private fun RemoteNodeTitle(nodeNum: Int, shortName: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        NodeIdText(nodeNum = nodeNum)
        if (shortName.isNotEmpty()) {
            Text(
                text = shortName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One relay carrying this node's traffic: its byte, its unique name when it
 * has one, whether it is the relay the user arrived through, and how many of
 * *this node's* packets ([packetCount], resolved by the caller from
 * [RelayStats.fromNodeStats] - never this relay's own overall
 * [RelayStats.packetCount], a different and larger figure) it has carried.
 * Tappable, like every other row in this screen's family - calls [onClick]
 * ([onOpenRelay][RemoteNodeScreen] closed over this row's own relay byte).
 */
@Composable
private fun RelayCarryingRow(
    relay: RelayStats,
    uniqueName: String,
    packetCount: Int,
    isCurrent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(onClick = onClick, modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = relay.hexId, style = MaterialTheme.typography.bodyMedium)
                // Names can be "", not null - isNotEmpty() rather than an ?:
                // fallback, and uniqueRelayName already returns "" whenever
                // the byte is ambiguous, so this never needs a second match-
                // count check the way RelayCard's caller-supplied uniqueName
                // does - the honesty rule this screen's own KDoc points to.
                if (uniqueName.isNotEmpty()) {
                    Text(
                        text = uniqueName,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (isCurrent) {
                    Text(
                        text = stringResource(R.string.remote_via_relay_current),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(
                text = pluralStringResource(R.plurals.plural_packets, packetCount, packetCount),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/**
 * Supplies a stable "now" to [LocalRelativeClock] for previews - without it,
 * [NodeCard]'s own [com.cerocoder.meshrelay.ui.common.PositionLine] computes
 * its source-aged text against `0L` instead of a real clock. Mirrors the
 * identical private helper every other screen-level preview in this app
 * already carries - see
 * [com.cerocoder.meshrelay.ui.relays.RelayListScreen]'s copy for the full
 * reasoning.
 */
@Composable
private fun PreviewClock(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRelativeClock provides System.currentTimeMillis()) {
        content()
    }
}

/**
 * [SampleData] has no ready-made case of one node carried by more than one
 * relay - every fixture relay's `fromNodeStats` was built for its own
 * screen's needs, not this one - and this task's file allowance does not
 * extend to editing `SampleData.kt` to add one. This rebuilds
 * [SampleData.RELAY_THREE_MATCH_BYTE]'s own fixture with
 * [SampleData.NUM_TOLEDO_ALTA] (already carried by
 * [SampleData.RELAY_ONE_MATCH_BYTE] with `packetCount == 2`, from that
 * fixture's own two `.plus(...)` calls) added at `packetCount = 5` - more
 * than the other relay's count, so the "most packets first" order this
 * screen promises puts the ambiguous-byte relay above the named one.
 */
private val previewRelayAlsoCarryingToledoAlta: RelayStats =
    SampleData.relay(SampleData.RELAY_THREE_MATCH_BYTE).let { relay ->
        relay.copy(
            fromNodeStats = relay.fromNodeStats + (SampleData.NUM_TOLEDO_ALTA to RemoteNodeStats(packetCount = 5)),
        )
    }

private val previewSeveralRelaysSnapshot: StatsSnapshot = SampleData.snapshot.copy(
    relays = SampleData.snapshot.relays.map { relay ->
        if (relay.relayByte == SampleData.RELAY_THREE_MATCH_BYTE) previewRelayAlsoCarryingToledoAlta else relay
    },
)

@Preview(showBackground = true, name = "Carried by several relays, arrival relay marked")
@Composable
private fun RemoteNodeScreenSeveralRelaysPreview() {
    MeshRelayTheme {
        PreviewClock {
            RemoteNodeScreen(
                nodeNum = SampleData.NUM_TOLEDO_ALTA,
                viaRelayByte = SampleData.RELAY_ONE_MATCH_BYTE,
                snapshot = previewSeveralRelaysSnapshot,
                meshviewUrl = "https://meshview.meshtastic.es",
                onBack = {},
                onOpenRelay = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Carried by exactly one relay")
@Composable
private fun RemoteNodeScreenSingleRelayPreview() {
    MeshRelayTheme {
        PreviewClock {
            RemoteNodeScreen(
                nodeNum = SampleData.NUM_SIERRA_LARGA,
                viaRelayByte = SampleData.RELAY_ONE_MATCH_BYTE,
                snapshot = SampleData.snapshot,
                meshviewUrl = "https://meshview.meshtastic.es",
                onBack = {},
                onOpenRelay = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Carried by no relay - heard directly")
@Composable
private fun RemoteNodeScreenNoRelayPreview() {
    MeshRelayTheme {
        PreviewClock {
            RemoteNodeScreen(
                nodeNum = SampleData.NUM_PINTO_SINDATOS,
                viaRelayByte = null,
                snapshot = SampleData.snapshot,
                meshviewUrl = "https://meshview.meshtastic.es",
                onBack = {},
                onOpenRelay = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Arrival via an ambiguous relay byte - no name shown")
@Composable
private fun RemoteNodeScreenAmbiguousArrivalPreview() {
    MeshRelayTheme {
        PreviewClock {
            RemoteNodeScreen(
                nodeNum = SampleData.NUM_YUNCOS_REINICIO,
                viaRelayByte = SampleData.RELAY_THREE_MATCH_BYTE,
                snapshot = SampleData.snapshot,
                meshviewUrl = "https://meshview.meshtastic.es",
                onBack = {},
                onOpenRelay = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Dark theme", uiMode = 0x20)
@Composable
private fun RemoteNodeScreenDarkPreview() {
    MeshRelayTheme(darkTheme = true) {
        PreviewClock {
            RemoteNodeScreen(
                nodeNum = SampleData.NUM_TOLEDO_ALTA,
                viaRelayByte = SampleData.RELAY_ONE_MATCH_BYTE,
                snapshot = previewSeveralRelaysSnapshot,
                meshviewUrl = "https://meshview.meshtastic.es",
                onBack = {},
                onOpenRelay = {},
            )
        }
    }
}
