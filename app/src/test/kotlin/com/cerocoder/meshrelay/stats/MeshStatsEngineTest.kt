package com.cerocoder.meshrelay.stats

import com.cerocoder.meshrelay.stats.model.Counters
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.Data
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.User
import kotlin.time.Duration.Companion.minutes

private const val SENDER = 0x9e75f1a4.toInt()

private fun relayed(relay: Int = 0x69, from: Int = SENDER, snr: Float = -7.5f, rssi: Int = -94) =
    TimestampedFrame(
        rxMillis = 1_000L,
        frame = FromRadio(
            packet = MeshPacket(
                from = from, relay_node = relay, hop_start = 3, hop_limit = 1,
                rx_snr = snr, rx_rssi = rssi,
            ),
        ),
    )

private fun direct(from: Int = SENDER) = TimestampedFrame(
    rxMillis = 1_000L,
    frame = FromRadio(packet = MeshPacket(from = from, relay_node = 0, rx_snr = -3f, rx_rssi = -80)),
)

/** One node database entry, as the radio hands it over during the handshake. */
private fun nodeInfoFrame(num: Int, longName: String) = TimestampedFrame(
    rxMillis = 1_000L,
    frame = FromRadio(
        node_info = NodeInfo(num = num, user = User(long_name = longName, short_name = "1ce5")),
    ),
)

/**
 * A NODEINFO_APP packet: a node announcing itself in ordinary traffic rather than
 * through the node database. Used to prove that a packet with no sender teaches
 * the directory nothing - `applyUser(0, ...)` would create a record for node 0,
 * and `lastByteOfNodeNum(0)` is 0xff, so node 0 would become a naming candidate
 * for every relay that identifies itself as ff.
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

        subject.attach(flowOf(nodeInfoFrame(SENDER, "PQPL1")))
        runCurrent()

        assertEquals(1, subject.snapshot.value.directory.count)
        assertEquals(Counters.EMPTY, subject.counters.value)
    }

    @Test
    fun `reset clears statistics but keeps the node database and the skip list`() = runTest(StandardTestDispatcher()) {
        val skipped = MutableStateFlow(setOf(0x11223344))
        val subject = engine(backgroundScope, skipped)
        collectSnapshots(subject)
        subject.attach(flowOf(nodeInfoFrame(SENDER, "PQPL1"), relayed(), direct()))
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
        subject.attach(flowOf(nodeInfoFrame(SENDER, "PQPL1"), relayed(relay = 0xa4)))
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
        assertEquals("node 0 is not in the node database", 0, after.directory.count)
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
}
