package com.cerocoder.meshrelay.ui.common

import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.settings.MapProvider

/**
 * The string resource for each [MapProvider], one lookup table shared by the
 * settings screen's own radio group
 * ([com.cerocoder.meshrelay.ui.settings.SettingsScreen]) and [PositionLine]'s
 * single map link, so the two can never disagree about what a provider is
 * called. Mirrors [SortModeLabels]'s own shape and reason for existing.
 *
 * Lives in `ui/common` rather than `ui/settings` because [PositionLine], which
 * needs it just as much as the settings screen does, is common too.
 */
object MapProviderLabels {
    fun labelOf(provider: MapProvider): Int = when (provider) {
        MapProvider.GOOGLE -> R.string.map_provider_google
        MapProvider.OPEN_STREET_MAP -> R.string.map_provider_osm
    }
}
