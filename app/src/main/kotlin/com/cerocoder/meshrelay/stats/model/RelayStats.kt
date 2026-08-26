package com.cerocoder.meshrelay.stats.model

import java.util.Locale

/**
 * Signal and hop statistics for one relay, identified by the last byte of its
 * NodeNum. Ports RelayNodeStats, mesh_stats.py:381-437.
 *
 * Multiple nodes in the directory can share a [relayByte] (it is only one byte
 * wide); resolving that ambiguity to a display name is the node directory's job,
 * not this type's - [nodeName] is populated by the caller only when the byte
 * matched exactly one node.
 */
data class RelayStats(
    val relayByte: Int,
    val nodeName: String = "",
    val snr: SignalStats = SignalStats.EMPTY,
    val rssi: SignalStats = SignalStats.EMPTY,
    val packetCount: Int = 0,
    val firstPacketAtMillis: Long = 0,
    val lastPacketAtMillis: Long = 0,
    val fromNodeStats: Map<Int, RemoteNodeStats> = emptyMap(),
) {
    /** Always two lower case hex digits, e.g. `"0x0a"`, never `"0xa"`. */
    val hexId: String get() = String.format(Locale.ROOT, "0x%02x", relayByte)

    /** Distinct remote nodes whose packets this relay has forwarded. */
    val knownNodesCount: Int get() = fromNodeStats.size

    /**
     * Packets per hour over the observed window ([firstPacketAtMillis] to
     * [lastPacketAtMillis]). Zero before there is a window to measure: with fewer
     * than two packets, or a non-positive duration, there is nothing to divide by
     * that would not report an infinite rate for a relay's first packet.
     */
    val packetsPerHour: Float
        get() {
            if (packetCount < 2) return 0f
            val durationSeconds = (lastPacketAtMillis - firstPacketAtMillis) / 1000f
            if (durationSeconds <= 0f) return 0f
            return packetCount / durationSeconds * 3600f
        }
}
