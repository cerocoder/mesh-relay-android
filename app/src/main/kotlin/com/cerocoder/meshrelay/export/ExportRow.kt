package com.cerocoder.meshrelay.export

/**
 * One CSV row's worth of data, already resolved from the engine's types -
 * [com.cerocoder.meshrelay.export.CsvWriter] turns this into text and nothing
 * else does, so this type stays free of any formatting decision.
 *
 * [nodeId]/[nodeName] describe the relay or neighbour this row is filed under;
 * [sourceNodeId]/[sourceNodeName] describe the packet's actual sender, which can
 * differ from the relay for a relay row and is always the same node for a
 * neighbour row. See the design spec, section 7.
 */
data class ExportRow(
    val nodeId: String,
    val nodeName: String,
    val sourceNodeId: String,
    val sourceNodeName: String,
    val atMillis: Long,
    val rssi: Float,
    val snr: Float,
    val observerLat: Double?,
    val observerLon: Double?,
    val observerAltitudeM: Int?,
    val observerSource: String?,
)
