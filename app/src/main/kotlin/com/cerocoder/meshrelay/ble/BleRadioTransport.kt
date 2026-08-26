package com.cerocoder.meshrelay.ble

import android.util.Log
import com.cerocoder.meshrelay.ble.protocol.BleFailure
import com.cerocoder.meshrelay.ble.protocol.BleSession
import com.cerocoder.meshrelay.ble.protocol.MeshRadioProfile
import com.cerocoder.meshrelay.transport.RadioTransport
import com.cerocoder.meshrelay.transport.RadioTransportCallback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Transport to a real node over Bluetooth LE.
 *
 * Opening the session is handed in from outside ([openSession]) - thanks to this the
 * reconnect loop, the order of operations and resource release are all verified by
 * plain JVM tests, while everything that knows about Android lives in `ble/nordic/`.
 */
class BleRadioTransport(
    private val mac: String,
    private val callback: RadioTransportCallback,
    parentScope: CoroutineScope,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    private val now: () -> Long = { System.currentTimeMillis() },
    private val openSession: suspend (mac: String) -> BleSession,
) : RadioTransport {

    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)

    @Volatile
    private var profile: MeshRadioProfile? = null

    /**
     * The moment the session actually opened, or null if it never managed to open.
     * A separate field is needed because "stability" has to measure the time the
     * link was actually alive, not the duration of the attempt: a failed connection
     * takes fifteen seconds to time out, which is longer than the stability
     * threshold, and without this distinction every failure would count as a
     * successful connection, reset the counter, and the backoff would never grow.
     */
    @Volatile
    private var sessionOpenedAt: Long? = null

    override fun start() {
        scope.launch {
            while (isActive) {
                // A pause before every attempt, including the first one: the firmware
                // needs time to release its own GATT session, otherwise the connection
                // breaks down in the middle of the handshake.
                delay(policy.settleDelay)
                sessionOpenedAt = null
                val reason = runSession()
                val openedAt = sessionOpenedAt
                val stable = openedAt != null &&
                    now() - openedAt >= policy.minStableConnection.inWholeMilliseconds
                val failures = policy.onOutcome(wasStable = stable)
                callback.onDisconnect(isPermanent = false, reason = reason)
                delay(policy.backoffFor(failures))
            }
        }
    }

    /**
     * One attempt to "connect and live until the link breaks".
     *
     * Returns the reason the session ended - the user will see it. The Nordic layer
     * hands it over already described; all that is left here is a fallback text for
     * the case of an error it did not manage to describe.
     */
    private suspend fun runSession(): String? {
        val session = try {
            openSession(mac)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "failed to open a session with $mac", e)
            return (e as? BleFailure)?.description ?: "failed to connect to the node"
        }

        var reason: String? = null
        try {
            val radio = MeshRadioProfile(session.client)
            profile = radio
            sessionOpenedAt = now()
            callback.onConnect()
            coroutineScope {
                val pump = launch { radio.fromRadio.collect { callback.onDataReceived(it) } }
                // The frame pump is infinite by itself: it waits for triggers, and a
                // dead node never produces any. The session ends either from a fatal
                // read error or from the stack reporting a disconnect - and the latter
                // is the only signal there is when the link dies in silence.
                val watcher = launch {
                    reason = session.awaitDisconnect()
                    Log.i(TAG, "stack reported a disconnect: $reason")
                    pump.cancel()
                }
                pump.join()
                watcher.cancel()
            }
        } catch (e: CancellationException) {
            // A cancellation here is our own close(), not a lost link. Swallowing it
            // would send us back into the loop, where we would record a failure in
            // the policy and report a disconnect upward that never happened - the
            // user disconnected on purpose. Rethrow: the loop ends, and the session
            // is still closed by the finally block under NonCancellable.
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "session ended with an error", e)
            reason = (e as? BleFailure)?.description ?: "the session was interrupted"
        } finally {
            profile = null
            // An unclosed session is a leaked GATT connection and status 133 on the
            // next attempt. Closing must not be broken by cancellation.
            withContext(NonCancellable) { session.close() }
        }
        return reason
    }

    override fun send(bytes: ByteArray) {
        val radio = profile
        if (radio == null) {
            Log.w(TAG, "no active profile, frame dropped")
            return
        }
        scope.launch {
            try {
                radio.send(bytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "write to TORADIO failed", e)
            }
        }
    }

    override suspend fun close() {
        withContext(NonCancellable) { job.cancelAndJoin() }
    }

    private companion object {
        const val TAG = "BleRadioTransport"
    }
}
