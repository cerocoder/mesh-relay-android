package com.cerocoder.meshrelay.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.ui.common.StatsFormat
import com.cerocoder.meshrelay.ui.nav.DetailSubject
import com.cerocoder.meshrelay.ui.theme.MeshRelayTheme
import java.util.Locale

/**
 * The detail screen's summary block: three label/value lines, ported from the
 * top of `build_detail_lines`, mesh_stats.py:1815-1821 - but the third line
 * differs by subject, which is this whole port's central point (spec §10.4):
 *
 * - [DetailSubject.Relay]: total packets relayed through this byte
 *   ([R.string.detail_total_relayed]), its packets/hour, then
 *   [R.string.detail_skipped_nodes] - shown only when [skippedCount] is
 *   positive, exactly as the original's "Explicitly skipped relay nodes" line
 *   only appears when its list is non-empty (mesh_stats.py:1818-1820).
 * - [DetailSubject.Neighbour]: total direct packets from this node
 *   ([R.string.detail_total_direct]), its packets/hour, then
 *   [R.string.node_hops_away] from the node database - always shown, reading
 *   [R.string.common_not_available] rather than a fabricated distance when
 *   the database has never reported a hop count.
 *
 * [packetsPerHour] is nullable for a reason worth stating plainly: unlike
 * [com.cerocoder.meshrelay.stats.model.RelayStats], which stores the
 * `firstPacketAtMillis` a rate needs,
 * [com.cerocoder.meshrelay.stats.model.NeighbourStats] carries no equivalent
 * field - only a `lastPacketAtMillis` and two signal histories whose sample
 * timestamps are capped and skip any packet that carried no decodable signal.
 * Deriving a rate from that data would silently misrepresent it as an exact
 * figure the way the relay's is, which is exactly what the honesty rule this
 * whole screen is built around forbids - so a neighbour's rate reads
 * [R.string.common_not_available] instead of a guess. See this task's report
 * for the full reasoning; a caller here always passes `null` for a neighbour.
 */
@Composable
fun DetailSummary(
    subject: DetailSubject,
    totalPackets: Int,
    packetsPerHour: Float?,
    skippedCount: Int,
    hopsAway: Int?,
    modifier: Modifier = Modifier,
) {
    val locale = displayLocale()
    val notAvailable = stringResource(R.string.common_not_available)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SummaryRow(
            label = stringResource(
                if (subject is DetailSubject.Relay) R.string.detail_total_relayed else R.string.detail_total_direct,
            ),
            // A plain digit sequence, not a locale-sensitive number - the same
            // reasoning RelayListScreen's own LabelledCount follows.
            value = totalPackets.toString(),
        )
        SummaryRow(
            label = stringResource(R.string.detail_packets_per_hour),
            value = packetsPerHour?.let {
                stringResource(R.string.format_packets_per_hour, StatsFormat.packetsPerHour(it, locale))
            } ?: notAvailable,
        )
        when (subject) {
            is DetailSubject.Relay -> if (skippedCount > 0) {
                SummaryRow(
                    label = stringResource(R.string.detail_skipped_nodes),
                    value = pluralStringResource(R.plurals.plural_skipped_nodes, skippedCount, skippedCount),
                )
            }
            is DetailSubject.Neighbour -> SummaryRow(
                label = stringResource(R.string.node_hops_away),
                value = hopsAway?.toString() ?: notAvailable,
            )
        }
    }
}

/** One label/value pair, label leading and value trailing on the same line. */
@Composable
private fun SummaryRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The configured display locale. A second, independent copy of the identical
 *  private helper [SignalBlock] and every card screen already carry - see
 *  [com.cerocoder.meshrelay.ui.relays.RelayCard]'s copy for the full reasoning. */
@Composable
private fun displayLocale(): Locale {
    val locales = LocalConfiguration.current.locales
    return if (locales.isEmpty()) Locale.getDefault() else locales.get(0)
}

@Preview(showBackground = true, name = "Relay, with skipped nodes")
@Composable
private fun DetailSummaryRelayPreview() {
    MeshRelayTheme {
        DetailSummary(
            subject = DetailSubject.Relay(0x69),
            totalPackets = 412,
            packetsPerHour = 118.4f,
            skippedCount = 2,
            hopsAway = null,
        )
    }
}

@Preview(showBackground = true, name = "Relay, nothing skipped")
@Composable
private fun DetailSummaryRelayNoSkipsPreview() {
    MeshRelayTheme {
        DetailSummary(
            subject = DetailSubject.Relay(0x1e),
            totalPackets = 47,
            packetsPerHour = 12.0f,
            skippedCount = 0,
            hopsAway = null,
        )
    }
}

@Preview(showBackground = true, name = "Neighbour, hops known")
@Composable
private fun DetailSummaryNeighbourPreview() {
    MeshRelayTheme {
        DetailSummary(
            subject = DetailSubject.Neighbour(0x9e75f1a4.toInt()),
            totalPackets = 8,
            packetsPerHour = null,
            skippedCount = 0,
            hopsAway = 2,
        )
    }
}

@Preview(showBackground = true, name = "Neighbour, hops unknown")
@Composable
private fun DetailSummaryNeighbourNoHopsPreview() {
    MeshRelayTheme {
        DetailSummary(
            subject = DetailSubject.Neighbour(0x9e75f1a4.toInt()),
            totalPackets = 1,
            packetsPerHour = null,
            skippedCount = 0,
            hopsAway = null,
        )
    }
}
