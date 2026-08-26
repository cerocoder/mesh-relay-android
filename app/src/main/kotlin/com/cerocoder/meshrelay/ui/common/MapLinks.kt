package com.cerocoder.meshrelay.ui.common

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
     * [baseUrl] is a setting typed by hand in Settings. A trailing slash
     * there would double up with the leading slash in the template below and
     * produce a link that 404s - which reads as a broken feature, not the
     * typo it actually is - so it is trimmed before use.
     */
    fun meshview(baseUrl: String, nodeNum: Int): String =
        String.format(Locale.ROOT, MESHVIEW_NODE_TEMPLATE, baseUrl.trimEnd('/'), nodeNum)
}
