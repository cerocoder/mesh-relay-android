package com.cerocoder.meshrelay.ble.protocol

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.IOException

/**
 * A controllable double of the GATT client.
 *
 * The frame queue imitates the firmware's buffer: [readFromRadio] hands frames
 * out one at a time and returns an empty array once the queue is exhausted -
 * exactly like a real node.
 */
class FakeMeshGattClient : MeshGattClient {

    private val queue = ArrayDeque<ByteArray>()
    private val notifications = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    private val subscriptionReady = CompletableDeferred<Unit>()

    override val fromNumNotifications: SharedFlow<Unit> = notifications

    /** Frames written by the app, in the order they were sent. */
    val writes = mutableListOf<ByteArray>()

    /** How many times the protocol read from FROMRADIO. */
    var reads = 0
        private set

    /** If true, the next read throws an IOException and resets the flag. */
    var failNextRead = false

    /** Put frames into the "firmware" queue. */
    fun enqueue(vararg frames: ByteArray) {
        queue.addAll(frames)
    }

    /** Simulate a FROMNUM notification. */
    suspend fun emitNotification() {
        notifications.emit(Unit)
    }

    /** Let the protocol proceed past waiting for the CCCD. */
    fun markSubscriptionReady() {
        if (!subscriptionReady.isCompleted) subscriptionReady.complete(Unit)
    }

    override suspend fun awaitSubscriptionReady() {
        subscriptionReady.await()
    }

    override suspend fun readFromRadio(): ByteArray {
        reads++
        if (failNextRead) {
            failNextRead = false
            throw IOException("simulated read failure")
        }
        return queue.removeFirstOrNull() ?: ByteArray(0)
    }

    override suspend fun writeToRadio(bytes: ByteArray) {
        writes += bytes
    }
}
