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
        // for the one relay that has never been heard from.
        assertEquals(RelativeAge.Never, AgeText.relative(Long.MIN_VALUE))
        assertEquals(RelativeAge.Never, AgeText.relative(-1))
    }
}
