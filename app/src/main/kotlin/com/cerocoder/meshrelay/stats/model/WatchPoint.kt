package com.cerocoder.meshrelay.stats.model

/**
 * A bookmark the owner placed on one measurement of a node's signal: "I stood here, and the
 * signal was good". Everything about the measurement is copied in, so the bookmark outlives
 * the series' ring buffer evicting that measurement.
 *
 * Identified by [atMillis] within one node: the Graph finds the bookmark for a measurement by
 * the measurement's own timestamp.
 */
data class WatchPoint(
    val atMillis: Long,
    val rssi: Float,
    val snr: Float,
    /** Where the observer stood; null when that measurement stored no position. */
    val position: StampedPosition?,
    /** Free text the owner typed in the bookmark's info pop-up. */
    val note: String = "",
)
