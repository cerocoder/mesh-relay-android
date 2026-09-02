package com.cerocoder.meshrelay.settings

/**
 * Which external map service the crosshair globe on the Graph screen opens.
 *
 * Governs only the single-glyph case, where there is room for exactly one target:
 * `ui/common/PositionLine.kt` offers Google Maps, OpenStreetMap and Meshview as
 * three separate labelled buttons, and that is unaffected by this setting - where
 * there is room to offer both, offering both is better than sending the reader to
 * Settings. This is for the one place that cannot do that, `ui/graph/SignalGraphScreen.kt`'s
 * crosshair, whose globe is a single tap target and must point somewhere.
 *
 * Lives in settings/ rather than ui/ because AppSettings carries it, and the
 * persistence layer must not depend on the interface layer - the same reasoning
 * [GaugeMode]'s own KDoc gives.
 */
enum class MapProvider { GOOGLE, OPEN_STREET_MAP }
