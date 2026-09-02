package com.cerocoder.meshrelay.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.cerocoder.meshrelay.stats.model.SignalSeries

/**
 * The plot itself: two clouds of points, virtualised, drawn newest-at-the-top.
 *
 * **Points, not lines - decision 40, overriding requirement 11.** One dot per
 * measurement per metric, never a stroke between two of them. With Auto scale on,
 * each metric is stretched across its own observed minimum and maximum, so both
 * traces span the full width of the plot and one polyline paints straight over
 * the other - the covered metric disappears entirely. Discrete points interleave
 * visibly where the two cross; a stroked path cannot. The colour half of
 * requirement 11 still stands: each metric keeps its own colour, which is what
 * lets this chart do without a legend.
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
    pointRadiusPx: Float,
    onViewportHeight: (Float) -> Unit,
    onCrosshairAt: (Float) -> Unit,
    onCrosshairCleared: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { onViewportHeight(it.height.toFloat()) }
            // Two handlers, and the order is load-bearing. A later pointerInput
            // in a chain is the *inner* one, and the Main pass runs inner first -
            // so the tap handler below sees each down before the drag handler and
            // can consume it, while the drag handler's `requireUnconsumed = false`
            // means that consumption does not stop a drag from starting.
            .pointerInput(Unit) {
                // The drag half. Consuming the movement is what stops the
                // `scrollable` around this canvas from acting on the same pointer:
                // on this screen a drag moves the crosshair and the scrollbar
                // scrolls, and only one of the two can win a gesture.
                //
                // A consumed movement also cancels the tap handler's gesture, so a
                // drag can never be mistaken for a tap and can never clear the
                // crosshair.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    drag(down.id) { change ->
                        onCrosshairAt(change.position.y)
                        change.consume()
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    // `onPress`, not `onTap`: with an `onDoubleTap` supplied,
                    // `onTap` is withheld until the double-tap timeout expires,
                    // which would put a visible delay on every placement of the
                    // crosshair. `onPress` fires on finger-down.
                    //
                    // The cost, accepted deliberately: a double tap places the
                    // crosshair on its first press and again on its second before
                    // clearing it, so it flickers once on the way out. That is the
                    // price of immediate placement - do not "fix" it by moving to
                    // `onTap`.
                    onPress = { onCrosshairAt(it.y) },
                    onDoubleTap = { onCrosshairCleared() },
                )
            },
    ) {
        val window = ChartGeometry.visibleRows(scrollPx, size.height, series.size, pxPerSample)
        if (window.isEmpty) return@Canvas

        drawMetric(series, window, scrollPx, pxPerSample, rssiRange, rssiColor, pointRadiusPx) { series.rssi(it) }
        drawMetric(series, window, scrollPx, pxPerSample, snrRange, snrColor, pointRadiusPx) { series.snr(it) }
    }
}

/**
 * One metric's points over [window]: one filled circle per row, always.
 *
 * **The single-row case is gone because it became the general case.** The
 * polyline this replaced needed a special branch for a one-row window - a `Path`
 * with one `moveTo` and no `lineTo` strokes nothing at all, so "one measurement"
 * rendered as an empty chart - and needed the caller to hand it a stroke width
 * that a one-row window then had to reinterpret as a radius. A loop of circles
 * needs neither: one measurement is one circle, five thousand are five thousand,
 * and there is no shape a window of any length can degenerate into.
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
    pointRadiusPx: Float,
    valueOf: (index: Int) -> Float,
) {
    val widthPx = size.width

    for (row in window.firstRow..window.lastRow) {
        drawCircle(
            color = color,
            radius = pointRadiusPx,
            center = Offset(
                x = xOfRow(series, row, range, widthPx, valueOf),
                y = ChartGeometry.yOf(row, scrollPx, pxPerSample),
            ),
        )
    }
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
