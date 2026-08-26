package com.cerocoder.meshrelay.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class SignalScalesTest {

    @Test
    fun `fraction maps the scale ends to zero and one`() {
        assertEquals(0f, SignalScales.fraction(-20f, SignalScales.SNR_MIN, SignalScales.SNR_MAX), 0.0001f)
        assertEquals(1f, SignalScales.fraction(15f, SignalScales.SNR_MIN, SignalScales.SNR_MAX), 0.0001f)
    }

    @Test
    fun `fraction maps the midpoint to one half`() {
        // SNR spans 35 dB from -20; the midpoint is -2.5 dB.
        assertEquals(0.5f, SignalScales.fraction(-2.5f, SignalScales.SNR_MIN, SignalScales.SNR_MAX), 0.0001f)
        // RSSI spans 100 dB from -130; the midpoint is -80 dBm.
        assertEquals(0.5f, SignalScales.fraction(-80f, SignalScales.RSSI_MIN, SignalScales.RSSI_MAX), 0.0001f)
    }

    @Test
    fun `fraction clamps values outside the scale`() {
        // A node can genuinely report SNR above +15 dB or RSSI below -130 dBm.
        // Clamping keeps the gauge inside its track instead of drawing off the end.
        assertEquals(0f, SignalScales.fraction(-999f, SignalScales.SNR_MIN, SignalScales.SNR_MAX), 0.0001f)
        assertEquals(1f, SignalScales.fraction(999f, SignalScales.SNR_MIN, SignalScales.SNR_MAX), 0.0001f)
    }

    @Test
    fun `a degenerate scale does not divide by zero`() {
        assertEquals(0f, SignalScales.fraction(5f, 3f, 3f), 0.0001f)
    }
}
