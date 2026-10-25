package com.cerocoder.meshrelay.nodefile

import com.cerocoder.meshrelay.stats.model.PositionOrigin
import com.cerocoder.meshrelay.stats.model.StampedPosition
import com.cerocoder.meshrelay.stats.model.WatchPoint
import org.json.JSONObject

/**
 * One [WatchPoint] as the text stored in `NodeAnnotations.watchPoints` and, from there, in the
 * node file's `watch_points` array. Coordinates keep the protobuf's scaled integers
 * (1e-7 degrees), so a round trip is exact; the altitude is whole metres in `alt_m`, written
 * only when the position had one, so a file from before it was stored reads back with none.
 * Absent position fields mean no position; a field
 * a later version adds is ignored on the way in.
 */
object WatchPointJson {
    fun encode(point: WatchPoint): String {
        val json = JSONObject()
            .put("at_millis", point.atMillis)
            .put("rssi", point.rssi.toDouble())
            .put("snr", point.snr.toDouble())
            .put("note", point.note)
        point.position?.let {
            json.put("lat_e7", it.latI).put("lon_e7", it.lonI).put("origin", it.origin.name.lowercase())
            it.altitude?.let { altitude -> json.put("alt_m", altitude) }
        }
        return json.toString()
    }

    /** Null when [raw] is not a bookmark this version can read. */
    fun decode(raw: String): WatchPoint? = runCatching {
        val json = JSONObject(raw)
        val origin = json.optString("origin").let { name -> PositionOrigin.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
        val position = if (origin != null && json.has("lat_e7") && json.has("lon_e7")) {
            StampedPosition(
                json.getInt("lat_e7"),
                json.getInt("lon_e7"),
                origin,
                altitude = if (json.has("alt_m")) json.getInt("alt_m") else null,
            )
        } else {
            null
        }
        WatchPoint(
            atMillis = json.getLong("at_millis"),
            rssi = json.getDouble("rssi").toFloat(),
            snr = json.getDouble("snr").toFloat(),
            position = position,
            note = json.optString("note"),
        )
    }.getOrNull()
}
