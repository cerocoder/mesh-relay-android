package com.cerocoder.meshrelay.nodefile

import com.cerocoder.meshrelay.stats.NodeId
import com.cerocoder.meshrelay.stats.model.LearntSource
import com.cerocoder.meshrelay.stats.model.NodeAnnotations
import com.cerocoder.meshrelay.stats.model.NodeFile
import com.cerocoder.meshrelay.stats.model.NodeFileEntry
import com.cerocoder.meshrelay.stats.model.NodeRecord
import com.cerocoder.meshrelay.stats.model.PositionReport
import com.cerocoder.meshrelay.stats.model.TelemetrySample
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Why a node file could not be read. The message is for the log; the reason picks the user's text. */
class NodeFileException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { NOT_JSON, UNKNOWN_VERSION, MALFORMED }
}

/** [skippedRecords] counts node records that were present but unreadable and so left out. */
data class DecodedNodeFile(val file: NodeFile, val skippedRecords: Int)

/**
 * Reads and writes the node file's text. An interface so a different JSON library
 * could replace [OrgJsonNodeFileCodec] without touching a caller.
 */
interface NodeFileCodec {
    fun encode(file: NodeFile): String

    @Throws(NodeFileException::class)
    fun decode(text: String): DecodedNodeFile
}

/**
 * The codec over `org.json`, which ships with Android. Absent optionals are left
 * out of the output rather than written as `null`, and unknown fields are ignored
 * on the way in, so a file written by a later version still loads.
 */
object OrgJsonNodeFileCodec : NodeFileCodec {

    const val VERSION = 1

    override fun encode(file: NodeFile): String {
        val root = JSONObject()
        root.put("version", VERSION)
        root.put("nodes", JSONArray().also { array -> file.entries.forEach { array.put(encodeEntry(it)) } })
        root.put("skipped_nodes", JSONArray().also { array -> file.skippedNodes.sorted().forEach { array.put(NodeId.format(it)) } })
        if (file.relayWatchPoints.isNotEmpty()) {
            // Only written when there is something to say, so a file with no relay bookmarks
            // is byte for byte what version 1 always wrote.
            root.put("relay_watch_points", JSONArray().also { array ->
                file.relayWatchPoints.toSortedMap().forEach { (relayByte, points) ->
                    array.put(
                        JSONObject()
                            .put("relay_byte", "0x%02x".format(relayByte))
                            .put("watch_points", JSONArray().also { out ->
                                points.forEach { raw -> runCatching { JSONTokener(raw).nextValue() }.getOrNull()?.let { out.put(it) } }
                            }),
                    )
                }
            })
        }
        return root.toString(2)
    }

    override fun decode(text: String): DecodedNodeFile {
        val root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw NodeFileException(NodeFileException.Reason.NOT_JSON, "not a JSON object: ${e.message}")
        }
        val version = root.opt("version") as? Number
            ?: throw NodeFileException(NodeFileException.Reason.MALFORMED, "no version")
        if (version.toInt() != VERSION) {
            throw NodeFileException(NodeFileException.Reason.UNKNOWN_VERSION, "version ${version.toInt()}")
        }
        val nodes = root.optJSONArray("nodes")
            ?: throw NodeFileException(NodeFileException.Reason.MALFORMED, "no nodes array")

        val entries = ArrayList<NodeFileEntry>(nodes.length())
        var skipped = 0
        for (index in 0 until nodes.length()) {
            val entry = nodes.optJSONObject(index)?.let { decodeEntry(it) }
            if (entry == null) skipped++ else entries.add(entry)
        }

        val skippedNodes = HashSet<Int>()
        root.optJSONArray("skipped_nodes")?.let { array ->
            for (index in 0 until array.length()) {
                // An unparseable id is dropped silently: the list is a convenience copy of
                // the settings, and one bad entry must not reject the whole file.
                NodeId.parse(array.optString(index, ""))?.let { skippedNodes.add(it) }
            }
        }

