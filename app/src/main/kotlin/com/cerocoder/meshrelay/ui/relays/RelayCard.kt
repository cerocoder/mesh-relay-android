package com.cerocoder.meshrelay.ui.relays

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.settings.GaugeMode
import com.cerocoder.meshrelay.stats.SignalScales
import com.cerocoder.meshrelay.stats.model.LocationInfo
import com.cerocoder.meshrelay.stats.model.RelayStats
import com.cerocoder.meshrelay.stats.model.SignalStats
import com.cerocoder.meshrelay.ui.common.AgeLabel
import com.cerocoder.meshrelay.ui.common.LocalRelativeClock
import com.cerocoder.meshrelay.ui.common.PositionLineText
import com.cerocoder.meshrelay.ui.common.SignalGauge
import com.cerocoder.meshrelay.ui.common.StatsFormat
import com.cerocoder.meshrelay.ui.common.resolvePositionStrings
import com.cerocoder.meshrelay.ui.theme.RssiMarker
import com.cerocoder.meshrelay.ui.theme.RssiTrack
import com.cerocoder.meshrelay.ui.theme.SnrMarker
import com.cerocoder.meshrelay.ui.theme.SnrTrack
import java.util.Locale

/**
 * One relay, three lines. Ports `render_node_row`, mesh_stats.py:1348-1522, as a
 * tappable card rather than two fixed-column terminal rows.
 *
 * [matchCount] and [uniqueName] are supplied by the caller rather than derived
 * here from [relay] alone - [relay] only carries one byte, and resolving which
 * directory nodes could be behind it is [com.cerocoder.meshrelay.stats.model.NodeDirectorySnapshot]'s
 * job. Whenever [matchCount] is not exactly 1, [uniqueName] is expected to be
 * `""` and is never shown: a name beside an ambiguous byte would present a
 * guess as a fact. [location] is held to the same rule, and this component
 * enforces it itself rather than trusting the caller alone - it is public
 * precisely so Task 23 reuses it, and a rule only a caller can break is one
 * bad caller away from showing a distance to a node that may not even be the
 * relay in question.
 *
 * Public so Task 23's relay detail screen can reuse the exact same row shape
 * for its own header rather than re-deriving it.
 */
@Composable
fun RelayCard(
    relay: RelayStats,
    matchCount: Int,
    uniqueName: String,
    location: LocationInfo?,
    totalRelayed: Int,
    gaugeMode: GaugeMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = displayLocale()
    val nowMillis = LocalRelativeClock.current
    val positionStrings = resolvePositionStrings()

    // Distance and altitude - never the coordinates or the source label
    // PositionLine prints elsewhere, so a relay row stays one compact line
    // even when a full PositionLine would run to several - are shown only
    // when exactly one node matches. Gated here again, not just by the
    // caller: a relay byte is one byte, and a position beside an ambiguous
    // one would present a guess as a fact, same as the name above it.
    val positionText = if (matchCount == 1) {
        location?.let { info ->
            val parts = PositionLineText.parts(info, nowMillis, positionStrings)
            listOfNotNull(parts.distance, parts.altitude).joinToString(separator = " ")
        }
    } else {
        null
    }

    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Line 1: relay byte + match count, the unique name (if any), age.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = hexWithMatchCount(relay.hexId, matchCount),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (matchCount == 1 && uniqueName.isNotEmpty()) {
                    Text(
                        text = uniqueName,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                AgeLabel(atMillis = relay.lastPacketAtMillis)
            }

            // Line 2: SNR then RSSI, each its own min/avg/max, gauge and latest value.
            SignalRow(
                label = stringResource(R.string.gauge_snr),
                unitFormatRes = R.string.format_snr_db,
                stats = relay.snr,
                scaleMin = SignalScales.SNR_MIN,
                scaleMax = SignalScales.SNR_MAX,
                mode = gaugeMode,
                lastPacketAtMillis = relay.lastPacketAtMillis,
                trackColor = SnrTrack,
                markerColor = SnrMarker,
                locale = locale,
            )
            SignalRow(
                label = stringResource(R.string.gauge_rssi),
                unitFormatRes = R.string.format_rssi_dbm,
                stats = relay.rssi,
                scaleMin = SignalScales.RSSI_MIN,
                scaleMax = SignalScales.RSSI_MAX,
                mode = gaugeMode,
                lastPacketAtMillis = relay.lastPacketAtMillis,
                trackColor = RssiTrack,
                markerColor = RssiMarker,
                locale = locale,
            )

            // Line 3: distance/altitude (single match only), packets, share, known nodes.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!positionText.isNullOrEmpty()) {
                    Text(
                        text = positionText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                Text(
                    text = pluralStringResource(R.plurals.plural_packets, relay.packetCount, relay.packetCount),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(
                        R.string.format_percent,
                        StatsFormat.percentageOf(relay.packetCount, totalRelayed, locale),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.plural_known_nodes,
                        relay.knownNodesCount,
                        relay.knownNodesCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * One signal metric's row: a short label, its min/avg/max triple, the gauge
 * itself and the latest reading. [SignalGauge] draws only the bar - it carries
 * no label and no numeric text of its own - so this is the "compose your own
 * row with the number beside it" the gauge's own documentation calls for.
 */
@Composable
private fun SignalRow(
    label: String,
    unitFormatRes: Int,
    stats: SignalStats,
    scaleMin: Float,
    scaleMax: Float,
    mode: GaugeMode,
    lastPacketAtMillis: Long,
    trackColor: Color,
    markerColor: Color,
    locale: Locale,
    modifier: Modifier = Modifier,
) {
    val notAvailable = stringResource(R.string.common_not_available)
    val tripleText = StatsFormat.signalTriple(stats, locale)?.let { stringResource(unitFormatRes, it) } ?: notAvailable
    val lastText = StatsFormat.signalLast(stats, locale)?.let { stringResource(unitFormatRes, it) } ?: notAvailable

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelSmall)
        Text(text = tripleText, style = MaterialTheme.typography.bodySmall)
        SignalGauge(
            stats = stats,
            scaleMin = scaleMin,
            scaleMax = scaleMax,
            mode = mode,
            lastPacketAtMillis = lastPacketAtMillis,
            trackColor = trackColor,
            markerColor = markerColor,
            modifier = Modifier.weight(1f),
        )
        Text(text = lastText, style = MaterialTheme.typography.bodySmall)
    }
}

/** The configured display locale, for the same locale-sensitive number
 *  formatting [PositionLineText] itself uses - a Spanish reader expects a
 *  decimal comma, not a period, in the SNR/RSSI triple or the percentage. */
@Composable
private fun displayLocale(): Locale {
    val locales = LocalConfiguration.current.locales
    return if (locales.isEmpty()) Locale.getDefault() else locales.get(0)
}

/**
 * `"0x1e[1]"` - [RelayStats.hexId] with the match count appended in brackets,
 * the same notation mesh_stats.py:1371 builds as `f"{hex_id}[{match_count}]"`.
 * The brackets are structural notation carried over from the original, not
 * translatable prose, so they are a literal here rather than a resource - the
 * same treatment [PositionLineText]'s direction separator gets.
 */
private fun hexWithMatchCount(hexId: String, matchCount: Int): String = "$hexId[$matchCount]"
