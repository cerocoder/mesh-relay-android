package com.cerocoder.meshrelay.nodefile

import com.cerocoder.meshrelay.stats.model.PositionOrigin
import com.cerocoder.meshrelay.stats.model.StampedPosition
import com.cerocoder.meshrelay.stats.model.WatchPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchPointJsonTest {

    @Test
    fun `a bookmark with a position survives a round trip`() {
        val point = WatchPoint(
            atMillis = 1_789_929_124_000L,
            rssi = -92.5f,
            snr = -7.25f,
            position = StampedPosition(403_300_000, -37_500_000, PositionOrigin.PHONE),
            note = "ridge, good line of sight",
        )
        assertEquals(point, WatchPointJson.decode(WatchPointJson.encode(point)))
    }

    @Test
    fun `the altitude of a bookmark's position survives a round trip`() {
        val point = WatchPoint(
            atMillis = 7L,
            rssi = -90f,
            snr = 1.5f,
            position = StampedPosition(403_300_000, -37_500_000, PositionOrigin.NODE, altitude = 612),
        )
        val raw = WatchPointJson.encode(point)
        assertEquals(point, WatchPointJson.decode(raw))
        assertEquals(612, WatchPointJson.decode(raw)?.position?.altitude)
    }

    @Test
    fun `a bookmark saved before altitudes were kept reads back with none`() {
        val raw = """{"at_millis":7,"rssi":-90,"snr":1.5,"note":"","lat_e7":403300000,"lon_e7":-37500000,"origin":"phone"}"""
        val decoded = WatchPointJson.decode(raw)
        assertEquals(PositionOrigin.PHONE, decoded?.position?.origin)
        assertNull(decoded?.position?.altitude)
    }

    @Test
    fun `a position without an altitude writes no alt_m`() {
        val point = WatchPoint(1L, -80f, 3f, StampedPosition(1, 2, PositionOrigin.PHONE))
        assertEquals(false, WatchPointJson.encode(point).contains("alt_m"))
    }

    @Test
    fun `a bookmark without a position round trips with no position`() {
        val point = WatchPoint(atMillis = 5L, rssi = -80f, snr = 3f, position = null)
        val decoded = WatchPointJson.decode(WatchPointJson.encode(point))
        assertEquals(point, decoded)
        assertNull(decoded?.position)
    }

    @Test
    fun `text that is not a bookmark decodes to null`() {
        assertNull(WatchPointJson.decode("not json"))
        assertNull(WatchPointJson.decode("""{"rssi":-1}"""))
    }
}
