package com.cerocoder.meshrelay.stats

/**
 * Which subject a signal series belongs to.
 *
 * Mirrors `ui.nav.DetailSubject` and deliberately is not it: `stats/` may not
 * import `ui/`, and the navigation host is the one place that maps one to the
 * other. The two must stay in step; a subject that can be opened but whose
 * measurements are filed under no key would draw an empty chart.
 */
sealed interface SeriesKey {
    /** One relay byte, `0x00..0xff` as the firmware reports it. */
    data class Relay(val relayByte: Int) : SeriesKey

    /** One whole node number, heard directly. */
    data class Neighbour(val nodeNum: Int) : SeriesKey
}
