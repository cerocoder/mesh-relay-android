package com.cerocoder.meshrelay.stats.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AggregatesTest {

    @Test
    fun `hop averages follow the packets that carried hop information`() {
        val stats = RemoteNodeStats()
            .plus(hopStart = 3, hopLimit = 1, hopsKnown = true)
            .plus(hopStart = 5, hopLimit = 2, hopsKnown = true)
        assertEquals(2, stats.packetCount)
        assertEquals(2.5f, stats.avgHopsMade!!, 0.0001f)   // (2 + 3) / 2
        assertEquals(1.5f, stats.avgHopsLeft!!, 0.0001f)   // (1 + 2) / 2
    }

    @Test
    fun `a packet without hop information still counts but does not skew the averages`() {
        // hop_start is not an optional field, so 0 is what an unset value looks like.
        // Folding 0 into the average would pull every relay's hop count toward zero
        // and make distant nodes look adjacent.
        val stats = RemoteNodeStats()
            .plus(hopStart = 3, hopLimit = 1, hopsKnown = true)
            .plus(hopStart = 0, hopLimit = 0, hopsKnown = false)
        assertEquals(2, stats.packetCount)
        assertEquals(2f, stats.avgHopsMade!!, 0.0001f)
        assertEquals(1f, stats.avgHopsLeft!!, 0.0001f)
    }

    @Test
    fun `hop averages are absent when nothing carried hop information`() {
        val stats = RemoteNodeStats().plus(0, 0, hopsKnown = false)
        assertNull(stats.avgHopsMade)
        assertNull(stats.avgHopsLeft)
    }

    @Test
    fun `the hexadecimal identifier is always two lower case digits`() {
        assertEquals("0x69", RelayStats(relayByte = 0x69).hexId)
        assertEquals("0x0a", RelayStats(relayByte = 0x0a).hexId)
        assertEquals("0xff", RelayStats(relayByte = 0xff).hexId)
    }

    @Test
    fun `known nodes counts the distinct senders behind a relay`() {
        val relay = RelayStats(
            relayByte = 0x69,
            fromNodeStats = mapOf(1 to RemoteNodeStats(), 2 to RemoteNodeStats()),
        )
        assertEquals(2, relay.knownNodesCount)
    }

    @Test
    fun `the packet rate is measured over the observed window`() {
        val relay = RelayStats(
            relayByte = 0x69,
            packetCount = 60,
            firstPacketAtMillis = 1_000_000L,
            lastPacketAtMillis = 1_000_000L + 30 * 60_000L,
        )
        assertEquals(120f, relay.packetsPerHour, 0.01f)   // 60 packets in half an hour
    }

    @Test
    fun `the packet rate is zero before there is a window to measure`() {
        // One packet gives no duration, and dividing by it would report an infinite
        // rate for every relay in its first second.
        assertEquals(0f, RelayStats(relayByte = 1, packetCount = 1).packetsPerHour, 0.0001f)
        assertEquals(0f, RelayStats(relayByte = 1, packetCount = 0).packetsPerHour, 0.0001f)
        assertEquals(
            0f,
            RelayStats(relayByte = 1, packetCount = 9, firstPacketAtMillis = 5L, lastPacketAtMillis = 5L).packetsPerHour,
            0.0001f,
        )
    }

    @Test
    fun `an uptime that falls back counts as a restart`() {
        // The only way to notice a node rebooting: the counter starts again from zero.
        var record = TelemetryRecord()
        record = record.withUptime(3_600)
        record = record.withUptime(7_200)
        assertEquals(0, record.observedRestartCount)
        record = record.withUptime(30)
        assertEquals(1, record.observedRestartCount)
        assertEquals(30, record.lastUptimeSeconds)
    }

    @Test
    fun `two packets over a positive window give a real rate`() {
        // Separates the packetCount < 2 guard from the duration guard: every other
        // case in this file has both guards true or both false at once. Nudging the
        // threshold to packetCount < 3 (or requiring more than two packets some
        // other way) would return 0f here instead of a real rate.
        val relay = RelayStats(
            relayByte = 1,
            packetCount = 2,
            firstPacketAtMillis = 0L,
            lastPacketAtMillis = 1_000L,
        )
        assertEquals(7200f, relay.packetsPerHour, 0.01f)   // 2 packets in 1 second
    }

    @Test
    fun `an uptime repeated exactly is not a restart`() {
        // Identical uptime_seconds arriving twice is ordinary on a lossy mesh - the
        // same telemetry reaching this device by two relay paths. A regression that
        // treats "not greater than" as a fall (using <= instead of <) would count
        // this as a reboot and inflate the restart count for a node that never went
        // down.
        var record = TelemetryRecord()
            .withUptime(3_600)
            .withUptime(7_200)
            .withUptime(30)
        record = record.withUptime(30)
        assertEquals(1, record.observedRestartCount)
        assertEquals(30, record.lastUptimeSeconds)
    }
}
