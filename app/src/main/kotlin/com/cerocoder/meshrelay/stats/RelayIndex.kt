package com.cerocoder.meshrelay.stats

import com.cerocoder.meshrelay.stats.model.RelayStats

/**
 * Inverts the relay table to answer the one question it is not shaped for: for a
 * single remote node, which relays carry its traffic?
 *
 * This has no counterpart in mesh_stats. Its curses screens can only ever show one
 * relay at a time, while the project README calls the answer essential - packets
 * from one node are relayed through different relays, and comparing them is the
 * point of the tool. The fold is over at most 255 relays and is computed on
 * demand rather than stored, so no second index has to be kept in step.
 */
object RelayIndex {

    /**
     * Relays carrying [nodeNum]'s traffic, most of *that node's* packets first -
     * not the busiest relays overall, which is a different question and a different
     * order.
     */
    fun relaysCarrying(nodeNum: Int, relays: List<RelayStats>): List<RelayStats> = relays
        .filter { it.fromNodeStats.containsKey(nodeNum) }
        .sortedByDescending { it.fromNodeStats.getValue(nodeNum).packetCount }
}
