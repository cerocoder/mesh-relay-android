package com.cerocoder.meshrelay.stats

/**
 * Which subject a signal series belongs to.
 *
 * Mirrors `ui.nav.DetailSubject` and deliberately is not it: `stats/` may not
 * import `ui/`, and the navigation host is the one place that maps one to the
 * other. The two must stay in step; a subject that can be opened but whose
 * measurements are filed under no key would draw an empty chart.
 *
 * Both variants must stay `data class`es. The navigation host's
 * `DisposableEffect(key)` receives a freshly constructed `SeriesKey` on every
 * recomposition; only structural equality lets `remember` treat that as "the
 * same key" and leave the watch alone. A plain class would compare by identity,
 * so the watch would tear down and re-arm on every frame of an active chart.
 */
sealed interface SeriesKey {
    /** One relay byte, `0x00..0xff` as the firmware reports it. */
    data class Relay(val relayByte: Int) : SeriesKey

    /** One whole node number, heard directly. */
    data class Neighbour(val nodeNum: Int) : SeriesKey
}
