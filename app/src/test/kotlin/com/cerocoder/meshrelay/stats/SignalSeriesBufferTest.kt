package com.cerocoder.meshrelay.stats

import com.cerocoder.meshrelay.stats.model.PositionOrigin
import com.cerocoder.meshrelay.stats.model.StampedPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The buffer is a fixed-size ring, so every test here that matters is about what
 * happens at and past the wrap. A capacity of 4 is used rather than the real
 * 5000: the arithmetic is identical and a failure prints a list a human can read.
 */
class SignalSeriesBufferTest {

    private val getafe = StampedPosition.fromDegrees(40.3057734, -3.7325611, PositionOrigin.PHONE)
    private val toledo = StampedPosition.fromDegrees(39.8628316, -4.0273231, PositionOrigin.NODE)

    @Test
    fun `samples come back oldest first, in the order they were appended`() {
        val buffer = SignalSeriesBuffer(capacity = 4)
        buffer.append(1_000L, -90f, 5f, null)
        buffer.append(2_000L, -91f, 4f, null)

        val series = buffer.snapshot()
        assertEquals(2, series.size)
        assertEquals(1_000L, series.atMillis(0))
        assertEquals(2_000L, series.atMillis(1))
        assertEquals(-90f, series.rssi(0), 0.0001f)
        assertEquals(4f, series.snr(1), 0.0001f)
    }

    @Test
    fun `past capacity the oldest sample is evicted and the order still holds`() {
        val buffer = SignalSeriesBuffer(capacity = 4)
        // Six into a ring of four: 1 and 2 are gone, 3..6 survive in order. A
        // buffer that read its arrays from index 0 instead of from the head would
        // return 5, 6, 3, 4 here and pass every test that only appends twice.
        repeat(6) { i -> buffer.append((i + 1) * 1_000L, -90f - i, i.toFloat(), null) }

        val series = buffer.snapshot()
        assertEquals(4, series.size)
        assertEquals(listOf(3_000L, 4_000L, 5_000L, 6_000L), (0 until series.size).map { series.atMillis(it) })
        assertEquals(-92f, series.rssi(0), 0.0001f)
        assertEquals(5f, series.snr(3), 0.0001f)
    }

    @Test
    fun `a position and its origin survive the arrays they are split across`() {
        val buffer = SignalSeriesBuffer(capacity = 4)
        buffer.append(1_000L, -90f, 5f, getafe)
        buffer.append(2_000L, -91f, 4f, toledo)

        val series = buffer.snapshot()
        assertEquals(getafe, series.positionOf(0))
        assertEquals(toledo, series.positionOf(1))
        assertEquals(PositionOrigin.PHONE, series.positionOf(0)?.origin)
        assertEquals(PositionOrigin.NODE, series.positionOf(1)?.origin)
    }

    @Test
    fun `a sample with no position reads back as no position`() {
        // Not as 0,0. The globe on that measurement's crosshair must be disabled,
        // not pointed at the Gulf of Guinea.
        val buffer = SignalSeriesBuffer(capacity = 4)
        buffer.append(1_000L, -90f, 5f, null)
        assertNull(buffer.snapshot().positionOf(0))
    }

    @Test
    fun `a slot reused after the wrap does not keep the evicted sample's position`() {
        // The failure this forbids: latI/lonI/source are only written when a
        // position is present, so a positioned sample evicted by an unpositioned
        // one would leave its coordinates in the slot and the new sample would
        // inherit them.
        val buffer = SignalSeriesBuffer(capacity = 2)
        buffer.append(1_000L, -90f, 5f, getafe)
        buffer.append(2_000L, -90f, 5f, getafe)
        buffer.append(3_000L, -90f, 5f, null)

        val series = buffer.snapshot()
        assertEquals(getafe, series.positionOf(0))
        assertNull(series.positionOf(1))
    }

    @Test
    fun `totalAppended counts every sample ever appended, not the ones retained`() {
        // This is what the chart re-anchors its scroll by. Once the ring is full,
        // size stops moving and only this number still says how many arrived.
        val buffer = SignalSeriesBuffer(capacity = 4)
        repeat(10) { buffer.append(it.toLong(), -90f, 5f, null) }
        assertEquals(10L, buffer.totalAppended)
        assertEquals(10L, buffer.snapshot().totalAppended)
        assertEquals(4, buffer.snapshot().size)
    }

    @Test
    fun `clear empties the buffer and restarts the count`() {
        val buffer = SignalSeriesBuffer(capacity = 4)
        repeat(6) { buffer.append(it.toLong(), -90f, 5f, getafe) }
        buffer.clear()

        assertEquals(0, buffer.snapshot().size)
        assertEquals(0L, buffer.totalAppended)

        // And it is usable afterwards, from the head, not from wherever the ring
        // happened to stop.
        buffer.append(9_000L, -70f, 1f, null)
        assertEquals(1, buffer.snapshot().size)
        assertEquals(9_000L, buffer.snapshot().atMillis(0))
    }

    @Test
    fun `the default capacity is the one the memory budget was calculated for`() {
        // 25 bytes per measurement x 5000 = 125 KB per relay or neighbour. If this
        // number changes, the figure in the spec's section 5.3 is wrong.
        assertEquals(5000, SignalSeriesBuffer.MAX_SAMPLES)
    }
}
