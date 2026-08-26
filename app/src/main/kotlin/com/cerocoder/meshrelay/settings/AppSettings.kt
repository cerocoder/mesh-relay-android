package com.cerocoder.meshrelay.settings

import com.cerocoder.meshrelay.stats.SortMode

data class AppSettings(
    val language: LanguageOption = LanguageOption.SYSTEM,
    val gaugeMode: GaugeMode = GaugeMode.SIMPLE,
    val defaultSortMode: SortMode = SortMode.PACKETS,
    /** The Spanish community instance, used throughout this mesh. */
    val meshviewUrl: String = "https://meshview.meshtastic.es",
    val keepScreenOn: Boolean = false,
    val backgroundCollection: Boolean = true,
)
