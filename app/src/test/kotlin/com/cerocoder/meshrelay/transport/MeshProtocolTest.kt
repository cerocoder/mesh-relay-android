package com.cerocoder.meshrelay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeshProtocolTest {

    @Test
    fun `a demo address yields the scenario id`() {
        assertEquals("5nodes", MeshProtocol.scenarioIdOrNull("m:5nodes"))
    }

    @Test
    fun `a BLE address is not treated as a demo address`() {
        assertNull(MeshProtocol.scenarioIdOrNull("xAA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `a demo address without an id is discarded`() {
        assertNull(MeshProtocol.scenarioIdOrNull("m:"))
    }

    @Test
    fun `a BLE address yields the MAC`() {
        assertEquals("AA:BB:CC:DD:EE:FF", MeshProtocol.bleMacOrNull("xAA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `a demo address is not treated as a BLE address`() {
        assertNull(MeshProtocol.bleMacOrNull("m:5nodes"))
    }

    @Test
    fun `a demo device address is built from the scenario id`() {
        assertEquals("m:5nodes", DeviceListEntry.Demo("5nodes", "Demo: 5 nodes").address)
    }

    @Test
    fun `a BLE device address is built from the MAC`() {
        val entry = DeviceListEntry.Ble("Meshtastic_a1b2", "AA:BB:CC:DD:EE:FF", bonded = true, rssi = -60)
        assertEquals("xAA:BB:CC:DD:EE:FF", entry.address)
    }

    @Test
    fun `the reload nonce differs from both handshake nonces`() {
        // If it ever collides, a reload silently reruns handshake completion and hands
        // a dying link a free extension of the silence timeout.
        assertNotEquals(MeshProtocol.CONFIG_NONCE, MeshProtocol.NODE_INFO_RELOAD_NONCE)
        assertNotEquals(MeshProtocol.NODE_INFO_NONCE, MeshProtocol.NODE_INFO_RELOAD_NONCE)
    }
}
