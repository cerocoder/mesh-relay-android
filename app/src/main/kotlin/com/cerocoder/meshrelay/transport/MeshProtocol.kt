package com.cerocoder.meshrelay.transport

/**
 * Meshtastic protocol constants and parsing of internal device addresses.
 *
 * An address is a single string whose first character selects the transport:
 * "m:" - demo device, "x" - BLE. The same convention is used by the official app.
 */
object MeshProtocol {

    /** Nonce for the config request (handshake stage 1). */
    const val CONFIG_NONCE = 69420

    /** Nonce for the node-database request (handshake stage 2). */
    const val NODE_INFO_NONCE = 69421

    /**
     * Nonce for a node-database reload requested mid-session (the terminal tool's
     * [D] key).
     *
     * Deliberately distinct from NODE_INFO_NONCE. Reusing the handshake nonce would
     * drive the connection manager back through its "handshake finished" branch,
     * which republishes Connected and restarts the heartbeat - and the heartbeat
     * restart resets the silence detector, so a genuinely dying link would get a free
     * extension every time the user pressed reload.
     */
    const val NODE_INFO_RELOAD_NONCE = 69422

    /** Ceiling on frame size: anything larger is discarded as garbage. */
    const val MAX_FRAME_BYTES = 512

    const val DEMO_PREFIX = "m:"
    const val BLE_PREFIX = "x"

    /** The scenario id from a demo address, or null if the address is not a demo one. */
    fun scenarioIdOrNull(address: String): String? =
        if (address.startsWith(DEMO_PREFIX)) {
            address.removePrefix(DEMO_PREFIX).takeIf { it.isNotEmpty() }
        } else {
            null
        }

    /** The MAC address from a BLE address, or null if the address is not a BLE one. */
    fun bleMacOrNull(address: String): String? =
        if (address.startsWith(BLE_PREFIX) && !address.startsWith(DEMO_PREFIX)) {
            address.removePrefix(BLE_PREFIX).takeIf { it.isNotEmpty() }
        } else {
            null
        }
}
