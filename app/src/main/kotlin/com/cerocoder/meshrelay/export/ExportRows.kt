package com.cerocoder.meshrelay.export

import com.cerocoder.meshrelay.stats.NodeId
import com.cerocoder.meshrelay.stats.SeriesKey
import com.cerocoder.meshrelay.stats.model.PositionOrigin
import com.cerocoder.meshrelay.stats.model.RelayStats
import com.cerocoder.meshrelay.stats.model.SignalSeries
import com.cerocoder.meshrelay.stats.model.StatsSnapshot

/**
 * Turns one or more subjects' [SignalSeries] into the flat, chronological row
 * list [CsvWriter] writes. The only place that resolves a row's id/name pair -
 * the row-per-sample expansion and the identity lookups both live here so a
 * test can assert on either without touching Android or a ring buffer.
 */
object ExportRows {

    fun build(seriesByKey: Map<SeriesKey, SignalSeries>, snapshot: StatsSnapshot): List<ExportRow> {
        val rows = mutableListOf<ExportRow>()
        for ((key, series) in seriesByKey) {
            val (nodeId, nodeName) = identityOf(key, snapshot)
            for (i in 0 until series.size) {
                val sourceNodeNum = series.sourceNodeNum(i)
                val position = series.positionOf(i)
                rows += ExportRow(
                    nodeId = nodeId,
                    nodeName = nodeName,
                    sourceNodeId = NodeId.format(sourceNodeNum),
                    sourceNodeName = snapshot.directory.shortName(sourceNodeNum),
                    atMillis = series.atMillis(i),
                    rssi = series.rssi(i),
                    snr = series.snr(i),
                    observerLat = position?.latitude,
                    observerLon = position?.longitude,
                    observerAltitudeM = position?.altitude,
                    observerSource = when (position?.origin) {
                        PositionOrigin.NODE -> "node"
                        PositionOrigin.PHONE -> "phone"
                        null -> null
                    },
                )
            }
        }
        // Stable, so two samples sharing a timestamp keep the order they were
        // already in - each key's own samples oldest-first, keys visited in the
        // map's own iteration order. See the design spec's Row order decision.
        return rows.sortedBy { it.atMillis }
    }

    private fun identityOf(key: SeriesKey, snapshot: StatsSnapshot): Pair<String, String> = when (key) {
        is SeriesKey.Relay -> {
            val relay = snapshot.relays.find { it.relayByte == key.relayByte } ?: RelayStats(relayByte = key.relayByte)
            relay.hexId to relay.nodeName
        }
        is SeriesKey.Neighbour -> NodeId.format(key.nodeNum) to snapshot.directory.shortName(key.nodeNum)
    }
}
