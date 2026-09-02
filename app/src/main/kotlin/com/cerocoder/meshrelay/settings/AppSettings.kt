package com.cerocoder.meshrelay.settings

import com.cerocoder.meshrelay.stats.SortMode

data class AppSettings(
    val language: LanguageOption = LanguageOption.SYSTEM,
    val gaugeMode: GaugeMode = GaugeMode.SIMPLE,
    val defaultSortMode: SortMode = SortMode.PACKETS,
    /**
     * Where the Graph's crosshair globe points, see [MapProvider]. Default
     * `GOOGLE` because that is the globe's behaviour today; this setting must not
     * silently move existing users to a different service.
     */
    val mapProvider: MapProvider = MapProvider.GOOGLE,
    /** The Spanish community instance, used throughout this mesh. */
    val meshviewUrl: String = "https://meshview.meshtastic.es",
    val keepScreenOn: Boolean = false,
    val backgroundCollection: Boolean = true,
    /**
     * Stamp each measurement with the phone's own fix rather than with the local
     * node's position. On by default: the phone is what the surveyor is carrying,
     * and the node's position is the coarser answer. Off stops the location
     * updates rather than merely ignoring them.
     */
    val usePhoneLocation: Boolean = true,
)
