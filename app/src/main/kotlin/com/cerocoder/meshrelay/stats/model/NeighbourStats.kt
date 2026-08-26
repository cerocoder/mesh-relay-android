package com.cerocoder.meshrelay.stats.model

/**
 * Signal history for one directly-heard neighbour (a node whose packets reached
 * this device with no relay in between). Ports NeighbourStat, mesh_stats.py:439-452.
 */
data class NeighbourStats(
    val nodeNum: Int,
    val snr: SignalHistory = SignalHistory(),
    val rssi: SignalHistory = SignalHistory(),
    val packetCount: Int = 0,
    val lastPacketAtMillis: Long = 0,
)
