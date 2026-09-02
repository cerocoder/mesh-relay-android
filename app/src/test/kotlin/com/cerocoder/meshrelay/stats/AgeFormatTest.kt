package com.cerocoder.meshrelay.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class AgeFormatTest {

    @Test
    fun `under a minute counts in whole seconds`() {
        assertEquals(RelativeAge.Seconds(0), AgeText.relative(0))
        assertEquals(RelativeAge.Seconds(2), AgeText.relative(2_400))
        assertEquals(RelativeAge.Seconds(59), AgeText.relative(59_999))
    }

    @Test
    fun `under an hour counts in minutes and seconds`() {
        assertEquals(RelativeAge.Minutes(1, 0), AgeText.relative(60_000))
        assertEquals(RelativeAge.Minutes(1, 12), AgeText.relative(72_000))
        assertEquals(RelativeAge.Minutes(59, 59), AgeText.relative(3_599_999))
    }

    @Test
    fun `an hour and beyond counts in hours and minutes`() {
        assertEquals(RelativeAge.Hours(1, 0), AgeText.relative(3_600_000))
        assertEquals(RelativeAge.Hours(2, 5), AgeText.relative(2 * 3_600_000L + 5 * 60_000L))
    }

    @Test
    fun `an age that has not happened is never, not zero seconds`() {
        // A relay with no packet yet has lastPacketAtMillis == 0. Rendered through
        // the seconds branch it would read "0s ago" - the freshest thing on screen,
        // for the one relay that has never been heard from. That sentinel is
        // caught by relativeTo's atMillis == 0L check before it ever reaches
        // relative(), so what lands here as a very negative elapsed time is a
        // nonsense timestamp far beyond any clock-tick skew, not the sentinel.
        assertEquals(RelativeAge.Never, AgeText.relative(Long.MIN_VALUE))
    }

    @Test
    fun `a packet newer than the clock tick reads as zero seconds, not never`() {
        // The screens' clock ticks once a second, so a packet that lands between
        // two ticks is ahead of "now". Before this fix that read "Never" - the
        // worst possible answer for the freshest thing on screen.
        assertEquals(RelativeAge.Seconds(0), AgeText.relative(-1))
        assertEquals(RelativeAge.Seconds(0), AgeText.relative(-999))
        assertEquals(RelativeAge.Seconds(0), AgeText.relativeTo(nowMillis = 1_000L, atMillis = 1_400L))
    }

    @Test
    fun `a timestamp far in the future is still not a real age`() {
        // Beyond one tick's worth of skew this is not a clock artefact, it is a
        // nonsense timestamp, and clamping it to "0s ago" would present it as the
        // freshest packet on screen. One minute is well past any tick.
        assertEquals(RelativeAge.Never, AgeText.relative(-60_000))
    }

    @Test
    fun `never heard is still never`() {
        // The sentinel case must be untouched: relativeTo, not relative, is what
        // knows the difference, and RelayStats/NeighbourStats default the field to 0.
        assertEquals(RelativeAge.Never, AgeText.relativeTo(nowMillis = 5_000L, atMillis = 0L))
    }

    @Test
    fun `relativeTo treats a zero timestamp as never, not an epoch of hours`() {
        // A real wall-clock "now" - RelayStats and NeighbourStats default
        // lastPacketAtMillis to 0 for a relay never heard from. Subtracting
        // that sentinel from "now" is what produced "492777h 46m" before this
        // overload existed.
        val now = 1_772_140_800_000L
        assertEquals(RelativeAge.Never, AgeText.relativeTo(now, 0L))
    }

    @Test
    fun `relativeTo delegates to relative for a real timestamp`() {
        val now = 1_772_140_800_000L
        assertEquals(RelativeAge.Seconds(2), AgeText.relativeTo(now, now - 2_400))
    }

    @Test
    fun `relativeTo still reports never for a timestamp in the future`() {
        val now = 1_772_140_800_000L
        assertEquals(RelativeAge.Never, AgeText.relativeTo(now, now + 5_000))
    }
}
