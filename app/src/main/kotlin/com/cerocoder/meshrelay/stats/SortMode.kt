package com.cerocoder.meshrelay.stats

/**
 * Ports SORT_MODES, mesh_stats.py:167-174 - the first five. [KNOWN_NODES] and
 * [LATEST_PACKET] are additions, not ports: the terminal tool has neither, and
 * a reader comparing the two should not go looking for them.
 *
 * Labels live in the ui layer.
 */
enum class SortMode {
    PACKETS, PERCENT, AVG_SNR, AVG_RSSI, NAME, KNOWN_NODES, LATEST_PACKET;

    /**
     * This mode as the neighbour list can honour it.
     *
     * [KNOWN_NODES] counts the distinct remote nodes a relay forwards for. A
     * neighbour is a single node heard directly and has no such set, so it is not
     * offered in the neighbour sort menu - but the sort mode is one engine-wide
     * value, so it can still arrive here from the relay screen or from the saved
     * default. It degrades to [PACKETS].
     *
     * The neighbour status strip calls this too, so the screen names the order it
     * actually applied rather than the one that was asked for. Both callers must
     * keep using this function: an inlined `if` in either place is how the label
     * and the list start disagreeing.
     */
    fun forNeighbours(): SortMode = if (this == KNOWN_NODES) PACKETS else this
}
