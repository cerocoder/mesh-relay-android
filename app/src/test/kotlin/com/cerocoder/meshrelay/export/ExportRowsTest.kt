package com.cerocoder.meshrelay.export

import com.cerocoder.meshrelay.stats.SeriesKey
import com.cerocoder.meshrelay.stats.SignalSeriesBuffer
import com.cerocoder.meshrelay.stats.model.Counters
import com.cerocoder.meshrelay.stats.model.NodeDirectorySnapshot
import com.cerocoder.meshrelay.stats.model.NodeRecord
import com.cerocoder.meshrelay.stats.model.PositionOrigin
import com.cerocoder.meshrelay.stats.model.RelayStats
import com.cerocoder.meshrelay.stats.model.StampedPosition
import com.cerocoder.meshrelay.stats.model.StatsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val DB_AT_MILLIS = 1_700_000_000_000L

class ExportRowsTest {

    private fun emptyDirectory() = NodeDirectorySnapshot(
        nodes = emptyMap(),
        airNodes = emptyMap(),
        loadedAtMillis = null,
        localNodeNum = null,
        positions = emptyMap(),
        telemetry = emptyMap(),
        skipped = emptySet(),
    )

    private fun snapshotWith(relays: List<RelayStats>, directory: NodeDirectorySnapshot) = StatsSnapshot(
        relays = relays,
        neighbours = emptyList(),
        counters = Counters.EMPTY,
        paused = false,
        sortMode = com.cerocoder.meshrelay.stats.SortMode.PACKETS,
        lastPacketAtMillis = null,
        lastRelayedPacketAtMillis = null,
        directory = directory,
        skippedRelayNodes = emptySet(),
    )

    @Test
    fun `a relay row carries the relay's own id and name plus the packet's source id and name`() {
        val buffer = SignalSeriesBuffer()
        buffer.append(
            1_000L, -94f, -7.5f,
            StampedPosition.fromDegrees(40.330012, -3.750441, PositionOrigin.NODE, altitude = 612),
            0x9e75f1a4.toInt(),
        )
        val relay = RelayStats(relayByte = 0x1a, nodeName = "PQPL1")
        val directory = emptyDirectory()

        val rows = ExportRows.build(
            mapOf(SeriesKey.Relay(0x1a) to buffer.snapshot()),
            snapshotWith(listOf(relay), directory),
        )

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("0x1a", row.nodeId)
        assertEquals("PQPL1", row.nodeName)
        assertEquals("!9e75f1a4", row.sourceNodeId)
        assertEquals(1_000L, row.atMillis)
        assertEquals(-94f, row.rssi, 0.0001f)
        assertEquals(-7.5f, row.snr, 0.0001f)
        assertEquals(40.330012, row.observerLat!!, 1e-6)
        assertEquals(612, row.observerAltitudeM)
        assertEquals("node", row.observerSource)
    }

    @Test
    fun `a relay with several matching candidates gets a blank name but keeps its id`() {
        // RelayStats.nodeName is already "" when the byte is ambiguous - this
        // proves the row-building function passes that through rather than
        // inventing its own guess.
        val buffer = SignalSeriesBuffer()
        buffer.append(1_000L, -94f, -7.5f, null, 0x11111111)
        val relay = RelayStats(relayByte = 0x1a, nodeName = "")

        val rows = ExportRows.build(
            mapOf(SeriesKey.Relay(0x1a) to buffer.snapshot()),
            snapshotWith(listOf(relay), emptyDirectory()),
        )

        assertEquals("0x1a", rows.single().nodeId)
        assertEquals("", rows.single().nodeName)
    }

