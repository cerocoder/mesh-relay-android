package com.cerocoder.meshrelay.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.stats.model.LocationInfo
import com.cerocoder.meshrelay.stats.model.RelayStats
import com.cerocoder.meshrelay.stats.model.RemoteNodeStats
import com.cerocoder.meshrelay.stats.model.StatsSnapshot
import com.cerocoder.meshrelay.ui.common.LocalRelativeClock
import com.cerocoder.meshrelay.ui.common.NodeIdText
import com.cerocoder.meshrelay.ui.common.PositionLine
import com.cerocoder.meshrelay.ui.common.StatsFormat
import com.cerocoder.meshrelay.ui.preview.SampleData
import com.cerocoder.meshrelay.ui.theme.MeshRelayTheme
import java.util.Locale

/**
 * The relay detail screen's second tab, answering the question its sibling
 * ([MatchingNodesTab]) cannot: not which candidate this relay byte *is*, but
 * whose traffic it *carries*. Ports the "Known Remote Nodes" table,
 * mesh_stats.py:1908-1943.
 *
 * [remote_direction_hint][R.string.remote_direction_hint] above the list is
 * not decoration - it is this tab's reason to exist. A relay often hears
 * from one direction only, and the spread of directions among the rows
 * below (each one's own [PositionLine]) is what tells a user where that
 * relay is listening, information the antenna-placement decision behind
 * this whole port depends on.
 *
 * **The honesty rule, twice over here:**
 * - A row in this list was heard *via* [relay], not *from* it directly -
 *   this tab never claims otherwise, the same distinction
 *   [DetailScreen]'s own header keeps for [relay] itself.
 * - [RemoteNodeStats.avgHopsMade]/`.avgHopsLeft` are `null`, never `0f`,
 *   before any packet counted for that node carried hop information -
 *   folding that into a fabricated `0.0` would read as "this node is
 *   adjacent", the opposite of "unknown". [R.string.common_not_available]
 *   is rendered instead, exactly as [DetailSummary] already does for a
 *   neighbour's absent hop count.
 *
 * No `meshviewUrl` parameter: unlike [MatchingNodesTab] and [NodeCard],
 * this tab's own brief specifies a signature without one, so every
 * [PositionLine] below opens with `meshviewUrl = null` - a remote node here
 * still gets its Google Maps / OpenStreetMap buttons whenever it has
 * coordinates, just not the Meshview one.
 */
@Composable
fun RemoteNodesTab(
    relay: RelayStats,
    snapshot: StatsSnapshot,
    onOpenRemoteNode: (nodeNum: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = displayLocale()
    val notAvailable = stringResource(R.string.common_not_available)
    val directory = snapshot.directory
    val localPosition = directory.localPosition()

    // Ports the descending packet-count sort mesh_stats.py:1926-1930 applies
    // before rendering. Kotlin's sortedByDescending, like Python's sorted,
    // is a stable sort - two nodes tied on packet count keep whatever
    // relative order relay.fromNodeStats itself iterates them in, rather
    // than being reordered arbitrarily on every recomposition.
    val rows = relay.fromNodeStats.entries.sortedByDescending { it.value.packetCount }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.remote_direction_hint),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        if (rows.isEmpty()) {
            EmptyRemoteNodesState(modifier = Modifier.weight(1f))
        } else {
            RemoteNodesHeaderRow(
                // 8.dp (the LazyColumn's own contentPadding below) + 12.dp
                // (each RemoteNodeRow card's internal padding) = 20.dp, so
                // this header's columns line up with the card content under
                // them rather than with the cards' own outer edges.
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
            )
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Keyed on the node number, not list position, so a live
                // update that changes the packet-count order animates rows
                // into place instead of rebuilding every one - same
                // reasoning as MatchingNodesTab's own itemsIndexed.
                items(items = rows, key = { it.key }) { (nodeNum, stats) ->
                    RemoteNodeRow(
                        nodeNum = nodeNum,
                        shortName = directory.shortName(nodeNum),
                        stats = stats,
                        location = directory.locationInfo(nodeNum, localPosition),
                        notAvailable = notAvailable,
                        locale = locale,
                        onClick = { onOpenRemoteNode(nodeNum) },
                    )
                }
            }
        }
    }
}

/** Column widths shared between [RemoteNodesHeaderRow] and every
 *  [RemoteNodeRow] so the two stay aligned. */
private val PACKETS_COLUMN_WIDTH = 56.dp
private val HOP_COLUMN_WIDTH = 48.dp

/**
 * The four column headers this table ports from mesh_stats.py:1922's fixed-
 * width header line. The fifth original column, "Position and GEO-info", has
 * no [R.string] of its own here - it is not needed, since [PositionLine]
 * below renders as its own self-explanatory sentence rather than a value
 * under a heading, the same choice [NodeCard] already makes for the
 * identical text.
 */
@Composable
private fun RemoteNodesHeaderRow(modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.remote_column_node),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.remote_column_packets),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(PACKETS_COLUMN_WIDTH),
            textAlign = TextAlign.End,
        )
        Text(
            text = stringResource(R.string.remote_column_hops),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(HOP_COLUMN_WIDTH),
            textAlign = TextAlign.End,
        )
        Text(
            text = stringResource(R.string.remote_column_left),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(HOP_COLUMN_WIDTH),
            textAlign = TextAlign.End,
        )
    }
}