        val relayWatchPoints = LinkedHashMap<Int, List<String>>()
        root.optJSONArray("relay_watch_points")?.let { array ->
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                // A byte that does not parse is dropped, like an unparseable skipped id.
                val relayByte = parseRelayByte(item.optString("relay_byte", "")) ?: continue
                val points = ArrayList<String>()
                item.optJSONArray("watch_points")?.let { list ->
                    for (i in 0 until list.length()) {
                        val element = list.get(i)
                        points.add(if (element is String) JSONObject.quote(element) else element.toString())
                    }
                }
                if (points.isNotEmpty()) relayWatchPoints[relayByte] = points
            }
        }
        return DecodedNodeFile(NodeFile(entries, skippedNodes, relayWatchPoints), skipped)
    }

    /** `0x2a` or `2a`, one byte; null for anything else. */
    private fun parseRelayByte(text: String): Int? =
        text.removePrefix("0x").removePrefix("0X").toIntOrNull(16)?.takeIf { it in 0..0xFF }

    private fun encodeEntry(entry: NodeFileEntry): JSONObject {
        val record = entry.record
        val annotations = entry.annotations
        val node = JSONObject()
        node.put("num", NodeId.format(record.num))
        record.longName?.let { node.put("long_name", it) }
        record.shortName?.let { node.put("short_name", it) }
        record.hwModel?.let { node.put("hw_model", it) }
        record.role?.let { node.put("role", it) }
        node.put("has_public_key", record.hasPublicKey)
        record.dbPosition?.let { node.put("position", encodePosition(it)) }
        record.dbSnr?.let { node.put("db_snr", it.toDouble()) }
        record.lastHeardEpochSeconds?.let { node.put("last_heard", it) }
        record.hopsAway?.let { node.put("hops_away", it) }
        entry.uptimeSeconds?.let { node.put("uptime_seconds", it) }
        if (entry.telemetry.isNotEmpty()) {
            val telemetry = JSONObject()
            entry.telemetry.forEach { (metric, sample) ->
                telemetry.put(metric, JSONObject().put("value", sample.value.toDouble()).put("at_millis", sample.atMillis))
            }
            node.put("telemetry", telemetry)
        }
        node.put("learnt_source", annotations.learntSource.fileName)
        node.put("known_as_relay", annotations.knownAsRelay)
        node.put("notes", annotations.notes)
        node.put("watch_points", JSONArray().also { array ->
            // Stored as parsed JSON so the file reads naturally; an element that is not
            // JSON at all is dropped, since only a later feature will ever write one.
            annotations.watchPoints.forEach { raw -> runCatching { JSONTokener(raw).nextValue() }.getOrNull()?.let { array.put(it) } }
        })
        annotations.avgRssi?.let { node.put("avg_rssi", it.toDouble()) }
        annotations.avgSnr?.let { node.put("avg_snr", it.toDouble()) }
        return node
    }

    private fun encodePosition(position: PositionReport): JSONObject {
        val json = JSONObject().put("at_millis", position.atMillis)
        position.latitude?.let { json.put("latitude", it) }
        position.longitude?.let { json.put("longitude", it) }
        position.altitude?.let { json.put("altitude", it) }
        return json
    }

    /** Null when this record cannot be read; the caller counts it and moves on. */
    private fun decodeEntry(node: JSONObject): NodeFileEntry? = try {
        val num = NodeId.parse(node.getString("num")) ?: return null
        val record = NodeRecord(
            num = num,
            longName = node.optStringOrNull("long_name"),
            shortName = node.optStringOrNull("short_name"),
            hwModel = node.optStringOrNull("hw_model"),
            role = node.optStringOrNull("role"),
            dbPosition = node.optJSONObject("position")?.let { decodePosition(it) },
            dbSnr = node.optDoubleOrNull("db_snr")?.toFloat(),
            lastHeardEpochSeconds = node.optIntOrNull("last_heard"),
            hopsAway = node.optIntOrNull("hops_away"),
            hasPublicKey = node.optBoolean("has_public_key", false),
            // Not stored: the directory stamps what it takes in.
            receivedAtMillis = 0L,
        )
        val watchPoints = ArrayList<String>()
        node.optJSONArray("watch_points")?.let { array ->
            for (index in 0 until array.length()) {
                val element = array.get(index)
                watchPoints.add(if (element is String) JSONObject.quote(element) else element.toString())
            }
        }
        val telemetry = LinkedHashMap<String, TelemetrySample>()
        node.optJSONObject("telemetry")?.let { json ->
            json.keys().forEach { metric ->
                val sample = json.optJSONObject(metric) ?: return@forEach
                val value = sample.optDoubleOrNull("value") ?: return@forEach
                telemetry[metric] = TelemetrySample(value.toFloat(), sample.optLong("at_millis", 0L))
            }
        }
        NodeFileEntry(
            record = record,
            annotations = NodeAnnotations(
                // A hand-written file may leave this out; the radio is the safer claim.
                learntSource = LearntSource.fromFileName(node.optString("learnt_source", "")) ?: LearntSource.NODE,
                knownAsRelay = node.optBoolean("known_as_relay", false),
                notes = node.optString("notes", ""),
                watchPoints = watchPoints,
                avgRssi = node.optDoubleOrNull("avg_rssi")?.toFloat(),
                avgSnr = node.optDoubleOrNull("avg_snr")?.toFloat(),
            ),
            uptimeSeconds = node.optIntOrNull("uptime_seconds"),
            telemetry = telemetry,
        )
    } catch (e: JSONException) {
        null
    }

    private fun decodePosition(json: JSONObject) = PositionReport(
        atMillis = json.optLong("at_millis", 0L),
        latitude = json.optDoubleOrNull("latitude"),
        longitude = json.optDoubleOrNull("longitude"),
        altitude = json.optIntOrNull("altitude"),
        precisionBits = null,
    )

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key) else null

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() } else null

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null
}
