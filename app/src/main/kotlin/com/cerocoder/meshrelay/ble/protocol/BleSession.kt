package com.cerocoder.meshrelay.ble.protocol

/**
 * An open session with a node: a client ready to use, and a way to close it.
 *
 * Exists for testability. The transport is handed the session-opening function
 * from outside, so its reconnect loop, the order of operations, and resource
 * release are all tested on the JVM - without Android, without Nordic, and
 * without a node.
 */
interface BleSession {

    /** The client the protocol runs over. Valid until [close] is called. */
    val client: MeshGattClient

    /**
     * Suspends until a disconnect that the Bluetooth stack reported.
     *
     * Without this signal, a session that died in silence never ends: FROMNUM
     * notifications simply stop arriving (the Nordic flow closes with an empty
     * `awaitClose` and delivers neither an error nor a completion), nothing tries
     * to read, so nothing throws either - and the reconnect loop is waiting
     * precisely for the session to end. A disconnect must arrive as an event, not
     * be inferred from the next operation's failure.
     */
    suspend fun awaitDisconnect(): String

    /** Close the session and release resources. Safe to call again. */
    suspend fun close()
}
