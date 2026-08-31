package com.cerocoder.meshrelay.connection

import com.cerocoder.meshrelay.emulator.Scenarios
import com.cerocoder.meshrelay.stats.TimeSource
import com.cerocoder.meshrelay.stats.TimestampedFrame
import com.cerocoder.meshrelay.transport.FailureReason
import com.cerocoder.meshrelay.transport.FakeRadioTransport
import com.cerocoder.meshrelay.transport.MeshProtocol
import com.cerocoder.meshrelay.transport.RadioTransport
import com.cerocoder.meshrelay.transport.RadioTransportCallback
import com.cerocoder.meshrelay.transport.RadioTransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.ToRadio
import org.meshtastic.proto.User
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// The values these tests do arithmetic against, mirrored from the manager. The first two
// are constructor defaults, and every test that reasons about them also passes them to
// the constructor, so the two sides cannot drift apart silently. The other two are
// private companion members with no way in: if either ever changes, the reload-timeout
// test and the overflow test fail and point here.
private val HEARTBEAT_INTERVAL = 30.seconds
private val SILENCE_TIMEOUT = 60.seconds
private val RELOAD_TIMEOUT = 30.seconds
private const val PACKET_QUEUE_CAPACITY = 256

/** A factory handing out a fake transport with no delays. */
private class TestFactory(private val scope: CoroutineScope) : RadioTransportFactory {
    var createdCount = 0

    override fun create(address: String, callback: RadioTransportCallback): RadioTransport {
        createdCount++
        val scenarioId = requireNotNull(MeshProtocol.scenarioIdOrNull(address))
        return FakeRadioTransport(
            scenario = requireNotNull(Scenarios.byId(scenarioId)),
            callback = callback,
            parentScope = scope,
            connectDelay = ZERO,
            frameDelay = ZERO,
        )
    }
}

/**
 * A transport that remembers the order of the calls it received, so the goodbye can be
 * checked to go out before the close, and remembers the payloads themselves, so the
 * request the manager actually sent can be decoded. It answers nothing on its own.
 */
private class RecordingTransport(private val callback: RadioTransportCallback) : RadioTransport {
    val events = mutableListOf<String>()
    val sent = mutableListOf<ByteArray>()

    override fun start() {
        callback.onConnect()
    }

    override fun send(bytes: ByteArray) {
        sent += bytes
        val message = ToRadio.ADAPTER.decode(bytes)
        if (message.disconnect == true) events += "goodbye"
    }

    override suspend fun close() {
        events += "close"
    }
}

/** Hands out [RecordingTransport]s and keeps the last one for assertions. */
private class RecordingFactory : RadioTransportFactory {
    lateinit var last: RecordingTransport
        private set

    override fun create(address: String, callback: RadioTransportCallback): RadioTransport =
        RecordingTransport(callback).also { last = it }
}

/** A transport that connects but never answers a request. */
private class SilentTransport(private val callback: RadioTransportCallback) : RadioTransport {
    override fun start() = callback.onConnect()
    override fun send(bytes: ByteArray) = Unit
    override suspend fun close() = Unit
}

private class SilentFactory : RadioTransportFactory {
    var created = 0
        private set

    override fun create(address: String, callback: RadioTransportCallback): RadioTransport {
        created++
        return SilentTransport(callback)
    }
}

/** A transport that finishes the handshake and sends nothing after that. */
private class SilentAfterConnectTransport(private val callback: RadioTransportCallback) : RadioTransport {
    override fun start() {
        callback.onConnect()
    }

    override fun send(bytes: ByteArray) {
        val message = ToRadio.ADAPTER.decode(bytes)
        when (message.want_config_id) {
            MeshProtocol.CONFIG_NONCE ->
                callback.onDataReceived(FromRadio(config_complete_id = MeshProtocol.CONFIG_NONCE).encode())

            MeshProtocol.NODE_INFO_NONCE ->
                callback.onDataReceived(FromRadio(config_complete_id = MeshProtocol.NODE_INFO_NONCE).encode())
        }
        // The heartbeat is deliberately left unanswered - that is exactly what a zombie
        // session looks like: the physical link is there (the transport has reported no
        // break), and there is no more data.
    }

    var closed = false
        private set

