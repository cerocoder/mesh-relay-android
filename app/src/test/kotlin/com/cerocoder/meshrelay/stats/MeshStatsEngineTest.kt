package com.cerocoder.meshrelay.stats

import com.cerocoder.meshrelay.stats.model.Counters
import com.cerocoder.meshrelay.stats.model.PositionOrigin
import com.cerocoder.meshrelay.stats.model.SignalSeries
import com.cerocoder.meshrelay.stats.model.StampedPosition
import com.cerocoder.meshrelay.stats.model.StatsSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.ByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.MyNodeInfo
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.User
import kotlin.time.Duration.Companion.minutes

private const val SENDER = 0x9e75f1a4.toInt()

private fun relayed(
    relay: Int = 0x69,
    from: Int = SENDER,
    snr: Float = -7.5f,
    rssi: Int = -94,
    at: Long = 1_000L,
) =
    TimestampedFrame(
        rxMillis = at,
        frame = FromRadio(
            packet = MeshPacket(
                from = from, relay_node = relay, hop_start = 3, hop_limit = 1,
                rx_snr = snr, rx_rssi = rssi,
            ),
        ),
    )

private fun direct(from: Int = SENDER, at: Long = 1_000L) = TimestampedFrame(
    rxMillis = at,
    frame = FromRadio(packet = MeshPacket(from = from, relay_node = 0, rx_snr = -3f, rx_rssi = -80)),
)

/** A POSITION_APP packet from [from], carrying coordinates and an altitude - both
 *  are set here for realism, but PositionHistory.newestWithCoordinates only
 *  requires the coordinates; these tests do not exercise the altitude-optional
 *  case. */
private fun positionFrame(from: Int, latI: Int, lonI: Int, at: Long = 1_000L) = TimestampedFrame(
    rxMillis = at,
    frame = FromRadio(
        packet = MeshPacket(
            from = from, relay_node = 0, rx_snr = -3f, rx_rssi = -80,
            decoded = Data(
                portnum = PortNum.POSITION_APP,
                payload = ByteString.of(
                    *Position(latitude_i = latI, longitude_i = lonI, altitude = 600).encode(),
                ),
            ),
        ),
    ),
)

/** The handshake frame that tells the engine which node is ours. */
private fun myInfoFrame(num: Int) = TimestampedFrame(
    rxMillis = 1_000L,
    frame = FromRadio(my_info = MyNodeInfo(my_node_num = num)),
)

/** One node database entry, as the radio hands it over during the handshake. */
private fun nodeInfoFrame(num: Int, longName: String) = TimestampedFrame(
    rxMillis = 1_000L,
    frame = FromRadio(
        node_info = NodeInfo(num = num, user = User(long_name = longName, short_name = "1ce5")),
    ),
)

/**
 * Terminates a want_config round. [NodeDirectory.markLoaded] commits whatever
 * `node_info` frames the round staged; stats/ cannot tell one nonce from another
 * (that needs transport/, which it may not import), so the value carried here is
 * never inspected and any value works.
 */
private fun configCompleteFrame() = TimestampedFrame(
    rxMillis = 1_000L,
    frame = FromRadio(config_complete_id = 1),
)

/**
 * A NODEINFO_APP packet: a node announcing itself in ordinary traffic rather than
 * through the node database. Used to prove that a packet with no sender teaches
 * the directory nothing - `applyUser(0, ...)` would write an air record for node
 * 0, and `lastByteOfNodeNum(0)` is 0xff, so node 0 would become a naming
 * candidate for every relay that identifies itself as ff.
 */
private fun userFrame(from: Int, shortName: String) = TimestampedFrame(
    rxMillis = 1_000L,
    frame = FromRadio(
        packet = MeshPacket(
            from = from, relay_node = 0, rx_snr = -3f, rx_rssi = -80,
            decoded = Data(
                portnum = PortNum.NODEINFO_APP,
                payload = ByteString.of(*User(long_name = "ghost", short_name = shortName).encode()),
            ),
        ),
    ),
)

/**
 * A POSITION_APP packet whose payload cannot be decoded.
 *
 * The single byte 0x0f is a field tag naming wire type 7, which no protobuf
 * encoding defines, so the reader throws before it has read a field - the
 * shortest input that is guaranteed to fail rather than merely to produce
 * nonsense. Sent as a direct packet so the relay assertions around it stay about
 * the frame that follows.
 */
private fun brokenPositionFrame() = TimestampedFrame(
    rxMillis = 1_000L,
    frame = FromRadio(
        packet = MeshPacket(
            from = SENDER, relay_node = 0, rx_snr = -3f, rx_rssi = -80,
            decoded = Data(
                portnum = PortNum.POSITION_APP,
                payload = ByteString.of(0x0f.toByte()),
            ),
        ),
    ),
)

