package com.cerocoder.meshrelay.stats.model

/**
 * Compass bearing from the local node, in 45-degree sectors.
 *
 * UNKNOWN also covers the case where the position obfuscation radius reaches the
 * distance: the uncertainty then exceeds the separation and any direction we
 * printed would be invented.
 */
enum class Direction { N, NE, E, SE, S, SW, W, NW, UNKNOWN }
