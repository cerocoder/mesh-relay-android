package com.cerocoder.meshrelay.stats.model

import com.cerocoder.meshrelay.stats.SignalSeriesBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SignalSeriesTest {

    @Test
    fun `the snapshot is trimmed to size, not to capacity`() {
        // A snapshot the length of the capacity would hand the interface 4998
        // zero-timestamped measurements at the start of a session, and the chart
        // would plot them.
        val buffer = SignalSeriesBuffer(capacity = 100)
        buffer.append(1_000L, -90f, 5f, null)
        buffer.append(2_000L, -91f, 4f, null)
        assertEquals(2, buffer.snapshot().size)
    }

    @Test
    fun `the snapshot does not change when the buffer does`() {
        // The whole reason snapshot() copies. The chart holds this value for as
        // long as a composition lives, on another thread, while the engine keeps
        // folding packets into the arrays behind it.
        val buffer = SignalSeriesBuffer(capacity = 100)
        buffer.append(1_000L, -90f, 5f, null)
        val taken = buffer.snapshot()

        buffer.append(2_000L, -91f, 4f, null)

        assertEquals(1, taken.size)
        assertEquals(2, buffer.snapshot().size)
    }

    @Test
    fun `EMPTY has nothing in it and answers every question`() {
        assertEquals(0, SignalSeries.EMPTY.size)
        assertEquals(0L, SignalSeries.EMPTY.totalAppended)
    }

    @Test
    fun `an out-of-range index is a programming error, not a silent zero`() {
        // The accessors index the arrays directly. That is deliberate - a bounds
        // check per pixel row is not free - and this test records that the failure
        // mode is a thrown exception rather than a plotted zero.
        val series = SignalSeriesBuffer(capacity = 4).also { it.append(1L, -90f, 5f, null) }.snapshot()
        assertThrows(IndexOutOfBoundsException::class.java) { series.rssi(1) }
    }
}
