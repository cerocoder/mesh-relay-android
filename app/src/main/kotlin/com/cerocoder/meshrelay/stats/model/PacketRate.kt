package com.cerocoder.meshrelay.stats.model

/** The one packets-per-hour rule, shared by [RelayStats] and [NeighbourStats]. */
internal object PacketRate {
    /** Fewer packets than this say nothing about how often a node sends. The owner's number. */
    const val MIN_PACKETS = 3

    /** A shorter window than this makes the rate swing wildly. The owner's number: one minute. */
    const val MIN_WINDOW_MILLIS = 60_000L

    /**
     * Packets per hour over the observed window ([firstAtMillis] to [lastAtMillis]); null until
     * there are at least [MIN_PACKETS] packets spread over at least [MIN_WINDOW_MILLIS], so a
     * node heard twice a few seconds apart does not read thousands an hour.
     */
    fun perHour(packetCount: Int, firstAtMillis: Long, lastAtMillis: Long): Float? {
        if (packetCount < MIN_PACKETS) return null
        val windowMillis = lastAtMillis - firstAtMillis
        if (windowMillis < MIN_WINDOW_MILLIS) return null
        return packetCount / (windowMillis / 1000f) * 3600f
    }
}
