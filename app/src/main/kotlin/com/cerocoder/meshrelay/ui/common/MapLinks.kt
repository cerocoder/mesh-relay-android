package com.cerocoder.meshrelay.ui.common

import com.cerocoder.meshrelay.settings.MapProvider
import java.util.Locale

/**
 * External map links for a node's position. Ports the URL construction at
 * mesh_stats.py:1868-1872, plus the Meshview node link built alongside
 * `render_position_oneline` at mesh_stats.py:1793-1794.
 *
 * Every value here is built with [Locale.ROOT], never the active display
 * locale: these are URLs, not prose. This app ships Spanish, and under a
 * Spanish locale a `%f`-style conversion of `40.4168` renders `40,4168` -
 * with a decimal comma that breaks the query string and turns the link
 * into one that goes nowhere. [PositionLineText] is the one place display
 * text is built, and it uses the caller's locale on purpose; this object
 * exists precisely so nothing here is ever tempted to reuse that
 * locale-formatted text for a link target.
 */
object MapLinks {

    private const val GOOGLE_MAPS_TEMPLATE = "https://maps.google.com/?q=%s,%s"
    private const val OPEN_STREET_MAP_TEMPLATE = "https://www.openstreetmap.org/?mlat=%s&mlon=%s&zoom=15"
    private const val MESHVIEW_NODE_TEMPLATE = "%s/node/%d"

    fun googleMaps(lat: Double, lon: Double): String =
        String.format(Locale.ROOT, GOOGLE_MAPS_TEMPLATE, lat, lon)

    fun openStreetMap(lat: Double, lon: Double): String =
        String.format(Locale.ROOT, OPEN_STREET_MAP_TEMPLATE, lat, lon)

    /**
     * [googleMaps] or [openStreetMap], chosen by [provider] - the setting
     * [com.cerocoder.meshrelay.settings.AppSettings.mapProvider] governs. The `when`
     * is deliberately exhaustive with no `else`: adding a third [MapProvider] must
     * fail the build here rather than silently falling back to Google.
     */
    fun forProvider(provider: MapProvider, lat: Double, lon: Double): String =
        when (provider) {
            MapProvider.GOOGLE -> googleMaps(lat, lon)
            MapProvider.OPEN_STREET_MAP -> openStreetMap(lat, lon)
        }

    /**
     * [baseUrl] is a setting typed by hand in Settings, with no validation on the
     * way in. Two of the ways that shows up are corrected here rather than at the
     * point of entry, so the repair lives in one tested place instead of being
     * spread between Settings and every call site:
     *
     * - A trailing slash would double up with the leading slash in the template
     *   below and produce a link that 404s - which reads as a broken feature, not
     *   the typo it actually is - so it is trimmed before use.
     * - A missing scheme (`meshview.meshtastic.es` rather than
     *   `https://meshview.meshtastic.es`) produces a URI with no scheme, which
     *   matches no activity: [android.content.Intent] resolution throws
     *   `ActivityNotFoundException` rather than failing gracefully. `https://` is
     *   assumed for anything that does not already declare a scheme; an
     *   already-schemed URL, `http://` included, is left exactly as typed - this
     *   function corrects an absent scheme, it does not second-guess one that is
     *   merely unencrypted.
     */
    fun meshview(baseUrl: String, nodeNum: Int): String {
        val trimmed = baseUrl.trimEnd('/')
        val withScheme = if (SCHEME_PATTERN.containsMatchIn(trimmed)) trimmed else "https://$trimmed"
        return String.format(Locale.ROOT, MESHVIEW_NODE_TEMPLATE, withScheme, nodeNum)
    }

    // A scheme plus "://" at the very start, e.g. "https://", "http://" - deliberately
    // narrow rather than a full URI-scheme grammar, since the only two values this
    // has ever seen are http and https.
    private val SCHEME_PATTERN = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
}
