package com.cerocoder.meshrelay.ui.graph

import com.cerocoder.meshrelay.stats.SignalScales
import com.cerocoder.meshrelay.stats.model.SignalStats
import kotlin.math.floor

/**
 * An inclusive range of rows to draw, or empty when there is nothing to draw.
 */
data class RowWindow(val firstRow: Int, val lastRow: Int) {
    val isEmpty: Boolean get() = lastRow < firstRow
}

/** One metric's horizontal scale, left edge to right edge. */
data class ScaleRange(val min: Float, val max: Float)

/**
 * Pure geometry for [SignalChart], in the shape [com.cerocoder.meshrelay.ui.common.GaugeGeometry]
 * established: no Compose types in any signature, every number the canvas needs,
 * and its own unit tests. This project's rule is that no `@Composable` contains
 * arithmetic; this object is where the chart's arithmetic went.
 *
 * **Rows run newest first.** Row 0 is the most recent measurement and sits at the
 * top of the viewport when `scrollPx` is 0, which is requirement 7. The series
 * itself is stored oldest first, its natural append order; [indexOfRow] is the
 * one place that inversion happens.
 *
 * **One measurement is one pixel row**, and only the rows inside the scrolled
 * window are drawn - 5000 rows in a single `Canvas` inside a `verticalScroll`
 * would be a layer far past the maximum texture size and fails on real hardware.
 * [pxPerSample] is a parameter throughout and is fixed at `1f` by its single
 * caller; requirement 13 asks for exactly that, so a zoom control (2x, 4x, or a
 * fraction such as 0.1) becomes a value to pass rather than a restructuring. It
 * may be fractional, which is why it is a `Float`.
 */
object ChartGeometry {

    /**
     * One row of overscan at each edge.
     *
     * A polyline segment joins two consecutive rows, so a window clipped exactly
     * to the viewport would be missing the segment that leaves the top edge and
     * the one that enters the bottom - the line would appear to stop short of both
     * edges and to jump when scrolled.
     */
    const val OVERSCAN_ROWS = 1

    /** The series index this row draws. Storage is oldest-first; rows are newest-first. */
    fun indexOfRow(row: Int, size: Int): Int = size - 1 - row

    /** The height the whole series would occupy if every row were drawn at once. */
    fun contentHeightPx(size: Int, pxPerSample: Float): Float = size * pxPerSample

    /** How far the chart can be scrolled before the oldest measurement reaches the bottom. */
    fun maxScrollPx(size: Int, viewportPx: Float, pxPerSample: Float): Float =
        (contentHeightPx(size, pxPerSample) - viewportPx).coerceAtLeast(0f)

    /** The rows that need drawing at this offset, clamped to the series and overscanned. */
    fun visibleRows(scrollPx: Float, viewportPx: Float, size: Int, pxPerSample: Float): RowWindow {
        if (size <= 0 || pxPerSample <= 0f || viewportPx <= 0f) return RowWindow(0, -1)
        val first = floor(scrollPx / pxPerSample).toInt() - OVERSCAN_ROWS
        val last = floor((scrollPx + viewportPx) / pxPerSample).toInt() + OVERSCAN_ROWS
        return RowWindow(
            firstRow = first.coerceIn(0, size - 1),
            lastRow = last.coerceIn(0, size - 1),
        )
    }

    /**
     * The two rows the screen's `Time` fields print: the newest measurement on
     * screen and the oldest.
     *
     * [visibleRows] with one guarantee added - never empty while the series is
     * not. On the very first composition the plot has not been measured yet
     * (`onSizeChanged` has not run, so the viewport is still `0`) and
     * [visibleRows] correctly answers "there is nothing to draw"; a label built
     * from that window's `lastRow` would ask for row `-1`, and [indexOfRow] would
     * hand the series an index one past its end - an exception on the first
     * frame, before anything has been drawn at all. Before there is a viewport
     * the honest answer is the whole series: its newest row and its oldest.
     */
    fun labelRows(scrollPx: Float, viewportPx: Float, size: Int, pxPerSample: Float): RowWindow {
        if (size <= 0) return RowWindow(0, -1)
        val window = visibleRows(scrollPx, viewportPx, size, pxPerSample)
        return if (window.isEmpty) RowWindow(0, size - 1) else window
    }

    /** Where this row sits in the viewport, in pixels from its top edge. */
    fun yOf(row: Int, scrollPx: Float, pxPerSample: Float): Float = row * pxPerSample - scrollPx

    /**
     * Absorbs the float cancellation in [yOf]'s subtract-then-add round trip.
     *
     * `yOf` computes `row * pxPerSample - scrollPx` and `rowAt` undoes it by
     * adding `scrollPx` back; in IEEE-754 those two operations do not cancel
     * exactly unless `pxPerSample` is a power of two. The relative error is
     * about 1e-7 of `row`, so at the 5000-sample ceiling
     * (`SignalSeriesBuffer.MAX_SAMPLES`) it reaches ~5e-4 of a row. A thousandth
     * of a row covers that with a factor of two in hand, and is itself far below
     * one pixel at any scale this chart draws.
     */
    private const val ROW_EPSILON = 1e-3f

    /**
     * Which row a touch at [y] landed on. Not clamped: the caller knows the size
     * and clamps, and then draws the crosshair at [yOf] of the clamped row - so
     * the line and the numbers beside it always describe the same measurement,
     * even when the touch was below the last one.
     *
     * Adds [ROW_EPSILON] before flooring. Without it, this is not an exact
     * inverse of [yOf] at a fractional [pxPerSample]: `yOf` computes
     * `row * pxPerSample - scrollPx`, and undoing that by adding `scrollPx` back
     * and dividing does not cancel exactly in IEEE-754 unless `pxPerSample` is a
     * power of two, so the quotient can land a hair under the intended row and
     * floor to one row short of it.
     */
    fun rowAt(y: Float, scrollPx: Float, pxPerSample: Float): Int =
        floor((y + scrollPx) / pxPerSample + ROW_EPSILON).toInt()

