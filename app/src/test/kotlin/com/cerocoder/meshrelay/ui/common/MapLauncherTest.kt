package com.cerocoder.meshrelay.ui.common

import androidx.compose.ui.platform.UriHandler
import com.cerocoder.meshrelay.settings.MapProvider
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [openPosition] is the one place this app decides whether a tap opens the
 * `geo:` scheme or a website, so its coverage is the decision itself, not
 * [MapLinks] - [MapLinksTest] already pins every string [MapLinks.geoUri] and
 * [MapLinks.forProvider] can produce.
 *
 * A hand-written [UriHandler], not a mocking library: this module has none
 * (see [MapAppAvailabilityTest]'s own note on that), and the interface is one
 * method.
 *
 * Every coordinate below carries a negative longitude on purpose - this mesh's
 * own area, south of Madrid - so a mutant that swapped an argument order
 * somewhere in the call chain would send a fake handler to the wrong
 * hemisphere instead of merely a wrong-looking one.
 */
class MapLauncherTest {

    private class FakeUriHandler(private val throwsOnGeo: Boolean = false) : UriHandler {
        val opened = mutableListOf<String>()

        override fun openUri(uri: String) {
            // Only a geo: attempt ever fails in practice - Compose's own
            // AndroidUriHandler wraps ActivityNotFoundException in an
            // IllegalStateException, which is one of the reasons openPosition's
            // catch is deliberately the broad Exception rather than one type.
            if (throwsOnGeo && uri.startsWith("geo:")) throw IllegalStateException("no handler for $uri")
            opened += uri
        }
    }

    @Test
    fun `the setting off opens the website and never attempts geo`() {
        val handler = FakeUriHandler()
        openPosition(
            uriHandler = handler,
            preferInstalledApp = false,
            provider = MapProvider.GOOGLE,
            lat = 40.3057,
            lon = -3.7325,
            label = "1ce5",
        )
        assertEquals(listOf(MapLinks.forProvider(MapProvider.GOOGLE, 40.3057, -3.7325)), handler.opened)
    }

    @Test
    fun `the setting on opens geo with the label and coordinates, and stops there`() {
        // A mutant that dropped the `return` after a successful geo attempt would
        // fall through and open the website too - this pins exactly one open call.
        val handler = FakeUriHandler()
        openPosition(
            uriHandler = handler,
            preferInstalledApp = true,
            provider = MapProvider.OPEN_STREET_MAP,
            lat = 40.3057,
            lon = -3.7325,
            label = "1ce5",
        )
        assertEquals(listOf(MapLinks.geoUri(40.3057, -3.7325, "1ce5")), handler.opened)
    }

    @Test
    fun `a failed geo attempt falls back to the website chosen under Map provider`() {
        // This is the case the launcher's catch exists for: an installed map
        // application that answered the settings screen's detector but is gone by
        // the time this line runs. A mutant that let the exception propagate
        // would fail this test by throwing instead of returning.
        val handler = FakeUriHandler(throwsOnGeo = true)
        openPosition(
            uriHandler = handler,
            preferInstalledApp = true,
            provider = MapProvider.GOOGLE,
            lat = 40.3057,
            lon = -3.7325,
            label = "1ce5",
        )
        assertEquals(listOf(MapLinks.forProvider(MapProvider.GOOGLE, 40.3057, -3.7325)), handler.opened)
    }
}
