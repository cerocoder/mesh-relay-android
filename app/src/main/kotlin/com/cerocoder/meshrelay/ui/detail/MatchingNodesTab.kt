package com.cerocoder.meshrelay.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.stats.Geo
import com.cerocoder.meshrelay.stats.model.NodeDirectorySnapshot
import com.cerocoder.meshrelay.stats.model.StatsSnapshot
import com.cerocoder.meshrelay.ui.common.LocalRelativeClock
import com.cerocoder.meshrelay.ui.preview.SampleData
import com.cerocoder.meshrelay.ui.theme.MeshRelayTheme

/**
 * The relay detail screen's first tab, and the reason this whole port exists: a
 * relay identifies itself with one byte, several database nodes can share that
 * byte, and the tool cannot tell which one actually forwarded the packet. This
 * lists every remaining candidate, with everything [NodeCard] knows how to show
 * about it, and lets the user rule out the ones that obviously are not it - each
 * skip narrows what
 * [com.cerocoder.meshrelay.stats.model.NodeDirectorySnapshot.matchingNodeNums]
 * returns until, ideally, one candidate is left. Ports the per-candidate loop of
 * `build_detail_lines`, mesh_stats.py:1849-1910.
 *
 * [relayByte] plus [snapshot] is enough to derive everything shown - the
 * candidate list itself
 * ([com.cerocoder.meshrelay.stats.model.NodeDirectorySnapshot.matchingNodeNums])
 * already has the skip list applied, exactly as the original's own
 * `find_matching_nodes` does (mesh_stats.py:704-712, :714-733) - so a node the
 * user has skipped simply is not one of the cards below. This function's own
 * `skippedCountForThisByte` exists so there is still a way back: it powers the
 * header row and the
 * [onClearSkipped] confirmation, without which a relay skipped down to zero
 * candidates would have no path except Settings' global skip list to recover.
 *
 * Stateless like every other screen in this port - [onSkipNode] and
 * [onClearSkipped] are called only after their own confirmation dialogs, one
 * per [NodeCard] (skip) and one here (clear-all), both `remember`ed locally on
 * the same terms [com.cerocoder.meshrelay.ui.relays.RelayListScreen]'s reset
 * dialog already is.
 */
