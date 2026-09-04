package com.cerocoder.meshrelay.ui.graph

import com.cerocoder.meshrelay.stats.SignalScales
import com.cerocoder.meshrelay.stats.model.SignalStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Row 0 is the newest measurement and sits at the top when the chart is scrolled
 * to the start; the series itself is stored oldest first. Half of what is tested
 * here is that one inversion, done in one place.
 *
 * `pxPerSample` is 1 in every test but the ones that name it. It is passed
 * explicitly everywhere, never taken from a constant, so the fixed pitch ruling
 * 47 introduced changes nothing here: `pxPerSample` is `POINT_SIZE_PX`, a value
 * the *screen* now hands these functions directly rather than computing it.
 */
class ChartGeometryTest {

    @Test
    fun `the visible window is the scrolled rows plus one of overscan each side`() {
        // The overscan is not cosmetic: a measurement is drawn as a disc, so a
        // row centred just beyond an edge still paints part of itself inside the
        // viewport. Without a row beyond each edge those points would pop in and
        // out at the top and bottom rather than sliding across them.
        val window = ChartGeometry.visibleRows(scrollPx = 100f, viewportPx = 50f, size = 1000, pxPerSample = 1f)
        assertEquals(99, window.firstRow)
        assertEquals(151, window.lastRow)
    }

    @Test
    fun `the window is clamped at both ends of the series`() {
        val top = ChartGeometry.visibleRows(scrollPx = 0f, viewportPx = 50f, size = 1000, pxPerSample = 1f)
        assertEquals(0, top.firstRow)

        val bottom = ChartGeometry.visibleRows(scrollPx = 980f, viewportPx = 50f, size = 1000, pxPerSample = 1f)
        assertEquals(999, bottom.lastRow)
    }

    @Test
    fun `a viewport taller than the series shows all of it and no more`() {
        val window = ChartGeometry.visibleRows(scrollPx = 0f, viewportPx = 800f, size = 3, pxPerSample = 1f)
        assertEquals(0, window.firstRow)
        assertEquals(2, window.lastRow)
    }

    @Test
    fun `an empty series has an empty window`() {
        // The canvas must draw nothing rather than index row 0 of nothing.
        assertTrue(ChartGeometry.visibleRows(0f, 800f, size = 0, pxPerSample = 1f).isEmpty)
    }

    @Test
    fun `y and row are inverses of each other`() {
        // This is what turns a touch into a measurement. If they drift, the
        // crosshair reports one row's numbers at another row's height.
        for (row in listOf(0, 1, 37, 999)) {
            val y = ChartGeometry.yOf(row, scrollPx = 120f, pxPerSample = 1f)
            assertEquals(row, ChartGeometry.rowAt(y, scrollPx = 120f, pxPerSample = 1f))
        }
    }

    @Test
    fun `the newest measurement is at the top when scrolled to the start`() {
        assertEquals(0f, ChartGeometry.yOf(0, scrollPx = 0f, pxPerSample = 1f), 0.0001f)
        // Pins the sign of the axis: a flipped implementation
        // (`scrollPx - row * pxPerSample`) also passes the assertion above, since
        // 0 - 0 is 0 either way. A non-zero scroll is what tells them apart.
        assertEquals(-120f, ChartGeometry.yOf(0, scrollPx = 120f, pxPerSample = 1f), 0.0001f)
    }

    @Test
    fun `a row is the series index counted from the end`() {
        // Storage is oldest-first; the chart is newest-at-the-top. One function
        // owns the inversion.
        assertEquals(9, ChartGeometry.indexOfRow(row = 0, size = 10))
        assertEquals(0, ChartGeometry.indexOfRow(row = 9, size = 10))
    }

    @Test
    fun `x is a fraction of the track, the same fraction the bars use`() {
        assertEquals(
            SignalScales.fraction(-92f, SignalScales.RSSI_MIN, SignalScales.RSSI_MAX),
            ChartGeometry.xOf(-92f, SignalScales.RSSI_MIN, SignalScales.RSSI_MAX),
            0.0001f,
        )
        assertEquals(0f, ChartGeometry.xOf(-200f, SignalScales.RSSI_MIN, SignalScales.RSSI_MAX), 0.0001f)
        assertEquals(1f, ChartGeometry.xOf(0f, SignalScales.RSSI_MIN, SignalScales.RSSI_MAX), 0.0001f)
    }

