package com.cerocoder.meshrelay.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.meshtastic.proto.MeshPacket

private const val SENDER = 0x9e75f1a4.toInt()      // low byte a4
private const val OTHER = 0x11223344                // low byte 44

private fun packet(
    from: Int = SENDER,
    relay: Int = 0x69,
    hopStart: Int = 3,
    hopLimit: Int = 1,
    snr: Float = -7.5f,
    rssi: Int = -94,
) = MeshPacket(
    from = from, relay_node = relay, hop_start = hopStart,
    hop_limit = hopLimit, rx_snr = snr, rx_rssi = rssi,
)

class PacketClassifierTest {

    @Test
    fun `an ordinary forwarded packet is relayed`() {
        val result = PacketClassifier.classify(packet(), emptySet())
        assertEquals(Ingest.Relayed(0x69, SENDER, 3, 1, Signal(-7.5f, -94f)), result)
    }

    @Test
    fun `a packet with no relay byte came to us directly`() {
        // relay_node is not an optional field, so 0 means the sender never set it.
        assertEquals(Ingest.Direct(SENDER, Signal(-7.5f, -94f)), PacketClassifier.classify(packet(relay = 0), emptySet()))
    }

    @Test
    fun `a sender relaying its own packet with no hops made is direct`() {
        // A node writes its own low byte into the relay field on first transmission.
        // Counted as a relay it would appear as a relay for its own traffic only,
        // which is exactly what the Neighbours view exists to separate out.
        val result = PacketClassifier.classify(packet(relay = 0xa4, hopStart = 3, hopLimit = 3), emptySet())
        assertEquals(Ingest.Direct(SENDER, Signal(-7.5f, -94f)), result)
    }

    @Test
    fun `the same low byte with hops made is a genuine relay`() {
        val result = PacketClassifier.classify(packet(relay = 0xa4, hopStart = 3, hopLimit = 2), emptySet())
        assertEquals(Ingest.Relayed(0xa4, SENDER, 3, 2, Signal(-7.5f, -94f)), result)
    }

    @Test
    fun `a sender whose low byte is zero identifies itself as ff`() {
        val zeroTailed = 0x9e75f100.toInt()
        val result = PacketClassifier.classify(
            packet(from = zeroTailed, relay = 0xFF, hopStart = 2, hopLimit = 2), emptySet(),
        )
        assertEquals(Ingest.Direct(zeroTailed, Signal(-7.5f, -94f)), result)
    }

    @Test
    fun `a skipped sender heard first hand is dropped`() {
        // One hop made means we are the first receiver, so this is the skipped node's
        // own transmission and must not be credited to any relay.
        assertEquals(Ingest.Dropped, PacketClassifier.classify(packet(hopStart = 3, hopLimit = 2), setOf(SENDER)))
    }

    @Test
    fun `a skipped sender heard through someone else still counts`() {
        // Two hops made: whatever this node is, something forwarded the packet, and
        // that something is a relay worth measuring.
        val result = PacketClassifier.classify(packet(hopStart = 3, hopLimit = 1), setOf(SENDER))
        assertEquals(Ingest.Relayed(0x69, SENDER, 3, 1, Signal(-7.5f, -94f)), result)
    }

    @Test
    fun `a skipped sender with no hop information is dropped`() {
        assertEquals(Ingest.Dropped, PacketClassifier.classify(packet(relay = 0x69, hopStart = 0, hopLimit = 0), setOf(SENDER)))
    }

    @Test
    fun `skipping one node does not affect another`() {
        val result = PacketClassifier.classify(packet(hopStart = 3, hopLimit = 2), setOf(OTHER))
        assertEquals(Ingest.Relayed(0x69, SENDER, 3, 2, Signal(-7.5f, -94f)), result)
    }

    @Test
    fun `zero received signal strength means the packet carried no signal information`() {
        // THE deviation from the original, and the one most likely to be quietly
        // undone. The Python tool reads packets as dicts with protobuf defaults
        // omitted, so it can tell an absent rx_snr from a real 0.0 dB. Wire cannot:
        // both arrive as 0. RSSI is the witness for the pair because 0 dBm is not
        // physically observable, whereas exactly 0.0 dB SNR is ordinary.
        //
        // Accept rx_rssi == 0 as a real sample and every relay collects a stream of
        // phantom 0/0 readings that drag its averages toward zero.
        assertNull(PacketClassifier.signalOf(packet(snr = 0f, rssi = 0)))
        assertNull(PacketClassifier.signalOf(packet(snr = -7.5f, rssi = 0)))
    }

    @Test
    fun `a real zero decibel signal to noise ratio is kept`() {
        assertEquals(Signal(0f, -94f), PacketClassifier.signalOf(packet(snr = 0f, rssi = -94)))
    }

    @Test
    fun `a packet without signal information is still classified and counted`() {
        val result = PacketClassifier.classify(packet(rssi = 0, snr = 0f), emptySet())
        assertEquals(Ingest.Relayed(0x69, SENDER, 3, 1, null), result)
    }

    @Test
    fun `an encrypted packet is classified like any other`() {
        // The point of the whole tool: relay topology is readable without reading
        // the traffic. An encrypted packet carries relay_node, hops and signal just
        // the same, and dropping it would hide most of the mesh.
        val encrypted = packet().copy(decoded = null, encrypted = okio.ByteString.of(1, 2, 3))
        assertEquals(Ingest.Relayed(0x69, SENDER, 3, 1, Signal(-7.5f, -94f)), PacketClassifier.classify(encrypted, emptySet()))
    }
}
