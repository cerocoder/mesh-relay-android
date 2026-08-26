package com.cerocoder.meshrelay.ble.protocol

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * The exchange protocol with a node over the characteristics.
 *
 * The FROMNUM characteristic is not a data channel, it is a "doorbell": it only
 * signals that data exists. The frames themselves are read out by repeated reads
 * of FROMRADIO, until an empty array comes back.
 *
 * The loop has three triggers: a FROMNUM notification, the start of the
 * subscription, and every write to TORADIO. The seed trigger is mandatory - the
 * firmware does not send FROMNUM until it has moved into a packet-sending state,
 * i.e. there are no notifications at all during exactly the handshake, and reading
 * has to be initiated by us.
 */
class MeshRadioProfile(private val client: MeshGattClient) {

    // A single slot that displaces the old value: a burst of triggers collapses into
    // one pass, writers never block, and stale requests do not pile up.
    private val drainTriggers = MutableSharedFlow<Unit>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val fromRadio: Flow<ByteArray> = channelFlow {
        client.awaitSubscriptionReady()

        launch {
            client.fromNumNotifications.collect { drainTriggers.tryEmit(Unit) }
        }

        drainTriggers.tryEmit(Unit)

        drainTriggers.collect {
            var keepReading = true
            while (keepReading) {
                val packet = try {
                    client.readFromRadio()
                } catch (e: IOException) {
                    // A narrow catch is a classifier, not an oversight. IOException is
                    // treated as transient: the current pass stops, but the flow stays
                    // alive and continues from the next trigger. Everything else (a
                    // lost link, a revoked permission, a dead GATT) must kill the flow:
                    // that is the only way the transport learns the session has ended
                    // and starts reconnecting. Swallowing those too would leave us with
                    // a loop forever waiting for triggers from a dead node.
                    Log.w(TAG, "error reading FROMRADIO, waiting for the next trigger", e)
                    keepReading = false
                    continue
                }
                if (packet.isEmpty()) keepReading = false else send(packet)
            }
        }
    }

    /** Send a frame and immediately request a read: the reply is usually ready already. */
    suspend fun send(bytes: ByteArray) {
        client.writeToRadio(bytes)
        drainTriggers.tryEmit(Unit)
    }

    private companion object {
        const val TAG = "MeshRadioProfile"
    }
}
