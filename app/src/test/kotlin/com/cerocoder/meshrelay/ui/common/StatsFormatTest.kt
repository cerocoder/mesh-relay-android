package com.cerocoder.meshrelay.ui.common

import com.cerocoder.meshrelay.stats.model.SignalStats
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun stats(vararg values: Float) = values.fold(SignalStats.EMPTY) { acc, v -> acc.plus(v) }

class StatsFormatTest {

    @Test
    fun `signalTriple is null before any packet has arrived`() {
        // Kills a mutant that drops the hasData guard and formats the EMPTY
        // sentinel's minVal/maxVal (+-Infinity) and avg (0) as if they were
        // real readings, instead of leaving this to the caller's fallback text.
        assertNull(StatsFormat.signalTriple(SignalStats.EMPTY, Locale.US))
    }

    @Test
    fun `signalLast is null before any packet has arrived`() {
        // The same guard, but on the sibling function - a mutant could drop
        // this one while leaving signalTriple's intact.
        assertNull(StatsFormat.signalLast(SignalStats.EMPTY, Locale.US))
    }

    @Test
    fun `signalTriple formats min and max as whole numbers but the average to one decimal`() {
        // min=-6, max=8, avg=(-6+8-2.5)/3=-0.1666...7. Kills a mutant that
        // swaps the min/max pattern with the average's: using the average's
        // one-decimal pattern for min/max would print "-6.0/-0.2/8.0", and
        // using the spot pattern for the average would round -0.1666... down
        // to "0" instead of "-0.2" - either swap changes this assertion.
        assertEquals("-6/-0.2/8", StatsFormat.signalTriple(stats(-6f, 8f, -2.5f), Locale.US))
    }

    @Test
    fun `signalLast is a spot reading, not the average, at the same precision as min and max`() {
        // avg and lastVal are both -2.5 for these inputs (min=-20, max=15,
        // last=-2.5, avg=(-20+15-2.5)/3=-2.5) - the only thing that can tell
        // signalLast's spot precision apart from the average's one-decimal
        // precision here is which pattern is actually used. A mutant that
        // formatted the average instead of lastVal would still pass this
        // input through unchanged in value but not in precision: "-3", not
        // "-2.5".
        val s = stats(-20f, 15f, -2.5f)
        assertEquals("-3", StatsFormat.signalLast(s, Locale.US))
    }

    @Test
    fun `signalTriple's average follows the given locale, not a fixed one`() {
        // Only the average carries a decimal point at all - min and max are
        // whole numbers here - so this is where a hard-coded Locale.US or
        // Locale.ROOT in signalTriple would first show up to a Spanish reader
        // expecting a decimal comma.
        val s = stats(1.5f)

        assertEquals("2/1.5/2", StatsFormat.signalTriple(s, Locale.US))
        assertEquals("2/1,5/2", StatsFormat.signalTriple(s, Locale("es", "ES")))
    }

    @Test
    fun `percentageOf is computed against the given total, not some other number`() {
        // 3/8 = 37.5% exactly, in floating point with no rounding ambiguity.
        // Kills a mutant that computes against the wrong total (a swapped
        // part/total would give 266.7%, not 37.5%) or against a total held
        // somewhere else than the parameter actually passed in.
        assertEquals("37.5", StatsFormat.percentageOf(part = 3, total = 8, locale = Locale.US))
    }

    @Test
    fun `percentageOf is zero rather than infinite when the total is zero`() {
        // A literal port of packet_count / total_relayed * 100 with no guard
        // divides a positive Float by zero, which does not throw - it
        // produces Float.POSITIVE_INFINITY, and String.format renders that as
        // the literal text "Infinity", not "0.0". A mutant that drops the
        // `total > 0` guard makes this assertion fail on that text, not on a
        // crash.
        assertEquals("0.0", StatsFormat.percentageOf(part = 5, total = 0, locale = Locale.US))
    }

    @Test
    fun `percentageOf follows the given locale, not a fixed one`() {
        // 1/3 = 33.333...%, which never terminates, so the digit before the
        // decimal separator is never in doubt - only the separator itself
        // changes between locales. Kills a mutant that formats with
        // Locale.US or Locale.ROOT regardless of the locale parameter.
        assertEquals("33.3", StatsFormat.percentageOf(part = 1, total = 3, locale = Locale.US))
        assertEquals("33,3", StatsFormat.percentageOf(part = 1, total = 3, locale = Locale("es", "ES")))
    }

    @Test
    fun `signalMin, signalAvg and signalMax are each null before any packet has arrived`() {
        // The same EMPTY-sentinel guard as signalTriple and signalLast, but on
        // the three functions signalTriple is now built from - a mutant could
        // drop the guard on any one of them while leaving the others intact.
        assertNull(StatsFormat.signalMin(SignalStats.EMPTY, Locale.US))
        assertNull(StatsFormat.signalAvg(SignalStats.EMPTY, Locale.US))
        assertNull(StatsFormat.signalMax(SignalStats.EMPTY, Locale.US))
    }

    @Test
    fun `signalMin, signalAvg and signalMax match the components of signalTriple`() {
        // Same inputs as the "min and max as whole numbers but the average to
        // one decimal" case above: min=-6, max=8, avg=-0.1666...7. Kills a
        // mutant that lets the three standalone functions drift from the
        // triple they compose - e.g. one of them silently reverting to the
        // wrong pattern while signalTriple's own test still passes because it
        // reads from the same (now-wrong) function.
        val s = stats(-6f, 8f, -2.5f)
        assertEquals("-6", StatsFormat.signalMin(s, Locale.US))
        assertEquals("-0.2", StatsFormat.signalAvg(s, Locale.US))
        assertEquals("8", StatsFormat.signalMax(s, Locale.US))
        assertEquals(
            "${StatsFormat.signalMin(s, Locale.US)}/${StatsFormat.signalAvg(s, Locale.US)}/${StatsFormat.signalMax(s, Locale.US)}",
            StatsFormat.signalTriple(s, Locale.US),
        )
    }

    @Test
    fun `signalAvg follows the given locale, not a fixed one`() {
        // Only the average carries a decimal point at all, same reasoning as
        // signalTriple's own locale test above.
        val s = stats(1.5f)
        assertEquals("1.5", StatsFormat.signalAvg(s, Locale.US))
        assertEquals("1,5", StatsFormat.signalAvg(s, Locale("es", "ES")))
    }

    @Test
    fun `packetsPerHour formats to one decimal place`() {
        // Ports the ":.1f" precision mesh_stats.py:1824 formats packets_per_hour
        // at. Kills a mutant that formats to zero decimals (a whole number,
        // like the spot readings) or to some other precision.
        assertEquals("120.0", StatsFormat.packetsPerHour(120f, Locale.US))
        assertEquals("7.3", StatsFormat.packetsPerHour(7.3456f, Locale.US))
    }

    @Test
    fun `packetsPerHour renders a real zero rather than hiding it`() {
        // A relay heard only once, or not long enough to measure a rate, is a
        // real answer of 0.0 pkt/h - not the absence of a reading the signal
        // formatters above use null for. Kills a mutant that special-cases
        // zero into null or an empty string.
        assertEquals("0.0", StatsFormat.packetsPerHour(0f, Locale.US))
    }

    @Test
    fun `packetsPerHour follows the given locale, not a fixed one`() {
        assertEquals("7.3", StatsFormat.packetsPerHour(7.3f, Locale.US))
        assertEquals("7,3", StatsFormat.packetsPerHour(7.3f, Locale("es", "ES")))
    }
}
