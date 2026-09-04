package com.cerocoder.meshrelay.ui.graph

import com.cerocoder.meshrelay.stats.SignalScales
import com.cerocoder.meshrelay.stats.model.SignalStats
import kotlin.math.ceil
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
 * **One measurement is one row**, and only the rows inside the scrolled window
 * are drawn - 5000 rows in a single `Canvas` inside a `verticalScroll` would be
 * a layer far past the maximum texture size and fails on real hardware. How tall
 * a row is - `pxPerSample` - is a parameter of every function that needs it and
 * is never a constant in here. Its single caller now passes `POINT_SIZE_PX`
 * itself (ruling 47): the pitch is fixed and equal to the mark size, so
 * consecutive marks tile edge to edge instead of leaving background between
 * them. It stays a `Float` parameter rather than a hardcoded pixel count because
 * requirement 13 says the coefficient may be fractional and this file's own
 * tests exercise it at several values - a fixed 4 px pitch is simply the one
 * value the screen hands in today.
 */
object ChartGeometry {

    /**
     * One row of overscan at each edge.
     *
     * A measurement is drawn as a 4x4 physical-pixel square, not as a
     * mathematical point (ruling 46; 2x2 under the decision 45 that preceded
     * it), so a row whose centre sits just *outside* the viewport can still
     * paint part of itself inside it. A window clipped exactly to the viewport
     * would never draw those rows, and points would pop in and out at the top
     * and bottom edges instead of sliding across them.
     *
     * (This row used to buy the polyline segments that join across the viewport
     * edge. The trace is points now - decision 40 - so that reason is gone; the
     * need for the row is not.)
     *
     * **One row is comfortably enough again, with a whole row of margin.**
     * Ruling 47 fixed the pitch to equal `POINT_SIZE_PX`: at a 4 px pitch the
     * square's 2 px half-extent is half the gap between rows, not the whole of
     * it as ruling 46's floor left it, so a row two row-gaps beyond the
     * viewport's edge is comfortably the first one that cannot reach it - one
     * row of overscan has slack rather than sitting exactly on the boundary.
     *
     * **The rule for a future editor:** one row of overscan is sufficient
     * exactly while the mark's half-extent does not exceed the pitch. That is
     * guaranteed by construction now, because the pitch *is* the mark size
     * (ruling 47) rather than a value that happens to match it - the two cannot
     * drift apart, being the same constant. If a future change ever
     * reintroduces a pitch not tied to `POINT_SIZE_PX` this way, recheck the
     * half-extent against it before assuming one row is still enough.
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
     * The rows a reader can actually see at this offset: every row whose [yOf]
     * falls inside the viewport, and no others.
     *
     * **Deliberately not [visibleRows].** That function answers a different
     * question - which rows must be *drawn* - and its answer is wider, because a
     * point centred just outside the viewport still paints part of its square
     * inside it and so has to be drawn. An overscan row is by definition not
     * displayed.
     *
     * The bounds are `ceil`, not `floor`, and that is what makes this exact
     * rather than approximately right. Row `r` is on screen exactly when
     * `0 <= yOf(r) < viewportPx`, which is `scrollPx <= r * pxPerSample <
     * scrollPx + viewportPx`: the first such row is the smallest integer at or
     * above `scrollPx / pxPerSample`, and the last is the largest integer
     * strictly below `(scrollPx + viewportPx) / pxPerSample`. Flooring both would
     * name a row above the top edge whenever the scroll offset is fractional, and
     * would name the row sitting exactly *on* the bottom edge - the first one off
     * screen - every time the viewport is a whole number of rows tall, which at
     * `pxPerSample == 1f` and an integer plot height is every time.
     */
    fun displayedRows(scrollPx: Float, viewportPx: Float, size: Int, pxPerSample: Float): RowWindow {
        if (size <= 0 || pxPerSample <= 0f || viewportPx <= 0f) return RowWindow(0, -1)
        val first = ceil(scrollPx / pxPerSample).toInt()
        val last = ceil((scrollPx + viewportPx) / pxPerSample).toInt() - 1
        return RowWindow(
            firstRow = first.coerceIn(0, size - 1),
            lastRow = last.coerceIn(0, size - 1),
        )
    }

