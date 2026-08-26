package com.cerocoder.meshrelay.stats.model

/**
 * Hop statistics for one remote node, tallied across the packets a relay has
 * forwarded on its behalf. Ports RemoteNodeStats, mesh_stats.py:346-368.
 *
 * `hop_start` and `hop_limit` are not `optional` in the protobuf, so Wire always
 * supplies a non-null `Int` defaulting to `0` - the same shape a genuine
 * `hop_start` of zero would have, which never happens in practice. The Python
 * reads packets as dicts with unset fields omitted and branches on `is None`; that
 * distinction is gone on the Wire side; the caller restores it by computing
 * `hopsKnown = hopStart != 0` before calling [plus]. Folding an unset packet's
 * zeros into the running sums would pull every relay's hop figures toward zero and
 * make distant nodes look adjacent.
 */
data class RemoteNodeStats(
    val packetCount: Int = 0,
    val hopsMadeSum: Long = 0,
    val hopsMadeCount: Int = 0,
    val hopsLeftSum: Long = 0,
    val hopsLeftCount: Int = 0,
) {
    /** Null when no packet counted here carried hop information - never `0f`. */
    val avgHopsMade: Float?
        get() = if (hopsMadeCount == 0) null else hopsMadeSum.toFloat() / hopsMadeCount

    /** Null when no packet counted here carried hop information - never `0f`. */
    val avgHopsLeft: Float?
        get() = if (hopsLeftCount == 0) null else hopsLeftSum.toFloat() / hopsLeftCount

    /**
     * Records one packet. [packetCount] always advances. The hop sums - hops made
     * (`hopStart - hopLimit`) and hops left (`hopLimit`) - fold in only when
     * [hopsKnown]; a packet without hop information still counts but never skews
     * the averages.
     */
    fun plus(hopStart: Int, hopLimit: Int, hopsKnown: Boolean): RemoteNodeStats {
        val withCount = copy(packetCount = packetCount + 1)
        if (!hopsKnown) return withCount
        return withCount.copy(
            hopsMadeSum = withCount.hopsMadeSum + (hopStart - hopLimit),
            hopsMadeCount = withCount.hopsMadeCount + 1,
            hopsLeftSum = withCount.hopsLeftSum + hopLimit,
            hopsLeftCount = withCount.hopsLeftCount + 1,
        )
    }
}
