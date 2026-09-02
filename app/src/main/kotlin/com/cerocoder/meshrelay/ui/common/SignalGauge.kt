package com.cerocoder.meshrelay.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cerocoder.meshrelay.settings.GaugeMode
import com.cerocoder.meshrelay.stats.SignalScales
import com.cerocoder.meshrelay.stats.model.SignalStats
import com.cerocoder.meshrelay.ui.theme.FlashMarker
import com.cerocoder.meshrelay.ui.theme.MeshRelayTheme
import com.cerocoder.meshrelay.ui.theme.SnrMarker
import com.cerocoder.meshrelay.ui.theme.SnrTrack
import kotlinx.coroutines.delay

private val GaugeHeight = 16.dp
private val AvgStrokeWidth = 2.dp
private val LastStrokeWidth = 4.dp
private const val EMPTY_TRACK_ALPHA = 0.15f

/**
 * Draws one signal gauge. Ports `render_bar_simple` and `render_bar_complex`,
 * mesh_stats.py:1167-1263, replacing the terminal's fixed character columns
 * with a track scaled to whatever width this composable is laid out at.
 *
 * All positions come from [GaugeGeometry.marks], already expressed as
 * fractions of the track; this draws them by multiplying each fraction by the
 * canvas's measured width and does no other arithmetic.
 *
 * The last-value marker flashes for [SignalScales.FLASH_MILLIS] whenever
 * [lastPacketAtMillis] changes, driven by a [remember]ed "seen" value compared
 * against the current one inside a [LaunchedEffect] rather than a timer -
 * there is no polling anywhere in this app, and a timer that outlived this
 * row would be a defect. `produceState` cannot do this job: it runs its
 * producer on first composition as well as on a key change, and a
 * `LazyColumn` row scrolled back into view is a first composition - so it lit
 * every row's marker on every scroll, for packets that could be minutes old.
 */
@Composable
fun SignalGauge(
    stats: SignalStats,
    scaleMin: Float,
    scaleMax: Float,
    mode: GaugeMode,
    lastPacketAtMillis: Long,
    trackColor: Color,
    markerColor: Color,
    modifier: Modifier = Modifier,
) {
    val marks = GaugeGeometry.marks(stats, scaleMin, scaleMax, mode)

    // The flash means "a packet just landed", so it must fire on a *change* of
    // lastPacketAtMillis and never on composition. produceState could not do
    // that: it runs its producer on first composition too, and a LazyColumn row
    // scrolled back into view is a first composition - which lit every row's
    // marker on every scroll, for packets minutes old.
    //
    // `seen` is seeded with the value this row was composed with, so the effect
    // below returns immediately the first time and only a genuine later change
    // reaches the delay. A recycled row re-seeds and is silent again.
    var flashing by remember { mutableStateOf(false) }
    var seen by remember { mutableLongStateOf(lastPacketAtMillis) }
    LaunchedEffect(lastPacketAtMillis) {
        if (lastPacketAtMillis == seen || lastPacketAtMillis == 0L) return@LaunchedEffect
        seen = lastPacketAtMillis
        flashing = true
        delay(SignalScales.FLASH_MILLIS)
        flashing = false
    }
    val lastMarkerColor = if (flashing) FlashMarker else markerColor

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(GaugeHeight),
    ) {
        val width = size.width
        val height = size.height

        // 1. Empty track, full width, low alpha.
        drawRect(color = trackColor, alpha = EMPTY_TRACK_ALPHA)

        // 2. The fill, from fillStart * width to fillEnd * width.
        val fillStartPx = marks.fillStart * width
        val fillEndPx = marks.fillEnd * width
        drawRect(
            color = trackColor,
            topLeft = Offset(fillStartPx, 0f),
            size = Size(fillEndPx - fillStartPx, height),
        )

        // 3. The average, a thin rule.
        marks.avg?.let { avg ->
            val x = avg * width
            drawLine(
                color = markerColor,
                start = Offset(x, 0f),
                end = Offset(x, height),
                strokeWidth = AvgStrokeWidth.toPx(),
            )
        }

        // 4. The latest value, a thicker rule drawn after the average so it
        // covers it when they coincide - the original's priority rule,
        // expressed here as draw order rather than a branch.
        marks.last?.let { last ->
            val x = last * width
            drawLine(
                color = lastMarkerColor,
                start = Offset(x, 0f),
                end = Offset(x, height),
                strokeWidth = LastStrokeWidth.toPx(),
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SignalGaugeNoDataPreview() {
    MeshRelayTheme {
        SignalGauge(
            stats = SignalStats.EMPTY,
            scaleMin = SignalScales.SNR_MIN,
            scaleMax = SignalScales.SNR_MAX,
            mode = GaugeMode.COMPLEX,
            lastPacketAtMillis = 0L,
            trackColor = SnrTrack,
            markerColor = SnrMarker,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SignalGaugeSimplePreview() {
    val stats = SignalStats.EMPTY.plus(-20f).plus(-8f).plus(-2.5f)
    MeshRelayTheme {
        SignalGauge(
            stats = stats,
            scaleMin = SignalScales.SNR_MIN,
            scaleMax = SignalScales.SNR_MAX,
            mode = GaugeMode.SIMPLE,
            lastPacketAtMillis = 0L,
            trackColor = SnrTrack,
            markerColor = SnrMarker,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SignalGaugeComplexPreview() {
    val stats = SignalStats.EMPTY.plus(-20f).plus(15f).plus(-2.5f)
    MeshRelayTheme {
        SignalGauge(
            stats = stats,
            scaleMin = SignalScales.SNR_MIN,
            scaleMax = SignalScales.SNR_MAX,
            mode = GaugeMode.COMPLEX,
            lastPacketAtMillis = 0L,
            trackColor = SnrTrack,
            markerColor = SnrMarker,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun SignalGaugeComplexFlashingPreview() {
    val stats = SignalStats.EMPTY.plus(-20f).plus(15f).plus(-2.5f)
    MeshRelayTheme {
        SignalGauge(
            stats = stats,
            scaleMin = SignalScales.SNR_MIN,
            scaleMax = SignalScales.SNR_MAX,
            mode = GaugeMode.COMPLEX,
            lastPacketAtMillis = 1L,
            trackColor = SnrTrack,
            markerColor = SnrMarker,
        )
    }
}
