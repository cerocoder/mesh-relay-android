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
import com.cerocoder.meshrelay.stats.AgeBucket
import com.cerocoder.meshrelay.stats.SignalScales
import com.cerocoder.meshrelay.stats.model.Direction
import com.cerocoder.meshrelay.stats.model.LocationInfo
import com.cerocoder.meshrelay.stats.model.PositionSource
import com.cerocoder.meshrelay.stats.model.RelayStats
import com.cerocoder.meshrelay.stats.model.SignalStats
import com.cerocoder.meshrelay.ui.common.AgeLabel
import com.cerocoder.meshrelay.ui.common.LocalRelativeClock
import com.cerocoder.meshrelay.ui.common.PositionLineText
import com.cerocoder.meshrelay.ui.common.PositionStrings
import com.cerocoder.meshrelay.ui.common.SignalGauge
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
 * guess as a fact. The same rule gates [location] - it is only ever non-null
 * when the caller found exactly one candidate to point it at.
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
    val locale = rememberDisplayLocale()
    val nowMillis = LocalRelativeClock.current
    val positionStrings = resolveCardPositionStrings(locale)

    // Only distance and altitude are shown here - never the coordinates or the
    // source label PositionLine prints elsewhere - so a relay row stays one
    // compact line even when a full PositionLine would run to several.
    val positionText = location?.let { info ->
        val parts = PositionLineText.parts(info, nowMillis, positionStrings)
        listOfNotNull(parts.distance, parts.altitude).joinToString(separator = " ")
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
                        formatPercentOfRelayed(relay.packetCount, totalRelayed, locale),
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
    val tripleText = formatSignalTriple(stats, locale)?.let { stringResource(unitFormatRes, it) } ?: notAvailable
    val lastText = formatSignalLast(stats, locale)?.let { stringResource(unitFormatRes, it) } ?: notAvailable

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

/**
 * Every format string and label [PositionLineText] needs for the fragments this
 * card shows. Duplicates [com.cerocoder.meshrelay.ui.common.PositionLine]'s own
 * private resolver - that function is not accessible from this file, and this
 * task may only create the four files listed in its brief - but every string
 * resource read here is the same one already reviewed and shipped with
 * [com.cerocoder.meshrelay.ui.common.PositionLine].
 */
@Composable
private fun resolveCardPositionStrings(locale: Locale): PositionStrings = PositionStrings(
    locale = locale,
    coordinatesFormat = stringResource(R.string.format_coordinates),
    distanceFormat = stringResource(R.string.format_distance_km),
    distanceUncertainFormat = stringResource(R.string.format_distance_km_uncertain),
    altitudeFormat = stringResource(R.string.format_altitude_m),
    sourceFormat = stringResource(R.string.format_source),
    sourceAgedFormat = stringResource(R.string.format_source_aged),
    ageLabels = mapOf(
        AgeBucket.M1 to stringResource(R.string.age_1m),
        AgeBucket.M5 to stringResource(R.string.age_5m),
        AgeBucket.M30 to stringResource(R.string.age_30m),
        AgeBucket.H1 to stringResource(R.string.age_1h),
        AgeBucket.H12 to stringResource(R.string.age_12h),
        AgeBucket.D1 to stringResource(R.string.age_1d),
        AgeBucket.W1 to stringResource(R.string.age_1w),
        AgeBucket.Y1 to stringResource(R.string.age_1y),
        AgeBucket.UNKNOWN to stringResource(R.string.age_unknown),
    ),
    directionLabels = mapOf(
        Direction.N to stringResource(R.string.direction_n),
        Direction.NE to stringResource(R.string.direction_ne),
        Direction.E to stringResource(R.string.direction_e),
        Direction.SE to stringResource(R.string.direction_se),
        Direction.S to stringResource(R.string.direction_s),
        Direction.SW to stringResource(R.string.direction_sw),
        Direction.W to stringResource(R.string.direction_w),
        Direction.NW to stringResource(R.string.direction_nw),
        Direction.UNKNOWN to stringResource(R.string.direction_unknown),
    ),
    sourceLabels = mapOf(
        PositionSource.DB to stringResource(R.string.source_db),
        PositionSource.CURRENT to stringResource(R.string.source_current),
    ),
)

/** The configured display locale, for the same locale-sensitive number
 *  formatting [PositionLineText] itself uses - a Spanish reader expects a
 *  decimal comma, not a period, in the SNR/RSSI triple or the percentage. */
@Composable
private fun rememberDisplayLocale(): Locale {
    val locales = LocalConfiguration.current.locales
    return if (locales.isEmpty()) Locale.getDefault() else locales.get(0)
}

private const val TRIPLE_SEPARATOR = "/"

/** mesh_stats.py's min/max precision (`:>4.0f`) - a single instantaneous
 *  reading, not an average, so whole decibels are all it claims. */
private const val SIGNAL_SPOT_PATTERN = "%.0f"

/** mesh_stats.py's average precision (`:>4.1f`). */
private const val SIGNAL_AVG_PATTERN = "%.1f"

private const val PERCENT_PATTERN = "%.1f"

/**
 * `"0x1e[1]"` - [RelayStats.hexId] with the match count appended in brackets,
 * the same notation mesh_stats.py:1371 builds as `f"{hex_id}[{match_count}]"`.
 * The brackets are structural notation carried over from the original, not
 * translatable prose, so they are a literal here rather than a resource - the
 * same treatment [PositionLineText]'s direction separator gets.
 */
private fun hexWithMatchCount(hexId: String, matchCount: Int): String = "$hexId[$matchCount]"

/**
 * `"min/avg/max"`, ports the triple mesh_stats.py:1412-1413 (and :1471-1472 for
 * rxRssi) builds as `f"{min:>4.0f}/{avg:>4.1f}/{max:>4.0f}"`, minus the
 * fixed-width padding a touch screen has no use for. `null` before [stats] has
 * any data - the caller falls back to [R.string.common_not_available], the
 * same case the original's `"  --/  --/  --"` placeholder covers.
 */
private fun formatSignalTriple(stats: SignalStats, locale: Locale): String? {
    if (!stats.hasData) return null
    val min = String.format(locale, SIGNAL_SPOT_PATTERN, stats.minVal)
    val avg = String.format(locale, SIGNAL_AVG_PATTERN, stats.avg)
    val max = String.format(locale, SIGNAL_SPOT_PATTERN, stats.maxVal)
    return "$min$TRIPLE_SEPARATOR$avg$TRIPLE_SEPARATOR$max"
}

/**
 * The latest reading, at [SIGNAL_SPOT_PATTERN] precision like the min/max
 * either side of it - a single instantaneous value, not an average. `null`
 * before [stats] has any data.
 */
private fun formatSignalLast(stats: SignalStats, locale: Locale): String? =
    if (stats.hasData) String.format(locale, SIGNAL_SPOT_PATTERN, stats.lastVal) else null

/**
 * This relay's share of all relayed traffic, ports the `pct` calculation at
 * mesh_stats.py:1461-1462 (`pct = packet_count / total_relayed * 100 if
 * total_relayed > 0 else 0`). Zero before anything has been relayed at all,
 * rather than a division by zero.
 */
private fun formatPercentOfRelayed(packetCount: Int, totalRelayed: Int, locale: Locale): String {
    val percent = if (totalRelayed > 0) packetCount.toFloat() / totalRelayed * 100f else 0f
    return String.format(locale, PERCENT_PATTERN, percent)
}
