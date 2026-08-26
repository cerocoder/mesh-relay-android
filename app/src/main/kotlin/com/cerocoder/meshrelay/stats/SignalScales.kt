package com.cerocoder.meshrelay.stats

/**
 * Gauge scales, single-sourced.
 *
 * Widened from the terminal tool's -20..+10 dB and -120..-60 dBm to the ranges
 * Meshtastic actually produces. The consequence is deliberate: a 100 dB RSSI
 * span compresses the -120..-60 region where nearly every real packet sits, so
 * the gauge reads less sensitively than the terminal's did. Everything reads
 * these constants, so turning the range into a setting later is a change in one
 * file.
 */
object SignalScales {
    const val SNR_MIN = -20f
    const val SNR_MAX = 15f
    const val RSSI_MIN = -130f
    const val RSSI_MAX = -30f

    /** How long the last-value marker stays lit after a packet arrives. */
    const val FLASH_MILLIS = 500L

    /** Position of [value] along the scale, clamped to 0f..1f. */
    fun fraction(value: Float, min: Float, max: Float): Float {
        val span = max - min
        if (span <= 0f) return 0f
        return ((value - min) / span).coerceIn(0f, 1f)
    }
}
