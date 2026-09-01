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
 * `pxPerSample` is 1 in every test but the two that name it, because 1 is what
 * the single caller passes (spec requirement 13: the parameter exists so a zoom
 * control can be added later without restructuring, and nothing sets it yet).
 */
class ChartGeometryTest {

    @Test
    fun `the visible window is the scrolled rows plus one of overscan each side`() {
        // The overscan is not cosmetic: a polyline segment joins two rows, so
        // without a row beyond each edge the top and bottom segments would be
        // missing and the line would appear to stop short of the viewport.
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
}
