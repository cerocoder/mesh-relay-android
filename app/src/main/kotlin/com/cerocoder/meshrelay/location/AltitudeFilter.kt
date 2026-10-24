package com.cerocoder.meshrelay.location

import kotlin.math.roundToInt

/**
 * Turns the phone's noisy GNSS altitude into one steady figure.
 *
 * Consumer GNSS gets its height worst of all three axes, and in a city the
 * reflections make it jump by tens of metres between fixes. Three steps:
 *
 * 1. A measurement with no accuracy figure, or a worse one than [MAX_ACCURACY_M], is dropped.
 * 2. A one-dimensional Kalman filter blends each kept measurement with the estimate so far,
 *    weighting it by its own reported accuracy: a precise fix moves the estimate a lot, a
 *    marginal one hardly at all.
 * 3. The estimate is the output. While nothing good arrives, the last output is held for
 *    [HOLD_MILLIS] and then withdrawn, so a stale height is never shown as a current one.
 *
 * Not thread safe; the caller owns the synchronisation.
 */
class AltitudeFilter {

    private var estimate = 0.0
    private var variance = 0.0
    private var lastAtMillis = NEVER

    /** Offers one measurement taken at [atMillis] (any monotonic clock, in milliseconds). */
    fun accept(atMillis: Long, altitudeM: Double, accuracyM: Float?) {
        if (accuracyM == null || accuracyM > MAX_ACCURACY_M || accuracyM <= 0f) return
        // The accuracy is one standard deviation, so its square is the measurement variance.
        val measurementVariance = accuracyM.toDouble() * accuracyM
        // An estimate that has been withdrawn is not blended with: the new fix starts afresh.
        if (valueAt(atMillis) == null) {
            estimate = altitudeM
            variance = measurementVariance
        } else {
            // A clock stepping backwards counts as no time passing.
            val elapsedSeconds = maxOf(0L, atMillis - lastAtMillis) / 1000.0
            val predicted = variance + PROCESS_NOISE_M2_PER_S * elapsedSeconds
            val gain = predicted / (predicted + measurementVariance)
            estimate += gain * (altitudeM - estimate)
            variance = (1 - gain) * predicted
        }
        lastAtMillis = maxOf(lastAtMillis, atMillis)
    }

    /** The held estimate at [nowMillis], or null when none was ever kept or the last is too old. */
    fun valueAt(nowMillis: Long): Int? {
        if (lastAtMillis == NEVER || nowMillis - lastAtMillis > HOLD_MILLIS) return null
        return estimate.roundToInt()
    }

    companion object {
        /**
         * Fifteen metres. Phones report a vertical accuracy of a few metres on an open
         * sky and two to three times the horizontal figure between buildings; beyond
         * fifteen the fix says itself that it cannot tell a floor from the street.
         */
        const val MAX_ACCURACY_M = 15f

        /**
         * A quarter of a square metre per second of drift: the height wanders by about
         * half a metre in a second and four in a minute. A pedestrian on stairs or a
         * slope fits that; a smaller figure makes the filter ignore a real climb, a
         * larger one lets the noise through again.
         */
        const val PROCESS_NOISE_M2_PER_S = 0.25

        /** Ten minutes: long enough to ride out a tunnel or a courtyard, short enough that a climb is not hidden for ever. */
        const val HOLD_MILLIS = 10 * 60_000L

        private const val NEVER = Long.MIN_VALUE
    }
}
