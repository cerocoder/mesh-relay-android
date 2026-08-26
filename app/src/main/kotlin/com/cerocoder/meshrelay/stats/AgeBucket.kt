package com.cerocoder.meshrelay.stats

/**
 * How old a piece of information is, in the grades the terminal tool shows.
 *
 * Deliberately coarse: an exact age would suggest the position is measured to the
 * second, when it was in fact learned from a broadcast at some point in the past.
 * Ports the table at mesh_stats.py:1775-1794.
 */
enum class AgeBucket {
    M1, M5, M30, H1, H12, D1, W1, Y1, UNKNOWN;

    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR

        fun of(elapsedMillis: Long): AgeBucket = when {
            // A node's clock can run ahead of the phone's, putting lastHeard in the
            // future. Calling that "fresh" would rank the stalest data first.
            elapsedMillis < 0 -> UNKNOWN
            elapsedMillis < MINUTE -> M1
            elapsedMillis < 5 * MINUTE -> M5
            elapsedMillis < 30 * MINUTE -> M30
            elapsedMillis < HOUR -> H1
            elapsedMillis < 12 * HOUR -> H12
            elapsedMillis < DAY -> D1
            elapsedMillis < 7 * DAY -> W1
            elapsedMillis < 365 * DAY -> Y1
            else -> UNKNOWN
        }
    }
}
