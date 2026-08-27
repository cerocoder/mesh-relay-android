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

    /**
     * Structural glue, not translatable prose - the same treatment
     * [PositionLineText]'s direction separator gets.
     */
    private const val TRIPLE_SEPARATOR = "/"

    /**
     * `"min/avg/max"`, ports the triple mesh_stats.py:1412-1413 (and
     * :1471-1472 for rxRssi) builds as `f"{min:>4.0f}/{avg:>4.1f}/{max:>4.0f}"`.
     * `null` before [stats] has any data - the same case the original's
     * `"  --/  --/  --"` placeholder covers; the caller supplies its own
     * localized fallback text for that case.
     */
    fun signalTriple(stats: SignalStats, locale: Locale): String? {
        if (!stats.hasData) return null
        val min = String.format(locale, SPOT_PATTERN, stats.minVal)
        val avg = String.format(locale, AVG_PATTERN, stats.avg)
        val max = String.format(locale, SPOT_PATTERN, stats.maxVal)
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
