package com.cerocoder.meshrelay.ble.protocol

/**
 * A BLE-level failure, already described in human language.
 *
 * Exists so the reason reaches the screen without dragging library types along
 * with it. The Nordic layer turns its exceptions into this, and the transport -
 * plain JVM code - simply takes the ready [description] and hands it upward. The
 * original exception's message never reaches the screen: it explains nothing to
 * the user and only exposes implementation detail.
 */
class BleFailure(
    val description: String,
    cause: Throwable? = null,
) : Exception(description, cause)