    override suspend fun close() {
        closed = true
    }
}

private class SilentAfterConnectFactory : RadioTransportFactory {
    lateinit var last: SilentAfterConnectTransport
        private set

    override fun create(address: String, callback: RadioTransportCallback): RadioTransport =
        SilentAfterConnectTransport(callback).also { last = it }
}

class RadioConnectionManagerTest {

    // scope() is built on top of backgroundScope rather than a bare
    // CoroutineScope(UnconfinedTestDispatcher(...)): the heartbeat is a REAL endless
    // while(isActive) { delay(...) } that reschedules itself for as long as the
    // connection is alive. advanceUntilIdle() in kotlinx-coroutines-test 1.11.0 winds
    // time forward while there is at least one foreground task left in the queue, and
    // stops only once there are none. An ordinary (foreground) scope keeps the
    // heartbeat permanently busy in the queue - a healthy connection
    // (FakeRadioTransport answers every heartbeat) means advanceUntilIdle() after
    // connecting would never see an empty queue and would hang for ever, breaking ALL
    // tests in which the handshake reaches Connected, not only the new ones. Verified
    // empirically by running a minimal reproduction against this same library version:
    // with an ordinary scope() advanceUntilIdle() hangs dead; with tasks marked as
    // background (inherited from backgroundScope.coroutineContext) advanceUntilIdle()
    // finishes correctly, while advanceTimeBy(...) still winds background tasks forward
    // inside its window - which is exactly why none of the existing tests that use
    // advanceTimeBy for timeouts change behaviour.
    private fun TestScope.scope(): CoroutineScope =
        CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    /**
     * Everything the manager publishes on [RadioConnectionManager.frames], drained as
     * it arrives.
     *
     * The stream is a Channel with a single consumer, so a test cannot both let the
     * manager fill it and then ask it for a snapshot: whoever collects first takes the
     * frames. Attaching one collector for the whole test and asserting against the list
     * it fills is also the only shape in which a test can claim that *nothing* arrived,
     * which is what the size and decode guards need.
     */
    private fun TestScope.drain(manager: RadioConnectionManager): List<TimestampedFrame> {
        val received = mutableListOf<TimestampedFrame>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            manager.frames.collect { received += it }
        }
        return received
    }

    /**
     * Drive both handshake stages by hand.
     *
     * [RecordingTransport] records what the manager sends but answers nothing, so a
     * test that needs a Connected manager has to supply both acknowledgements itself -
     * and in this order, because the manager only asks for the node database once
     * stage one is confirmed.
     */
    private fun completeHandshake(manager: RadioConnectionManager) {
        manager.onDataReceived(FromRadio(config_complete_id = MeshProtocol.CONFIG_NONCE).encode())
        manager.onDataReceived(FromRadio(config_complete_id = MeshProtocol.NODE_INFO_NONCE).encode())
    }

    @Test
    fun `initially there is no connection`() = runTest {
        val manager = RadioConnectionManager(TestFactory(scope()), scope())

        assertTrue(
            "expected a disconnected state, got ${manager.connectionState.value}",
            manager.connectionState.value is ConnectionState.Disconnected,
        )
    }

    @Test
    fun `a full handshake carries the state through to Connected`() = runTest {
        val manager = RadioConnectionManager(TestFactory(scope()), scope())

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        assertEquals(ConnectionState.Connected, manager.connectionState.value)
    }

    @Test
    fun `every node of the scenario reaches the frame stream`() = runTest {
        val manager = RadioConnectionManager(TestFactory(scope()), scope())
        val received = drain(manager)

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        assertEquals(5, received.count { it.frame.node_info != null })
    }

    @Test
    fun `the frame order in the stream matches the order they were sent`() = runTest {
        val manager = RadioConnectionManager(TestFactory(scope()), scope())
        val received = drain(manager)

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        val ids = received.map { it.frame.id }
        assertEquals(ids.sorted(), ids)
    }

    @Test
    fun `MyNodeInfo comes first in the stream`() = runTest {
        val manager = RadioConnectionManager(TestFactory(scope()), scope())
        val received = drain(manager)

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        assertTrue(received.first().frame.my_info != null)
    }

    @Test
    fun `disconnecting returns the state to Disconnected`() = runTest {
        val manager = RadioConnectionManager(TestFactory(scope()), scope())
        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        manager.disconnect()
        advanceUntilIdle()

        assertTrue(
            "expected a disconnected state, got ${manager.connectionState.value}",
            manager.connectionState.value is ConnectionState.Disconnected,
        )
    }

    @Test
    fun `the state stays Connecting until the second stage has finished`() = runTest {
        val manager = RadioConnectionManager(TestFactory(scope()), scope())

        manager.onConnect()
        assertEquals(ConnectionState.Connecting, manager.connectionState.value)

        manager.onDataReceived(FromRadio(config_complete_id = MeshProtocol.CONFIG_NONCE).encode())
        assertEquals(
            "after the first stage the connection is not ready yet",
            ConnectionState.Connecting,
            manager.connectionState.value,
        )

        manager.onDataReceived(FromRadio(config_complete_id = MeshProtocol.NODE_INFO_NONCE).encode())
        assertEquals(ConnectionState.Connected, manager.connectionState.value)
    }

    @Test
    fun `collecting the frame flow yields the whole handshake in order`() = runTest {
        val manager = RadioConnectionManager(TestFactory(scope()), scope())

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        val received = manager.frames.take(18).toList().map { it.frame }

        assertNotNull("MyNodeInfo comes first", received.first().my_info)
        assertEquals(
            "and the acknowledgement of the second stage last",
            MeshProtocol.NODE_INFO_NONCE,
            received.last().config_complete_id,
        )
        val ids = received.map { it.id }
        assertEquals("the identifiers are not reordered", ids.sorted(), ids)
        assertEquals(5, received.count { it.node_info != null })
    }

    @Test
    fun `disconnecting sends a goodbye frame before closing the transport`() = runTest {
        val factory = RecordingFactory()
        val manager = RadioConnectionManager(factory, scope())

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        runCurrent()
        manager.disconnect()
        advanceUntilIdle()

        assertEquals(listOf("goodbye", "close"), factory.last.events)
    }

    @Test
    fun `a silent node moves the connection to Disconnected on timeout`() = runTest {
        val manager = RadioConnectionManager(
            factory = SilentFactory(),
            scope = scope(),
            handshakeTimeout = 30.seconds,
        )

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceTimeBy(31.seconds)
        advanceUntilIdle()

        assertTrue(
            "expected a disconnected state, got ${manager.connectionState.value}",
            manager.connectionState.value is ConnectionState.Disconnected,
        )
    }

    @Test
    fun `before the timeout expires the connection stays in Connecting`() = runTest {
        val manager = RadioConnectionManager(
            factory = SilentFactory(),
            scope = scope(),
            handshakeTimeout = 30.seconds,
        )

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceTimeBy(29.seconds)

        assertEquals(ConnectionState.Connecting, manager.connectionState.value)
    }

    @Test
    fun `connecting to the same address again does not recreate the transport`() = runTest {
        val factory = TestFactory(scope())
        val manager = RadioConnectionManager(factory, scope())

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()
        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        assertEquals(1, factory.createdCount)
    }

    @Test
    fun `connecting to a different address recreates the transport`() = runTest {
        val factory = TestFactory(scope())
        val manager = RadioConnectionManager(factory, scope())

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()
        manager.connect("m:${Scenarios.EMPTY_MESH_ID}")
        advanceUntilIdle()

        assertEquals(2, factory.createdCount)
    }

    @Test
    fun `an oversized frame is discarded`() = runTest {
        val manager = RadioConnectionManager(TestFactory(scope()), scope())
        val received = drain(manager)
        // The frame is valid and decodable - the only reason for it not to reach the
        // stream is the size guard.
        val oversized = FromRadio(node_info = NodeInfo(user = User(long_name = "x".repeat(600)))).encode()
        assertTrue("the frame has to exceed the limit", oversized.size > MeshProtocol.MAX_FRAME_BYTES)

        manager.onDataReceived(oversized)
        advanceUntilIdle()

        assertTrue(received.isEmpty())
    }

    @Test
    fun `a corrupt frame does not reach the stream and does not bring the manager down`() = runTest {
        val manager = RadioConnectionManager(TestFactory(scope()), scope())
        val received = drain(manager)

        manager.onDataReceived(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        advanceUntilIdle()

        assertTrue(received.isEmpty())
    }

    @Test
    fun `after a timeout connecting to the same address creates the transport anew`() = runTest {
        val factory = SilentFactory()
        val manager = RadioConnectionManager(factory, scope(), handshakeTimeout = 30.seconds)

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceTimeBy(31.seconds)
        advanceUntilIdle()
        assertTrue(
            "expected a disconnected state, got ${manager.connectionState.value}",
            manager.connectionState.value is ConnectionState.Disconnected,
        )

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()

        assertEquals(
            "after a break on timeout the same address has to connect again",
            2,
            factory.created,
        )
    }

    @Test
    fun `after a failed handshake the manager brings the transport up itself`() = runTest {
        val factory = SilentFactory()
        val manager = RadioConnectionManager(
            factory = factory,
            scope = scope(),
            handshakeTimeout = 30.seconds,
            recoveryDelay = 5.seconds,
        )

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceTimeBy(31.seconds)
        assertEquals("the watchdog is obliged to close the first transport", 1, factory.created)

        advanceTimeBy(6.seconds)

        // The watchdog closes the transport, and its own reconnect loop along with it.
        // Without this fix the application would stand dead until the user tapped, even
        // though the node might simply have been rebooting.
        assertEquals("the manager is obliged to bring the transport up itself", 2, factory.created)
    }

    @Test
    fun `self-recovery is not endless`() = runTest {
        val factory = SilentFactory()
        val manager = RadioConnectionManager(
            factory = factory,
            scope = scope(),
            handshakeTimeout = 30.seconds,
            recoveryDelay = 5.seconds,
        )

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        // Every lap is a timeout plus a pause, that is 35 seconds. Five minutes is
        // enough for all the attempts with plenty to spare.
        advanceTimeBy(5.minutes)

        // The first transport plus three recoveries: a node that connects but does not
        // answer is seriously broken, and an endless loop would only burn the battery.
        assertEquals("the attempts are obliged to run out", 4, factory.created)

        // This flag is what the interface uses to shut the foreground service down.
        // While attempts are still going the process has to stay protected; after
        // surrender there is no point keeping it, and a notification about the
        // connection would be a lie.
        val state = manager.connectionState.value
        assertTrue("the state should be Disconnected, but was $state", state is ConnectionState.Disconnected)
        assertEquals(
            "once the attempts are exhausted the retry flag is obliged to go out",
            false,
            (state as ConnectionState.Disconnected).retrying,
        )
    }

    @Test
    fun `a non-permanent break is marked as attempts still going on`() {
        val manager = RadioConnectionManager(SilentFactory(), CoroutineScope(UnconfinedTestDispatcher()))

        // Exactly what the transport sends on every lap of its own loop: the link is
        // lost, but it carries on trying by itself.
        manager.onDisconnect(isPermanent = false, reason = FailureReason.Literal("the node is out of range"))

        val state = manager.connectionState.value as ConnectionState.Disconnected
        assertEquals(FailureReason.Literal("the node is out of range"), state.reason)
        assertEquals("the service must not go out while the transport is trying", true, state.retrying)
    }

    @Test
    fun `after connecting the heartbeat goes out on schedule`() = runTest {
        val manager = RadioConnectionManager(TestFactory(scope()), scope())
        val received = drain(manager)

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()
        val before = received.count { it.frame.queueStatus != null }

        advanceTimeBy(31.seconds)
        advanceUntilIdle()

        assertTrue(
            "the node answers a heartbeat with a queue status - so it was sent",
            received.count { it.frame.queueStatus != null } > before,
        )
    }

    @Test
    fun `silence for longer than the timeout tears down a zombie session`() = runTest {
        // time = { currentTime }: without TestScope's virtual clock the silence detector
        // would be comparing System.currentTimeMillis() (real time, which barely moves
        // over the course of a test run) against itself - the test would pass even with
        // no working detector at all, having proved nothing.
        val factory = SilentAfterConnectFactory()
        val manager = RadioConnectionManager(
            factory = factory,
            scope = scope(),
            silenceTimeout = SILENCE_TIMEOUT,
            time = TimeSource { currentTime },
        )

        manager.connect("m:${Scenarios.FIVE_NODES_ID}")
        advanceUntilIdle()
        // Silence is only checked AT THE MOMENT the next heartbeat is sent, not
        // continuously, and the comparison is strict (>). With heartbeatInterval=30s and
        // silenceTimeout=60s the threshold condition first holds on the THIRD heartbeat:
        // t=30s (silence 30s, not more than 60s), t=60s (silence exactly 60s, not more -
        // a strict inequality does not let it through), t=90s (silence 90s, more than
        // 60s - break). 91 seconds is guaranteed to capture that tick.
        advanceTimeBy(91.seconds)
        advanceUntilIdle()

        assertTrue(
            "under silence the link counts as dead even if the stack says nothing about a break",
            manager.connectionState.value is ConnectionState.Disconnected,
        )
        // The state alone is not enough: the danger of a zombie session is precisely
        // that nothing underneath will report the break, and an unclosed transport would
        // be left hanging.
        assertTrue("the zombie session's transport is obliged to be closed", factory.last.closed)
    }

    @Test
    fun `the reception time comes from the clock handed to the manager`() = runTest {
        val manager = RadioConnectionManager(
            SilentFactory(),
            scope(),
            time = TimeSource { 1_700_000_009_000 },
        )
        manager.connect("m:test")
        runCurrent()

        manager.onDataReceived(FromRadio(id = 1).encode())

        assertEquals(1_700_000_009_000, manager.frames.take(1).toList().single().rxMillis)
    }

    @Test
    fun `an overflowing queue drops the newest frame and counts it`() = runTest {
        // The documented choice: on overflow it is the newest frame that goes, so the
        // frames already accepted keep their order. Dropping the oldest instead would
        // reorder the configuration frames the handshake is decided by, and the loss
        // would be invisible - the queue would always look full and healthy.
        val manager = RadioConnectionManager(SilentFactory(), scope())
        manager.connect("m:test")
        runCurrent()
        val overflow = 44

        repeat(PACKET_QUEUE_CAPACITY + overflow) { manager.onDataReceived(FromRadio(id = it + 1).encode()) }

        assertEquals(overflow, manager.droppedFrames)
        assertEquals(listOf(1, 2, 3), manager.frames.take(3).toList().map { it.frame.id })
    }

    @Test
    fun `connecting drains the frames of the previous session`() = runTest {
        // Otherwise the previous session's frames occupy the buffer and the new session
        // loses frames of its own while reporting a dropped-frame count of zero.
        val manager = RadioConnectionManager(SilentFactory(), scope())
        manager.connect("m:first")
        runCurrent()
        manager.onDataReceived(FromRadio(id = 1).encode())

        manager.connect("m:second")
        runCurrent()
        manager.onDataReceived(FromRadio(id = 2).encode())

        assertEquals(
            "the first frame a consumer sees belongs to the new session",
            2,
            manager.frames.take(1).toList().single().frame.id,
        )
        assertEquals(0, manager.droppedFrames)
    }

    @Test
    fun `a reload asks the node for its database again`() = runTest {
        val factory = RecordingFactory()
        val manager = RadioConnectionManager(factory, scope())
        manager.connect("m:test")
        runCurrent()
        completeHandshake(manager)
        factory.last.sent.clear()

        manager.reloadNodeDatabase()
        // runCurrent, not advanceUntilIdle: the reload watchdog is scheduled 30 seconds
        // out, and any helper that winds the clock that far would clear the flag itself
        // and leave the assertion below proving nothing.
        runCurrent()

        val request = ToRadio.ADAPTER.decode(factory.last.sent.single())
        assertEquals(MeshProtocol.NODE_INFO_RELOAD_NONCE, request.want_config_id)
        assertTrue(manager.nodeDbReloading.value)
    }

    @Test
    fun `a reload finishes when the node acknowledges it`() = runTest {
        val manager = RadioConnectionManager(RecordingFactory(), scope())
        manager.connect("m:test")
        runCurrent()
        completeHandshake(manager)
        manager.reloadNodeDatabase()
        runCurrent()
        // Asserted before the acknowledgement, and the clock deliberately left where it
        // is: if the watchdog had already fired, the assertion below would hold even
        // with no acknowledgement branch at all.
        assertTrue(manager.nodeDbReloading.value)

        manager.onDataReceived(FromRadio(config_complete_id = MeshProtocol.NODE_INFO_RELOAD_NONCE).encode())
        runCurrent()

        assertFalse(manager.nodeDbReloading.value)
    }

    @Test
    fun `a reload leaves the connection state and the heartbeat alone`() = runTest {
        // The whole reason for a separate nonce. Rerunning handshake completion would
        // republish Connected and restart the heartbeat, and a heartbeat restart resets
        // the silence detector - handing a dying link a free extension on every reload.
        val factory = RecordingFactory()
        val manager = RadioConnectionManager(
            factory = factory,
            scope = scope(),
            time = TimeSource { currentTime },
            heartbeatInterval = HEARTBEAT_INTERVAL,
            silenceTimeout = SILENCE_TIMEOUT,
            // Self-recovery would create a second transport and move the state back to
            // Connecting five seconds after the silence detector fired; pushing it out of
            // the way keeps the assertion window below unambiguous.
            recoveryDelay = 10.minutes,
        )
        manager.connect("m:test")
        runCurrent()
        completeHandshake(manager)

        // The reload has to land mid-session rather than at t=0: a reload at the very
        // instant the heartbeat started would restart it onto exactly the schedule it
        // already had, and the test could not tell the two implementations apart.
        advanceTimeBy(50.seconds)
        manager.reloadNodeDatabase()
        manager.onDataReceived(FromRadio(config_complete_id = MeshProtocol.NODE_INFO_RELOAD_NONCE).encode())
        runCurrent()

        assertEquals(ConnectionState.Connected, manager.connectionState.value)

        // The heartbeat keeps the schedule it already had, and the silence detector keeps
        // measuring from the last frame: its ticks at t=60s and t=90s see 10s and 40s of
        // silence, and the tick at t=120s sees 70s - past the 60s limit. A reload that
        // restarted the heartbeat would move those ticks to 80/110/140 and would still
        // be Connected here.
        //
        // That discrimination rests on the silence comparison in startKeepAlive being
        // strict (>): the restarted heartbeat's tick at t=110s sees exactly 60_000 ms of
        // silence and survives, which is what pushes its break out to t=140s, past the
        // window asserted below. Relax that comparison to >= and the mutant breaks at
        // t=110s instead - this test would keep passing while no longer telling the two
        // implementations apart. Anyone touching the comparison has to revisit the numbers
        // here.
        advanceTimeBy(75.seconds)

        val state = manager.connectionState.value
        assertTrue(
            "the silence detector still measures from the last frame, got $state",
            state is ConnectionState.Disconnected,
        )
    }

    @Test
    fun `a reload that is never answered stops claiming to be in progress`() = runTest {
        // Without a timeout the spinner spins for ever on a node that ignores the
        // request, and the user cannot tell a slow reload from a broken one.
        val manager = RadioConnectionManager(
            factory = RecordingFactory(),
            scope = scope(),
            time = TimeSource { currentTime },
        )
        manager.connect("m:test")
        runCurrent()
        completeHandshake(manager)
        manager.reloadNodeDatabase()
        runCurrent()
        assertTrue(manager.nodeDbReloading.value)

        advanceTimeBy(RELOAD_TIMEOUT + 1.seconds)

        assertFalse(manager.nodeDbReloading.value)
    }

    @Test
    fun `a reload is refused without a finished handshake`() = runTest {
        val manager = RadioConnectionManager(SilentFactory(), scope())

        // Nothing has ever been connected.
        manager.reloadNodeDatabase()
        runCurrent()
        assertFalse(manager.nodeDbReloading.value)

        // And the link is up but the node has not answered the handshake, so there is no
        // database to ask for yet. A gate written as "not Disconnected" would let this
        // one through, and the reload would be sent into a session that has not proved
        // it can answer anything.
        manager.connect("m:test")
        runCurrent()
        assertEquals(ConnectionState.Connecting, manager.connectionState.value)

        manager.reloadNodeDatabase()
        runCurrent()
        assertFalse(manager.nodeDbReloading.value)
    }
}
