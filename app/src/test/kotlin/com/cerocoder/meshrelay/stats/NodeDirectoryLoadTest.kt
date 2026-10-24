package com.cerocoder.meshrelay.stats

import com.cerocoder.meshrelay.stats.model.IdentitySource
import com.cerocoder.meshrelay.stats.model.LearntSource
import com.cerocoder.meshrelay.stats.model.NodeAnnotations
import com.cerocoder.meshrelay.stats.model.NodeFileEntry
import com.cerocoder.meshrelay.stats.model.NodeRecord
import com.cerocoder.meshrelay.stats.model.TelemetrySample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.User

private val FILE_ONLY = 0xB2000A2A.toInt()
private val RADIO_NODE = 0xB2000B31.toInt()
private const val NOW = 1_700_000_000_000L
private const val SAMPLE_AT = 1_699_999_000_000L

class NodeDirectoryLoadTest {

    private var now = NOW
    private val directory = NodeDirectory(TimeSource { now })

    private fun record(
        num: Int,
        longName: String? = null,
        shortName: String? = null,
        hopsAway: Int? = null,
        dbSnr: Float? = null,
    ) = NodeRecord(
        num = num,
        longName = longName,
        shortName = shortName,
        hwModel = null,
        role = null,
        dbPosition = null,
        dbSnr = dbSnr,
        lastHeardEpochSeconds = null,
        hopsAway = hopsAway,
        hasPublicKey = false,
        receivedAtMillis = 0L,
    )

    private fun entry(
        record: NodeRecord,
        annotations: NodeAnnotations = NodeAnnotations(LearntSource.AIR),
        uptimeSeconds: Int? = null,
        telemetry: Map<String, TelemetrySample> = emptyMap(),
    ) = NodeFileEntry(record, annotations, uptimeSeconds, telemetry)

    private fun snapshot() = directory.snapshot(emptySet())

    private fun radioHasNode(num: Int, longName: String, hops: Int? = null) {
        directory.beginRound()
        directory.applyNodeInfo(NodeInfo(num = num, user = User(long_name = longName), hops_away = hops))
        directory.markLoaded(NOW)
    }

    @Test
    fun `a loaded node absent from memory is created`() {
        directory.applyLoaded(listOf(entry(record(FILE_ONLY, shortName = "ABCD"))))

        val snapshot = snapshot()

        assertEquals("ABCD", snapshot.identity(FILE_ONLY).shortName)
        assertEquals(IdentitySource.FILE, snapshot.identity(FILE_ONLY).source)
        assertTrue(snapshot.matchingNodeNums(Geo.lastByteOfNodeNum(FILE_ONLY)).contains(FILE_ONLY))
        assertEquals(0, snapshot.count)
    }

    @Test
    fun `radio data wins over loaded data field by field`() {
        radioHasNode(RADIO_NODE, longName = "Radio", hops = null)

        directory.applyLoaded(listOf(entry(record(RADIO_NODE, longName = "File", hopsAway = 3, dbSnr = 5f))))

        val node = snapshot().node(RADIO_NODE)
        assertNotNull(node)
        assertEquals("Radio", node!!.longName)
        assertEquals(3, node.hopsAway)
        assertEquals(5f, node.dbSnr)
    }

    @Test
    fun `air identity wins over loaded identity whole`() {
        directory.applyUser(RADIO_NODE, User(long_name = "Air"), NOW)

        directory.applyLoaded(listOf(entry(record(RADIO_NODE, longName = "File", shortName = "FILE"))))

        val identity = snapshot().identity(RADIO_NODE)
        assertEquals(IdentitySource.AIR, identity.source)
        assertEquals("Air", identity.longName)
        assertNull(identity.shortName)
    }