    @Test
    fun `a subject with no buffered samples produces zero rows`() {
        val rows = ExportRows.build(
            mapOf(SeriesKey.Relay(0x1a) to com.cerocoder.meshrelay.stats.model.SignalSeries.EMPTY),
            snapshotWith(listOf(RelayStats(relayByte = 0x1a)), emptyDirectory()),
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `a sample with no position produces blank observer fields`() {
        val buffer = SignalSeriesBuffer()
        buffer.append(1_000L, -94f, -7.5f, null, 0x11111111)

        val rows = ExportRows.build(
            mapOf(SeriesKey.Relay(0x1a) to buffer.snapshot()),
            snapshotWith(listOf(RelayStats(relayByte = 0x1a)), emptyDirectory()),
        )

        val row = rows.single()
        assertNull(row.observerLat)
        assertNull(row.observerLon)
        assertNull(row.observerAltitudeM)
        assertNull(row.observerSource)
    }

    @Test
    fun `rows are sorted chronologically across every subject in the map, not grouped by key`() {
        val laterFirstBuffer = SignalSeriesBuffer()
        laterFirstBuffer.append(5_000L, -90f, 1f, null, 0x11111111)
        val earlierSecondBuffer = SignalSeriesBuffer()
        earlierSecondBuffer.append(1_000L, -90f, 1f, null, 0x22222222)

        val rows = ExportRows.build(
            // Insertion order deliberately puts the later timestamp first, so a
            // function that merely concatenated per-key blocks would fail this.
            linkedMapOf(
                SeriesKey.Relay(0x1a) to laterFirstBuffer.snapshot(),
                SeriesKey.Relay(0x2b) to earlierSecondBuffer.snapshot(),
            ),
            snapshotWith(listOf(RelayStats(relayByte = 0x1a), RelayStats(relayByte = 0x2b)), emptyDirectory()),
        )

        assertEquals(listOf(1_000L, 5_000L), rows.map { it.atMillis })
    }

    @Test
    fun `a neighbour row resolves both its own name and its source name from a populated directory`() {
        // Every other test in this file uses emptyDirectory(), so shortName()
        // never runs against a real entry and always falls through to "" - which
        // would pass just as well whether the lookup were wired correctly or not.
        // This one populates the directory's own node database (not the air
        // store, but shortName()/identity() resolve either the same way) with a
        // real short name, and uses SeriesKey.Neighbour rather than
        // SeriesKey.Relay: a neighbour's source is always itself, so the same
        // node number is asserted on both nodeName and sourceNodeName.
        val nodeNum = 0xA1000A2A.toInt()
        val buffer = SignalSeriesBuffer()
        buffer.append(1_000L, -94f, -7.5f, null, nodeNum)
        val directory = NodeDirectorySnapshot(
            nodes = mapOf(
                nodeNum to NodeRecord(
                    num = nodeNum,
                    longName = "Getafe Router",
                    shortName = "gt2a",
                    // fromProto copies Wire's defaults verbatim rather than nulls
                    // (NodeDirectoryTest pins this) - "UNSET"/"CLIENT" is the shape
                    // a real database record actually has, not a state the app
                    // cannot reach.
                    hwModel = "UNSET",
                    role = "CLIENT",
                    dbPosition = null,
                    dbSnr = null,
                    lastHeardEpochSeconds = null,
                    hopsAway = null,
                    hasPublicKey = false,
                    // Never 0L on a committed snapshot: markLoaded overwrites this
                    // at commit, and 0L is only the pre-commit sentinel.
                    receivedAtMillis = DB_AT_MILLIS,
                ),
            ),
            airNodes = emptyMap(),
            loadedAtMillis = null,
            localNodeNum = null,
            positions = emptyMap(),
            telemetry = emptyMap(),
            skipped = emptySet(),
        )

        val rows = ExportRows.build(
            mapOf(SeriesKey.Neighbour(nodeNum) to buffer.snapshot()),
            snapshotWith(emptyList(), directory),
        )

        assertEquals(1, rows.size)
        val row = rows.single()
        // The real, non-blank name - not "", which is what every other test in
        // this file gets from an empty directory and would pass either way.
        assertEquals("gt2a", row.nodeName)
        assertEquals("gt2a", row.sourceNodeName)
    }

    @Test
    fun `two rows sharing a timestamp keep their original relative order`() {
        val buffer = SignalSeriesBuffer()
        buffer.append(1_000L, -90f, 1f, null, 0x11111111)
        buffer.append(1_000L, -80f, 2f, null, 0x22222222)

        val rows = ExportRows.build(
            mapOf(SeriesKey.Relay(0x1a) to buffer.snapshot()),
            snapshotWith(listOf(RelayStats(relayByte = 0x1a)), emptyDirectory()),
        )

        // Both timestamps tie; a stable sort must not swap them.
        assertEquals(listOf(-90f, -80f), rows.map { it.rssi })
    }
}
