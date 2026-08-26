package com.cerocoder.meshrelay

import org.junit.Assert.assertEquals
import org.junit.Test
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket

/**
 * Proves the protobuf dependency resolves and that the fields this whole port
 * depends on exist and round-trip. If relay_node ever disappears from the
 * schema, this is the test that says so.
 */
class ProtobufSmokeTest {

    @Test
    fun `relay fields survive an encode and decode round trip`() {
        val original = FromRadio(
            packet = MeshPacket(
                from = 0x9e75f1a4.toInt(),
                relay_node = 0x69,
                hop_start = 3,
                hop_limit = 1,
                rx_snr = -7.5f,
                rx_rssi = -94,
            ),
        )

        val decoded = FromRadio.ADAPTER.decode(original.encode())

        assertEquals(0x69, decoded.packet?.relay_node)
        assertEquals(3, decoded.packet?.hop_start)
        assertEquals(1, decoded.packet?.hop_limit)
        assertEquals(-7.5f, decoded.packet?.rx_snr!!, 0.001f)
        assertEquals(-94, decoded.packet?.rx_rssi)
    }
}
