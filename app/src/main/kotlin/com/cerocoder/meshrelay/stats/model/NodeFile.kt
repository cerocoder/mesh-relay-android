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
)
