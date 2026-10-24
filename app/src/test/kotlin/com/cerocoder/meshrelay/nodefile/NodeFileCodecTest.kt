package com.cerocoder.meshrelay.nodefile

import com.cerocoder.meshrelay.stats.model.LearntSource
import com.cerocoder.meshrelay.stats.model.NodeAnnotations
import com.cerocoder.meshrelay.stats.model.NodeFile
import com.cerocoder.meshrelay.stats.model.NodeFileEntry
import com.cerocoder.meshrelay.stats.model.NodeRecord
import com.cerocoder.meshrelay.stats.model.PositionReport
import com.cerocoder.meshrelay.stats.model.TelemetrySample
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NodeFileCodecTest {

    private val codec: NodeFileCodec = OrgJsonNodeFileCodec

    private fun record(num: Int) = NodeRecord(
        num = num,
        longName = "Getafe Router",
        shortName = "GTF1",
        hwModel = "HELTEC_V3",
        role = "ROUTER",
        // precisionBits is never stored for a database position, so it must be null to round trip.
        dbPosition = PositionReport(
            atMillis = 1_761_300_000_000L,
            latitude = 40.3083,
            longitude = -3.7325,
            altitude = 640,
            precisionBits = null,
        ),
        dbSnr = 7.25f,
        lastHeardEpochSeconds = 1_761_299_000,
        hopsAway = 2,
        hasPublicKey = true,
        // Not stored; a decoded record carries the placeholder the model documents.
        receivedAtMillis = 0L,
    )

    private val full = NodeFileEntry(
        record = record(0x1234abcd),
        annotations = NodeAnnotations(
            learntSource = LearntSource.AIR,
            knownAsRelay = true,
            notes = "behind the hill\nsecond line",
            watchPoints = emptyList(),
            avgRssi = -97.5f,
            avgSnr = 4.5f,
        ),
        uptimeSeconds = 86_400,
        telemetry = mapOf("battery_level" to TelemetrySample(87f, 1_761_300_100_000L)),
    )

    private fun fileOf(vararg entries: NodeFileEntry, skipped: Set<Int> = emptySet()) =
        NodeFile(entries.toList(), skipped)

    private fun assertRejected(text: String, reason: NodeFileException.Reason) {
        try {
            codec.decode(text)
            fail("expected $reason")
        } catch (e: NodeFileException) {
            assertEquals(reason, e.reason)
        }
    }

    @Test
    fun `round trip keeps every field`() {
        val original = fileOf(full, skipped = setOf(0x0000aa01))

        val decoded = codec.decode(codec.encode(original))

        assertEquals(original, decoded.file)
        assertEquals(0, decoded.skippedRecords)
    }

    @Test
    fun `watch points round trip as raw json`() {
        val withPoints = full.copy(annotations = full.annotations.copy(watchPoints = listOf("""{"t":1}""")))

        val decoded = codec.decode(codec.encode(fileOf(withPoints)))

        assertEquals(listOf("""{"t":1}"""), decoded.file.entries.single().annotations.watchPoints)
    }

    @Test
    fun `absent optionals are omitted from the output`() {
        val bare = NodeFileEntry(
            record = record(0x1234abcd).copy(dbPosition = null, dbSnr = null, hopsAway = null),
            annotations = NodeAnnotations(learntSource = LearntSource.NODE),
            uptimeSeconds = null,
            telemetry = emptyMap(),
        )

        val node = JSONObject(codec.encode(fileOf(bare))).getJSONArray("nodes").getJSONObject(0)

        assertFalse(node.has("avg_rssi"))
        assertFalse(node.has("avg_snr"))
        assertFalse(node.has("position"))
        assertFalse(node.has("uptime_seconds"))
    }

    @Test
    fun `unknown fields are ignored`() {
        val json = JSONObject(codec.encode(fileOf(full)))
        json.put("future", 42)
        json.getJSONArray("nodes").getJSONObject(0).put("future", "x")

        val decoded = codec.decode(json.toString())

        assertEquals(fileOf(full), decoded.file)
    }

    @Test
    fun `unknown version is rejected`() {
        val json = JSONObject(codec.encode(fileOf(full))).put("version", 2)

        assertRejected(json.toString(), NodeFileException.Reason.UNKNOWN_VERSION)
    }

    @Test
    fun `non json is rejected`() {
        assertRejected("not json", NodeFileException.Reason.NOT_JSON)
        assertRejected("", NodeFileException.Reason.NOT_JSON)
    }

    @Test
    fun `missing nodes array is rejected`() {
        assertRejected("""{"version":1}""", NodeFileException.Reason.MALFORMED)
    }

    @Test
    fun `a record with an unparseable num is skipped and counted`() {
        val json = JSONObject(codec.encode(fileOf(full, full.copy(record = record(0x00000042)))))
        json.getJSONArray("nodes").getJSONObject(1).put("num", "zzz")

        val decoded = codec.decode(json.toString())

        assertEquals(1, decoded.file.entries.size)
        assertEquals(1, decoded.skippedRecords)
    }

    @Test
    fun `skipped nodes round trip as node ids`() {
        val json = JSONObject(codec.encode(fileOf(skipped = setOf(0x1234abcd))))

        assertEquals("!1234abcd", json.getJSONArray("skipped_nodes").getString(0))
        assertTrue(codec.decode(json.toString()).file.skippedNodes == setOf(0x1234abcd))
    }
}
