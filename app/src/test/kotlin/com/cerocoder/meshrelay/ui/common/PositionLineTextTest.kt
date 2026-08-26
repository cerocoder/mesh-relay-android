package com.cerocoder.meshrelay.ui.common

import com.cerocoder.meshrelay.stats.AgeBucket
import com.cerocoder.meshrelay.stats.model.Direction
import com.cerocoder.meshrelay.stats.model.LocationInfo
import com.cerocoder.meshrelay.stats.model.PositionSource
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val MINUTE = 60_000L
private const val NOW = 1_700_000_000_000L

/**
 * Stands in for what [PositionLine] resolves from string resources - the
 * English values from `values/strings.xml`, by default, so assertions read
 * the same words a user would see. [locale] can be swapped out to prove
 * display text really does follow it, unlike [MapLinks], which never varies.
 */
private fun fakeStrings(locale: Locale = Locale.US) = PositionStrings(
    locale = locale,
    coordinatesFormat = "%1\$s, %2\$s",
    distanceFormat = "%1\$s km",
    distanceUncertainFormat = "%1\$s±%2\$s km",
    altitudeFormat = "%1\$d m",
    sourceFormat = "Src: %1\$s",
    sourceAgedFormat = "Src: %1\$s:%2\$s",
    ageLabels = mapOf(
        AgeBucket.M1 to "1m",
        AgeBucket.M5 to "5m",
        AgeBucket.M30 to "30m",
        AgeBucket.H1 to "1h",
        AgeBucket.H12 to "12h",
        AgeBucket.D1 to "1d",
        AgeBucket.W1 to "1w",
        AgeBucket.Y1 to "1y",
        AgeBucket.UNKNOWN to "??",
    ),
    directionLabels = mapOf(
        Direction.N to "N",
        Direction.NE to "NE",
        Direction.E to "E",
        Direction.SE to "SE",
        Direction.S to "S",
        Direction.SW to "SW",
        Direction.W to "W",
        Direction.NW to "NW",
        Direction.UNKNOWN to "—",
    ),
    sourceLabels = mapOf(
        PositionSource.DB to "DB",
        PositionSource.CURRENT to "current",
    ),
)

class PositionLineTextTest {

    @Test
    fun `coordinates are shown to six decimal places`() {
        val info = LocationInfo.EMPTY.copy(lat = 12.3456789, lon = -98.7654321)

        val enParts = PositionLineText.parts(info, NOW, fakeStrings(Locale.US))
        assertEquals("12.345679, -98.765432", enParts.coordinates)

        // Display text follows the active locale - unlike MapLinks, which is
        // pinned to Locale.ROOT because it builds URLs, not prose. A locale
        // hard-coded here instead of taken from PositionStrings.locale would
        // make this second assertion fail while the first still passes.
        val esParts = PositionLineText.parts(info, NOW, fakeStrings(Locale("es", "ES")))
        assertEquals("12,345679, -98,765432", esParts.coordinates)
    }

    @Test
    fun `distance carries the direction when one is known`() {
        val info = LocationInfo.EMPTY.copy(lat = 40.0, lon = -3.0, distanceKm = 12.3, direction = Direction.S)

        val parts = PositionLineText.parts(info, NOW, fakeStrings())

        assertEquals("12.3 km/S", parts.distance)
    }

    @Test
    fun `distance carries the uncertainty when the position is obfuscated`() {
        val res = fakeStrings()

        val clearlyObfuscated = LocationInfo.EMPTY.copy(
            lat = 40.0, lon = -3.0, distanceKm = 2.1, obfuscationRadiusMeters = 2900.0,
        )
        assertEquals("2.1±2.9 km", PositionLineText.parts(clearlyObfuscated, NOW, res).distance)

        // Exactly at the 0.1 km floor: still shown - the check is "at or
        // above", not "strictly above".
        val atTheFloor = LocationInfo.EMPTY.copy(
            lat = 40.0, lon = -3.0, distanceKm = 5.0, obfuscationRadiusMeters = 100.0,
        )
        assertEquals("5.0±0.1 km", PositionLineText.parts(atTheFloor, NOW, res).distance)

        // Below the floor: the obfuscation radius is narrower than the one
        // decimal place the distance itself is printed to, so it would be
        // false precision to show it at all.
        val belowTheFloor = LocationInfo.EMPTY.copy(
            lat = 40.0, lon = -3.0, distanceKm = 5.0, obfuscationRadiusMeters = 50.0,
        )
        assertEquals("5.0 km", PositionLineText.parts(belowTheFloor, NOW, res).distance)
    }

    @Test
    fun `no direction is appended when the direction is unknown`() {
        val info = LocationInfo.EMPTY.copy(
            lat = 40.0, lon = -3.0, distanceKm = 12.3, direction = Direction.UNKNOWN,
        )

        val parts = PositionLineText.parts(info, NOW, fakeStrings())

        assertEquals("12.3 km", parts.distance)
    }

    @Test
    fun `altitude is omitted rather than shown as zero when absent`() {
        val res = fakeStrings()

        assertNull(PositionLineText.parts(LocationInfo.EMPTY, NOW, res).altitude)

        // A real reading of sea level is not the same thing as no reading at
        // all, and must not be swallowed by the same branch that omits it.
        val seaLevel = LocationInfo.EMPTY.copy(altitude = 0)
        assertEquals("0 m", PositionLineText.parts(seaLevel, NOW, res).altitude)
    }

    @Test
    fun `the source is shown with its age bucket`() {
        val info = LocationInfo.EMPTY.copy(source = PositionSource.DB, atMillis = NOW - 2 * MINUTE)

        val parts = PositionLineText.parts(info, NOW, fakeStrings())

        assertEquals("Src: DB:5m", parts.source)
    }

    @Test
    fun `the source is shown without an age when no timestamp is known`() {
        val info = LocationInfo.EMPTY.copy(source = PositionSource.DB, atMillis = null)

        val parts = PositionLineText.parts(info, NOW, fakeStrings())

        assertEquals("Src: DB", parts.source)
    }

    @Test
    fun `an empty location produces no parts at all`() {
        val parts = PositionLineText.parts(LocationInfo.EMPTY, NOW, fakeStrings())

        assertEquals(PositionParts(coordinates = null, distance = null, altitude = null, source = null), parts)
    }

    @Test
    fun `map links use the coordinate order each site expects`() {
        assertEquals("https://maps.google.com/?q=40.4168,-3.7038", MapLinks.googleMaps(40.4168, -3.7038))
        assertEquals(
            "https://www.openstreetmap.org/?mlat=40.4168&mlon=-3.7038&zoom=15",
            MapLinks.openStreetMap(40.4168, -3.7038),
        )
        assertEquals("https://meshview.meshtastic.es/node/42", MapLinks.meshview("https://meshview.meshtastic.es/", 42))
    }
}
