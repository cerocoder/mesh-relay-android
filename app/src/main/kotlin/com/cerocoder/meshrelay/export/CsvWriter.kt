package com.cerocoder.meshrelay.export

import com.cerocoder.meshrelay.ui.common.StatsFormat
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Pure text formatting, and nothing else - [ExportRows] decides what a row
 * *is*, this decides how it *reads*. Android-free, like
 * [com.cerocoder.meshrelay.ui.common.PositionLineText] and
 * [com.cerocoder.meshrelay.ui.graph.ChartGeometry], so it is unit-testable on
 * the JVM without a `Uri` or a `ContentResolver` in sight.
 *
 * Every number is [Locale.ROOT], always - this is a data file, not prose, and
 * unlike [com.cerocoder.meshrelay.ui.common.PositionLineText] (which
 * deliberately uses the *display* locale) a decimal comma here would break
 * every spreadsheet's import. RSSI and SNR reuse
 * [StatsFormat.sampleRssi]/[StatsFormat.sampleSnr] - the same precision the
 * Graph screen's own crosshair already shows for one measurement - rather than
 * inventing a second formatting rule for the same numbers.
 */
object CsvWriter {

    private const val COORDINATE_PATTERN = "%.7f"

    private val HEADER = listOf(
        "node_id", "node_name", "source_node_id", "source_node_name",
        "timestamp_utc", "rssi_dbm", "snr_db",
        "observer_lat", "observer_lon", "observer_altitude_m", "observer_source",
    ).joinToString(",")

    fun write(rows: List<ExportRow>): String {
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        for (row in rows) sb.append(lineOf(row)).append('\n')
        return sb.toString()
    }

    private fun lineOf(row: ExportRow): String = listOf(
        row.nodeId,
        row.nodeName,
        row.sourceNodeId,
        row.sourceNodeName,
        timestampOf(row.atMillis),
        StatsFormat.sampleRssi(row.rssi, Locale.ROOT),
        StatsFormat.sampleSnr(row.snr, Locale.ROOT),
        row.observerLat?.let { String.format(Locale.ROOT, COORDINATE_PATTERN, it) } ?: "",
        row.observerLon?.let { String.format(Locale.ROOT, COORDINATE_PATTERN, it) } ?: "",
        row.observerAltitudeM?.toString() ?: "",
        row.observerSource ?: "",
    ).joinToString(",") { quoteIfNeeded(it) }

    /** Seconds precision, always UTC - a file opened later must not depend on the writer's time zone. */
    private fun timestampOf(atMillis: Long): String =
        Instant.ofEpochMilli(atMillis).truncatedTo(ChronoUnit.SECONDS).toString()

    /** RFC 4180: quote a field that contains the delimiter, a quote, or a line break; double any quote inside it. */
    private fun quoteIfNeeded(field: String): String {
        if (field.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }
}