/**
 * One row of the table: a remote node this relay has forwarded traffic for.
 * A tappable [Card] rather than a fixed-width terminal line, on the same
 * terms [com.cerocoder.meshrelay.ui.relays.RelayCard] already turns its own
 * ported row into one - tapping
 * calls [onClick] ([onOpenRemoteNode][RemoteNodesTab] closed over the row's
 * own [nodeNum]).
 *
 * [notAvailable] and [locale] arrive pre-resolved from the caller rather
 * than read again per row, the same shape [MatchingNodesTab] already passes
 * its own once-resolved values down to each [NodeCard].
 */
@Composable
private fun RemoteNodeRow(
    nodeNum: Int,
    shortName: String,
    stats: RemoteNodeStats,
    location: LocationInfo,
    notAvailable: String,
    locale: Locale,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hopsMadeText = stats.avgHopsMade?.let { StatsFormat.remoteNodeHopAverage(it, locale) } ?: notAvailable
    val hopsLeftText = stats.avgHopsLeft?.let { StatsFormat.remoteNodeHopAverage(it, locale) } ?: notAvailable

    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    NodeIdText(nodeNum = nodeNum)
                    // Names can be "", not null - isNotEmpty() rather than
                    // an ?: fallback, same guard NodeCard's own header row
                    // and RelayCard's uniqueName both apply.
                    if (shortName.isNotEmpty()) {
                        Text(
                            text = shortName,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = stats.packetCount.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(PACKETS_COLUMN_WIDTH),
                    textAlign = TextAlign.End,
                )
                Text(
                    text = hopsMadeText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(HOP_COLUMN_WIDTH),
                    textAlign = TextAlign.End,
                )
                Text(
                    text = hopsLeftText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.width(HOP_COLUMN_WIDTH),
                    textAlign = TextAlign.End,
                )
            }
            // No meshviewUrl to hand in - see this file's own top-level
            // KDoc for why - so a remote node's own position line offers
            // only the map-app buttons it has coordinates for.
            PositionLine(info = location, nodeNum = nodeNum, meshviewUrl = null)
        }
    }
}

/** This relay has forwarded no traffic on behalf of any other node yet -
 *  either because it never has, or because [RelayStats.fromNodeStats] simply
 *  has not been populated for it. Mirrors [MatchingNodesTab]'s own empty
 *  state in spirit, but with a single line: unlike that tab's two-string
 *  title+body, this tab's brief supplies exactly one string,
 *  [R.string.remote_empty], for this case. */
@Composable
private fun EmptyRemoteNodesState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.remote_empty),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
    }
}

/** The configured display locale. A second, independent copy of the
 *  identical private helper every other card in this app already carries -
 *  see [com.cerocoder.meshrelay.ui.relays.RelayCard]'s copy for the full
 *  reasoning. */
@Composable
private fun displayLocale(): Locale {
    val locales = LocalConfiguration.current.locales
    return if (locales.isEmpty()) Locale.getDefault() else locales.get(0)
}

/**
 * Supplies a stable "now" to [LocalRelativeClock] for previews - without it,
 * every [PositionLine] below computes its source-aged text against `0L`
 * instead of a real clock. Mirrors the identical private helper every other
 * screen-level preview in this app already carries - see
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
 * [SampleData]'s [SampleData.RELAY_THREE_MATCH_BYTE] fixture only carries one
 * remote node ([SampleData.NUM_YUNCOS_REINICIO], hops known). This task's
 * file allowance does not extend to editing `SampleData.kt` to add a second
 * one with no hop information recorded at all, so this rebuilds that one
 * relay's [RelayStats.fromNodeStats] with [SampleData.NUM_VALDEMORO_QUIETO]
 * (a real directory node with no position of its own, per its own fixture
 * comment) added at `RemoteNodeStats(packetCount = 4)` - a node whose
 * packets were counted but never carried hop information, so
 * `avgHopsMade`/`avgHopsLeft` are `null` rather than `0f`. Together the two
 * rows this produces cover both "absent hop data" and "no position" in one
 * preview.
 */
private val previewRelayWithAbsentHopData: RelayStats =
    SampleData.relay(SampleData.RELAY_THREE_MATCH_BYTE).let { relay ->
        relay.copy(
            fromNodeStats = relay.fromNodeStats + (SampleData.NUM_VALDEMORO_QUIETO to RemoteNodeStats(packetCount = 4)),
        )
    }

@Preview(showBackground = true, name = "Several remote nodes, varied hop counts")
@Composable
private fun RemoteNodesTabVariedHopsPreview() {
    MeshRelayTheme {
        PreviewClock {
            RemoteNodesTab(
                relay = SampleData.relay(SampleData.RELAY_ONE_MATCH_BYTE),
                snapshot = SampleData.snapshot,
                onOpenRemoteNode = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Absent hop data, no position")
@Composable
private fun RemoteNodesTabAbsentHopDataPreview() {
    MeshRelayTheme {
        PreviewClock {
            RemoteNodesTab(
                relay = previewRelayWithAbsentHopData,
                snapshot = SampleData.snapshot,
                onOpenRemoteNode = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "No remote nodes yet")
@Composable
private fun RemoteNodesTabEmptyPreview() {
    MeshRelayTheme {
        PreviewClock {
            RemoteNodesTab(
                relay = SampleData.relay(SampleData.RELAY_NO_MATCH_BYTE),
                snapshot = SampleData.snapshot,
                onOpenRemoteNode = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Dark theme", uiMode = 0x20)
@Composable
private fun RemoteNodesTabDarkPreview() {
    MeshRelayTheme(darkTheme = true) {
        PreviewClock {
            RemoteNodesTab(
                relay = previewRelayWithAbsentHopData,
                snapshot = SampleData.snapshot,
                onOpenRemoteNode = {},
            )
        }
    }
}
