package com.cerocoder.meshrelay.ui.common

import androidx.compose.ui.platform.UriHandler
import com.cerocoder.meshrelay.settings.MapProvider

/**
 * Opens a coordinate where the owner asked for it.
 *
 * Tries the `geo:` URI when the setting is on, and falls back to the website
 * otherwise **or on failure**. The catch is not redundant with the settings
 * screen's detection: an application can be uninstalled between that screen being
 * drawn and this line running, and an uncaught ActivityNotFoundException does not
 * degrade - it takes the screen down.
 */
fun openPosition(
    uriHandler: UriHandler,
    preferInstalledApp: Boolean,
    provider: MapProvider,
    lat: Double,
    lon: Double,
    label: String,
) {
    if (preferInstalledApp) {
        try {
            uriHandler.openUri(MapLinks.geoUri(lat, lon, label))
            return
        } catch (noHandler: Exception) {
            // Deliberately Exception: Compose's AndroidUriHandler wraps
            // ActivityNotFoundException in an IllegalStateException, and which one
            // surfaces has changed between Compose versions. Falling through to a
            // website that always resolves is strictly better than either.
        }
    }
    uriHandler.openUri(MapLinks.forProvider(provider, lat, lon))
}