    /**
     * Where a value sits along the track, as a fraction in `0f..1f`.
     *
     * [SignalScales.fraction], the same function the gauges use, so the chart and
     * the bars above it cannot drift apart.
     */
    fun xOf(value: Float, min: Float, max: Float): Float = SignalScales.fraction(value, min, max)

    /**
     * The horizontal range for one metric.
     *
     * Auto scale off: the fixed range every other screen uses. On: the metric's
     * whole-session minimum and maximum - the same two figures the bars print
     * beside themselves, so bars and plot share one range and neither can
     * misrepresent the other. Every retained sample necessarily falls inside it,
     * the retained samples being a subset of the session those statistics cover.
     *
     * A degenerate span - one sample, or a perfectly flat relay - falls back to
     * the fixed range: [SignalScales.fraction] returns 0 for a zero span, which
     * would stack every point against the left edge and read as a dead link.
     */
    fun scaleRange(stats: SignalStats, autoScale: Boolean, fixedMin: Float, fixedMax: Float): ScaleRange {
        if (!autoScale || !stats.hasData) return ScaleRange(fixedMin, fixedMax)
        if (stats.maxVal - stats.minVal <= 0f) return ScaleRange(fixedMin, fixedMax)
        return ScaleRange(stats.minVal, stats.maxVal)
    }

    /**
     * Where the scroll offset goes when [appended] measurements arrive.
     *
     * At the top, it stays at the top - which is what a live chart should do.
     * Scrolled down, it advances by exactly the height the new rows added, so the
     * measurement under the reader's eye does not move: data arriving must never
     * yank the view.
     *
     * The result is not clamped. Once the ring buffer saturates, `size` stops
     * growing while measurements keep arriving, so the caller clamps to
     * [maxScrollPx] with the viewport height it alone knows.
     */
    fun anchorAfterAppend(scrollPx: Float, appended: Long, pxPerSample: Float): Float =
        if (scrollPx <= 0f) 0f else scrollPx + appended * pxPerSample

    /**
     * [scrollPx] brought inside the scrollable range.
     *
     * Lives here rather than in the screen for the reason every other number in
     * this file does: a clamp is geometry, and a `@Composable` in this project
     * does no arithmetic. It also means one clamped value can be derived once and
     * shared by every consumer, so no caller can forget to apply it.
     */
    fun clampScroll(scrollPx: Float, size: Int, viewportPx: Float, pxPerSample: Float): Float =
        scrollPx.coerceIn(0f, maxScrollPx(size, viewportPx, pxPerSample))

    /**
     * The scrollbar thumb's height as a fraction of its track: how much of the
     * whole series is on screen at once.
     *
     * `1` when there is nothing to scroll. The one caller draws no scrollbar at
     * all in that case, so that value is a defensive answer rather than one
     * anything renders - but it is the right one, a thumb filling its track being
     * exactly what "all of it is already visible" looks like.
     */
    fun thumbHeightFraction(viewportPx: Float, contentPx: Float): Float =
        if (contentPx <= 0f) 1f else (viewportPx / contentPx).coerceIn(0f, 1f)

    /**
     * The thumb's top edge as a fraction of its track.
     *
     * Clamped so the thumb's *bottom* cannot leave the track: at [maxScrollPx]
     * the top lands on `1 - thumbHeightFraction` exactly - the thumb flush
     * against the bottom of the track, which is the reading a chart scrolled to
     * its oldest measurement should give.
     */
    fun thumbTopFraction(scrollPx: Float, viewportPx: Float, contentPx: Float): Float {
        if (contentPx <= 0f) return 0f
        val maxTop = (1f - thumbHeightFraction(viewportPx, contentPx)).coerceAtLeast(0f)
        return (scrollPx / contentPx).coerceIn(0f, maxTop)
    }

    /**
     * A drag of [trackDeltaPx] down a track [trackPx] tall, as a scroll in
     * content pixels.
     *
     * The exact inverse of [thumbTopFraction]'s mapping, and exactly so on
     * purpose: dragging the thumb from the top of the track until its bottom
     * meets the bottom of the track travels `trackPx * (1 - viewportPx /
     * contentPx)` track pixels, which this converts to `contentPx - viewportPx` -
     * [maxScrollPx] precisely. The gesture therefore reaches the oldest
     * measurement exactly as the thumb reaches the end of its track, and no
     * sooner or later.
     */
    fun contentDeltaFor(trackDeltaPx: Float, trackPx: Float, contentPx: Float): Float =
        if (trackPx <= 0f) 0f else trackDeltaPx * contentPx / trackPx

    /**
     * Where an overlay anchored to the crosshair starts, kept inside the plot.
     *
     * The crosshair's label block hangs [aboveAnchorPx] above the rule, and its
     * globe half the globe's own height above it. Near either edge of the plot an
     * unclamped offset would put the block over the `Time` field above the plot,
     * or past the bottom of it - Compose clips neither. Clamping degrades to "as
     * close to the rule as the plot allows", which is the readable failure: the
     * labels stay legible and stay inside the chart, and only their alignment
     * with the rule is lost, in the two places the rule is at an edge anyway.
     */
    fun overlayTopPx(anchorY: Float, aboveAnchorPx: Float, overlayHeightPx: Float, viewportPx: Float): Float =
        (anchorY - aboveAnchorPx).coerceIn(0f, (viewportPx - overlayHeightPx).coerceAtLeast(0f))
}
