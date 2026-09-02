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
 * is never a constant in here. Its single caller no longer passes a constant
 * either: it passes [fitPxPerSample], which fits the retained series to the plot
 * until the fit would crush the points together, and holds a floor after that
 * (ruling 44, field issue F-7). It may be fractional, and after that change it
 * usually is, which is why it is a `Float`.
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
     * **One row is exactly enough now, not comfortably enough.** The square's
     * half-extent is 2 px in each direction - since ruling 46, equal to, not
     * less than, the screen's floor of `2f` pixels per sample
     * (`MIN_PX_PER_SAMPLE`), the smallest gap this chart ever puts between two
     * rows and so the worst case. At that floor, a row centred exactly one
     * row-gap beyond the viewport's edge reaches precisely to that edge and no
     * further; a row two row-gaps beyond never reaches it. So one row of
     * overscan still covers every row that could paint a visible pixel, but the
     * margin ruling 41 gave this constant - "a full pixel to spare" - is gone:
     * the half-extent now equals the floor exactly, with nothing left over.
     * **If `POINT_SIZE_PX` is raised again past 4, this constant must go to
     * 2** - a half-extent that exceeds the floor is exactly the case one row of
     * overscan no longer covers.
     */
    const val OVERSCAN_ROWS = 1

    /** The series index this row draws. Storage is oldest-first; rows are newest-first. */
    fun indexOfRow(row: Int, size: Int): Int = size - 1 - row

    /**
     * How tall one row should be so that [size] rows exactly fill a plot
     * [viewportPx] tall - but never shorter than [minPxPerSample].
     *
     * **The scale is derived, not fixed** (ruling 44, closing field issue F-7).
     * A fixed row height means a young series is a thin band at the top of a
     * mostly empty plot: at `1f` on a 450 dpi phone, 69 measurements filled 87 of
     * the plot's 1100 pixels - eight per cent - and reading as broken rather than
     * as sparse. Doubling the constant to `2f` halved the problem and did not
     * remove it. Fitting removes it: while the series is short the plot is full,
     * and it stays full as the series grows.
     *
     * **Fit while it fits, then scroll.** The two regimes are worth stating
     * separately, because each is what the other is not:
     *
     * - While the retained series is shorter than the plot is tall, the fit wins
     *   and the whole series is on screen. [maxScrollPx] is 0, so there is
     *   nothing to scroll and the scrollbar correctly has no travel. That is not
     *   a regression against the fixed scale that scrolled from the first
     *   measurement; it is the fix. Nothing is hidden, so nothing needs revealing.
     * - Past that point [minPxPerSample] takes over and the chart behaves exactly
     *   as a fixed scale does: content grows past the viewport, scrolling
     *   resumes, and a saturated 5000-sample buffer is 5000 * [minPxPerSample]
     *   pixels of content. The changeover is at `viewportPx / minPxPerSample`
     *   measurements - on the owner's 1100 px plot at a `2f` floor, 550 of them.
     *
     * **The floor is what stops the fit from being absurd.** Without it two
     * measurements would be a plot-tall staircase, and - the reason it is `2f`
     * rather than something smaller - a long series would be squeezed further
     * below the size of the mark the chart draws with. It no longer keeps
     * consecutive dots apart even at `2f`: since ruling 46 the mark is a 4x4
     * physical-pixel square, whose 2 px half-extent equals this floor exactly,
     * so rows at the floor touch and, once fitting gives way to it past the
     * changeover, overlap by up to 2 px - a long session's trace reads as a band
     * rather than as the discrete dots ruling 40 introduced. That is accepted
     * (ruling 46), not fixed by raising this floor, which would trade against
     * how much history fits on screen before the chart scrolls.
     *
     * **This is what makes `ROW_EPSILON` load-bearing.** A fitted scale is an
     * arbitrary `Float` - 15.94 for 69 measurements in an 1100 px plot - and
     * [yOf]'s subtract-then-add round trip does *not* cancel exactly at such a
     * value, where at a power of two like the old fixed `2f` it did. The epsilon
     * (ruling 35) was dormant then and is the only thing keeping the crosshair's
     * numbers on the row its rule is drawn at now. It must not be simplified away.
     *
     * A [viewportPx] of `0` is the first composition, before `onSizeChanged` has
     * measured the plot; the answer there must be the floor and not `0`, which
     * would make every row zero-tall and stop [visibleRows] drawing anything at
     * all. The clamp gives that on its own - a fit of nothing is `0`, and `0` is
     * below the floor - so it needs no branch of its own. The one branch is for a
     * [size] of `0`, where there is no fit to divide at all.
     */
    fun fitPxPerSample(size: Int, viewportPx: Float, minPxPerSample: Float): Float =
        if (size <= 0) minPxPerSample else (viewportPx / size).coerceAtLeast(minPxPerSample)

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
     * **It is doing work now, and it was not before.** Ruling 35 added it against
     * a `pxPerSample` the screen fixed at a power of two, where the round trip
     * happened to be exact and no test but the one naming a fractional scale
     * could tell it apart from nothing. Since ruling 44 the scale is
     * [fitPxPerSample]'s answer - an arbitrary `Float` such as 15.94 - and the
     * round trip is genuinely inexact. Do not remove this as dead defence: it is
     * the only thing keeping the crosshair's numbers on the row its rule is
     * drawn at.
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
