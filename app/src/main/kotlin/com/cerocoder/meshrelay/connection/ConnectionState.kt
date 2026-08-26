package com.cerocoder.meshrelay.connection

/**
 * The connection state as the application sees it.
 *
 * [Connecting] means the physical link exists but the configuration has not been
 * pulled off the node yet. The user must not be shown "connected" before the
 * second handshake stage has finished.
 */
sealed interface ConnectionState {

    /**
     * There is no link.
     *
     * @param reason a human-readable reason, if the link went down against the
     *   user's will: a handshake timeout, a refused permission, a switched-off
     *   adapter. `null` means a deliberate disconnect and is not shown as an error.
     */
    data class Disconnected(
        val reason: String? = null,
        /**
         * Whether attempts to restore the link are still going on.
         *
         * Without this flag, "disconnected with a reason" means both an ordinary
         * failed attempt inside the retry loop and a final surrender - and those
         * call for different decisions. It is what shuts the foreground service
         * down, in particular: keeping the process alive across the back-offs
         * between attempts is necessary, but after surrender it is pointless, and
         * a notification about the connection would then be a lie.
         */
        val retrying: Boolean = false,
    ) : ConnectionState

    data object Connecting : ConnectionState

    data object Connected : ConnectionState
}
