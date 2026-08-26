package com.cerocoder.meshrelay.ui.common

import com.cerocoder.meshrelay.settings.GaugeMode
import com.cerocoder.meshrelay.stats.SignalScales
import com.cerocoder.meshrelay.stats.model.SignalStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

private const val MIN = SignalScales.SNR_MIN   // -20
private const val MAX = SignalScales.SNR_MAX   // +15

private fun stats(vararg values: Float) = values.fold(SignalStats.EMPTY) { acc, v -> acc.plus(v) }

class GaugeGeometryTest {

    @Test
    fun `an empty gauge draws nothing at all`() {
        val marks = GaugeGeometry.marks(SignalStats.EMPTY, MIN, MAX, GaugeMode.COMPLEX)
        assertEquals(0f, marks.fillStart, 0.0001f)
        assertEquals(0f, marks.fillEnd, 0.0001f)
        assertNull(marks.avg)
        assertNull(marks.last)
    }

    @Test
    fun `simple mode fills from the scale floor to the latest value`() {
        // Three distinct values on purpose: minVal (-6) is not the scale floor and
        // maxVal (8) is not the latest value, so this kills both the "fill from the
        // observed minimum" and the "fill to maxVal" mutants. With minVal == floor or
        // maxVal == lastVal the assertions hold for the wrong implementation too.
        val marks = GaugeGeometry.marks(stats(-6f, 8f, -2.5f), MIN, MAX, GaugeMode.SIMPLE)
        assertEquals(0f, marks.fillStart, 0.0001f)
        assertEquals(0.5f, marks.fillEnd, 0.0001f)
        assertNull(marks.avg)
        assertNull(marks.last)
    }

    @Test
    fun `complex mode fills the observed span and marks the average and the latest`() {
        // Values -20, 15, -2.5: span is the whole track, average is -2.5 (the middle),
        // latest is -2.5 as well.
        val marks = GaugeGeometry.marks(stats(-20f, 15f, -2.5f), MIN, MAX, GaugeMode.COMPLEX)
        assertEquals(0f, marks.fillStart, 0.0001f)
        assertEquals(1f, marks.fillEnd, 0.0001f)
        assertEquals(0.5f, marks.avg!!, 0.0001f)
        assertEquals(0.5f, marks.last!!, 0.0001f)
    }

    @Test
    fun `both marks are returned even when they coincide`() {
        // The original gives the latest-value marker priority when it lands on the
        // average. Geometry does not resolve that - it returns both, and the
        // composable draws the average first so the latest covers it.
        val marks = GaugeGeometry.marks(stats(-5f), MIN, MAX, GaugeMode.COMPLEX)
        assertNotNull(marks.avg)
        assertNotNull(marks.last)
        assertEquals(marks.avg!!, marks.last!!, 0.0001f)
    }

    @Test
    fun `a single sample gives a span of zero width rather than an inverted one`() {
        val marks = GaugeGeometry.marks(stats(-5f), MIN, MAX, GaugeMode.COMPLEX)
        assertEquals(marks.fillStart, marks.fillEnd, 0.0001f)
    }

    @Test
    fun `values beyond the scale are clamped into the track`() {
        // A node can report SNR above the scale maximum. Without clamping the fill
        // would be drawn past the end of its own track.
        val marks = GaugeGeometry.marks(stats(-200f, 200f), MIN, MAX, GaugeMode.COMPLEX)
        assertEquals(0f, marks.fillStart, 0.0001f)
        assertEquals(1f, marks.fillEnd, 0.0001f)
    }
}
