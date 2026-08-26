package com.cerocoder.meshrelay.stats

import org.junit.Assert.assertEquals
import org.junit.Test

private const val SECOND = 1_000L
private const val MINUTE = 60 * SECOND
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

class AgeBucketTest {

    @Test
    fun `each boundary belongs to the coarser bucket`() {
        assertEquals(AgeBucket.M1, AgeBucket.of(0))
        assertEquals(AgeBucket.M1, AgeBucket.of(MINUTE - 1))
        assertEquals(AgeBucket.M5, AgeBucket.of(MINUTE))
        assertEquals(AgeBucket.M5, AgeBucket.of(5 * MINUTE - 1))
        assertEquals(AgeBucket.M30, AgeBucket.of(5 * MINUTE))
        assertEquals(AgeBucket.M30, AgeBucket.of(30 * MINUTE - 1))
        assertEquals(AgeBucket.H1, AgeBucket.of(30 * MINUTE))
        assertEquals(AgeBucket.H1, AgeBucket.of(HOUR - 1))
        assertEquals(AgeBucket.H12, AgeBucket.of(HOUR))
        assertEquals(AgeBucket.H12, AgeBucket.of(12 * HOUR - 1))
        assertEquals(AgeBucket.D1, AgeBucket.of(12 * HOUR))
        assertEquals(AgeBucket.D1, AgeBucket.of(DAY - 1))
        assertEquals(AgeBucket.W1, AgeBucket.of(DAY))
        assertEquals(AgeBucket.W1, AgeBucket.of(7 * DAY - 1))
        assertEquals(AgeBucket.Y1, AgeBucket.of(7 * DAY))
        assertEquals(AgeBucket.Y1, AgeBucket.of(365 * DAY - 1))
        assertEquals(AgeBucket.UNKNOWN, AgeBucket.of(365 * DAY))
    }

    @Test
    fun `a negative age is unknown rather than fresh`() {
        // A node's clock can be ahead of the phone's, which makes lastHeard land in
        // the future. Reporting that as "under a minute" would present a stale
        // position as the freshest thing on screen.
        assertEquals(AgeBucket.UNKNOWN, AgeBucket.of(-1))
        assertEquals(AgeBucket.UNKNOWN, AgeBucket.of(-DAY))
    }
}
