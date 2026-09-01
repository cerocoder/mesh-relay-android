package com.cerocoder.meshrelay.stats.model

/**
 * Signal statistics for one directly-heard neighbour (a node whose packets
 * reached this device with no relay in between). Ports NeighbourStat,
 * mesh_stats.py:439-452.
 *
 * [snr] and [rssi] were [SignalHistory] until the signal series arrived. Their
 * sample lists were never read by anything in `main/` and are now genuinely
 * duplicate storage - `MeshStatsEngine` keeps a `SignalSeriesBuffer` per
 * neighbour, capped ten times higher and carrying the position each sample was
 * taken at. [TelemetryRecord] still uses [SignalHistory]; telemetry really does
 * need a per-metric sample list, so the type stays.
 */
data class NeighbourStats(
    val nodeNum: Int,
    val snr: SignalStats = SignalStats.EMPTY,
    val rssi: SignalStats = SignalStats.EMPTY,
    val packetCount: Int = 0,
    val lastPacketAtMillis: Long = 0,
)
