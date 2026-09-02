package com.cerocoder.meshrelay.ui.common

import com.cerocoder.meshrelay.settings.MapProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class MapLinksTest {

    @Test
    fun `forProvider dispatches GOOGLE to the google maps link`() {
        // A mutant that swapped the two branches, or that ignored the provider and
        // always returned one of the two, fails this against the OSM test below.
        val link = MapLinks.forProvider(MapProvider.GOOGLE, lat = 40.4168, lon = -3.7038)
        assertEquals(MapLinks.googleMaps(lat = 40.4168, lon = -3.7038), link)
    }

    @Test
    fun `forProvider dispatches OPEN_STREET_MAP to the open street map link`() {
        val link = MapLinks.forProvider(MapProvider.OPEN_STREET_MAP, lat = 40.4168, lon = -3.7038)
        assertEquals(MapLinks.openStreetMap(lat = 40.4168, lon = -3.7038), link)
    }

    @Test
    fun `forProvider formats coordinates with a decimal point, never a comma`() {
        // Pins Locale.ROOT end to end through forProvider, not just through the two
        // functions it delegates to: a mutant that routed through a formatter using
        // the active display locale would render "40,4168" here under Spanish and
        // break the query string (see MapLinks's own KDoc).
        val link = MapLinks.forProvider(MapProvider.GOOGLE, lat = 40.4168, lon = -3.7038)
        assertEquals("https://maps.google.com/?q=40.4168,-3.7038", link)
    }

    @Test
    fun `google maps link places latitude before longitude with no locale-formatted comma`() {
        // A mutant that swapped the two coordinates, or that built the query with the
        // active locale instead of Locale ROOT (rendering "40,4168" under a Spanish
        // locale and breaking the query string), would both fail this exact string.
        val link = MapLinks.googleMaps(lat = 40.4168, lon = -3.7038)
        assertEquals("https://maps.google.com/?q=40.4168,-3.7038", link)
    }

    @Test
    fun `open street map link names its query parameters mlat and mlon`() {
        // A mutant that swapped mlat/mlon, dropped the zoom parameter, or reordered
        // lat and lon fails this: the parameter names and their values are both
        // pinned, not just "the two numbers appear somewhere".
        val link = MapLinks.openStreetMap(lat = 40.4168, lon = -3.7038)
        assertEquals("https://www.openstreetmap.org/?mlat=40.4168&mlon=-3.7038&zoom=15", link)
    }

    @Test
    fun `a trailing slash on the base url is trimmed`() {
        // Without the trim, this produces ".../node//123" - a mutant that deleted
        // trimEnd('/') fails this exact assertion, not just "does not crash".
        val link = MapLinks.meshview("https://meshview.meshtastic.es/", nodeNum = 123)
        assertEquals("https://meshview.meshtastic.es/node/123", link)
    }

    @Test
    fun `a base url with no scheme is given https`() {
        // The crash this guards against: no scheme means no activity matches the
        // resulting URI, and openUri throws ActivityNotFoundException. A mutant that
        // dropped the scheme normalisation, or that produced "https:/" or
        // "https:meshview..." (a missing or single slash), fails this exact string.
        val link = MapLinks.meshview("meshview.meshtastic.es", nodeNum = 123)
        assertEquals("https://meshview.meshtastic.es/node/123", link)
    }

    @Test
    fun `a base url that already declares https is left unchanged`() {
        // A mutant that always prepended "https://" regardless of what was already
        // there would double it up into "https://https://..." and fail this.
        val link = MapLinks.meshview("https://meshview.meshtastic.es", nodeNum = 123)
        assertEquals("https://meshview.meshtastic.es/node/123", link)
    }

    @Test
    fun `a base url that already declares http is not upgraded to https`() {
        // Deliberate choice: this function repairs an absent scheme, it does not
        // second-guess one the user actually typed. A mutant that normalised every
        // scheme to "https" rather than only filling in a missing one would turn
        // "http" into "https" here and fail this.
        val link = MapLinks.meshview("http://meshview.meshtastic.es", nodeNum = 123)
        assertEquals("http://meshview.meshtastic.es/node/123", link)
    }

    @Test
    fun `a missing scheme and a trailing slash are both corrected together`() {
        // The two fixes must compose: a mutant that only ran one of the two checks
        // (e.g. an early return before the scheme check, or trimming before adding
        // the scheme instead of after) fails this combined case.
        val link = MapLinks.meshview("meshview.meshtastic.es/", nodeNum = 123)
        assertEquals("https://meshview.meshtastic.es/node/123", link)
    }
}
