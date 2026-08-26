package com.cerocoder.meshrelay.stats.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.Config
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.Position
import org.meshtastic.proto.User

class NodeRecordTest {

    @Test
    fun `identity fields come across from the protobuf`() {
        val info = NodeInfo(
            num = 0x9e75f1a4.toInt(),
            user = User(long_name = "PQPL1", short_name = "1ce5", hw_model = HardwareModel.T_ECHO),
            snr = 6.25f,
            last_heard = 1_700_000_000,
            hops_away = 2,
        )
        val record = NodeRecord.fromProto(info)

        assertEquals(0x9e75f1a4.toInt(), record.num)
        assertEquals("PQPL1", record.longName)
        assertEquals("1ce5", record.shortName)
        assertEquals("T_ECHO", record.hwModel)
        assertEquals(6.25f, record.dbSnr!!, 0.0001f)
        assertEquals(1_700_000_000, record.lastHeardEpochSeconds)
        assertEquals(2, record.hopsAway)
    }

    @Test
    fun `an absent role reads as CLIENT`() {
        // The protocol default. The detail screen uses the role to judge how likely
        // a candidate is to be relaying at all, so a blank there would remove the
        // one hint that distinguishes a router from a silent client.
        assertEquals("CLIENT", NodeRecord.fromProto(NodeInfo(num = 1, user = User())).role)
    }

    @Test
    fun `a declared role is carried through`() {
        val info = NodeInfo(num = 1, user = User(role = Config.DeviceConfig.Role.ROUTER))
        assertEquals("ROUTER", NodeRecord.fromProto(info).role)
    }

    @Test
    fun `a node with no user record still produces a record`() {
        // Nodes appear in the database from routing alone, before ever broadcasting
        // their node info. Dropping them would hide candidate relays.
        val record = NodeRecord.fromProto(NodeInfo(num = 42))
        assertEquals(42, record.num)
        assertNull(record.longName)
        assertNull(record.shortName)
        assertFalse(record.hasPublicKey)
    }

    @Test
    fun `zero signal to noise and zero last heard read as absent`() {
        // Neither field is optional, so 0 is what unset looks like. Shown literally,
        // a node nobody has heard from would claim to have been heard in 1970.
        val record = NodeRecord.fromProto(NodeInfo(num = 1, snr = 0f, last_heard = 0))
        assertNull(record.dbSnr)
        assertNull(record.lastHeardEpochSeconds)
    }

    @Test
    fun `a database position becomes a position report with no precision`() {
        // The database does not carry precision_bits, so the obfuscation radius is
        // unknown for a database position and the direction must not be suppressed
        // on account of it.
        val info = NodeInfo(num = 1, position = Position(latitude_i = 404168000, longitude_i = -37038000, altitude = 667))
        val position = NodeRecord.fromProto(info).dbPosition!!
        assertEquals(40.4168, position.latitude!!, 1e-9)
        assertEquals(667, position.altitude)
        assertNull(position.precisionBits)
    }

    @Test
    fun `a public key is reported as present without being exposed`() {
        val info = NodeInfo(num = 1, user = User(public_key = okio.ByteString.of(1, 2, 3)))
        assertTrue(NodeRecord.fromProto(info).hasPublicKey)
    }
}
