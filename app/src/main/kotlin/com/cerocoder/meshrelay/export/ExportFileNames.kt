package com.cerocoder.meshrelay.export

import com.cerocoder.meshrelay.stats.NodeId
import com.cerocoder.meshrelay.stats.SeriesKey
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The document picker's suggested filename - never the final one, since the
 * picker always lets the person saving it rename or relocate it. [kind] decides
 * the shape rather than inspecting [keys], because a list export with nothing
 * tracked yet still has to name itself as a relay or a neighbour export.
 */
object ExportFileNames {

    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC)

    fun suggest(kind: ExportKind, keys: List<SeriesKey>, nowMillis: Long): String {
        val stamp = TIMESTAMP.format(Instant.ofEpochMilli(nowMillis))
        return when (kind) {
            ExportKind.RELAY_DETAIL -> {
                val relayByte = (keys.single() as SeriesKey.Relay).relayByte
                "meshrelay_relay_${String.format(Locale.ROOT, "0x%02x", relayByte)}_$stamp.csv"
            }
            ExportKind.NEIGHBOUR_DETAIL -> {
                val nodeNum = (keys.single() as SeriesKey.Neighbour).nodeNum
                "meshrelay_neighbour_${NodeId.format(nodeNum).removePrefix("!")}_$stamp.csv"
            }
            ExportKind.RELAY_LIST -> "meshrelay_relays_$stamp.csv"
            ExportKind.NEIGHBOUR_LIST -> "meshrelay_neighbours_$stamp.csv"
        }
    }
}
