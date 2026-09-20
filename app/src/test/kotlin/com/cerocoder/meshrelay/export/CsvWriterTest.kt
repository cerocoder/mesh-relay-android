package com.cerocoder.meshrelay.export

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class CsvWriterTest {

    private val header = "node_id,node_name,source_node_id,source_node_name,timestamp_utc,rssi_dbm,snr_db,observer_lat,observer_lon,observer_altitude_m,observer_source"

    private fun row(
        nodeId: String = "0x1a",
        nodeName: String = "PQPL1",
        sourceNodeId: String = "!9e75f1a4",
        sourceNodeName: String = "1ce5",
        atMillis: Long = 1_758_000_000_000L,
        rssi: Float = -94f,
        snr: Float = -7.5f,
        observerLat: Double? = 40.330012,
        observerLon: Double? = -3.750441,
        observerAltitudeM: Int? = 612,
        observerSource: String? = "node",
    ) = ExportRow(nodeId, nodeName, sourceNodeId, sourceNodeName, atMillis, rssi, snr, observerLat, observerLon, observerAltitudeM, observerSource)

    @Test
    fun `an empty row list still writes the header`() {
        assertEquals("$header\n", CsvWriter.write(emptyList()))
    }

    @Test
    fun `one row writes exactly eleven comma-separated fields`() {
        val text = CsvWriter.write(listOf(row()))
        val dataLine = text.lines()[1]
        assertEquals(11, dataLine.split(",").size)
    }

    @Test
    fun `rssi is whole and snr keeps one decimal, matching the app's own single-sample precision`() {
        val text = CsvWriter.write(listOf(row(rssi = -94f, snr = -7.5f)))
        val fields = text.lines()[1].split(",")
        assertEquals("-94", fields[5])
        assertEquals("-7.5", fields[6])
    }

    @Test
    fun `coordinates are formatted to seven decimal places`() {
        val text = CsvWriter.write(listOf(row(observerLat = 40.330012, observerLon = -3.750441)))
        val fields = text.lines()[1].split(",")
        assertEquals("40.3300120", fields[7])
        assertEquals("-3.7504410", fields[8])
    }

    @Test
    fun `null observer fields render as blank, not the word null`() {
        val text = CsvWriter.write(
            listOf(row(observerLat = null, observerLon = null, observerAltitudeM = null, observerSource = null)),
        )
        val fields = text.lines()[1].split(",")
        assertEquals("", fields[7])
        assertEquals("", fields[8])
        assertEquals("", fields[9])
        assertEquals("", fields[10])
    }

    @Test
    fun `the timestamp is ISO-8601 UTC at seconds precision`() {
        // 2026-09-16T04:00:00Z
        val text = CsvWriter.write(listOf(row(atMillis = 1_789_531_200_000L)))
        assertEquals("2026-09-16T04:00:00Z", text.lines()[1].split(",")[4])
    }

    @Test
    fun `a node name containing a comma is quoted`() {
        val text = CsvWriter.write(listOf(row(nodeName = "Getafe, Router 2")))
        assertEquals("\"Getafe, Router 2\"", text.lines()[1].split(",", limit = 3).let {
            // Re-join is unnecessary: a correctly quoted comma keeps the field whole,
            // so splitting on "," naively would otherwise have produced 12 pieces.
            CsvWriter.write(listOf(row(nodeName = "Getafe, Router 2"))).lines()[1]
        }.let { line -> Regex("\"[^\"]*\"").find(line)!!.value })
    }

    @Test
    fun `a node name containing a double quote is escaped by doubling it`() {
        val text = CsvWriter.write(listOf(row(nodeName = """Node "One"""")))
        assertEquals("\"Node \"\"One\"\"\"", Regex("\"(?:[^\"]|\"\")*\"").find(text.lines()[1])!!.value)
    }

    @Test
    fun `a node name containing a newline is quoted`() {
        val text = CsvWriter.write(listOf(row(nodeName = "Line one\nLine two")))
        // The embedded newline is preserved literally inside the quotes (RFC 4180
        // allows a quoted field to contain a line break), rather than being escaped
        // away - so it does not vanish. But that also means a newline-naive split
        // like `String.lines()` cannot tell this literal newline apart from a row
        // separator: it sees three raw lines here (the header, plus the data row
        // split in two around the embedded newline), not two. The RFC 4180 property
        // that actually matters - that this is still one logical row, not two -
        // is verified below via `splitCsvLine`, which does understand quoting.
        val rawLines = text.trim('\n').lines()
        assertEquals(3, rawLines.size)
        val reconstructedDataLine = rawLines.drop(1).joinToString("\n")
        val fields = splitCsvLine(reconstructedDataLine)
        assertEquals(11, fields.size)
        assertEquals("Line one\nLine two", fields[1])
    }

    @Test
    fun `a quoted field's own comma does not create an extra column`() {
        val text = CsvWriter.write(listOf(row(nodeName = "Getafe, Router 2")))
        val dataLine = text.lines()[1]
        // A naive split on "," would see 12 pieces; a correct CSV reader sees 11.
        assertEquals(11, splitCsvLine(dataLine).size)
    }

    /** A minimal RFC 4180 field splitter, for this test file only - not production code. */
    private fun splitCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { current.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { fields += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        fields += current.toString()
        return fields
    }

    @Test
    fun `numbers use a decimal point even when the default locale uses a comma`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("es-ES"))
            val text = CsvWriter.write(listOf(row(rssi = -94f, snr = -7.5f, observerLat = 40.330012, observerLon = -3.750441)))
            val fields = text.lines()[1].split(",")
            assertEquals("-94", fields[5])
            assertEquals("-7.5", fields[6])
            assertEquals("40.3300120", fields[7])
            assertEquals("-3.7504410", fields[8])
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `the field separator is always a comma, never a locale semicolon`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("es-ES"))
            val text = CsvWriter.write(listOf(row()))
            assertEquals(header, text.lines()[0])
        } finally {
            Locale.setDefault(previous)
        }
    }
}
