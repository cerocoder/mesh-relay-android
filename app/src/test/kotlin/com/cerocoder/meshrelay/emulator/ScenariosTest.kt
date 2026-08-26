package com.cerocoder.meshrelay.emulator

import com.cerocoder.meshrelay.stats.Geo
import com.cerocoder.meshrelay.stats.Ingest
import com.cerocoder.meshrelay.stats.PacketClassifier
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenariosTest {

    private val scenario = Scenarios.all.first()
    private val traffic = scenario.trafficFrames().take(400).mapNotNull { it.packet }.toList()

    @Test
    fun `there is enough traffic to fill a screen`() {
        assertTrue("traffic must not run out immediately", traffic.size >= 100)
    }

    @Test
    fun `traffic exercises every branch of the classifier`() {
        val kinds = traffic.map { PacketClassifier.classify(it, emptySet()) }
        assertTrue("no relayed packets", kinds.any { it is Ingest.Relayed })
        assertTrue("no direct packets", kinds.any { it is Ingest.Direct })
    }

    @Test
    fun `several distinct relays appear`() {
        val relays = traffic.mapNotNull { (PacketClassifier.classify(it, emptySet()) as? Ingest.Relayed)?.relayByte }
        assertTrue("need at least three relays, had ${relays.distinct().size}", relays.distinct().size >= 3)
    }

    @Test
    fun `at least one relay byte matches more than one node in the database`() {
        // The whole reason the relay list shows a match count: one byte is not an
        // identity. A demo where every byte resolves uniquely would let a screen
        // that ignores ambiguity look correct.
        val byLowByte = scenario.nodes.groupBy { Geo.lastByteOfNodeNum(it.num) }
        assertTrue("no ambiguous relay byte in the scenario", byLowByte.any { it.value.size > 1 })
    }

    @Test
    fun `signal strengths span enough of the scale to show a gauge working`() {
        val snr = traffic.filter { it.rx_rssi != 0 }.map { it.rx_snr }
        assertTrue("SNR spread too narrow", snr.max() - snr.min() >= 10f)
    }

    @Test
    fun `encrypted packets are present`() {
        // Most real mesh traffic cannot be decoded by the phone, and a demo made
        // only of decoded packets would hide any screen that mishandles them.
        assertTrue(traffic.any { it.encrypted != null })
    }

    @Test
    fun `position and telemetry packets are present`() {
        val ports = traffic.mapNotNull { it.decoded?.portnum?.name }.toSet()
        assertTrue("no POSITION_APP", "POSITION_APP" in ports)
        assertTrue("no TELEMETRY_APP", "TELEMETRY_APP" in ports)
    }

    @Test
    fun `every scenario identifier is unique`() {
        assertTrue(Scenarios.all.map { it.id }.distinct().size == Scenarios.all.size)
    }
}
