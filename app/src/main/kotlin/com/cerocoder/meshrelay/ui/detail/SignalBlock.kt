package com.cerocoder.meshrelay.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.settings.GaugeMode
import com.cerocoder.meshrelay.stats.SignalScales
import com.cerocoder.meshrelay.stats.model.SignalStats
import com.cerocoder.meshrelay.ui.common.SignalGauge
import com.cerocoder.meshrelay.ui.common.StatsFormat
import com.cerocoder.meshrelay.ui.theme.MeshRelayTheme
import com.cerocoder.meshrelay.ui.theme.RssiMarker
import com.cerocoder.meshrelay.ui.theme.RssiTrack
import com.cerocoder.meshrelay.ui.theme.SnrMarker
import com.cerocoder.meshrelay.ui.theme.SnrTrack
import java.util.Locale

/**
 * The detail screen's numeric signal block: min/avg/max/last/count for SNR and
 * RSSI, each with its own gauge. Ports the `rxSnr (dB): rxRssi (dBm):` section of
 * `build_detail_lines`, mesh_stats.py:1826-1833, as two labelled rows instead of
 * the terminal's two-column table.
 *
 * Public - not because Task 25 needs it (it does not: [com.cerocoder.meshrelay.ui.detail.MatchingNodesTab]'s
 * per-candidate cards are a different, unrelated shape), but so this block's
 * layout and its "no data yet" fallback live in exactly one place rather than
 * being re-derived by every future screen that shows a relay's or a
 * neighbour's raw signal history.
 *
 * The original gates its whole numeric block on *both* metrics having data
 * (`if node.snr.count > 0 and node.rssi.count > 0`). This deliberately does
 * not: [R.string.detail_no_signal_data] shows only when *neither* metric has
 * a sample, so a relay or neighbour with SNR readings but no RSSI ones (or the
 * reverse) still shows what it has, with `common_not_available` in the five
 * cells that have nothing - more informative than hiding a real reading
 * because its sibling metric is missing.
 */
@Composable
fun SignalBlock(
    snr: SignalStats,
    rssi: SignalStats,
    gaugeMode: GaugeMode,
    lastPacketAtMillis: Long,
    modifier: Modifier = Modifier,
) {
    if (!snr.hasData && !rssi.hasData) {
        Text(
            text = stringResource(R.string.detail_no_signal_data),
            style = MaterialTheme.typography.bodyMedium,
            modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        return
    }

    val locale = displayLocale()
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MetricBlock(
            label = stringResource(R.string.detail_signal_snr),
            unitFormatRes = R.string.format_snr_db,
            stats = snr,
            scaleMin = SignalScales.SNR_MIN,
            scaleMax = SignalScales.SNR_MAX,
            mode = gaugeMode,
            lastPacketAtMillis = lastPacketAtMillis,
            trackColor = SnrTrack,
            markerColor = SnrMarker,
            locale = locale,
        )
        MetricBlock(
            label = stringResource(R.string.detail_signal_rssi),
            unitFormatRes = R.string.format_rssi_dbm,
            stats = rssi,
            scaleMin = SignalScales.RSSI_MIN,
            scaleMax = SignalScales.RSSI_MAX,
            mode = gaugeMode,
            lastPacketAtMillis = lastPacketAtMillis,
            trackColor = RssiTrack,
            markerColor = RssiMarker,
            locale = locale,
        )
    }
}

/**
 * One metric's block: its label, its gauge, then five labelled stats
 * (min/avg/max/last/count). Unlike [com.cerocoder.meshrelay.ui.relays.RelayCard]'s
 * compact `SignalRow` (a single slashed "min/avg/max" string beside the gauge),
 * this is the detail screen's expanded form, with each of the five figures
 * under its own [R.string.detail_stat_min]-style label - the original's
 * `Min:`/`Avg:`/`Max:`/`Last:`/`Count:` rows, mesh_stats.py:1828-1832.
 */
@Composable
private fun MetricBlock(
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
    val minText = StatsFormat.signalMin(stats, locale)?.let { stringResource(unitFormatRes, it) } ?: notAvailable
    val avgText = StatsFormat.signalAvg(stats, locale)?.let { stringResource(unitFormatRes, it) } ?: notAvailable
    val maxText = StatsFormat.signalMax(stats, locale)?.let { stringResource(unitFormatRes, it) } ?: notAvailable
    val lastText = StatsFormat.signalLast(stats, locale)?.let { stringResource(unitFormatRes, it) } ?: notAvailable

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.titleSmall)
        SignalGauge(
            stats = stats,
            scaleMin = scaleMin,
            scaleMax = scaleMax,
            mode = mode,
            lastPacketAtMillis = lastPacketAtMillis,
            trackColor = trackColor,
            markerColor = markerColor,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatItem(stringResource(R.string.detail_stat_min), minText)
            StatItem(stringResource(R.string.detail_stat_avg), avgText)
            StatItem(stringResource(R.string.detail_stat_max), maxText)
            StatItem(stringResource(R.string.detail_stat_last), lastText)
            // count is a plain digit sequence, not a locale-sensitive number,
            // the same reasoning RelayListScreen's own LabelledCount follows -
            // there is nothing here for a pure formatter to own.
            StatItem(stringResource(R.string.detail_stat_count), stats.count.toString())
        }
    }
}

/** One label-over-value pair in [MetricBlock]'s stat row. */
@Composable
private fun StatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(text = label, style = MaterialTheme.typography.labelSmall)
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}

/** The configured display locale, for the same locale-sensitive number
 *  formatting [com.cerocoder.meshrelay.ui.common.PositionLineText] itself uses -
 *  a Spanish reader expects a decimal comma, not a period, in these figures.
 *  A second, independent copy of the identical private helper
 *  [com.cerocoder.meshrelay.ui.relays.RelayCard] and
 *  [com.cerocoder.meshrelay.ui.neighbours.NeighbourCard] each already carry. */
@Composable
private fun displayLocale(): Locale {
    val locales = LocalConfiguration.current.locales
    return if (locales.isEmpty()) Locale.getDefault() else locales.get(0)
}

@Preview(showBackground = true, name = "Both metrics")
@Composable
private fun SignalBlockPopulatedPreview() {
    MeshRelayTheme {
        SignalBlock(
            snr = SignalStats.EMPTY.plus(-6f).plus(8f).plus(-2.5f),
            rssi = SignalStats.EMPTY.plus(-102f).plus(-64f).plus(-88f),
            gaugeMode = GaugeMode.COMPLEX,
            lastPacketAtMillis = System.currentTimeMillis(),
        )
    }
}

@Preview(showBackground = true, name = "No signal data")
@Composable
private fun SignalBlockNoDataPreview() {
    MeshRelayTheme {
        SignalBlock(
            snr = SignalStats.EMPTY,
            rssi = SignalStats.EMPTY,
            gaugeMode = GaugeMode.COMPLEX,
            lastPacketAtMillis = 0L,
        )
    }
}

@Preview(showBackground = true, name = "One metric missing")
@Composable
private fun SignalBlockPartialPreview() {
    MeshRelayTheme {
        SignalBlock(
            snr = SignalStats.EMPTY.plus(4.5f),
            rssi = SignalStats.EMPTY,
            gaugeMode = GaugeMode.SIMPLE,
            lastPacketAtMillis = System.currentTimeMillis(),
        )
    }
}