    @Test
    fun `loaded annotations overwrite existing completely`() {
        val first = NodeAnnotations(LearntSource.AIR, knownAsRelay = true, notes = "a", watchPoints = listOf("1"), avgRssi = -90f)
        val second = NodeAnnotations(LearntSource.NODE, knownAsRelay = false, notes = "b")
        directory.applyLoaded(listOf(entry(record(FILE_ONLY), first)))

        directory.applyLoaded(listOf(entry(record(FILE_ONLY), second)))

        assertEquals(second, snapshot().annotations(FILE_ONLY))
    }

    @Test
    fun `loaded fields fill only and never overwrite an earlier load`() {
        directory.applyLoaded(listOf(entry(record(FILE_ONLY, longName = "First"))))

        directory.applyLoaded(listOf(entry(record(FILE_ONLY, longName = "Second", hopsAway = 4))))

        val snapshot = snapshot()
        assertEquals("First", snapshot.identity(FILE_ONLY).longName)
        assertEquals(4, snapshot.node(FILE_ONLY)!!.hopsAway)
    }

    @Test
    fun `a radio refresh does not remove a loaded only node`() {
        directory.applyLoaded(listOf(entry(record(FILE_ONLY, shortName = "ABCD"))))

        radioHasNode(RADIO_NODE, longName = "Radio")

        val snapshot = snapshot()
        assertEquals("ABCD", snapshot.identity(FILE_ONLY).shortName)
        assertEquals(1, snapshot.count)
    }

    @Test
    fun `reset keeps loaded data and clearAll removes it`() {
        directory.applyLoaded(listOf(entry(record(FILE_ONLY, shortName = "ABCD"), NodeAnnotations(LearntSource.AIR, notes = "n"))))

        directory.clearRuntimeData()
        assertEquals("n", snapshot().annotations(FILE_ONLY)?.notes)
        assertEquals("ABCD", snapshot().identity(FILE_ONLY).shortName)

        directory.clearAll()
        assertNull(snapshot().annotations(FILE_ONLY))
        assertNull(snapshot().node(FILE_ONLY))
        assertEquals(IdentitySource.NONE, snapshot().identity(FILE_ONLY).source)
    }

    @Test
    fun `loaded telemetry is shown only until live telemetry exists`() {
        directory.applyLoaded(
            listOf(
                entry(
                    record(FILE_ONLY),
                    uptimeSeconds = 100,
                    telemetry = mapOf("battery_level" to TelemetrySample(80f, SAMPLE_AT)),
                ),
            ),
        )

        val loaded = snapshot().telemetry(FILE_ONLY)!!
        assertEquals(80f, loaded.metrics.getValue("battery_level").stats.lastVal)
        assertEquals(SAMPLE_AT, loaded.metrics.getValue("battery_level").samples.single().atMillis)
        assertEquals(100, loaded.lastUptimeSeconds)

        directory.applyTelemetry(FILE_ONLY, Telemetry(device_metrics = DeviceMetrics(battery_level = 50)), NOW)

        val live = snapshot().telemetry(FILE_ONLY)!!
        assertEquals(50f, live.metrics.getValue("battery_level").stats.lastVal)
        assertEquals(1, live.metrics.getValue("battery_level").samples.size)
    }

    @Test
    fun `latchKnownRelay sets the flag and keeps notes`() {
        directory.applyLoaded(listOf(entry(record(FILE_ONLY), NodeAnnotations(LearntSource.AIR, notes = "x"))))

        directory.latchKnownRelay(FILE_ONLY, LearntSource.AIR)

        val annotations = snapshot().annotations(FILE_ONLY)!!
        assertTrue(annotations.knownAsRelay)
        assertEquals("x", annotations.notes)
    }

    @Test
    fun `latchKnownRelay creates annotations for a node that had none`() {
        directory.latchKnownRelay(RADIO_NODE, LearntSource.NODE)

        val annotations = snapshot().annotations(RADIO_NODE)!!
        assertTrue(annotations.knownAsRelay)
        assertEquals(LearntSource.NODE, annotations.learntSource)
    }
}
