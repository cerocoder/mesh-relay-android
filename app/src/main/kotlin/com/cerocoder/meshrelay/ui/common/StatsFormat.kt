package com.cerocoder.meshrelay.ui.common

import com.cerocoder.meshrelay.stats.model.SignalStats
import java.util.Locale

/**
 * Pure, locale-aware number formatting shared by every card that shows a
 * signal triple or one quantity's share of a total - the relay list's
 * `RelayCard` today, Task 23's neighbour cards next (both need the same
 * triple and the same percentage shape, just over different node
 * collections). Ports the fixed-width formatting mesh_stats.py:1412-1413,
 * :1471-1472 (the rxSnr/rxRssi triple) and :1461-1462 (the percentage) build
 * for the terminal, minus the column padding a touch screen has no use for.
 *
 * No Compose import, on the same principle [PositionLineText] already
 * follows: every string this object's callers wrap its output in (a unit
 * suffix, a "not available" fallback) is resolved from resources by the
 * caller, not here, so this stays testable on the JVM without a Composable
 * host.
 */
object StatsFormat {

    /**
     * A single instantaneous reading's precision (`:>4.0f` in the original) -
     * min, max and the latest value are all "spot" readings, not averages,
     * so whole units are all any of them claims.
     */
    private const val SPOT_PATTERN = "%.0f"

    /** The average's own precision (`:>4.1f` in the original). */
    private const val AVG_PATTERN = "%.1f"

    private const val PERCENT_PATTERN = "%.1f"

    /** A rate's own precision (`:.1f` in the original's `packets_per_hour` line,
     *  mesh_stats.py:1824) - numerically the same pattern as [AVG_PATTERN], kept
     *  as its own named constant because the two format unrelated quantities. */
    private const val RATE_PATTERN = "%.1f"

    /**
     * Structural glue, not translatable prose - the same treatment
     * [PositionLineText]'s direction separator gets.
     */
    private const val TRIPLE_SEPARATOR = "/"

    /**
     * The minimum out of a [signalTriple], on its own - the detail screen's
     * signal block labels min/avg/max/last/count separately (mesh_stats.py:
     * 1826-1830's `Min:`/`Avg:`/`Max:`/`Last:`/`Count:` rows) rather than the
     * card's compact slashed form, so each needs its own formatter instead of
     * a string this object would have to split back apart. `null` before
     * [stats] has any data, on the same terms as [signalTriple] and [signalLast].
     */
    fun signalMin(stats: SignalStats, locale: Locale): String? =
        if (stats.hasData) String.format(locale, SPOT_PATTERN, stats.minVal) else null

    /** The average out of a [signalTriple], on its own. See [signalMin]. */
    fun signalAvg(stats: SignalStats, locale: Locale): String? =
        if (stats.hasData) String.format(locale, AVG_PATTERN, stats.avg) else null

    /** The maximum out of a [signalTriple], on its own. See [signalMin]. */
    fun signalMax(stats: SignalStats, locale: Locale): String? =
        if (stats.hasData) String.format(locale, SPOT_PATTERN, stats.maxVal) else null

    /**
     * `"min/avg/max"`, ports the triple mesh_stats.py:1412-1413 (and
     * :1471-1472 for rxRssi) builds as `f"{min:>4.0f}/{avg:>4.1f}/{max:>4.0f}"`.
     * `null` before [stats] has any data - the same case the original's
     * `"  --/  --/  --"` placeholder covers; the caller supplies its own
     * localized fallback text for that case.
     *
     * Built from [signalMin]/[signalAvg]/[signalMax] rather than duplicating
     * their `String.format` calls, so the two shapes of this data (slashed
     * triple, five separately labelled stats) can never drift apart.
     */
    fun signalTriple(stats: SignalStats, locale: Locale): String? {
        if (!stats.hasData) return null
        val min = signalMin(stats, locale)
        val avg = signalAvg(stats, locale)
        val max = signalMax(stats, locale)
        return "$min$TRIPLE_SEPARATOR$avg$TRIPLE_SEPARATOR$max"
    }

    /**
     * The latest reading, at [SPOT_PATTERN] precision like the min/max either
     * side of it in [signalTriple] - a single instantaneous value, not an
     * average. `null` before [stats] has any data.
     */
    fun signalLast(stats: SignalStats, locale: Locale): String? =
        if (stats.hasData) String.format(locale, SPOT_PATTERN, stats.lastVal) else null

    /**
     * A rate of packets per hour, ports the `:.1f` precision mesh_stats.py:1824
     * formats `packets_per_hour` at. Unlike the signal readings above, a rate of
     * zero (a relay heard only once, or not long enough to measure) is a real
     * answer, not an absence of data - so this always returns a value, never `null`.
     */
    fun packetsPerHour(value: Float, locale: Locale): String = String.format(locale, RATE_PATTERN, value)

    /**
     * [part]'s share of [total] as a percentage, ports the `pct` calculation
     * at mesh_stats.py:1461-1462 (`pct = packet_count / total_relayed * 100
     * if total_relayed > 0 else 0`). Zero when [total] is not positive,
     * rather than the division by zero a literal port would perform.
     */
    fun percentageOf(part: Int, total: Int, locale: Locale): String {
        val percent = if (total > 0) part.toFloat() / total * 100f else 0f
        return String.format(locale, PERCENT_PATTERN, percent)
    }
}
