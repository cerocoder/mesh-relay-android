package com.cerocoder.meshrelay.stats.model

/** The one packets-per-hour rule, shared by [RelayStats] and [NeighbourStats]. */
internal object PacketRate {
    /**
     * Packets per hour over the observed window ([firstAtMillis] to [lastAtMillis]). Zero before
     * there is a window to measure: with fewer than two packets, or a non-positive duration,
     * there is nothing to divide by that would not report an infinite rate for a first packet.
     */
    fun perHour(packetCount: Int, firstAtMillis: Long, lastAtMillis: Long): Float {
        if (packetCount < 2) return 0f
        val durationSeconds = (lastAtMillis - firstAtMillis) / 1000f
        if (durationSeconds <= 0f) return 0f
        return packetCount / durationSeconds * 3600f
    }
}
