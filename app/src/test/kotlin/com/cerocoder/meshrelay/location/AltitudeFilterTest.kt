package com.cerocoder.meshrelay.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AltitudeFilterTest {

    private val filter = AltitudeFilter()

    private val tenSeconds = 10_000L
    private val minute = 60_000L

    @Test
    fun `no value before the first good measurement`() {
        assertNull(filter.valueAt(0))
    }

    @Test
    fun `the first good measurement is the value`() {
        filter.accept(0, 720.4, 8f)

        assertEquals(720, filter.valueAt(0))
    }

    @Test
    fun `a measurement with no accuracy figure is dropped`() {
        filter.accept(0, 720.0, null)

        assertNull(filter.valueAt(0))
    }

    @Test
    fun `a measurement less precise than the limit is dropped`() {
        filter.accept(0, 720.0, 15.1f)

        assertNull(filter.valueAt(0))
    }

    @Test
    fun `a measurement exactly at the limit is kept`() {
        filter.accept(0, 720.0, 15f)

        assertEquals(720, filter.valueAt(0))
    }

    @Test
    fun `a dropped measurement leaves the held value alone`() {
        filter.accept(0, 720.0, 5f)
        filter.accept(tenSeconds, 790.0, 40f)

        assertEquals(720, filter.valueAt(tenSeconds))
    }

    @Test
    fun `a spike is damped rather than followed`() {
        filter.accept(0, 720.0, 8f)
        filter.accept(tenSeconds, 750.0, 8f)

        val afterSpike = filter.valueAt(tenSeconds)!!
        assertTrue("spike followed: $afterSpike", afterSpike < 740)

        filter.accept(2 * tenSeconds, 730.0, 8f)

        val settled = filter.valueAt(2 * tenSeconds)!!
        assertTrue("not settled: $settled", settled in 728..738)
    }

    @Test
    fun `a precise measurement outweighs an imprecise one`() {
        filter.accept(0, 720.0, 3f)
        filter.accept(tenSeconds, 740.0, 14f)

        assertTrue(filter.valueAt(tenSeconds)!! < 725)
    }

    @Test
    fun `the value is held for the hold time and then withdrawn`() {
        filter.accept(0, 720.0, 5f)

        assertEquals(720, filter.valueAt(9 * minute))
        assertNull(filter.valueAt(10 * minute + 1))
    }

    @Test
    fun `after a long silence a new measurement counts for more`() {
        filter.accept(0, 720.0, 3f)
        filter.accept(5 * minute, 740.0, 8f)

        assertTrue(filter.valueAt(5 * minute)!! > 730)
    }

    @Test
    fun `a good measurement after the hold ran out starts a value again`() {
        filter.accept(0, 720.0, 5f)
        assertNull(filter.valueAt(11 * minute))

        filter.accept(11 * minute, 700.0, 5f)

        assertEquals(700, filter.valueAt(11 * minute))
    }

    @Test
    fun `a clock that steps backwards does not corrupt the value`() {
        filter.accept(10 * tenSeconds, 720.0, 5f)
        filter.accept(0, 720.0, 5f)

        assertEquals(720, filter.valueAt(10 * tenSeconds))
    }
}
