package com.cerocoder.meshrelay.stats.model

data class Sample(val atMillis: Long, val value: Float)

/**
 * Statistics plus the samples behind them. Ports SignalHistoryStat,
 * mesh_stats.py:254-266.
 *
 * The sample list is capped where the original's grows without bound. A phone
 * left collecting for an afternoon would otherwise accumulate one entry per
 * packet per metric per node. Statistics are kept whole across eviction: they are
 * folded, not recomputed from the surviving window, so the session minimum does
 * not creep upward as old samples fall off.
 */
data class SignalHistory(
    val stats: SignalStats = SignalStats.EMPTY,
    val samples: List<Sample> = emptyList(),
) {
    fun plus(atMillis: Long, value: Float): SignalHistory {
        val grown = samples + Sample(atMillis, value)
        val trimmed = if (grown.size > MAX_SAMPLES) grown.subList(grown.size - MAX_SAMPLES, grown.size) else grown
        return SignalHistory(stats.plus(value), trimmed)
    }

    companion object {
        const val MAX_SAMPLES = 500
    }
}
