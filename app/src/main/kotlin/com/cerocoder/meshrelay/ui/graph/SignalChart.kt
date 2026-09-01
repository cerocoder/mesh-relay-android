package com.cerocoder.meshrelay.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.cerocoder.meshrelay.stats.model.SignalSeries

/**
 * The plot itself: two polylines, virtualised, drawn newest-at-the-top.
 *
 * **Virtualised, and that is the design.** A `Canvas` holding all 5000 rows
 * inside a `verticalScroll` would be a layer far past the maximum texture size
 * and fails on real hardware; this one is exactly the size of the viewport and
 * draws only [ChartGeometry.visibleRows]. Owning the scroll offset rather than
 * delegating it to a scroll modifier is also what makes the custom scrollbar and
 * the crosshair possible at all.
 *
 * **No arithmetic lives here.** Every position comes from [ChartGeometry], which
 * has its own tests; this function multiplies fractions by a measured width and
 * does nothing else - the same division of labour
 * [com.cerocoder.meshrelay.ui.common.SignalGauge] and `GaugeGeometry` already
 * have.
 *
 * A touch anywhere on the plot places the crosshair, and a drag moves it: the
 * scrollbar beside it is what scrolls. That split is deliberate - a
 * `Modifier.scrollable` under the same touch drag would fight the crosshair, and
 * only one of the two can win a gesture. The screen keeps a `Modifier.scrollable`
 * *outside* this composable all the same, so a mouse wheel or a hardware scroll
 * still moves the chart; the drag consumed below is what keeps that modifier out
 * of a touch gesture this one has already claimed.
 */
@Composable
fun SignalChart(
    series: SignalSeries,
    scrollPx: Float,
    pxPerSample: Float,
    rssiRange: ScaleRange,
    snrRange: ScaleRange,
    rssiColor: Color,
    snrColor: Color,
    strokeWidthPx: Float,
    onViewportHeight: (Float) -> Unit,
    onCrosshairAt: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { onViewportHeight(it.height.toFloat()) }
            .pointerInput(Unit) {
                // One gesture handler for both the touch and the drag that
                // follows it, so the two cannot disagree about which is in
                // charge. The drag is consumed, which is what stops the
                // scrollable around it from also acting on the same pointer.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onCrosshairAt(down.position.y)
                    drag(down.id) { change ->
                        onCrosshairAt(change.position.y)
                        change.consume()
                    }
                }
            },
    ) {
        val window = ChartGeometry.visibleRows(scrollPx, size.height, series.size, pxPerSample)
        if (window.isEmpty) return@Canvas

        drawMetric(series, window, scrollPx, pxPerSample, rssiRange, rssiColor, strokeWidthPx) { series.rssi(it) }
        drawMetric(series, window, scrollPx, pxPerSample, snrRange, snrColor, strokeWidthPx) { series.snr(it) }
    }
}

/**
 * One metric's polyline over [window].
 *
 * A single-row window draws a point rather than a path: a `Path` with one
 * `moveTo` and no `lineTo` strokes nothing at all, which is how "one measurement"
 * would otherwise render as an empty chart.
 *
 * Not `inline`, though it takes a lambda and runs per frame: an inline function
 * cannot hand its lambda parameter to a nested local function without
 * `crossinline`, and two closure allocations a frame is not worth the modifier
 * pair it would take to keep that legal.
 */
private fun DrawScope.drawMetric(
    series: SignalSeries,
    window: RowWindow,
    scrollPx: Float,
    pxPerSample: Float,
    range: ScaleRange,
    color: Color,
    strokeWidthPx: Float,
    valueOf: (index: Int) -> Float,
) {
    val widthPx = size.width

    if (window.firstRow == window.lastRow) {
        drawCircle(
            color = color,
            radius = strokeWidthPx,
            center = Offset(
                x = xOfRow(series, window.firstRow, range, widthPx, valueOf),
                y = ChartGeometry.yOf(window.firstRow, scrollPx, pxPerSample),
            ),
        )
        return
    }

    val path = Path()
    for (row in window.firstRow..window.lastRow) {
        val x = xOfRow(series, row, range, widthPx, valueOf)
        val y = ChartGeometry.yOf(row, scrollPx, pxPerSample)
        if (row == window.firstRow) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color = color, style = Stroke(width = strokeWidthPx))
}

/**
 * One row's horizontal position across a plot [widthPx] wide.
 *
 * [ChartGeometry.indexOfRow] is the only inversion from newest-at-the-top rows
 * to the series' oldest-first storage, and this is the only place the chart
 * indexes the series at all - so a row can never reach [SignalSeries] without
 * passing through it.
 */
private fun xOfRow(
    series: SignalSeries,
    row: Int,
    range: ScaleRange,
    widthPx: Float,
    valueOf: (index: Int) -> Float,
): Float {
    val index = ChartGeometry.indexOfRow(row, series.size)
    return ChartGeometry.xOf(valueOf(index), range.min, range.max) * widthPx
}
