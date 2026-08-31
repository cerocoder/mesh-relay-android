package com.cerocoder.meshrelay.connection

import com.cerocoder.meshrelay.transport.FailureReason

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
     * @param reason a [FailureReason], if the link went down against the user's
     *   will: a handshake timeout, a refused permission, a switched-off adapter.
     *   `null` means a deliberate disconnect and is not shown as an error. Kept
     *   unresolved (a resource id, or already-resolved text carried up from a
     *   lower layer) rather than a plain `String`, so that naming the failure
     *   never requires a `Context` and a runtime language change is reflected
     *   the next time this state is shown.
     */
    data class Disconnected(
        val reason: FailureReason? = null,
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