    @Test
    fun `auto scale off is the fixed range every other screen uses`() {
        val stats = SignalStats.EMPTY.plus(-100f).plus(-80f)
        val range = ChartGeometry.scaleRange(stats, autoScale = false, SignalScales.RSSI_MIN, SignalScales.RSSI_MAX)
        assertEquals(SignalScales.RSSI_MIN, range.min, 0.0001f)
        assertEquals(SignalScales.RSSI_MAX, range.max, 0.0001f)
    }

    @Test
    fun `auto scale on is the observed span, the same figures the bars print`() {
        val stats = SignalStats.EMPTY.plus(-100f).plus(-80f).plus(-92f)
        val range = ChartGeometry.scaleRange(stats, autoScale = true, SignalScales.RSSI_MIN, SignalScales.RSSI_MAX)
        assertEquals(-100f, range.min, 0.0001f)
        assertEquals(-80f, range.max, 0.0001f)
    }

    @Test
    fun `a degenerate span falls back to the fixed range`() {
        // One sample, or a perfectly flat relay. Dividing by a zero span would put
        // every point hard against the left edge and read as a dead link.
        val flat = SignalStats.EMPTY.plus(-92f)
        val range = ChartGeometry.scaleRange(flat, autoScale = true, SignalScales.RSSI_MIN, SignalScales.RSSI_MAX)
        assertEquals(SignalScales.RSSI_MIN, range.min, 0.0001f)
        assertEquals(SignalScales.RSSI_MAX, range.max, 0.0001f)
    }

    @Test
    fun `no data at all falls back to the fixed range`() {
        // This does not isolate the `!stats.hasData` guard from the degenerate-span
        // guard below it: SignalStats.EMPTY has minVal = +Infinity and
        // maxVal = -Infinity, so `maxVal - minVal <= 0f` is already true and would
        // catch this case even if the hasData check were deleted. There is no way
        // to isolate them through the public API - the only mutator is `plus`,
        // which always increments count, so a SignalStats with hasData == false
        // and a positive min/max span cannot be constructed. Both guards are
        // exercised together here; only the degenerate-span guard is uniquely
        // exercised (by `a degenerate span falls back to the fixed range`, above).
        val range = ChartGeometry.scaleRange(SignalStats.EMPTY, autoScale = true, SignalScales.SNR_MIN, SignalScales.SNR_MAX)
        assertEquals(SignalScales.SNR_MIN, range.min, 0.0001f)
        assertEquals(SignalScales.SNR_MAX, range.max, 0.0001f)
    }

    @Test
    fun `at the top, new measurements do not move the view`() {
        // A live chart scrolled to the newest stays at the newest.
        assertEquals(0f, ChartGeometry.anchorAfterAppend(0f, appended = 5, pxPerSample = 1f), 0.0001f)
    }

    @Test
    fun `scrolled down, the measurement under the reader's eye does not move`() {
        // Data arriving must never yank the view.
        assertEquals(305f, ChartGeometry.anchorAfterAppend(300f, appended = 5, pxPerSample = 1f), 0.0001f)
    }

    @Test
    fun `the scroll cannot go past the end of a saturated series`() {
        // Once the ring is full, size stops growing while measurements keep
        // arriving - so the anchor keeps advancing and something has to clamp it.
        val max = ChartGeometry.maxScrollPx(size = 5000, viewportPx = 1200f, pxPerSample = 1f)
        assertEquals(3800f, max, 0.0001f)
        assertEquals(0f, ChartGeometry.maxScrollPx(size = 3, viewportPx = 1200f, pxPerSample = 1f), 0.0001f)
    }

    @Test
    fun `a scale coefficient other than one moves every number with it`() {
        // Requirement 13: the parameter is present in the geometry and absent from
        // the interface. It must actually work when something eventually sets it.
        assertEquals(8f, ChartGeometry.yOf(row = 2, scrollPx = 0f, pxPerSample = 4f), 0.0001f)
        assertEquals(2, ChartGeometry.rowAt(y = 8f, scrollPx = 0f, pxPerSample = 4f))
        assertEquals(20f, ChartGeometry.anchorAfterAppend(10f, appended = 100, pxPerSample = 0.1f), 0.0001f)
    }

