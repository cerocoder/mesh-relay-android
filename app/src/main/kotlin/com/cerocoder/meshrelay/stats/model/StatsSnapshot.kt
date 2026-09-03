package com.cerocoder.meshrelay.stats.model

import com.cerocoder.meshrelay.stats.SortMode

/**
 * Everything one frame of the interface needs, taken from the engine in one piece.
 *
 * [relays] and [neighbours] arrive already sorted per [sortMode]. Sorting is
 * engine state - it is the terminal tool's `[S]` key - and a screen that re-sorted
 * on every recomposition would repeat the work for nothing.
 */
data class StatsSnapshot(
    val relays: List<RelayStats>,
    val neighbours: List<NeighbourStats>,
    val counters: Counters,
    val paused: Boolean,
    val sortMode: SortMode,
    val lastPacketAtMillis: Long?,
    val lastRelayedPacketAtMillis: Long?,
    val directory: NodeDirectorySnapshot,
    val skippedRelayNodes: Set<Int>,
) {
    companion object {
        /** Before a single packet has arrived - what the screens render at startup. */
        val EMPTY = StatsSnapshot(
            relays = emptyList(),
            neighbours = emptyList(),
            counters = Counters.EMPTY,
            paused = false,
            sortMode = SortMode.PACKETS,
            lastPacketAtMillis = null,
            lastRelayedPacketAtMillis = null,
            directory = NodeDirectorySnapshot(
                nodes = emptyMap(),
                airNodes = emptyMap(),
                loadedAtMillis = null,
                localNodeNum = null,
                positions = emptyMap(),
                telemetry = emptyMap(),
                skipped = emptySet(),
            ),
            skippedRelayNodes = emptySet(),
        )
    }
}