/**
 * A TELEMETRY_APP packet whose payload fills two variants of one `oneof`.
 *
 * `12 00` is field 2, length 0 - an empty `device_metrics`; `1a 00` is field 3,
 * length 0 - an empty `environment_metrics`. Every byte is well formed, so the
 * reader never objects: it is Wire's *generated constructor* that rejects the
 * result, with a `require()` over `Internal.countNonNull` on the variant fields,
 * and that throws `IllegalArgumentException`.
 *
 * This is the case that decides the width of the catch, and the reason
 * [brokenPositionFrame] cannot decide it alone: a wire-type error surfaces as
 * `java.net.ProtocolException`, which *extends* `IOException`, so a catch
 * narrowed to `IOException` would still handle it. Only this frame escapes such a
 * catch and kills the loop. Sent as a direct packet, like [brokenPositionFrame].
 */
private fun conflictingTelemetryFrame() = TimestampedFrame(
    rxMillis = 1_000L,
    frame = FromRadio(
        packet = MeshPacket(
            from = SENDER, relay_node = 0, rx_snr = -3f, rx_rssi = -80,
            decoded = Data(
                portnum = PortNum.TELEMETRY_APP,
                payload = ByteString.of(*byteArrayOf(0x12, 0x00, 0x1a, 0x00)),
            ),
        ),
    ),
)

/**
 * Every test drives the virtual clock with [runCurrent] rather than
 * `advanceUntilIdle()`.
 *
 * The engine and everything it launches live on `backgroundScope`, and
 * `advanceUntilIdle()` stops as soon as no *foreground* task is left in the
 * queue - with nothing but background work it runs nothing at all and every
 * assertion below would read state that was never computed. `runCurrent()` has
 * no such distinction: it runs every task queued at the current virtual time,
 * which is all of them, because the engine has no timer of its own.
 *
 * `advanceTimeBy` appears exactly once, in the test that asserts idleness, and
 * that scarcity is the point: there is nothing else here for time to drive.
 */
class MeshStatsEngineTest {

    private fun engine(
        scope: CoroutineScope,
        skipped: MutableStateFlow<Set<Int>> = MutableStateFlow(emptySet()),
    ) = MeshStatsEngine(scope, skipped, SortMode.PACKETS) { 1_000L }

