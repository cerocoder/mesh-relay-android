package com.cerocoder.meshrelay.connection

import android.util.Log
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.stats.SystemTimeSource
import com.cerocoder.meshrelay.stats.TimeSource
import com.cerocoder.meshrelay.stats.TimestampedFrame
import com.cerocoder.meshrelay.transport.FailureReason
import com.cerocoder.meshrelay.transport.MeshProtocol
import com.cerocoder.meshrelay.transport.RadioTransport
import com.cerocoder.meshrelay.transport.RadioTransportCallback
import com.cerocoder.meshrelay.transport.RadioTransportFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.Heartbeat
import org.meshtastic.proto.ToRadio
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The single owner of the active transport.
 *
 * Runs the two-stage handshake, publishes the connection state and the stream of
 * received frames. It has no idea what sits underneath it - a demo device or BLE.
 */
class RadioConnectionManager(
    private val factory: RadioTransportFactory,
    private val scope: CoroutineScope,
    // The clock was lifted out of System.currentTimeMillis(): the tests run on
    // runTest's virtual clock, and without this parameter the silence detector could
    // not tell real silence from a test that finished in an instant.
    private val time: TimeSource = SystemTimeSource,
    private val handshakeTimeout: Duration = 30.seconds,
    private val heartbeatInterval: Duration = 30.seconds,
    private val silenceTimeout: Duration = 60.seconds,
    private val recoveryDelay: Duration = 5.seconds,
    // Notifies the engine that a node-database round is starting, so it can
    // discard a stale partial round left by an earlier one - see
    // NodeDirectory.beginRound's KDoc. Called from two sites below, both times
    // before the corresponding sendToRadio, never after - see the comment at each
    // call site for why that ordering is the whole correctness argument. Defaults
    // to a no-op so a test with no opinion about the node database does not need
    // to build a MeshStatsEngine just to construct this class.
    private val onBeginNodeDbRound: () -> Boolean = { true },
) : RadioTransportCallback {

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _nodeDbReloading = MutableStateFlow(false)
    val nodeDbReloading: StateFlow<Boolean> = _nodeDbReloading.asStateFlow()

    // A Channel, not a SharedFlow: strict FIFO is mandatory - the order of the
    // configuration frames decides whether the handshake is correct. On overflow the
    // newest frame is the one thrown away, so the frames already accepted keep their
    // order.
    private val _frames = Channel<TimestampedFrame>(capacity = PACKET_QUEUE_CAPACITY)
    val frames: Flow<TimestampedFrame> = _frames.receiveAsFlow()

    private val droppedFrameCount = AtomicInteger(0)
    val droppedFrames: Int get() = droppedFrameCount.get()

    private val transportMutex = Mutex()

    @Volatile
    private var transport: RadioTransport? = null

    @Volatile
    private var currentAddress: String? = null
    // @Volatile is mandatory: both timers are created from onConnect and
    // onDataReceived, that is from transport threads and without the lock, while they
    // are cancelled from connect, disconnect and onDisconnect, which do hold the lock.
    // Reading a stale reference means a timer that outlived its session and later
    // closes a connection that is no longer its own.
    @Volatile
    private var watchdog: Job? = null

    @Volatile
    private var keepAlive: Job? = null

    // Same reason as its neighbours above: created and cancelled from different
    // threads, one of which holds the lock and the other does not.
    @Volatile
    private var reloadWatchdog: Job? = null

    @Volatile
    private var lastFrameAt: Long = 0

    private val heartbeatNonce = AtomicInteger(0)

    /** The heartbeat instance number. Needed only for reading logs: it shows whether
     *  the timer that is ticking belongs to a session that has already been closed. */
    private val keepAliveSeq = AtomicInteger(0)

    @Volatile
    private var recovery: Job? = null

    /**
     * How many times in a row the manager has brought the transport back up after a
     * failure it declared itself.
     *
     * `@Volatile` for the same reason as the neighbouring timers: this counter is
     * reset from onDataReceived, that is from a transport thread and without the
     * lock, while it is read under the lock on another thread. Without it, the reset
     * after a successful handshake might never become visible, and the attempt limit
     * would run out earlier than intended.
     */
    @Volatile
    private var recoveryAttempts = 0

    /** Connect to a device by its internal address. */
    fun connect(address: String) = connect(address, byUser = true)

    /**
     * @param byUser a tap by the user grants a fresh budget of self-recovery
     *   attempts, whereas a recovery attempt itself does not - otherwise the limit
     *   would never run out and the loop would be eternal.
     */
    private fun connect(address: String, byUser: Boolean) {
        scope.launch {
            transportMutex.withLock {
                // Idempotency: tapping an already-connected device again must not
                // recreate the transport - otherwise two transports would be left
                // writing into the same channel.
                if (address == currentAddress && _connectionState.value !is ConnectionState.Disconnected) {
                    Log.d(TAG, "already connected to this address, ignoring the repeat")
                    return@withLock
                }
                // Take down the previous session's watchdog: otherwise it may fire
                // against the new transport and cut off a healthy connection.
                watchdog?.cancel()
                // And the previous session's heartbeat - otherwise it outlives the
                // transport and keeps writing into a connection that is already dead.
                keepAlive?.cancel()
                recovery?.cancel()
                endReload()
                if (byUser) recoveryAttempts = 0
                closeTransportLocked()
                // Drain the channel: otherwise the previous session's frames occupy the
                // buffer, and the new session loses frames of its own while showing a
                // dropped-frame counter of zero.
                @Suppress("ControlFlowWithEmptyBody")
                while (_frames.tryReceive().isSuccess) {}
                droppedFrameCount.set(0)
                currentAddress = address
                try {
                    val created = factory.create(address, this@RadioConnectionManager)
                    transport = created
                    created.start()
                } catch (e: Throwable) {
                    Log.w(TAG, "failed to create a transport for the address", e)
                    transport = null
                    _connectionState.value =
                        ConnectionState.Disconnected(FailureReason.Resource(R.string.connection_failed_to_connect))
                }
            }
        }
    }

    /** Disconnect and release the transport. */
    suspend fun disconnect() {
        Log.i(TAG, "disconnecting at the user's request")
        withContext(NonCancellable) {
            transportMutex.withLock {
                watchdog?.cancel()
                keepAlive?.cancel()
                recovery?.cancel()
                endReload()
                recoveryAttempts = 0
                transport?.let { active ->
                    // A polite goodbye: it lets the node know the break is deliberate.
                    active.send(ToRadio(disconnect = true).encode())
                }
                closeTransportLocked()
                currentAddress = null
            }
            _connectionState.value = ConnectionState.Disconnected()
        }
    }

    private suspend fun closeTransportLocked() {
        transport?.let { active ->
            try {
                active.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "error while closing the transport", e)
            }
        }
        transport = null
    }

    /**
     * Ask the node to send its database again. Ports the terminal tool's D key,
     * mesh_stats.py:582-609.
     *
     * Since the node-storage split, nothing arriving over the air touches the
     * radio's own database (`nodes`) any more - a NODEINFO_APP broadcast is folded
     * into the separate air store instead. This reload is therefore now the
     * *only* way `nodes`, `dbSnr`, `lastHeardEpochSeconds`, `hopsAway` and
     * `loadedAtMillis` change mid-session: the node has learned about nodes we
     * have not heard from directly (or updated what it already knew about ones we
     * have), and this is the one way to pull that in without waiting for a
     * reconnect. That makes this button more important than it was before the
     * split, not less.
     *
     * Statistics are untouched, exactly as in the original.
     */
    /**
     * Tell the engine a node-database round is starting, and say so if it could not
     * be told.
     *
     * The engine reports rather than logs, because `stats/` may not import
     * `android.*` and that package's answer has always been silence rather than a
     * second logging mechanism. This is where the failure belongs: beside every
     * other message about the round it concerns.
     *
     * A dropped command means the stale-round reset did not happen, so this round
     * can commit a union of two refreshes instead of replacing the store - the leak
     * ruling P4 closed. It is rare, it corrects itself at the next completed round,
     * and it is worth seeing in a bug report.
     */
    private fun beginNodeDbRound() {
        if (!onBeginNodeDbRound()) {
            Log.w(TAG, "node database round start was dropped: the engine command queue is full")
        }
    }

    fun reloadNodeDatabase() {
        if (_connectionState.value != ConnectionState.Connected) {
            Log.d(TAG, "reload ignored: not connected")
            return
        }
        _nodeDbReloading.value = true
        reloadWatchdog?.cancel()
        reloadWatchdog = scope.launch {
            delay(RELOAD_TIMEOUT)
            // A node that never answers must not leave the interface claiming a reload
            // is still running: an endless spinner is indistinguishable from a hang.
            Log.w(TAG, "node did not acknowledge the database reload within $RELOAD_TIMEOUT")
            _nodeDbReloading.value = false
        }
        // Signalled before the request goes out, not after. The command and the
        // reply frames reach the engine's queue on the same channel but from
        // different coroutines, so ordering them by wall-clock is a race that would
        // usually work - "after" looks equally correct and is not. Queuing the
        // command before the request even leaves makes it physically impossible
        // for a node_info reply to be processed first: the round trip to the radio
        // cannot complete before this line does.
        beginNodeDbRound()
        sendToRadio(ToRadio(want_config_id = MeshProtocol.NODE_INFO_RELOAD_NONCE))
    }

    /**
     * End a reload that is still in flight.
     *
     * A reload whose session is gone is over, whatever the node was going to answer:
     * the acknowledgement it is waiting for can no longer arrive, so the flag would
     * stay raised until the watchdog fired, showing a spinner over a screen that is
     * no longer connected to anything.
     */
    private fun endReload() {
        reloadWatchdog?.cancel()
        _nodeDbReloading.value = false
    }

    override fun onConnect() {
        _connectionState.value = ConnectionState.Connecting
        Log.i(TAG, "link established, starting handshake stage 1")
        startHandshakeWatchdog()
        sendToRadio(ToRadio(want_config_id = MeshProtocol.CONFIG_NONCE))
    }

    override fun onDisconnect(isPermanent: Boolean, reason: FailureReason?) {
        watchdog?.cancel()
        keepAlive?.cancel()
        Log.i(TAG, "link lost (permanent=$isPermanent)")
        _connectionState.value = ConnectionState.Disconnected(
            reason ?: if (isPermanent) FailureReason.Resource(R.string.connection_lost) else null,
            // A non-permanent break is precisely another lap of the reconnect loop
            // inside the transport, and it will carry on by itself.
            retrying = !isPermanent,
        )
        if (isPermanent) {
            endReload()
            // The owner has to release the transport: it will not look after itself,
            // and beyond the seam this is a live GATT connection.
            // We remember exactly the session whose death was reported: while the
            // coroutine waits for the lock the user may manage to connect again, and
            // without this check we would tear down a new connection that is already
            // alive.
            val doomed = transport ?: return
            scope.launch {
                transportMutex.withLock {
                    try {
                        doomed.close()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        Log.w(TAG, "error while closing the broken transport", e)
                    }
                    if (transport === doomed) {
                        transport = null
                        currentAddress = null
                    }
                }
            }
        }
    }

    override fun onDataReceived(bytes: ByteArray) {
        if (bytes.size > MeshProtocol.MAX_FRAME_BYTES) {
            Log.w(TAG, "a frame of ${bytes.size} bytes exceeds the ${MeshProtocol.MAX_FRAME_BYTES} limit, discarded")
            return
        }
        // What proves the channel is alive is the bytes off the wire themselves, not
        // whether they parse into a valid FromRadio: a corrupt frame still proves the
        // link is not silent.
        lastFrameAt = time.nowMillis()

        val frame = try {
            FromRadio.ADAPTER.decode(bytes)
        } catch (e: Exception) {
            // We catch Exception rather than IOException: the constructor of a
            // Wire-generated message validates the number of filled oneof fields
            // with require() (both in FromRadio and in the nested MeshPacket), so
            // a frame with two variants occupied throws IllegalArgumentException
            // straight past a narrower catch. One corrupt frame must not break
            // reception: this is the only channel through which data reaches the
            // application at all.
            Log.w(TAG, "failed to parse FromRadio (${bytes.size} bytes)", e)
            return
        }

        when (frame.config_complete_id) {
            MeshProtocol.CONFIG_NONCE -> {
                Log.i(TAG, "stage 1 finished, requesting the node database")
                // Same ordering rule as reloadNodeDatabase, and for the same reason -
                // see the comment there. my_info, received earlier in this same
                // stage, already clears the buffer for a reconnect, but this call
                // does not depend on that: it covers this request on its own terms,
                // exactly as the reload's does, and the two are idempotent together.
                beginNodeDbRound()
                sendToRadio(ToRadio(want_config_id = MeshProtocol.NODE_INFO_NONCE))
            }

            MeshProtocol.NODE_INFO_NONCE -> {
                watchdog?.cancel()
                Log.i(TAG, "handshake finished")
                _connectionState.value = ConnectionState.Connected
                // The connection is up - previous self-recovery attempts no longer
                // count, otherwise rare failures over a day would exhaust the limit.
                recoveryAttempts = 0
                startKeepAlive()
            }

            // A reload requested mid-session, and deliberately not the branch above:
            // the connection state and the heartbeat are left exactly as they are.
            // Republishing Connected and restarting the heartbeat would reset the
            // silence detector, so a link that is genuinely dying would win a free
            // extension every time the user pressed reload.
            MeshProtocol.NODE_INFO_RELOAD_NONCE -> {
                reloadWatchdog?.cancel()
                _nodeDbReloading.value = false
                Log.i(TAG, "node database reload finished")
            }
        }

        if (_frames.trySend(TimestampedFrame(time.nowMillis(), frame)).isFailure) {
            droppedFrameCount.incrementAndGet()
        }
    }

    private fun sendToRadio(message: ToRadio) {
        val active = transport
        if (active == null) {
            Log.w(TAG, "no active transport, the packet was discarded")
            return
        }
        active.send(message.encode())
    }

    /**
     * Insurance against a "silent" hang: the physical link is there, but the node does
     * not answer want_config_id. Without it the application would stay in Connecting
     * for ever.
     */
    private fun startHandshakeWatchdog() {
        watchdog?.cancel()
        watchdog = scope.launch {
            delay(handshakeTimeout)
            transportMutex.withLock {
                if (_connectionState.value == ConnectionState.Connecting) {
                    Log.w(TAG, "the handshake did not finish within $handshakeTimeout, dropping the link")
                    closeTransportLocked()
                    val willRetry = scheduleRecovery()
                    _connectionState.value = ConnectionState.Disconnected(
                        FailureReason.Resource(
                            R.string.connection_handshake_timeout,
                            listOf(handshakeTimeout.inWholeSeconds),
                        ),
                        retrying = willRetry,
                    )
                }
            }
        }
    }

    /**
     * Keeping the link alive and detecting a "zombie" session.
     *
     * The firmware keeps an idle timer of its own and drops the link if the
     * application says nothing (spec section 7) - the heartbeat closes that side. The
     * other side of it is the silence detector: if not a single frame has come off the
     * wire for [silenceTimeout] (the reply to the heartbeat itself included), we treat
     * the Android stack as hung and tear the session down ourselves, without waiting
     * for a callback from the transport.
     *
     * The nonce is obliged to grow: the firmware has a duplicate filter on identical
     * writes and would silently discard identical bytes, while we would decide the
     * link was alive.
     */
    private fun startKeepAlive() {
        keepAlive?.cancel()
        lastFrameAt = time.nowMillis()
        val id = keepAliveSeq.incrementAndGet()
        Log.i(TAG, "heartbeat #$id started")
        keepAlive = scope.launch {
            while (isActive) {
                delay(heartbeatInterval)
                Log.d(TAG, "heartbeat #$id tick")
                sendToRadio(ToRadio(heartbeat = Heartbeat(nonce = heartbeatNonce.incrementAndGet())))
                val silence = time.nowMillis() - lastFrameAt
                if (silence > silenceTimeout.inWholeMilliseconds) {
                    Log.w(TAG, "no frames for $silence ms - treating the session as dead")
                    // We close the transport ourselves, exactly as the handshake
                    // watchdog does. Merely moving the state to Disconnected is not
                    // enough: the danger of a zombie session is precisely that nothing
                    // underneath will report the break, and an abandoned GATT
                    // connection would go on hanging until the next connection attempt.
                    // Closing and changing the state happen under one lock, as in the
                    // handshake watchdog. Publishing the state after releasing the lock
                    // opens a window in which the transport is already gone while
                    // connectionState still says Connected: a connect() landing in that
                    // window is swallowed by the idempotency gate and does nothing, and
                    // the application is left with no transport and not a single
                    // attempt to reconnect.
                    transportMutex.withLock {
                        closeTransportLocked()
                        val willRetry = scheduleRecovery()
                        _connectionState.value = ConnectionState.Disconnected(
                            FailureReason.Resource(R.string.connection_node_stopped_responding),
                            retrying = willRetry,
                        )
                    }
                    return@launch
                }
            }
        }
        // Completion is logged from outside the coroutine: that way a cancellation
        // reaches the log too, not only an orderly exit. That is exactly what is needed
        // to spot a timer that has outlived its session.
        keepAlive?.invokeOnCompletion { Log.i(TAG, "heartbeat #$id finished") }
    }

    /**
     * Bring the transport back up after a failure declared by the manager itself.
     *
     * The handshake watchdog and the silence detector close the transport, and its own
     * reconnect loop dies along with it: only [connect] can create a transport. Without
     * this method the application would stand dead after such a failure until the user
     * tapped, even though the node might simply have been rebooting.
     *
     * The number of attempts is limited. A node that connects but does not answer the
     * configuration request is seriously broken, and an endless loop would only burn
     * the battery and hide the cause. Once the attempts are spent, the state is left as
     * it is, with its reason. The counter is zeroed by a successful handshake and by
     * actions of the user.
     */
    private fun scheduleRecovery(): Boolean {
        val address = currentAddress ?: return false
        if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) {
            Log.w(TAG, "recovery attempts exhausted, waiting for the user to act")
            return false
        }
        recoveryAttempts++
        recovery?.cancel()
        recovery = scope.launch {
            delay(recoveryDelay)
            Log.i(TAG, "recovery attempt $recoveryAttempts of $MAX_RECOVERY_ATTEMPTS")
            connect(address, byUser = false)
        }
        return true
    }

    private companion object {
        const val TAG = "RadioConnectionManager"
        const val MAX_RECOVERY_ATTEMPTS = 3
        const val PACKET_QUEUE_CAPACITY = 256
        val RELOAD_TIMEOUT = 30.seconds
    }
}
