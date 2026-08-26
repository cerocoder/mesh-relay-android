package com.cerocoder.meshrelay.stats.model

/**
 * Session-wide packet tallies shown in the snapshot header. Ports the counters
 * accumulated ad hoc across mesh_stats.py's packet handlers.
 */
data class Counters(
    val totalPackets: Int = 0,
    val totalRelayedPackets: Int = 0,
    val totalDirectPackets: Int = 0,
    val relayCount: Int = 0,
) {
    companion object {
        val EMPTY = Counters()
    }
}