    /**
     * Subscribes to [MeshStatsEngine.snapshot] and returns the list every emission
     * is appended to.
     *
     * The collector runs on an `UnconfinedTestDispatcher`, not on the test's
     * `StandardTestDispatcher`, and that is load-bearing rather than stylistic.
     * The command loop has **no suspension point** between buffered commands -
     * `apply`, `buildSnapshot`, `directory.snapshot` and both `StateFlow`
     * assignments are all ordinary calls, and `ChannelIterator.hasNext()` returns
     * straight from the buffer - so an implementation that rebuilt once per
     * command would perform all thirty assignments inside a single scheduler
     * event. A *dispatched* collector is resumed by the first assignment and, for
     * every assignment after it, merely marked pending
     * (`StateFlowSlot.makePending`); it would therefore wake exactly once and read
     * only the final value, and thirty rebuilds would be indistinguishable from
     * one. An unconfined collector is resumed inline by each assignment, so the
     * list below counts rebuilds rather than scheduler turns - which is the only
     * way `a burst of frames costs one snapshot` can fail against the
     * implementation it forbids.
     *
     * Same pattern, for the same reason, as `RadioConnectionManagerTest:181`. The
     * side effect is that the subscription is established eagerly, during this
     * call rather than at the next [runCurrent] - which only makes the
     * subscription point easier to reason about.
     */
    private fun TestScope.collectSnapshots(subject: MeshStatsEngine): List<StatsSnapshot> {
        val seen = mutableListOf<StatsSnapshot>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            subject.snapshot.collect { seen += it }
        }
        return seen
    }

    /** Subscribes to [MeshStatsEngine.series] the same unconfined way
     *  [collectSnapshots] subscribes to the snapshot, and for the same reason. */
    private fun TestScope.collectSeries(subject: MeshStatsEngine): List<SignalSeries?> {
        val seen = mutableListOf<SignalSeries?>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            subject.series.collect { seen += it }
        }
        return seen
    }

    @Test
    fun `a relayed packet builds a relay entry`() = runTest(StandardTestDispatcher()) {
        val subject = engine(backgroundScope)
        val seen = collectSnapshots(subject)
        runCurrent()

        subject.attach(flowOf(relayed()))
        runCurrent()

        val relay = seen.last().relays.single()
        assertEquals(0x69, relay.relayByte)
        assertEquals(1, relay.packetCount)
        assertEquals(-7.5f, relay.snr.lastVal, 0.0001f)
        assertEquals(-94f, relay.rssi.lastVal, 0.0001f)
        assertEquals(1, seen.last().counters.totalRelayedPackets)
        assertEquals(1, seen.last().counters.totalPackets)
        // The sender is what the relay carries, not a neighbour of ours.
        assertTrue(seen.last().neighbours.isEmpty())
        assertEquals(setOf(SENDER), relay.fromNodeStats.keys)
    }

    @Test
    fun `a direct packet builds a neighbour entry and no relay`() = runTest(StandardTestDispatcher()) {
        val subject = engine(backgroundScope)
        val seen = collectSnapshots(subject)
        runCurrent()

        subject.attach(flowOf(direct()))
        runCurrent()

        assertTrue(seen.last().relays.isEmpty())
        assertEquals(SENDER, seen.last().neighbours.single().nodeNum)
        assertEquals(1, seen.last().counters.totalDirectPackets)
    }

    @Test
    fun `our own node is not a neighbour and does not move any counter`() = runTest(StandardTestDispatcher()) {
        // Decision 1: the owner chose for our own traffic to be invisible to the
        // statistics, not merely absent from the Direct tally. A row with no SNR and
        // no RSSI in a list about signal quality is noise, and counting it inflates
        // the divisor every other neighbour's percentage uses.
        val subject = engine(backgroundScope)
        val seen = collectSnapshots(subject)
        subject.attach(flowOf(myInfoFrame(SENDER), direct(from = SENDER), direct(from = SENDER)))
        runCurrent()

        assertTrue(seen.last().neighbours.isEmpty())
        assertEquals(0, seen.last().counters.totalDirectPackets)
        assertEquals(0, seen.last().counters.totalPackets)
    }

    @Test
    fun `another node's direct traffic is unaffected`() = runTest(StandardTestDispatcher()) {
        val other = 0x1111_2222
        val subject = engine(backgroundScope)
        val seen = collectSnapshots(subject)
        subject.attach(flowOf(myInfoFrame(SENDER), direct(from = SENDER), direct(from = other)))
        runCurrent()

        assertEquals(listOf(other), seen.last().neighbours.map { it.nodeNum })
        assertEquals(1, seen.last().counters.totalDirectPackets)
        assertEquals(1, seen.last().counters.totalPackets)
    }

    @Test
    fun `our own position still reaches the directory`() = runTest(StandardTestDispatcher()) {
        // Decision 2, and the reason the guard sits after decodePayload: our own
        // POSITION_APP packets are how localPosition() learns where we are, which is
        // what stamps every Graph measurement when Use phone location is off.
        // Dropping the packet before decoding would silently blank every globe.
        val subject = engine(backgroundScope)
        val seen = collectSnapshots(subject)
        subject.attach(flowOf(myInfoFrame(SENDER), positionFrame(SENDER, 398628316, -40273231)))
        runCurrent()

        assertNotNull(seen.last().directory.localPosition())
        assertEquals(39.8628316, seen.last().directory.localPosition()!!.lat, 1e-7)
        // and still counted nowhere
        assertEquals(0, seen.last().counters.totalPackets)
    }

    @Test
    fun `our own transmission heard back through a relay is still a relay measurement`() = runTest(StandardTestDispatcher()) {
        // `from` being our node does not make a packet uninteresting. A relay that
        // rebroadcast our transmission and let us hear it has told us about its link
        // to us, which is exactly what this app measures. Only the Direct case - us as
        // our own neighbour - is excluded.
        val subject = engine(backgroundScope)
        val seen = collectSnapshots(subject)
        subject.attach(flowOf(myInfoFrame(SENDER), relayed(from = SENDER)))
        runCurrent()

        assertEquals(1, seen.last().relays.size)
        assertEquals(1, seen.last().counters.totalRelayedPackets)
        assertEquals(1, seen.last().counters.totalPackets)
        assertTrue(seen.last().neighbours.isEmpty())
    }

    @Test
    fun `a burst of frames costs one snapshot, not one per frame`() = runTest(StandardTestDispatcher()) {
        // The whole reason the loop drains before building. At mesh traffic rates a
        // snapshot per packet would rebuild and recompose the list dozens of times a
        // second for no visible difference.
        val subject = engine(backgroundScope)
        val seen = collectSnapshots(subject)
        runCurrent()
        val before = seen.size

        subject.attach(flowOf(*Array(30) { relayed() }))
        runCurrent()

        assertEquals("expected one snapshot for the burst", 1, seen.size - before)
        assertEquals(30, seen.last().relays.single().packetCount)
    }

    @Test
    fun `no snapshot is built while nothing is subscribed`() = runTest(StandardTestDispatcher()) {
        // With the screen off and the foreground service running, this is the whole
        // battery argument. Ingestion must continue; snapshot building must not.
        val subject = engine(backgroundScope)
        subject.attach(flowOf(relayed(), relayed(), relayed()))
        runCurrent()

        assertSame(StatsSnapshot.EMPTY, subject.snapshot.value)
        assertEquals(3, subject.counters.value.totalRelayedPackets)
        assertEquals(3, subject.counters.value.totalPackets)
        assertEquals(1, subject.counters.value.relayCount)
    }

    @Test
    fun `subscribing after the fact gets the current state, not an empty one`() = runTest(StandardTestDispatcher()) {
        val subject = engine(backgroundScope)
        subject.attach(flowOf(relayed()))
        runCurrent()

        val seen = collectSnapshots(subject)
        runCurrent()

        assertEquals(1, seen.last().relays.single().packetCount)
    }

    @Test
    fun `an idle engine rebuilds nothing`() = runTest(StandardTestDispatcher()) {
        // There is no tick. A snapshot exists because state changed, never because
        // time passed - which is what lets a subscribed screen sit on a quiet mesh
        // without the engine doing any work at all.
        val subject = engine(backgroundScope)
        val seen = collectSnapshots(subject)
        runCurrent()
        subject.attach(flowOf(relayed()))
        runCurrent()
        val settled = seen.size

        advanceTimeBy(10.minutes)
        runCurrent()

        assertEquals("an idle engine must not rebuild", settled, seen.size)
    }

    @Test
    fun `a paused engine drops packets whole`() = runTest(StandardTestDispatcher()) {
        // Ports mesh_stats.py:1012-1014: paused means the packet never happened, not
        // that it happened and was not displayed. Even the total is untouched.
        val subject = engine(backgroundScope)
        collectSnapshots(subject)
        runCurrent()
        subject.setPaused(true)
        runCurrent()

        subject.attach(flowOf(relayed()))
        runCurrent()

        assertEquals(Counters.EMPTY, subject.counters.value)
        assertTrue(subject.snapshot.value.relays.isEmpty())
        assertTrue(subject.snapshot.value.paused)
    }

    @Test
    fun `pausing does not stop the node database`() = runTest(StandardTestDispatcher()) {
        // The node database is not statistics. A handshake that lands while the user
        // has the screen paused must still be learned, or resuming would show relays
        // that cannot be named.
        val subject = engine(backgroundScope)
        collectSnapshots(subject)
        runCurrent()
        subject.setPaused(true)
        runCurrent()

        subject.attach(flowOf(nodeInfoFrame(SENDER, "PQPL1"), configCompleteFrame()))
        runCurrent()

        assertEquals(1, subject.snapshot.value.directory.count)
        assertEquals(Counters.EMPTY, subject.counters.value)
    }

    @Test
    fun `reset clears statistics but keeps the node database and the skip list`() = runTest(StandardTestDispatcher()) {
        val skipped = MutableStateFlow(setOf(0x11223344))
        val subject = engine(backgroundScope, skipped)
        collectSnapshots(subject)
        subject.attach(flowOf(nodeInfoFrame(SENDER, "PQPL1"), configCompleteFrame(), relayed(), direct()))
        runCurrent()

        subject.reset()
        runCurrent()

        val after = subject.snapshot.value
        assertTrue(after.relays.isEmpty())
        assertTrue(after.neighbours.isEmpty())
        assertEquals(Counters.EMPTY, after.counters)
        assertNull(after.lastPacketAtMillis)
        assertNull(after.lastRelayedPacketAtMillis)
        assertEquals(1, after.directory.count)                       // the node survives
        assertEquals(setOf(0x11223344), after.skippedRelayNodes)     // so does the skip list
    }

    @Test
    fun `a new node forgets the node database as well as the statistics`() = runTest(StandardTestDispatcher()) {
        // Reset keeps the node database on purpose - reloading it costs a round trip
        // to the radio. A *different* node is the opposite case: that database
        // describes somebody else's mesh view and every name in it may be wrong.
        val subject = engine(backgroundScope)
        val seen = collectSnapshots(subject)
        subject.attach(
            flowOf(
                myInfoFrame(SENDER),
                nodeInfoFrame(SENDER, "PQPL1"),
                configCompleteFrame(),
                relayed(),
                direct(from = 0x4242),
            ),
        )
        runCurrent()
        assertEquals(1, seen.last().relays.size)
        assertEquals(1, seen.last().directory.count)

        subject.resetForNewNode()
        runCurrent()

        assertTrue(seen.last().relays.isEmpty())
        assertTrue(seen.last().neighbours.isEmpty())
        assertEquals(0, seen.last().counters.totalPackets)
        assertEquals(0, seen.last().directory.count)
        assertNull(seen.last().directory.localNodeNum)
    }

    @Test
    fun `an ordinary reset still keeps the node database`() = runTest(StandardTestDispatcher()) {
        // The two must not converge: this is the distinction the new path exists for.
        val subject = engine(backgroundScope)
        val seen = collectSnapshots(subject)
        subject.attach(flowOf(nodeInfoFrame(SENDER, "PQPL1"), configCompleteFrame(), relayed()))
        runCurrent()

        subject.reset()
        runCurrent()

        assertTrue(seen.last().relays.isEmpty())
        assertEquals(1, seen.last().directory.count)
    }

    @Test
    fun `beginNodeDbRound reaches the directory and discards a stale partial round`() =
        runTest(StandardTestDispatcher()) {
            // Proves the public entry point RadioConnectionManager calls actually
            // reaches NodeDirectory.beginRound through the command channel, not just
            // that beginRound itself works - NodeDirectoryTest already covers that.
            val subject = engine(backgroundScope)
            val seen = collectSnapshots(subject)
            // A round abandoned mid-flight, as a dropped reload would leave it: staged
            // but never committed, so the directory does not see it yet either.
            subject.attach(flowOf(nodeInfoFrame(SENDER, "stale")))
            runCurrent()
            assertEquals(0, seen.last().directory.count)

            subject.beginNodeDbRound()
            runCurrent()

            subject.attach(flowOf(nodeInfoFrame(0x4242, "fresh"), configCompleteFrame()))
            runCurrent()

            val after = seen.last().directory
            assertEquals(setOf(0x4242), after.nodes.keys)
            assertEquals("fresh", after.node(0x4242)?.longName)
        }

    @Test
    fun `changing the sort mode reorders without losing anything`() = runTest(StandardTestDispatcher()) {
        val subject = engine(backgroundScope)
        collectSnapshots(subject)
        subject.attach(flowOf(relayed(relay = 0x69, snr = -15f), relayed(relay = 0x69, snr = -15f), relayed(relay = 0xa4, snr = 5f)))
        runCurrent()

        subject.setSortMode(SortMode.PACKETS)
        runCurrent()
        assertEquals(listOf(0x69, 0xa4), subject.snapshot.value.relays.map { it.relayByte })

        subject.setSortMode(SortMode.AVG_SNR)
        runCurrent()
        assertEquals(listOf(0xa4, 0x69), subject.snapshot.value.relays.map { it.relayByte })
        assertEquals(SortMode.AVG_SNR, subject.snapshot.value.sortMode)
        // Reordering is not a reset: both relays keep everything they had.
        assertEquals(listOf(1, 2), subject.snapshot.value.relays.map { it.packetCount })
        assertEquals(3, subject.snapshot.value.counters.totalRelayedPackets)
    }

    @Test
    fun `a relay with no signal samples sorts last rather than first`() = runTest(StandardTestDispatcher()) {
        // A relay whose packets all arrived with rx_rssi == 0 has no average. Treated
        // as 0.0 dB it would outrank every real measurement on the screen.
        val subject = engine(backgroundScope)
        collectSnapshots(subject)
        subject.attach(flowOf(relayed(relay = 0x69, snr = -15f), relayed(relay = 0xa4, rssi = 0, snr = 0f)))
        runCurrent()
        subject.setSortMode(SortMode.AVG_SNR)
        runCurrent()

        assertEquals(listOf(0x69, 0xa4), subject.snapshot.value.relays.map { it.relayByte })
    }

    @Test
    fun `skipping a node changes which relays it is a candidate for`() = runTest(StandardTestDispatcher()) {
        val skipped = MutableStateFlow(emptySet<Int>())
        val subject = engine(backgroundScope, skipped)
        collectSnapshots(subject)
        subject.attach(flowOf(nodeInfoFrame(SENDER, "PQPL1"), configCompleteFrame(), relayed(relay = 0xa4)))
        runCurrent()
        assertEquals("1ce5", subject.snapshot.value.directory.uniqueRelayName(0xa4))
        assertEquals("1ce5", subject.snapshot.value.relays.single().nodeName)

        skipped.value = setOf(SENDER)
        runCurrent()

        assertEquals("", subject.snapshot.value.directory.uniqueRelayName(0xa4))
        assertEquals("", subject.snapshot.value.relays.single().nodeName)
    }

    @Test
    fun `an undecodable payload does not stop ingestion`() = runTest(StandardTestDispatcher()) {
        // One malformed position packet must not end the only channel data arrives on.
        val subject = engine(backgroundScope)
        collectSnapshots(subject)
        subject.attach(flowOf(brokenPositionFrame(), relayed()))
        runCurrent()

        assertEquals(1, subject.snapshot.value.relays.single().packetCount)
        // The broken frame was still heard, and is still a directly received packet.
        assertEquals(2, subject.snapshot.value.counters.totalPackets)
        assertEquals(1, subject.snapshot.value.counters.totalDirectPackets)
    }

    @Test
    fun `a payload rejected by a generated require does not stop ingestion either`() = runTest(StandardTestDispatcher()) {
        // This is the test that fixes the width of the catch, and the one above
        // cannot do it: a wire-type error reaches us as java.net.ProtocolException,
        // which extends IOException, so narrowing the catch to IOException would
        // leave that test green. A Telemetry payload occupying two variants of its
        // oneof is rejected by Wire's generated constructor - a require() over
        // Internal.countNonNull - and throws IllegalArgumentException, which walks
        // straight past an IOException catch, out of the command loop, and ends the
        // only channel data arrives on. The relayed frame behind it is what proves
        // the loop is still alive.
        val subject = engine(backgroundScope)
        collectSnapshots(subject)
        subject.attach(flowOf(conflictingTelemetryFrame(), relayed()))
        runCurrent()

        assertEquals(1, subject.snapshot.value.relays.single().packetCount)
        assertEquals(2, subject.snapshot.value.counters.totalPackets)
        assertEquals(1, subject.snapshot.value.counters.totalDirectPackets)
    }

    @Test
    fun `a packet with no sender opens no entry anywhere`() = runTest(StandardTestDispatcher()) {
        // `from` is not optional on the wire, so a malformed packet arrives as node 0
        // rather than as an absent field, and the Python's `if from_node is not None`
        // (mesh_stats.py:1046) has nothing to test. Node 0 is not a node: it must not
        // become a neighbour, a relay's remote node, or a directory record - the last
        // of which would make it a naming candidate for relay byte ff.
        val subject = engine(backgroundScope)
        collectSnapshots(subject)
        subject.attach(flowOf(userFrame(from = 0, shortName = "gh05"), relayed(from = 0), relayed()))
        runCurrent()

        val after = subject.snapshot.value
        assertTrue("node 0 is not a neighbour", after.neighbours.isEmpty())
        // Not directory.count: applyUser can no longer write the database record
        // under any circumstances, so that would pass regardless of the from == 0
        // guard. What the guard actually protects is the air store - a record
        // there for node 0 would be a naming candidate for relay byte ff.
        assertTrue("node 0 is not in the air store either", after.directory.airNodes.isEmpty())
        assertEquals(setOf(SENDER), after.relays.single().fromNodeStats.keys)
        assertEquals(1, after.relays.single().packetCount)
        assertEquals(0, after.counters.totalDirectPackets)
        assertEquals(1, after.counters.totalRelayedPackets)
        // Heard is heard: the radio did receive three packets.
        assertEquals(3, after.counters.totalPackets)
    }

    @Test
    fun `a relay byte wider than a byte is rejected rather than masked`() = runTest(StandardTestDispatcher()) {
        // relay_node is a uint32 in the schema and reaches us as a plain Int, so a
        // malformed value can be anything - including negative, which is how a uint32
        // above 2^31 arrives. Masking it into range would invent a relay: 0x1269 would
        // become 0x69 and 0xffffffff would become 0xff, both real relays in this mesh,
        // and their measured averages would be polluted with packets that were never
        // theirs. RelayStats.hexId formats "0x%02x", so an unrejected 0x1269 would also
        // print as four digits everywhere the byte is shown.
        val subject = engine(backgroundScope)
        collectSnapshots(subject)
        subject.attach(flowOf(relayed(relay = 0x1269), relayed(relay = -1), relayed(relay = 0x69)))
        runCurrent()

        val after = subject.snapshot.value
        assertEquals(listOf("0x69"), after.relays.map { it.hexId })
        assertEquals(1, after.relays.single().packetCount)
        assertEquals(1, after.counters.totalRelayedPackets)
        assertEquals(1, after.counters.relayCount)
        assertEquals(3, after.counters.totalPackets)
    }

    @Test
    fun `known nodes sorts relays by how many remote nodes they carry`() = runTest(StandardTestDispatcher()) {
        // Not the packet count: 0x69 carries the most traffic and the fewest nodes.
        val subject = engine(backgroundScope)
        collectSnapshots(subject)
        subject.attach(
            flowOf(
                relayed(relay = 0x69, from = 0x11111111),
                relayed(relay = 0x69, from = 0x11111111),
                relayed(relay = 0x69, from = 0x11111111),
                relayed(relay = 0xa4, from = 0x11111111),
                relayed(relay = 0xa4, from = 0x22222222),
                relayed(relay = 0xa4, from = 0x33333333),
                relayed(relay = 0xb7, from = 0x11111111),
                relayed(relay = 0xb7, from = 0x22222222),
            ),
        )
        runCurrent()
        subject.setSortMode(SortMode.KNOWN_NODES)
        runCurrent()

        val after = subject.snapshot.value
        assertEquals(listOf(0xa4, 0xb7, 0x69), after.relays.map { it.relayByte })
        assertEquals(listOf(3, 2, 1), after.relays.map { it.knownNodesCount })
    }

    @Test
    fun `latest packet puts the most recently heard relay first`() = runTest(StandardTestDispatcher()) {
        val subject = engine(backgroundScope)
        collectSnapshots(subject)
        subject.attach(
            flowOf(
                relayed(relay = 0x69, at = 3_000L),
                relayed(relay = 0xa4, at = 1_000L),
                relayed(relay = 0xb7, at = 2_000L),
            ),
        )
        runCurrent()
        subject.setSortMode(SortMode.LATEST_PACKET)
        runCurrent()

        assertEquals(listOf(0x69, 0xb7, 0xa4), subject.snapshot.value.relays.map { it.relayByte })
    }

    @Test
    fun `latest packet orders neighbours the same way`() = runTest(StandardTestDispatcher()) {
        val subject = engine(backgroundScope)
        collectSnapshots(subject)
        subject.attach(
            flowOf(
                direct(from = 0x11111111, at = 1_000L),
                direct(from = 0x22222222, at = 3_000L),
                direct(from = 0x33333333, at = 2_000L),
            ),
        )
        runCurrent()
        subject.setSortMode(SortMode.LATEST_PACKET)
        runCurrent()

        assertEquals(
            listOf(0x22222222, 0x33333333, 0x11111111),
            subject.snapshot.value.neighbours.map { it.nodeNum },
        )
    }

    @Test
    fun `known nodes falls back to packet count for neighbours`() = runTest(StandardTestDispatcher()) {
        // A neighbour has no set of carried nodes. The mode can still reach this list by
        // being chosen on the relay screen or saved as the default, and when it does the
        // list must be ordered by something real - not left in map order.
        assertEquals(SortMode.PACKETS, SortMode.KNOWN_NODES.forNeighbours())

        // And the engine must agree with that function rather than reimplement it: the
        // order below is the one PACKETS produces, arrived at through KNOWN_NODES.
        val subject = engine(backgroundScope)
        collectSnapshots(subject)
        subject.attach(
            flowOf(
                direct(from = 0x11111111, at = 3_000L),
                direct(from = 0x22222222, at = 1_000L),
                direct(from = 0x22222222, at = 1_000L),
            ),
        )
        runCurrent()
        subject.setSortMode(SortMode.KNOWN_NODES)
        runCurrent()

        assertEquals(
            listOf(0x22222222, 0x11111111),
            subject.snapshot.value.neighbours.map { it.nodeNum },
        )
    }

    @Test
    fun `every other mode is unchanged for neighbours`() {
        SortMode.entries.filter { it != SortMode.KNOWN_NODES }
            .forEach { assertEquals(it, it.forNeighbours()) }
    }

    @Test
    fun `measurements are collected with nobody watching`() = runTest(StandardTestDispatcher()) {
        // Spec requirement 14: the series exists from the first packet, whether or not
        // a chart is open. A chart opened an hour into a survey must show that hour.
        val subject = engine(backgroundScope)
        subject.attach(flowOf(relayed(), relayed(), relayed()))
        runCurrent()

        val seen = collectSeries(subject)
        subject.watchSeries(SeriesKey.Relay(0x69))
        runCurrent()

        assertEquals(3, seen.last()?.size)
    }

    @Test
    fun `a neighbour's measurements land under a neighbour key`() = runTest(StandardTestDispatcher()) {
        val subject = engine(backgroundScope)
        val seen = collectSeries(subject)
        subject.watchSeries(SeriesKey.Neighbour(SENDER))
        subject.attach(flowOf(direct(), direct()))
        runCurrent()

        assertEquals(2, seen.last()?.size)
        assertEquals(-80f, seen.last()?.rssi(0)!!, 0.0001f)
        assertEquals(-3f, seen.last()?.snr(1)!!, 0.0001f)
    }

    @Test
    fun `nothing is published while nothing is watched`() = runTest(StandardTestDispatcher()) {
        // publishWatchedSeries()'s one gate is watchedSeries != null - never called
        // here - so the buffer fills but the series stays untouched.
        val subject = engine(backgroundScope)
        subject.attach(flowOf(relayed()))
        runCurrent()

        assertNull(subject.series.value)
    }

    @Test
    fun `watching publishes even before the series has a subscriber`() = runTest(StandardTestDispatcher()) {
        // publishWatchedSeries() used to have a second gate on
        // _series.subscriptionCount, mirroring _snapshot's - see the fix report for
        // why it was removed. This pins the removal: a watched subject's series is
        // built and set as soon as it exists, whether or not a screen has
        // subscribed to read it yet, so a later subscriber sees it immediately via
        // StateFlow's replay rather than needing a Refresh command to arrive first.
        val subject = engine(backgroundScope)
        subject.watchSeries(SeriesKey.Relay(0x69))
        subject.attach(flowOf(relayed()))
        runCurrent()

        assertEquals(1, subject.series.value?.size)
    }

    @Test
    fun `phone mode stamps the phone's fix`() = runTest(StandardTestDispatcher()) {
        val fix = MutableStateFlow<StampedPosition?>(
            StampedPosition.fromDegrees(40.3057734, -3.7325611, PositionOrigin.PHONE),
        )
        val subject = MeshStatsEngine(
            backgroundScope, MutableStateFlow(emptySet()), SortMode.PACKETS,
            positionMode = MutableStateFlow(PositionMode.PHONE), phoneFix = fix,
        ) { 1_000L }
        val seen = collectSeries(subject)
        subject.watchSeries(SeriesKey.Relay(0x69))
        runCurrent()

        subject.attach(flowOf(relayed()))
        runCurrent()

        assertEquals(PositionOrigin.PHONE, seen.last()?.positionOf(0)?.origin)
        assertEquals(403057734, seen.last()?.positionOf(0)?.latI)
    }

    @Test
    fun `phone mode falls back to the node when no fix has arrived`() = runTest(StandardTestDispatcher()) {
        // Spec section 6.3: a blank globe for the first minute of every session is
        // worse than a slightly-less-precise pin, and the origin flag keeps it honest.
        val subject = MeshStatsEngine(
            backgroundScope, MutableStateFlow(emptySet()), SortMode.PACKETS,
            positionMode = MutableStateFlow(PositionMode.PHONE),
            phoneFix = MutableStateFlow(null),
        ) { 1_000L }
        val seen = collectSeries(subject)
        subject.watchSeries(SeriesKey.Relay(0x69))
        subject.attach(flowOf(myInfoFrame(SENDER), positionFrame(SENDER, 398628316, -40273231), relayed()))
        runCurrent()

        assertEquals(PositionOrigin.NODE, seen.last()?.positionOf(0)?.origin)
        assertEquals(398628316, seen.last()?.positionOf(0)?.latI)
    }

    @Test
    fun `node mode never falls back to the phone`() = runTest(StandardTestDispatcher()) {
        // Turning the setting off is a request that the phone's GPS not be used.
        // Quietly using it anyway would break that request; a sample with no position
        // is the honest answer.
        val subject = MeshStatsEngine(
            backgroundScope, MutableStateFlow(emptySet()), SortMode.PACKETS,
            positionMode = MutableStateFlow(PositionMode.NODE),
            phoneFix = MutableStateFlow(
                StampedPosition.fromDegrees(40.3057734, -3.7325611, PositionOrigin.PHONE),
            ),
        ) { 1_000L }
        val seen = collectSeries(subject)
        subject.watchSeries(SeriesKey.Relay(0x69))
        subject.attach(flowOf(relayed()))
        runCurrent()

        assertNull(seen.last()?.positionOf(0))
    }

    @Test
    fun `a packet with no decodable signal adds no measurement`() = runTest(StandardTestDispatcher()) {
        // The chart's crosshair reports both values with no "not available" case,
        // which is only true because a measurement is never opened without both.
        val subject = engine(backgroundScope)
        val seen = collectSeries(subject)
        subject.watchSeries(SeriesKey.Relay(0x69))
        subject.attach(
            flowOf(
                TimestampedFrame(
                    rxMillis = 1_000L,
                    frame = FromRadio(
                        packet = MeshPacket(from = SENDER, relay_node = 0x69, hop_start = 3, hop_limit = 1),
                    ),
                ),
            ),
        )
        runCurrent()

        assertEquals(0, seen.last()?.size ?: 0)
    }

    @Test
    fun `reset clears the series with the statistics`() = runTest(StandardTestDispatcher()) {
        // A chart outliving a reset would be showing a session that no longer exists.
        val subject = engine(backgroundScope)
        val seen = collectSeries(subject)
        subject.watchSeries(SeriesKey.Relay(0x69))
        subject.attach(flowOf(relayed(), relayed()))
        runCurrent()
        assertEquals(2, seen.last()?.size)

        subject.reset()
        runCurrent()

        assertEquals(0, seen.last()?.size ?: 0)
    }

    @Test
    fun `a reset and the same number of packets again is not mistaken for no change`() =
        runTest(StandardTestDispatcher()) {
            // The ABA case the three-term guard exists for. With a (key, totalAppended)
            // guard this fails on the second assertion: totalAppended goes 2 -> 0 -> 2
            // inside one drained batch, the guard sees no change, and the chart keeps
            // drawing the session that was reset away.
            val subject = engine(backgroundScope)
            val seen = collectSeries(subject)
            subject.watchSeries(SeriesKey.Relay(0x69))
            subject.attach(flowOf(relayed(at = 1_000L), relayed(at = 1_000L)))
            runCurrent()
            assertEquals(1_000L, seen.last()!!.atMillis(0))

            // Producer queued but not yet run; the reset is enqueued ahead of its frames.
            subject.attach(flowOf(relayed(at = 9_000L), relayed(at = 9_000L)))
            subject.reset()
            runCurrent()

            assertEquals(2, seen.last()!!.size)
            assertEquals(9_000L, seen.last()!!.atMillis(0))
        }

    @Test
    fun `watching nothing drops the published series at once`() = runTest(StandardTestDispatcher()) {
        val subject = engine(backgroundScope)
        val seen = collectSeries(subject)
        subject.watchSeries(SeriesKey.Relay(0x69))
        subject.attach(flowOf(relayed()))
        runCurrent()
        assertEquals(1, seen.last()?.size)

        subject.watchSeries(null)
        runCurrent()

        assertNull(seen.last())
    }

    @Test
    fun `switching the watched subject replaces the published series`() = runTest(StandardTestDispatcher()) {
        val subject = engine(backgroundScope)
        val seen = collectSeries(subject)
        subject.watchSeries(SeriesKey.Relay(0x69))
        subject.attach(flowOf(relayed(), relayed(), direct()))
        runCurrent()
        assertEquals(2, seen.last()?.size)

        subject.watchSeries(SeriesKey.Neighbour(SENDER))
        runCurrent()

        // One, not three: the neighbour's own series, not the relay's left behind.
        assertEquals(1, seen.last()?.size)
    }
}
