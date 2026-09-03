package com.cerocoder.meshrelay.stats.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.Position

private fun report(
    at: Long = 1_000L,
    lat: Double? = 40.4168,
    lon: Double? = -3.7038,
    alt: Int? = 667,
    bits: Int? = null,
) = PositionReport(at, lat, lon, alt, bits)

class PositionTest {

    @Test
    fun `scaled integer coordinates become degrees`() {
        val proto = Position(latitude_i = 404168000, longitude_i = -37038000, altitude = 667)
        val parsed = PositionReport.fromProto(proto, atMillis = 5_000L)

        assertEquals(40.4168, parsed.latitude!!, 1e-9)
        assertEquals(-3.7038, parsed.longitude!!, 1e-9)
        assertEquals(667, parsed.altitude)
        assertEquals(5_000L, parsed.atMillis)
    }

    @Test
    fun `height above the ellipsoid wins over height above sea level`() {
        val proto = Position(latitude_i = 1, longitude_i = 1, altitude = 600, altitude_hae = 655)
        assertEquals(655, PositionReport.fromProto(proto, 0L).altitude)
    }

    @Test
    fun `sea level altitude is used when there is no ellipsoid height`() {
        val proto = Position(latitude_i = 1, longitude_i = 1, altitude = 600)
        assertEquals(600, PositionReport.fromProto(proto, 0L).altitude)
    }

    @Test
    fun `absent coordinates stay absent rather than becoming zero`() {
        // latitude_i is an optional field: null means the sender withheld its
        // position. Reading that as 0.0 would place the node in the Gulf of Guinea
        // and give it a plausible-looking distance.
        val parsed = PositionReport.fromProto(Position(), 0L)
        assertNull(parsed.latitude)
        assertNull(parsed.longitude)
        assertNull(parsed.altitude)
        assertFalse(parsed.hasCoordinates)
        assertFalse(parsed.hasAltitude)
    }

    @Test
    fun `zero precision bits means absent, not full precision`() {
        // precision_bits is not an optional field, so 0 is what an unset value looks
        // like. Treating it as a real precision would make the obfuscation radius
        // enormous and hide every direction behind UNKNOWN.
        assertNull(PositionReport.fromProto(Position(latitude_i = 1, longitude_i = 1), 0L).precisionBits)
        assertEquals(13, PositionReport.fromProto(Position(latitude_i = 1, longitude_i = 1, precision_bits = 13), 0L).precisionBits)
    }

    @Test
    fun `the newest report with coordinates wins, with or without an altitude`() {
        // The defect this replaced: a node that broadcasts coordinates without an
        // altitude - a 2D fix, or a fixed node configured with latitude and longitude
        // only - had NO qualifying report, so its card fell back to the node database
        // and read "DB" for ever, however many fresh positions arrived.
        val history = PositionHistory(nodeNum = 1)
            .plus(PositionReport(1_000L, 40.0, -3.0, altitude = 600, precisionBits = null))
            .plus(PositionReport(2_000L, 41.0, -4.0, altitude = null, precisionBits = null))

        val chosen = history.newestWithCoordinates
        assertEquals(2_000L, chosen!!.atMillis)
        assertEquals(41.0, chosen.latitude!!, 1e-9)
        // Decision 2: one report is one moment. The altitude is this report's, which
        // is none - it is NOT borrowed from the older report that had one.
        assertNull(chosen.altitude)
    }

    @Test
    fun `a report with no coordinates is skipped even when it carries an altitude`() {
        // Altitude alone is not a position. The older complete report still wins.
        val history = PositionHistory(nodeNum = 1)
            .plus(PositionReport(1_000L, 40.0, -3.0, altitude = 600, precisionBits = null))
            .plus(PositionReport(2_000L, null, null, altitude = 700, precisionBits = null))

        assertEquals(1_000L, history.newestWithCoordinates!!.atMillis)
    }

    @Test
    fun `a history with no coordinates anywhere has no position`() {
        val history = PositionHistory(nodeNum = 1)
            .plus(PositionReport(1_000L, null, null, altitude = 600, precisionBits = null))
        assertNull(history.newestWithCoordinates)
    }

    @Test
    fun `history is bounded and keeps the newest reports`() {
        var history = PositionHistory(nodeNum = 1)
        repeat(PositionHistory.MAX_REPORTS + 5) { i -> history = history.plus(report(at = i.toLong())) }
        assertEquals(PositionHistory.MAX_REPORTS, history.reports.size)
        assertEquals(5L, history.reports.first().atMillis)
        assertTrue(history.reports.last().atMillis == (PositionHistory.MAX_REPORTS + 4).toLong())
    }

    @Test
    fun `a stamped position round-trips through the scaled integer form`() {
        // Getafe, the mesh this app was built for. Seven decimal places is the
        // protobuf's own resolution, so nothing here should lose a digit.
        val stamped = StampedPosition.fromDegrees(40.3057734, -3.7325611, PositionOrigin.PHONE)
        assertEquals(403057734, stamped.latI)
        assertEquals(-37325611, stamped.lonI)
        assertEquals(40.3057734, stamped.latitude, 1e-9)
        assertEquals(-3.7325611, stamped.longitude, 1e-9)
        assertEquals(PositionOrigin.PHONE, stamped.origin)
    }

    @Test
    fun `the extremes of the coordinate system stay inside an Int`() {
        // The whole reason the scaled form is four bytes rather than eight: 180
        // degrees scales to 1.8e9, and Int tops out at 2.147e9. One degree more of
        // headroom than the coordinate system has.
        assertEquals(900000000, StampedPosition.fromDegrees(90.0, 180.0, PositionOrigin.NODE).latI)
        assertEquals(1800000000, StampedPosition.fromDegrees(90.0, 180.0, PositionOrigin.NODE).lonI)
        assertEquals(-1800000000, StampedPosition.fromDegrees(-90.0, -180.0, PositionOrigin.NODE).lonI)
    }

    @Test
    fun `an origin survives the byte it is stored as, and zero is not an origin`() {
        // Zero is the "no position" marker in SignalSeriesBuffer's source array, so it
        // must never decode to a real origin - that is what makes latI/lonI safe to
        // leave at their default when a sample has no position at all.
        PositionOrigin.entries.forEach { assertEquals(it, PositionOrigin.ofCode(it.code)) }
        assertNull(PositionOrigin.ofCode(PositionOrigin.NONE))
        assertNull(PositionOrigin.ofCode(99.toByte()))
    }
}