    /**
     * The two rows the screen's `Time` fields print: the newest measurement on
     * screen and the oldest.
     *
     * [displayedRows] - requirement 8 asks for the topmost and bottom points
     * *displayed*, and only that function answers exactly that - with one
     * guarantee added: never empty while the series is not. On the very first
     * composition the plot has not been measured yet (`onSizeChanged` has not
     * run, so the viewport is still `0`) and [displayedRows] correctly answers
     * "nothing is on screen"; a label built from that window's `lastRow` would
     * ask for row `-1`, and [indexOfRow] would hand the series an index one past
     * its end - an exception on the first frame, before anything has been drawn
     * at all. Before there is a viewport the honest answer is the whole series:
     * its newest row and its oldest.
     */
    fun labelRows(scrollPx: Float, viewportPx: Float, size: Int, pxPerSample: Float): RowWindow {
        if (size <= 0) return RowWindow(0, -1)
        val window = displayedRows(scrollPx, viewportPx, size, pxPerSample)
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
     *
     * **Dormant again, and that is fine.** Ruling 35 added this against a
     * `pxPerSample` the screen fixed at a power of two, where the round trip
     * happened to be exact and no test but the one naming a fractional scale
     * could tell it apart from nothing. Ruling 44's fitted scale made it
     * genuinely load-bearing for a while - an arbitrary `Float` such as 15.94,
     * where the round trip did not cancel without it. Ruling 47 fixed the pitch
     * again, to `POINT_SIZE_PX`, itself a power of two, so the round trip is
     * exact once more and this constant does no work at that value. Do not
     * remove it as dead defence: it is what would keep the crosshair correct if
     * an arbitrary pitch is ever reintroduced, exactly as it sat dormant between
     * ruling 35 and ruling 44 the first time.
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
     * [rowAt], brought inside the series - the clamp its own documentation says
     * the caller owes it.
     *
     * It lives here rather than at the one call site for the reason
     * [clampScroll] does: `size - 1` is arithmetic, and a `@Composable` in this
     * project does none. A touch below the last measurement resolves to the last
     * measurement, and the crosshair is then drawn at [yOf] of *that* row, so the
     * rule and the numbers beside it always describe the same measurement.
     *
     * Row `0` for an empty series, which no caller reaches: the screen renders
     * its empty state instead of a crosshair. Answering `0` rather than `-1`
     * keeps a defect from turning into a negative index.
     */
    fun rowAtClamped(y: Float, scrollPx: Float, pxPerSample: Float, size: Int): Int =
        if (size <= 0) 0 else rowAt(y, scrollPx, pxPerSample).coerceIn(0, size - 1)

    /** Which side of the plotted range a value fell outside, if any. */
    enum class OffScale { NONE, LOW, HIGH }

    /** Where a candidate's line is drawn, and whether the value fits on screen. */
    data class CandidateLine(val fraction: Float, val offScale: OffScale)

    /**
     * Where a value sits along the track, as a fraction in `0f..1f`.
     *
     * [SignalScales.fraction], the same function the gauges use, so the chart and
     * the bars above it cannot drift apart.
     */
    fun xOf(value: Float, min: Float, max: Float): Float = SignalScales.fraction(value, min, max)

    /**
     * The candidate line's horizontal position.
     *
     * [xOf] cannot answer this on its own. It delegates to
     * [com.cerocoder.meshrelay.stats.SignalScales.fraction], which ends in
     * `coerceIn(0f, 1f)`, so a value far below the range returns exactly the `0f`
     * a genuine minimum returns. Drawn that way the line would sit on the edge
     * with nothing saying it does not belong there - and "far off-scale" is the
     * most decisive verdict this screen can offer, so hiding it is the one outcome
     * worth designing against.
     *
     * A range with no span reports [OffScale.NONE]: `fraction` gives up and
     * returns `0f` there, and claiming the value was off-scale would be inventing
     * information the empty range does not carry.
     */
    fun candidateLine(value: Float, min: Float, max: Float): CandidateLine {
        val fraction = xOf(value, min, max)
        val offScale = when {
            max - min <= 0f -> OffScale.NONE
            value < min -> OffScale.LOW
            value > max -> OffScale.HIGH
            else -> OffScale.NONE
        }
        return CandidateLine(fraction = fraction, offScale = offScale)
    }

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
