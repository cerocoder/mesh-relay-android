package com.cerocoder.meshrelay.stats.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.Config
import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.User

class AirNodeRecordTest {

    @Test
    fun `a first packet creates the record and stamps it`() {
        val record = AirNodeRecord.folding(
            existing = null,
            num = 0x3f2a,
            user = User(long_name = "PQPL1 Getafe", short_name = "1ce5"),
            atMillis = 1_000L,
        )
        assertEquals(0x3f2a, record.num)
        assertEquals("PQPL1 Getafe", record.longName)
        assertEquals(1_000L, record.receivedAtMillis)
    }

    @Test
    fun `a thin packet does not blank a field a fat one filled`() {
        // The defect this rule prevents: per-record precedence means the panel shows
        // the air record whole, so a field blanked here is a field the panel loses
        // even though the database still knows it.
        val fat = AirNodeRecord.folding(
            existing = null,
            num = 1,
            user = User(long_name = "PQPL1 Getafe", short_name = "1ce5"),
            atMillis = 1_000L,
        )
        val thin = AirNodeRecord.folding(
            existing = fat,
            num = 1,
            user = User(short_name = "1ce5"),
            atMillis = 2_000L,
        )
        assertEquals("PQPL1 Getafe", thin.longName)
        // The stamp still moves: it says when we last heard this node identify
        // itself, not when its identity last changed.
        assertEquals(2_000L, thin.receivedAtMillis)
    }

    @Test
    fun `an empty string is treated as absence, exactly like a null`() {
        val fat = AirNodeRecord.folding(null, 1, User(long_name = "PQPL1 Getafe"), 1_000L)
        val blank = AirNodeRecord.folding(fat, 1, User(long_name = ""), 2_000L)
        assertEquals("PQPL1 Getafe", blank.longName)
    }

    @Test
    fun `an UNSET hardware model does not overwrite a known one`() {
        val known = AirNodeRecord.folding(null, 1, User(hw_model = HardwareModel.HELTEC_MESH_NODE_T114), 1_000L)
        val unset = AirNodeRecord.folding(known, 1, User(hw_model = HardwareModel.UNSET), 2_000L)
        assertEquals("HELTEC_MESH_NODE_T114", unset.hwModel)
    }

    @Test
    fun `role is taken even when it is CLIENT`() {
        // CLIENT is proto3's default and cannot be told from an omitted field. The
        // decision (spec section 3) is to let a real ROUTER -> CLIENT transition
        // through and accept that a User omitting role reports CLIENT.
        val router = AirNodeRecord.folding(null, 1, User(role = Config.DeviceConfig.Role.ROUTER), 1_000L)
        val client = AirNodeRecord.folding(router, 1, User(role = Config.DeviceConfig.Role.CLIENT), 2_000L)
        assertEquals("CLIENT", client.role)
    }

    @Test
    fun `a public key once observed is never unlearned`() {
        val keyed = AirNodeRecord.folding(null, 1, User(public_key = okio.ByteString.of(1, 2, 3)), 1_000L)
        val bare = AirNodeRecord.folding(keyed, 1, User(short_name = "1ce5"), 2_000L)
        assertTrue(bare.hasPublicKey)
    }
}