    @Test
    fun `y and row are inverses at a fractional scale coefficient too`() {
        // Requirement 13 says the coefficient may be a fraction, and 0.1 is the
        // example the spec gives. A non-zero scroll is essential: at scrollPx == 0
        // there is nothing to cancel, which is why the existing coefficient test
        // did not catch this.
        val p = 0.1f
        val scroll = 0.3f
        for (row in listOf(0, 1, 13, 26, 499, 4999)) {
            assertEquals(row, ChartGeometry.rowAt(ChartGeometry.yOf(row, scroll, p), scroll, p))
        }
    }

    @Test
    fun `the clamp keeps the scroll inside the series`() {
        // Both ends. The upper one matters most: once the ring buffer saturates,
        // anchorAfterAppend keeps advancing the offset while `size` has stopped
        // growing, so without this the view would scroll past the oldest measurement
        // into empty space.
        assertEquals(0f, ChartGeometry.clampScroll(-50f, size = 5000, viewportPx = 1200f, pxPerSample = 1f), 0.0001f)
        assertEquals(3800f, ChartGeometry.clampScroll(9_999f, size = 5000, viewportPx = 1200f, pxPerSample = 1f), 0.0001f)
        assertEquals(500f, ChartGeometry.clampScroll(500f, size = 5000, viewportPx = 1200f, pxPerSample = 1f), 0.0001f)
        // A series shorter than the viewport cannot scroll at all.
        assertEquals(0f, ChartGeometry.clampScroll(120f, size = 3, viewportPx = 1200f, pxPerSample = 1f), 0.0001f)
    }

    @Test
    fun `a touch past the last measurement resolves to the last measurement`() {
        // The crosshair is then drawn at yOf of this row, not at the touch's own
        // height, so the rule and the numbers beside it cannot describe different
        // measurements.
        assertEquals(299, ChartGeometry.rowAtClamped(y = 9_999f, scrollPx = 0f, pxPerSample = 1f, size = 300))
        assertEquals(0, ChartGeometry.rowAtClamped(y = -40f, scrollPx = 0f, pxPerSample = 1f, size = 300))
        assertEquals(120, ChartGeometry.rowAtClamped(y = 20f, scrollPx = 100f, pxPerSample = 1f, size = 300))
        // An empty series answers 0, not -1: no caller reaches it, and a defect
        // must not become a negative index into the series.
        assertEquals(0, ChartGeometry.rowAtClamped(y = 40f, scrollPx = 0f, pxPerSample = 1f, size = 0))
    }

    @Test
    fun `the label rows name displayed measurements, not overscanned ones`() {
        // Requirement 8 asks for the timestamps of the topmost and bottom points
        // *displayed*. An overscan row is by definition not displayed - it exists
        // only so a point centred just outside the viewport paints the sliver of
        // its disc that falls inside it - so a label taken from the drawn window
        // would name a measurement the reader cannot see.
        // Checked mid-scroll, where the two windows genuinely differ; at either
        // extreme the clamps hide the difference.
        val labels = ChartGeometry.labelRows(scrollPx = 100f, viewportPx = 50f, size = 1000, pxPerSample = 1f)
        val drawn = ChartGeometry.visibleRows(scrollPx = 100f, viewportPx = 50f, size = 1000, pxPerSample = 1f)

        // Row 100 sits at y == 0 and row 149 at y == 49, the last row inside a
        // viewport 50 px tall. Row 150 would sit exactly on the bottom edge.
        assertEquals(100, labels.firstRow)
        assertEquals(149, labels.lastRow)

        // Strictly inside the drawn window at both ends.
        assertTrue("$labels not inside $drawn", labels.firstRow > drawn.firstRow)
        assertTrue("$labels not inside $drawn", labels.lastRow < drawn.lastRow)
    }

