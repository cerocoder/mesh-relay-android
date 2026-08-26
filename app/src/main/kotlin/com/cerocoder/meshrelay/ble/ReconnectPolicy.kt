package com.cerocoder.meshrelay.ble

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Reconnect policy with exponential backoff.
 *
 * The 3-second pause before every attempt was not picked out of thin air, but it
 * is also not measured here: it is carried over from Meshtastic-Android v2.8.0,
 * where it is noted that at 1.5 seconds the firmware does not manage to release its
 * own GATT session and the connection breaks down in the middle of the handshake.
 * The source lives at `Research/connection/code/10-BleReconnectPolicy.kt`. Our stack
 * is different, so the value is treated as a justified starting point, not an
 * established fact: only manual acceptance testing on a live node can confirm or
 * refute it.
 */
class ReconnectPolicy(
    /** Shorter than this and the connection counts as a failed attempt, not a disconnect. */
    val minStableConnection: Duration = 5.seconds,
) {

    /** Pause before every attempt, including the first one. */
    val settleDelay: Duration = 3.seconds

    var consecutiveFailures: Int = 0
        private set

    /**
     * Record the outcome of an attempt and return the current count of consecutive
     * failures.
     *
     * There is deliberately no "disconnect was intentional" flag here. An
     * intentional disconnect cancels the transport's coroutine, and the reconnect
     * loop ends before it ever reaches this call - so there is simply no one left
     * to record that outcome.
     */
    fun onOutcome(wasStable: Boolean): Int {
        consecutiveFailures = if (wasStable) 0 else consecutiveFailures + 1
        return consecutiveFailures
    }

    /** Delay before the next attempt: 5, 10, 20, 40, then 60 seconds. */
    fun backoffFor(consecutiveFailures: Int): Duration {
        if (consecutiveFailures <= 0) return BASE_DELAY
        val multiplier = 1 shl (consecutiveFailures - 1).coerceAtMost(MAX_EXPONENT)
        return minOf(BASE_DELAY * multiplier, MAX_DELAY)
    }

    private companion object {
        val BASE_DELAY = 5.seconds
        val MAX_DELAY = 60.seconds
        const val MAX_EXPONENT = 4
    }
}
