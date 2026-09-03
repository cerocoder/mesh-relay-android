package com.cerocoder.meshrelay.ui.detail

import com.cerocoder.meshrelay.stats.model.AirNodeRecord
import com.cerocoder.meshrelay.stats.model.NodeDirectorySnapshot
import com.cerocoder.meshrelay.stats.model.NodeRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

// Node numbers are arbitrary and distinct - each test only cares which store(s)
// hold an entry for its own number, not the number itself.
private const val DB_ONLY = 0xA100101
private const val AIR_ONLY = 0xA100102
private const val BOTH = 0xA100103

private fun dbRecord(num: Int) = NodeRecord(
    num = num,
    longName = "DB Long",
    shortName = "dbsh",
    hwModel = "T_ECHO",
    role = "CLIENT",
    dbPosition = null,
    dbSnr = 6.5f,
    lastHeardEpochSeconds = 111,
    hopsAway = 2,
    hasPublicKey = true,
    receivedAtMillis = 555L,
)

private fun airRecord(num: Int) = AirNodeRecord(
    num = num,
    longName = "Air Long",
    shortName = "airsh",
    hwModel = "HELTEC_V3",
    role = "ROUTER",
    hasPublicKey = false,
    receivedAtMillis = 999L,
)

private fun snapshotOf(
    nodes: Map<Int, NodeRecord> = emptyMap(),
    airNodes: Map<Int, AirNodeRecord> = emptyMap(),
) = NodeDirectorySnapshot(
    nodes = nodes,
    airNodes = airNodes,
    loadedAtMillis = null,
    localNodeNum = null,
    positions = emptyMap(),
    telemetry = emptyMap(),
    skipped = emptySet(),
)

/**
 * Pins [candidateRecord] directly - the seam Critical finding C1 fixed. Before
 * the fix, [MatchingNodesTab] built its candidate list with
 * `candidates.mapNotNull { directory.node(it)?.let { ... } }`, which silently
 * dropped any candidate [NodeDirectorySnapshot.node] returns null for. Since
 * `matchingNodeNums` scans the union of both stores, that included every
 * air-only candidate - exactly the node the ambiguity is often about, per the
 * finding's own case acceptance item.
 *
 * No Compose/instrumented test harness exists in this module (see
 * `AGENTS.md`/the project's own note on verifying on-device instead), so this
 * exercises the plain-Kotlin composition function directly rather than
 * through the composable tree.
 */
class MatchingNodesTabCandidateRecordTest {

    @Test
    fun `an air-only candidate keeps its card with identity but no database fields`() {
        // This is the Critical finding's own failure mode: a node the radio has
        // never listed, reachable only because matchingNodeNums now scans the
        // union. directory.node(AIR_ONLY) is null here - candidateRecord must not
        // return null or throw, the way the old mapNotNull effectively did.
        val directory = snapshotOf(airNodes = mapOf(AIR_ONLY to airRecord(AIR_ONLY)))

        val record = candidateRecord(directory, AIR_ONLY)

        assertEquals(AIR_ONLY, record.num)
        assertEquals("Air Long", record.longName)
        assertEquals("airsh", record.shortName)
        assertEquals("HELTEC_V3", record.hwModel)
        assertEquals("ROUTER", record.role)
        assertFalse(record.hasPublicKey)
        // Nothing the radio's own database ever said - there is no database
        // entry to read one from.
        assertNull(record.dbPosition)
        assertNull(record.dbSnr)
        assertNull(record.lastHeardEpochSeconds)
        assertNull(record.hopsAway)
        assertEquals(0L, record.receivedAtMillis)
    }

    @Test
    fun `a database-only candidate gets its identity through the normalising path`() {
        val directory = snapshotOf(nodes = mapOf(DB_ONLY to dbRecord(DB_ONLY)))

        val record = candidateRecord(directory, DB_ONLY)

        assertEquals("DB Long", record.longName)
        assertEquals("dbsh", record.shortName)
        assertEquals("T_ECHO", record.hwModel)
        assertEquals("CLIENT", record.role)
        assertEquals(true, record.hasPublicKey)
        assertEquals(6.5f, record.dbSnr!!, 1e-6f)
        assertEquals(111, record.lastHeardEpochSeconds)
        assertEquals(2, record.hopsAway)
        assertEquals(555L, record.receivedAtMillis)
    }

    @Test
    fun `a candidate in both stores shows air identity with the database's own fields`() {
        // The per-record ruling (I1/C1's shared premise): identity comes from the
        // air record whole, never blended field by field with the database's -
        // even though the database also has an opinion about every field here.
        val directory = snapshotOf(nodes = mapOf(BOTH to dbRecord(BOTH)), airNodes = mapOf(BOTH to airRecord(BOTH)))

        val record = candidateRecord(directory, BOTH)

        assertEquals("Air Long", record.longName)
        assertEquals("airsh", record.shortName)
        assertEquals("HELTEC_V3", record.hwModel)
        assertEquals("ROUTER", record.role)
        assertFalse(record.hasPublicKey)
        // The database-only fields still come from the database record: identity
        // never carries them, so this is the only place they can come from.
        assertEquals(6.5f, record.dbSnr!!, 1e-6f)
        assertEquals(111, record.lastHeardEpochSeconds)
        assertEquals(2, record.hopsAway)
        assertEquals(555L, record.receivedAtMillis)
    }
}
