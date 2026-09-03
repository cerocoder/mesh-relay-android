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
 * that fix, [MatchingNodesTab] built its candidate list with
 * `candidates.mapNotNull { directory.node(it)?.let { ... } }`, which silently
 * dropped any candidate [NodeDirectorySnapshot.node] returns null for. Since
 * `matchingNodeNums` scans the union of both stores, that included every
 * air-only candidate - exactly the node the ambiguity is often about, per the
 * finding's own case acceptance item.
 *
 * Task 4 narrowed [candidateRecord]'s job: [NodeCard] now takes a
 * [com.cerocoder.meshrelay.stats.model.NodeIdentity] argument of its own,
 * resolved separately at the call site through
 * [NodeDirectorySnapshot.identity], so [candidateRecord] carries only the
 * database-only fields and leaves the identity fields at their all-absent
 * defaults - never reading them from either store. These tests were rewritten
 * to pin that shape: the "both stores" case from the first round no longer
 * has anything distinct to say now that identity has moved out of this
 * function entirely, so it was dropped rather than kept as a duplicate of the
 * database-only case.
 *
 * No Compose/instrumented test harness exists in this module (see
 * `AGENTS.md`/the project's own note on verifying on-device instead), so this
 * exercises the plain-Kotlin composition function directly rather than
 * through the composable tree.
 */
class MatchingNodesTabCandidateRecordTest {

    @Test
    fun `a candidate absent from the database gets null database fields, not a crash`() {
        // This is the Critical finding's own failure mode: a node the radio has
        // never listed, reachable only because matchingNodeNums now scans the
        // union. directory.node(AIR_ONLY) is null here - candidateRecord must not
        // return null or throw, the way the old mapNotNull effectively did.
        val directory = snapshotOf(airNodes = mapOf(AIR_ONLY to airRecord(AIR_ONLY)))

        val record = candidateRecord(directory, AIR_ONLY)

        assertEquals(AIR_ONLY, record.num)
        assertNull(record.dbPosition)
        assertNull(record.dbSnr)
        assertNull(record.lastHeardEpochSeconds)
        assertNull(record.hopsAway)
        assertEquals(0L, record.receivedAtMillis)
        // Identity fields are never sourced here any more - NodeCard's own
        // identity argument, resolved separately at the call site, is what
        // decides these now.
        assertNull(record.longName)
        assertNull(record.shortName)
        assertNull(record.hwModel)
        assertNull(record.role)
        assertFalse(record.hasPublicKey)
    }

    @Test
    fun `a candidate in the database carries its own database fields, and no identity fields`() {
        val directory = snapshotOf(nodes = mapOf(DB_ONLY to dbRecord(DB_ONLY)))

        val record = candidateRecord(directory, DB_ONLY)

        assertEquals(DB_ONLY, record.num)
        assertEquals(6.5f, record.dbSnr!!, 1e-6f)
        assertEquals(111, record.lastHeardEpochSeconds)
        assertEquals(2, record.hopsAway)
        assertEquals(555L, record.receivedAtMillis)
        // The database record's own longName/shortName/hwModel/role/hasPublicKey
        // ("DB Long", "dbsh", "T_ECHO", "CLIENT", true) are never copied - if a
        // future change reintroduced them here, the panel would blend a database
        // identity underneath whatever NodeCard's own identity argument shows.
        assertNull(record.longName)
        assertNull(record.shortName)
        assertNull(record.hwModel)
        assertNull(record.role)
        assertFalse(record.hasPublicKey)
    }
}
