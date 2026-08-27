package com.cerocoder.meshrelay.ui.neighbours

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
import com.cerocoder.meshrelay.stats.model.NeighbourStats
import com.cerocoder.meshrelay.stats.model.SignalStats
import com.cerocoder.meshrelay.ui.common.AgeLabel
import com.cerocoder.meshrelay.ui.common.LocalRelativeClock
import com.cerocoder.meshrelay.ui.common.NodeIdText
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
 * One neighbour, three lines. Ports `render_neighbour_row`, mesh_stats.py:1524-1659,
 * as a tappable card rather than two fixed-column terminal rows - the same
 * transformation [com.cerocoder.meshrelay.ui.relays.RelayCard] makes for a relay.
 *
 * A neighbour is a known node identity, not a one-byte guess: [neighbour] carries
 * a real `nodeNum`, so unlike a relay row there is no ambiguity to disclose and no
 * `[n]` match count next to the identifier. [location] is therefore accepted as a
 * plain, always-present [LocationInfo] (never `null`) - the caller resolves it
 * straight from [com.cerocoder.meshrelay.stats.model.NodeDirectorySnapshot.locationInfo],
 * with no matching step in between - and [totalDirect] is
 * [com.cerocoder.meshrelay.stats.model.Counters.totalDirectPackets], not the
 * relayed total a relay row's percentage is taken against.
 */
@Composable
fun NeighbourCard(
    neighbour: NeighbourStats,
    shortName: String,
    location: LocationInfo,
    totalDirect: Int,
    gaugeMode: GaugeMode,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = displayLocale()
    val nowMillis = LocalRelativeClock.current
    val positionStrings = resolvePositionStrings()

    // Distance and altitude only - never the coordinates or the source label
    // PositionLine prints elsewhere - so a neighbour row stays one compact
    // line, same restraint RelayCard applies to its own line three.
    val parts = PositionLineText.parts(location, nowMillis, positionStrings)
    val positionText = listOfNotNull(parts.distance, parts.altitude).joinToString(separator = " ")

    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Line 1: node identity (id + short name, if known), age.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NodeIdText(nodeNum = neighbour.nodeNum)
                if (shortName.isNotEmpty()) {
                    Text(
                        text = shortName,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                AgeLabel(atMillis = neighbour.lastPacketAtMillis)
            }

            // Line 2: SNR then RSSI, each its own min/avg/max, gauge and latest value.
            SignalRow(
                label = stringResource(R.string.gauge_snr),
                unitFormatRes = R.string.format_snr_db,
                stats = neighbour.snr.stats,
                scaleMin = SignalScales.SNR_MIN,
                scaleMax = SignalScales.SNR_MAX,
                mode = gaugeMode,
                lastPacketAtMillis = neighbour.lastPacketAtMillis,
                trackColor = SnrTrack,
                markerColor = SnrMarker,
                locale = locale,
            )
            SignalRow(
                label = stringResource(R.string.gauge_rssi),
                unitFormatRes = R.string.format_rssi_dbm,
                stats = neighbour.rssi.stats,
                scaleMin = SignalScales.RSSI_MIN,
                scaleMax = SignalScales.RSSI_MAX,
                mode = gaugeMode,
                lastPacketAtMillis = neighbour.lastPacketAtMillis,
                trackColor = RssiTrack,
                markerColor = RssiMarker,
                locale = locale,
            )

            // Line 3: distance/altitude, direct packet count, share of direct traffic.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (positionText.isNotEmpty()) {
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
                    text = pluralStringResource(
                        R.plurals.plural_packets,
                        neighbour.packetCount,
                        neighbour.packetCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(
                        R.string.format_percent,
                        StatsFormat.percentageOf(neighbour.packetCount, totalDirect, locale),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * One signal metric's row: a short label, its min/avg/max triple, the gauge
 * itself and the latest reading. Identical in shape to
 * [com.cerocoder.meshrelay.ui.relays.RelayCard]'s private row of the same name -
 * that one is not public, so this is a second, independent instance rather than
 * a shared one, on the same reasoning [displayLocale] below already follows.
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
