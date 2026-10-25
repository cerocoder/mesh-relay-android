package com.cerocoder.meshrelay.stats.model

/** One telemetry reading as stored in the node file: the latest value of one metric and when it was heard. */
data class TelemetrySample(val value: Float, val atMillis: Long)

/** One node as it is written to, and read from, the node file. */
data class NodeFileEntry(
    /** Identity and database fields as resolved at save time; `receivedAtMillis` is ignored on load. */
    val record: NodeRecord,
    val annotations: NodeAnnotations,
    val uptimeSeconds: Int?,
    val telemetry: Map<String, TelemetrySample>,
)

/** The whole node file. The format version is the codec's constant, not a field. */
data class NodeFile(
    val entries: List<NodeFileEntry>,
    val skippedNodes: Set<Int>,
    /**
     * Bookmarks made on a relay graph, by relay byte, each the raw JSON text of one bookmark.
     * A relay byte is not a node, so these are not in any node's annotations.
     */
    val relayWatchPoints: Map<Int, List<String>> = emptyMap(),
)

/** A node's average direct signal this session; null where it has no direct packets. */
data class LiveAverages(val rssi: Float?, val snr: Float?)
