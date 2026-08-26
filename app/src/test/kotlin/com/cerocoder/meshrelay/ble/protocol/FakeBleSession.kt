package com.cerocoder.meshrelay.ble.protocol

import kotlinx.coroutines.CompletableDeferred

/**
 * A controllable double of a BLE session.
 *
 * Lets the transport's reconnect and resource-release logic be tested on plain
 * JVM, without Android.
 */
class FakeBleSession(override val client: FakeMeshGattClient = FakeMeshGattClient()) : BleSession {

    /** Whether the session is closed. */
    var closed = false
        private set

    private val disconnected = CompletableDeferred<String>()

    /**
     * Report a disconnect the way the Bluetooth stack would.
     *
     * A separate lever is needed precisely because a real disconnect does not show
     * up as some operation failing: the link simply goes silent.
     */
    fun signalDisconnect(reason: String = "disconnect in the test") {
        if (!disconnected.isCompleted) disconnected.complete(reason)
    }

    override suspend fun awaitDisconnect(): String = disconnected.await()

    override suspend fun close() {
        closed = true
    }
}
