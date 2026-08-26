package com.cerocoder.meshrelay.stats.model

/**
 * Positions heard from one node. Ports NodePositionHistory, mesh_stats.py:310-343.
 */
data class PositionHistory(
    val nodeNum: Int,
    val reports: List<PositionReport> = emptyList(),
) {
    val last: PositionReport? get() = reports.lastOrNull()

    /**
     * The newest report carrying both coordinates and altitude.
     *
     * Null when no report has both - even when reports with coordinates alone
     * exist. That is a quirk of the original and is reproduced deliberately:
     * callers fall back to the node database in that case, and the position source
     * they then label (DB rather than CUR) is what the terminal tool shows.
     */
    val best: PositionReport?
        get() = reports.lastOrNull { it.hasCoordinates && it.hasAltitude }

    fun plus(report: PositionReport): PositionHistory {
        val grown = reports + report
        val trimmed = if (grown.size > MAX_REPORTS) grown.subList(grown.size - MAX_REPORTS, grown.size) else grown
        return copy(reports = trimmed)
    }

    companion object {
        const val MAX_REPORTS = 100
    }
}
