package com.cerocoder.meshrelay.stats

/**
 * How long ago something happened, broken into the fields the UI renders
 * through string resources.
 *
 * A sealed interface rather than a single data class with optional fields,
 * because the three lived branches (seconds / minutes+seconds / hours+minutes)
 * are mutually exclusive and each maps to exactly one string resource - the
 * UI layer switches on this rather than reformatting numbers itself.
 */
sealed interface RelativeAge {
    data class Seconds(val seconds: Int) : RelativeAge
    data class Minutes(val minutes: Int, val seconds: Int) : RelativeAge
    data class Hours(val hours: Int, val minutes: Int) : RelativeAge

    /** No packet has ever arrived; there is no "ago" to report. */
    data object Never : RelativeAge
}

/**
 * Ports the `since_str` branches at mesh_stats.py:1637-1648.
 *
 * Pure and Android-free: the caller does the subtraction against the ticking
 * clock and calls in here with the result, so this logic is testable on the
 * JVM without a Composable host.
 */
object AgeText {

    private const val SECOND = 1_000L
    private const val MINUTE = 60 * SECOND
    private const val HOUR = 60 * MINUTE

    /**
     * How far ahead of the displayed clock a timestamp may be and still be read
     * as "just now".
     *
     * `RelativeTimeTicker` refreshes the displayed "now" every 1000 ms, so a
     * packet can legitimately be up to one whole tick ahead of it. Two ticks of
     * headroom absorbs that plus any scheduling delay, while staying far below
     * any age a reader would notice being rounded.
     */
    private const val MAX_CLOCK_SKEW = 2 * SECOND

    /**
     * How long ago, in a shape a screen can render.
     *
     * A small *negative* elapsed time is a clock artefact, not a future event:
     * the screens read "now" from `LocalRelativeClock`, which refreshes about
     * once a second, so a packet that lands between two ticks is momentarily
     * ahead of it. Those clamp to zero seconds - reading "Never" for the packet
     * that arrived a moment ago was the defect this rule replaced.
     *
     * Beyond [MAX_CLOCK_SKEW] the timestamp is not a tick artefact but a
     * nonsense value, and presenting it as the freshest thing on screen would be
     * worse than admitting it cannot be placed.
     *
     * The genuine never-heard case does not come through here at all:
     * [relativeTo] tests the zero sentinel before subtracting.
     */
    fun relative(elapsedMillis: Long): RelativeAge = when {
        elapsedMillis < -MAX_CLOCK_SKEW -> RelativeAge.Never
        elapsedMillis < 0 -> RelativeAge.Seconds(0)
        elapsedMillis < MINUTE -> RelativeAge.Seconds((elapsedMillis / SECOND).toInt())
        elapsedMillis < HOUR -> RelativeAge.Minutes(
            minutes = (elapsedMillis / MINUTE).toInt(),
            seconds = ((elapsedMillis % MINUTE) / SECOND).toInt(),
        )
        else -> RelativeAge.Hours(
            hours = (elapsedMillis / HOUR).toInt(),
            minutes = ((elapsedMillis % HOUR) / MINUTE).toInt(),
        )
    }

    /**
     * Relative age of an event that may never have happened.
     *
     * A zero [atMillis] is the model's never-heard sentinel - RelayStats and
     * NeighbourStats both default the field to it - and must not be subtracted
     * from the wall clock, which would render an epoch's worth of hours. Callers
     * hold a timestamp rather than an elapsed time, so this is the only place that
     * can tell the sentinel from a real instant.
     */
    fun relativeTo(nowMillis: Long, atMillis: Long): RelativeAge =
        if (atMillis == 0L) RelativeAge.Never else relative(nowMillis - atMillis)
}
