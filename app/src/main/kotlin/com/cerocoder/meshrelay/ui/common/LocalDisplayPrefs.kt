package com.cerocoder.meshrelay.ui.common

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import com.cerocoder.meshrelay.settings.MapProvider
import com.cerocoder.meshrelay.settings.TimeFormat

/**
 * Display preferences that many leaf composables need and no screen owns.
 *
 * The same pattern [LocalRelativeClock] already uses, and for the same reason:
 * `NodeCard` alone has nine call sites and `RemoteNodesTab` five, most of them
 * previews, and threading a preference through all of them would put a parameter
 * on every screen between the settings and the one card that reads it.
 *
 * Both carry a default, so a preview renders without providing anything.
 */
val LocalTimeFormat: ProvidableCompositionLocal<TimeFormat> = compositionLocalOf { TimeFormat.TWENTY_FOUR_HOUR }
val LocalMapProvider: ProvidableCompositionLocal<MapProvider> = compositionLocalOf { MapProvider.GOOGLE }
