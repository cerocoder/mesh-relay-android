package com.cerocoder.meshrelay.stats.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalStatsTest {

    @Test
    fun `an empty instance reports no data`() {
        assertFalse(SignalStats.EMPTY.hasData)
        assertEquals(0, SignalStats.EMPTY.count)
        assertEquals(0f, SignalStats.EMPTY.avg, 0.0001f)
    }

    @Test
    fun `the first value becomes minimum maximum and last at once`() {
        val stats = SignalStats.EMPTY.plus(-7.5f)
        assertTrue(stats.hasData)
        assertEquals(-7.5f, stats.minVal, 0.0001f)
        assertEquals(-7.5f, stats.maxVal, 0.0001f)
        assertEquals(-7.5f, stats.lastVal, 0.0001f)
        assertEquals(-7.5f, stats.avg, 0.0001f)
    }

    @Test
    fun `minimum maximum average and last track a sequence`() {
        val stats = SignalStats.EMPTY.plus(-10f).plus(0f).plus(-5f)
        assertEquals(-10f, stats.minVal, 0.0001f)
        assertEquals(0f, stats.maxVal, 0.0001f)
        assertEquals(-5f, stats.lastVal, 0.0001f)
        assertEquals(-5f, stats.avg, 0.0001f)
        assertEquals(3, stats.count)
    }

    @Test
    fun `updating returns a new instance and leaves the old one alone`() {
        val first = SignalStats.EMPTY.plus(1f)
        val second = first.plus(9f)
        assertEquals(1, first.count)
        assertEquals(2, second.count)
    }

    @Test
    fun `the average does not drift over a long session`() {
        // The sum is a Double for exactly this reason: a Float accumulator over tens
        // of thousands of samples visibly pulls the average away from the true value.
        var stats = SignalStats.EMPTY
        repeat(100_000) { stats = stats.plus(-93.7f) }
        assertEquals(-93.7f, stats.avg, 0.0005f)
    }

    @Test
    fun `history keeps its samples in arrival order`() {
        val history = SignalHistory().plus(1_000L, -5f).plus(2_000L, -6f)
        assertEquals(listOf(Sample(1_000L, -5f), Sample(2_000L, -6f)), history.samples)
        assertEquals(2, history.stats.count)
        assertEquals(-6f, history.stats.lastVal, 0.0001f)
    }

    @Test
    fun `history drops the oldest sample past the cap but keeps the statistics`() {
        // The cap exists because a survey runs for hours: the terminal tool's list is
        // unbounded, which on a phone is a leak. Statistics must survive the eviction,
        // otherwise the minimum silently rises as old samples fall off the end.
        var history = SignalHistory()
        repeat(SignalHistory.MAX_SAMPLES + 10) { i -> history = history.plus(i.toLong(), i.toFloat()) }

        assertEquals(SignalHistory.MAX_SAMPLES, history.samples.size)
        assertEquals(10L, history.samples.first().atMillis)
        assertEquals(SignalHistory.MAX_SAMPLES + 10, history.stats.count)
        assertEquals(0f, history.stats.minVal, 0.0001f)
    }
}