@Composable
fun MatchingNodesTab(
    relayByte: Int,
    snapshot: StatsSnapshot,
    meshviewUrl: String?,
    onSkipNode: (nodeNum: Int) -> Unit,
    onClearSkipped: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var clearDialogVisible by remember { mutableStateOf(false) }

    val directory = snapshot.directory
    val candidates = directory.matchingNodeNums(relayByte)
    val skippedCountForThisByte = snapshot.skippedRelayNodes.count { Geo.lastByteOfNodeNum(it) == relayByte }
    val localPosition = directory.localPosition()
    // Only nodes the directory actually still knows about end up here - every
    // number in `candidates` came from `directory.nodes.keys` in the first
    // place (matchingNodeNums's own contract), so this never drops anything.
    val candidateRecords = candidates.mapNotNull { nodeNum -> directory.node(nodeNum)?.let { nodeNum to it } }

    Column(modifier = modifier.fillMaxSize()) {
        if (skippedCountForThisByte > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = pluralStringResource(
                        R.plurals.plural_skipped_nodes,
                        skippedCountForThisByte,
                        skippedCountForThisByte,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = { clearDialogVisible = true }) {
                    Text(stringResource(R.string.action_clear_skipped))
                }
            }
        }

        if (candidateRecords.isEmpty()) {
            EmptyMatchingNodesState(modifier = Modifier.weight(1f))
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Keyed on nodeNum, not list index, so a skip that shortens this
                // list animates the remaining cards into place instead of
                // rebuilding every one - same reasoning as the other two list
                // screens' own `items(..., key = ...)`.
                itemsIndexed(items = candidateRecords, key = { _, pair -> pair.first }) { index, (nodeNum, record) ->
                    NodeCard(
                        index = index + 1,
                        record = record,
                        location = directory.locationInfo(nodeNum, localPosition),
                        telemetry = directory.telemetry(nodeNum),
                        meshviewUrl = meshviewUrl,
                        onSkip = { onSkipNode(nodeNum) },
                    )
                }
            }
        }
    }

    if (clearDialogVisible) {
        AlertDialog(
            onDismissRequest = { clearDialogVisible = false },
            title = { Text(stringResource(R.string.action_clear_skipped_confirm_title)) },
            text = { Text(stringResource(R.string.action_clear_skipped_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearDialogVisible = false
                        onClearSkipped()
                    },
                ) {
                    Text(stringResource(R.string.action_clear_skipped))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearDialogVisible = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

/** No node in the database currently matches this relay's byte - either none
 *  ever has, or every one that did has been skipped. Points at what the user
 *  can do about the second case: the header row above this state (when there
 *  is one) still offers [R.string.action_clear_skipped]. */
@Composable
private fun EmptyMatchingNodesState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.node_no_matching_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.node_no_matching_body),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * [SampleData]'s own three-match fixture ([SampleData.RELAY_THREE_MATCH_BYTE])
 * has no candidate currently skipped - its one skipped fixture
 * ([SampleData.RELAY_SKIPPED_CANDIDATE_BYTE]) has exactly one candidate to
 * begin with, so skipping it empties the list rather than merely shrinking it.
 * Neither shape is "three candidates, one skipped" on its own, and this task's
 * file allowance does not extend to editing `SampleData.kt` to add a fixture
 * that is - so this rebuilds
 * [com.cerocoder.meshrelay.stats.model.NodeDirectorySnapshot] from
 * [SampleData.directory]'s own public `nodes`/`loadedAtMillis`/`localNodeNum`
 * with [SampleData.NUM_TOLEDO_BAJA] (one of the three
 * [SampleData.RELAY_THREE_MATCH_BYTE] candidates) added to the skip set.
 * `positions`/`telemetry` are dropped to empty maps rather than reconstructed -
 * neither of [SampleData]'s two live-position/telemetry fixtures
 * (`NUM_TOLEDO_NIEBLA`, `NUM_YUNCOS_REINICIO`) is one of this byte's three
 * candidates, so nothing this preview needs to show is lost.
 */
private val previewThreeMatchOneSkippedDirectory = NodeDirectorySnapshot(
    nodes = SampleData.directory.nodes,
    loadedAtMillis = SampleData.directory.loadedAtMillis,
    localNodeNum = SampleData.directory.localNodeNum,
    positions = emptyMap(),
    telemetry = emptyMap(),
    skipped = setOf(SampleData.NUM_TOLEDO_BAJA),
)

private val previewThreeMatchOneSkippedSnapshot = SampleData.snapshot.copy(
    directory = previewThreeMatchOneSkippedDirectory,
    skippedRelayNodes = setOf(SampleData.NUM_TOLEDO_BAJA),
)

/**
 * Supplies a stable "now" to [LocalRelativeClock] for previews, so every
 * [NodeCard] row built on [com.cerocoder.meshrelay.ui.common.AgeLabel] (last
 * heard) renders a believable "n ago" instead of falling through to the
 * composition local's `0L` default - see
 * [com.cerocoder.meshrelay.ui.relays.RelayListScreen]'s identical private
 * helper for the full reasoning.
 */
@Composable
private fun PreviewClock(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRelativeClock provides System.currentTimeMillis()) {
        content()
    }
}

@Preview(showBackground = true, name = "Three candidates, one skipped")
@Composable
private fun MatchingNodesTabThreeMatchOneSkippedPreview() {
    MeshRelayTheme {
        PreviewClock {
            MatchingNodesTab(
                relayByte = SampleData.RELAY_THREE_MATCH_BYTE,
                snapshot = previewThreeMatchOneSkippedSnapshot,
                meshviewUrl = "https://meshview.meshtastic.es",
                onSkipNode = {},
                onClearSkipped = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Single candidate")
@Composable
private fun MatchingNodesTabSingleMatchPreview() {
    MeshRelayTheme {
        PreviewClock {
            MatchingNodesTab(
                relayByte = SampleData.RELAY_ONE_MATCH_BYTE,
                snapshot = SampleData.snapshot,
                meshviewUrl = "https://meshview.meshtastic.es",
                onSkipNode = {},
                onClearSkipped = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "No candidates")
@Composable
private fun MatchingNodesTabNoMatchPreview() {
    MeshRelayTheme {
        PreviewClock {
            MatchingNodesTab(
                relayByte = SampleData.RELAY_NO_MATCH_BYTE,
                snapshot = SampleData.snapshot,
                meshviewUrl = "https://meshview.meshtastic.es",
                onSkipNode = {},
                onClearSkipped = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "No candidates, all skipped (recovery path)")
@Composable
private fun MatchingNodesTabAllSkippedPreview() {
    MeshRelayTheme {
        PreviewClock {
            MatchingNodesTab(
                relayByte = SampleData.RELAY_SKIPPED_CANDIDATE_BYTE,
                snapshot = SampleData.snapshot,
                meshviewUrl = "https://meshview.meshtastic.es",
                onSkipNode = {},
                onClearSkipped = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Dark theme", uiMode = 0x20)
@Composable
private fun MatchingNodesTabDarkPreview() {
    MeshRelayTheme(darkTheme = true) {
        PreviewClock {
            MatchingNodesTab(
                relayByte = SampleData.RELAY_THREE_MATCH_BYTE,
                snapshot = previewThreeMatchOneSkippedSnapshot,
                meshviewUrl = "https://meshview.meshtastic.es",
                onSkipNode = {},
                onClearSkipped = {},
            )
        }
    }
}
