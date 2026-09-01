package com.cerocoder.meshrelay.stats

/**
 * Where a measurement's coordinates are taken from.
 *
 * The engine's own vocabulary for the *Use phone location* setting, so that
 * `stats/` does not import `settings/` to ask a question it can be told the
 * answer to. [PHONE] falls back to the node when no fix has arrived; [NODE]
 * never falls back to the phone. See `MeshStatsEngine.positionForSample`.
 */
enum class PositionMode { PHONE, NODE }
