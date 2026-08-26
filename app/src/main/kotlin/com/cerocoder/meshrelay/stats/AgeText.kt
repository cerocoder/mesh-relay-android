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
     * [elapsedMillis] is `now - atMillis`. A negative value means the event is
     * in the future - in practice, a timestamp of zero for a relay that has
     * never sent a packet, subtracted from the current clock, would otherwise
     * fall through to [RelativeAge.Seconds] and read "0s ago": the freshest
     * thing on screen, for the one relay never heard from. So negative is
     * [RelativeAge.Never] rather than clamped to zero.
     */
    fun relative(elapsedMillis: Long): RelativeAge = when {
        elapsedMillis < 0 -> RelativeAge.Never
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