    @Test
    fun `the displayed window is exactly the rows whose y falls in the viewport`() {
        // Both bounds are ceil, not floor. A fractional scroll offset must not
        // name the row above the top edge, and a viewport a whole number of rows
        // tall - which at pxPerSample 1 and an integer plot height is every
        // viewport - must not name the row sitting exactly on the bottom edge.
        val whole = ChartGeometry.displayedRows(scrollPx = 0f, viewportPx = 800f, size = 1000, pxPerSample = 1f)
        assertEquals(0, whole.firstRow)
        assertEquals(799, whole.lastRow)
        assertEquals(0f, ChartGeometry.yOf(whole.firstRow, 0f, 1f), 0.0001f)
        assertEquals(799f, ChartGeometry.yOf(whole.lastRow, 0f, 1f), 0.0001f)

        val fractional = ChartGeometry.displayedRows(scrollPx = 100.5f, viewportPx = 50f, size = 1000, pxPerSample = 1f)
        assertEquals(101, fractional.firstRow)
        assertEquals(150, fractional.lastRow)
        // Every named row is on screen, and the ones just outside are not.
        assertTrue(ChartGeometry.yOf(fractional.firstRow, 100.5f, 1f) >= 0f)
        assertTrue(ChartGeometry.yOf(fractional.firstRow - 1, 100.5f, 1f) < 0f)
        assertTrue(ChartGeometry.yOf(fractional.lastRow, 100.5f, 1f) < 50f)
        assertTrue(ChartGeometry.yOf(fractional.lastRow + 1, 100.5f, 1f) >= 50f)
    }

    @Test
    fun `nothing is displayed before the plot is measured or in an empty series`() {
        assertTrue(ChartGeometry.displayedRows(0f, 0f, size = 1000, pxPerSample = 1f).isEmpty)
        assertTrue(ChartGeometry.displayedRows(0f, 800f, size = 0, pxPerSample = 1f).isEmpty)
    }

    @Test
    fun `the label rows never index past the series before the plot is measured`() {
        // The first composition, before onSizeChanged has reported a height:
        // visibleRows is empty and its lastRow is -1, which indexOfRow turns into
        // `size` - one past the end of the series, and SignalSeries does not
        // bounds-check. Without this the screen throws on its very first frame.
        val labels = ChartGeometry.labelRows(scrollPx = 0f, viewportPx = 0f, size = 600, pxPerSample = 1f)
        assertEquals(0, labels.firstRow)
        assertEquals(599, labels.lastRow)
        assertEquals(0, ChartGeometry.indexOfRow(labels.lastRow, 600))
        assertEquals(599, ChartGeometry.indexOfRow(labels.firstRow, 600))
    }

    @Test
    fun `an empty series still has no label rows`() {
        // The screen renders its empty state instead; there is no timestamp to print.
        assertTrue(ChartGeometry.labelRows(0f, 0f, size = 0, pxPerSample = 1f).isEmpty)
        assertTrue(ChartGeometry.labelRows(0f, 800f, size = 0, pxPerSample = 1f).isEmpty)
    }

    @Test
    fun `the thumb is the visible share of the series`() {
        // 1200 px of a 5000-row series on screen at once: just under a quarter.
        assertEquals(0.24f, ChartGeometry.thumbHeightFraction(viewportPx = 1200f, contentPx = 5000f), 0.0001f)
        // Nothing to scroll: the thumb fills its track.
        assertEquals(1f, ChartGeometry.thumbHeightFraction(viewportPx = 1200f, contentPx = 300f), 0.0001f)
        assertEquals(1f, ChartGeometry.thumbHeightFraction(viewportPx = 1200f, contentPx = 0f), 0.0001f)
    }

    @Test
    fun `the thumb reaches the bottom of its track exactly at the end of the series`() {
        // The reading a chart scrolled to its oldest measurement must give: the
        // thumb flush against the bottom, not short of it and not past it.
        val max = ChartGeometry.maxScrollPx(size = 5000, viewportPx = 1200f, pxPerSample = 1f)
        val top = ChartGeometry.thumbTopFraction(scrollPx = max, viewportPx = 1200f, contentPx = 5000f)
        val height = ChartGeometry.thumbHeightFraction(viewportPx = 1200f, contentPx = 5000f)
        assertEquals(1f, top + height, 0.0001f)

        assertEquals(0f, ChartGeometry.thumbTopFraction(0f, viewportPx = 1200f, contentPx = 5000f), 0.0001f)
        // Past the end - the caller clamps, but the bar must not overshoot even so.
        assertEquals(top, ChartGeometry.thumbTopFraction(9_999f, viewportPx = 1200f, contentPx = 5000f), 0.0001f)
    }

