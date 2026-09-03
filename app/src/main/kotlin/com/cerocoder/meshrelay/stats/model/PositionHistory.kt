package com.cerocoder.meshrelay.stats.model

/**
 * Positions heard from one node. Ports NodePositionHistory, mesh_stats.py:310-343.
 */
data class PositionHistory(
    val nodeNum: Int,
    val reports: List<PositionReport> = emptyList(),
) {
    /**
     * The newest report that carries coordinates.
     *
     * Altitude is deliberately **not** part of the test. A `Position` message
     * carries its coordinates and its altitude as independently optional fields,
     * so a node with a 2D fix - or a fixed node configured with a latitude and a
     * longitude and nothing else - broadcasts positions with no altitude at all.
     * Requiring both, as this property used to, meant no report from such a node
     * ever qualified and its card fell back to the node database's entry for ever,
     * however many fresh positions arrived. A stale position presented as the
     * node's position is the one failure this application cannot afford.
     *
     * Whatever altitude the winning report carries is the altitude shown, and that
     * may be none. It is never borrowed from an older report: one report is one
     * moment, and coordinates from now beside an altitude from ten minutes ago
     * would be a reading that never existed.
     */
    val newestWithCoordinates: PositionReport?
        get() = reports.lastOrNull { it.hasCoordinates }

    fun plus(report: PositionReport): PositionHistory {
        val grown = reports + report
        val trimmed = if (grown.size > MAX_REPORTS) grown.subList(grown.size - MAX_REPORTS, grown.size) else grown
        return copy(reports = trimmed)
    }

    companion object {
        const val MAX_REPORTS = 100
    }
}
