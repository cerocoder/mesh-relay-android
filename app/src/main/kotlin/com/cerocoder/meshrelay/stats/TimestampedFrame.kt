package com.cerocoder.meshrelay.stats

import org.meshtastic.proto.FromRadio

/**
 * A frame together with when it reached us.
 *
 * The engine consumes a flow of these rather than reading the connection manager
 * directly, so a file-backed source can be added later without touching the core.
 */
data class TimestampedFrame(val rxMillis: Long, val frame: FromRadio)