    @Test
    fun `a full drag of the thumb scrolls exactly the whole series`() {
        // The scrollbar's gesture and its thumb must agree, or the thumb arrives
        // at the end of its track while the chart is still short of the oldest
        // measurement (or the reverse, and the last rows become unreachable).
        val trackPx = 900f
        val contentPx = 5000f
        val viewportPx = 1200f
        val travel = trackPx * (1f - ChartGeometry.thumbHeightFraction(viewportPx, contentPx))
        val scrolled = ChartGeometry.contentDeltaFor(travel, trackPx, contentPx)
        assertEquals(ChartGeometry.maxScrollPx(5000, viewportPx, 1f), scrolled, 0.01f)
    }

    @Test
    fun `a drag on an unmeasured track scrolls nothing`() {
        assertEquals(0f, ChartGeometry.contentDeltaFor(40f, trackPx = 0f, contentPx = 5000f), 0.0001f)
    }

    @Test
    fun `a crosshair overlay stays inside the plot at both edges`() {
        val viewportPx = 800f
        // Comfortably in the middle: the overlay hangs where it was asked to.
        assertEquals(380f, ChartGeometry.overlayTopPx(400f, 20f, 60f, viewportPx), 0.0001f)
        // Against the top: it would have started above the plot.
        assertEquals(0f, ChartGeometry.overlayTopPx(5f, 20f, 60f, viewportPx), 0.0001f)
        // Against the bottom: its foot would have hung below the plot.
        assertEquals(740f, ChartGeometry.overlayTopPx(799f, 20f, 60f, viewportPx), 0.0001f)
    }

    @Test
    fun `an overlay taller than the plot is pinned to the top rather than throwing`() {
        // coerceIn throws when its minimum exceeds its maximum, and an unmeasured
        // plot (viewportPx still 0) is exactly that case.
        assertEquals(0f, ChartGeometry.overlayTopPx(0f, 20f, 60f, viewportPx = 0f), 0.0001f)
        assertEquals(0f, ChartGeometry.overlayTopPx(30f, 20f, 900f, viewportPx = 800f), 0.0001f)
    }

    @Test
    fun `a value inside the range is not off-scale`() {
        val line = ChartGeometry.candidateLine(value = -71f, min = -120f, max = -40f)
        assertEquals(ChartGeometry.OffScale.NONE, line.offScale)
        assertEquals(0.6125f, line.fraction, 1e-4f)
    }

    @Test
    fun `a value below the range is reported low and pinned to the left edge`() {
        // xOf alone cannot express this: SignalScales.fraction ends in coerceIn(0,1),
        // so an off-scale value returns the same 0f a genuine minimum does. Drawing
        // that with nothing to mark it would hide the most decisive answer this
        // screen can give.
        val line = ChartGeometry.candidateLine(value = -140f, min = -120f, max = -40f)
        assertEquals(ChartGeometry.OffScale.LOW, line.offScale)
        assertEquals(0f, line.fraction, 1e-6f)
    }

    @Test
    fun `a value above the range is reported high and pinned to the right edge`() {
        val line = ChartGeometry.candidateLine(value = -20f, min = -120f, max = -40f)
        assertEquals(ChartGeometry.OffScale.HIGH, line.offScale)
        assertEquals(1f, line.fraction, 1e-6f)
    }

    @Test
    fun `a value exactly on an edge is in range, not off-scale`() {
        assertEquals(ChartGeometry.OffScale.NONE, ChartGeometry.candidateLine(-120f, -120f, -40f).offScale)
        assertEquals(ChartGeometry.OffScale.NONE, ChartGeometry.candidateLine(-40f, -120f, -40f).offScale)
    }

    @Test
    fun `a degenerate range is not off-scale in either direction`() {
        // SignalScales.fraction returns 0f when the span is not positive; the line
        // must not then claim the value was off-scale.
        val line = ChartGeometry.candidateLine(value = -71f, min = -71f, max = -71f)
        assertEquals(ChartGeometry.OffScale.NONE, line.offScale)
    }
}
