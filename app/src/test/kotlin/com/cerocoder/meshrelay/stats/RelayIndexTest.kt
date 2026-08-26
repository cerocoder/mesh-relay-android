package com.cerocoder.meshrelay.stats

import com.cerocoder.meshrelay.stats.model.RelayStats
import com.cerocoder.meshrelay.stats.model.RemoteNodeStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private val REMOTE = 0xA1000C33.toInt()
private val OTHER_REMOTE = 0xA100119C.toInt()

/**
 * The reason this exists at all: the README calls knowing which relays carry one
 * node's traffic essential, and the terminal tool can only ever show it one relay
 * at a time.
 */
class RelayIndexTest {

    /**
     * [totalPackets] is deliberately unrelated to the per-node counts: a relay that
     * is busy overall is not the one that carries the most of *this* node's traffic,
     * and the ordering has to follow the latter.
     */
    private fun relay(relayByte: Int, totalPackets: Int, carried: Map<Int, Int>) = RelayStats(
        relayByte = relayByte,
        packetCount = totalPackets,
        fromNodeStats = carried.mapValues { (_, count) -> RemoteNodeStats(packetCount = count) },
    )

    @Test
    fun `relays carrying a node are returned most packets first`() {
        val relays = listOf(
            relay(0x2a, totalPackets = 100, carried = mapOf(REMOTE to 5)),
            relay(0x5d, totalPackets = 20, carried = mapOf(REMOTE to 12)),
            relay(0x77, totalPackets = 50, carried = mapOf(REMOTE to 3)),
        )

        val carrying = RelayIndex.relaysCarrying(REMOTE, relays)

        // 12, 5, 3 of this node's packets. The list order is 5, 12, 3; ascending
        // would be 3, 5, 12; by the relays' own totals it would be 0x2a, 0x77, 0x5d.
        assertEquals(listOf(0x5d, 0x2a, 0x77), carrying.map { it.relayByte })
    }

    @Test
    fun `a node carried by no relay yields an empty list`() {
        val relays = listOf(
            relay(0x2a, totalPackets = 100, carried = mapOf(OTHER_REMOTE to 5)),
            relay(0x5d, totalPackets = 20, carried = mapOf(OTHER_REMOTE to 12)),
        )

        assertTrue(RelayIndex.relaysCarrying(REMOTE, relays).isEmpty())
        assertTrue(RelayIndex.relaysCarrying(REMOTE, emptyList()).isEmpty())
    }

    @Test
    fun `a node carried by several relays yields all of them`() {
        val relays = listOf(
            relay(0x2a, totalPackets = 100, carried = mapOf(REMOTE to 4, OTHER_REMOTE to 30)),
            relay(0x33, totalPackets = 60, carried = mapOf(OTHER_REMOTE to 60)),
            relay(0x5d, totalPackets = 20, carried = mapOf(REMOTE to 9)),
            relay(0x77, totalPackets = 15, carried = mapOf(REMOTE to 1, OTHER_REMOTE to 2)),
            relay(0x9c, totalPackets = 40, carried = mapOf(OTHER_REMOTE to 40)),
        )

        val carrying = RelayIndex.relaysCarrying(REMOTE, relays)

        // Every relay that carries it and no other: 0x33 and 0x9c only ever carried
        // somebody else's traffic.
        assertEquals(3, carrying.size)
        assertEquals(listOf(0x5d, 0x2a, 0x77), carrying.map { it.relayByte })
    }
}
