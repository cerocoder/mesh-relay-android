package com.cerocoder.meshrelay.stats

import com.cerocoder.meshrelay.stats.model.Counters
import com.cerocoder.meshrelay.stats.model.NeighbourStats
import com.cerocoder.meshrelay.stats.model.NodeDirectorySnapshot
import com.cerocoder.meshrelay.stats.model.RelayStats
import com.cerocoder.meshrelay.stats.model.RemoteNodeStats
import com.cerocoder.meshrelay.stats.model.SignalStats
import com.cerocoder.meshrelay.stats.model.StatsSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.PortNum
import org.meshtastic.proto.Position
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.User

/**
 * Owns every mutable statistic in the application.
 *
 * One coroutine, one owner. Commands - frames and user actions alike - arrive
 * through a channel and are applied in order on that coroutine, so there is no
 * lock here, no @Volatile and no Atomic. That is deliberate rather than
 * economical: the whole test suite is single-threaded while this runs on
 * Dispatchers.Default, so a concurrency defect would be invisible to every test
 * that could be written. Confinement removes the class of defect instead of
 * testing for it.
 *
 * There is no tick. A snapshot is built when state changes and someone is
 * watching, never on a timer.
 *
 * Ports the state half of StatsCollector, mesh_stats.py:1001-1155.
 */
class MeshStatsEngine(
    private val scope: CoroutineScope,
    skippedRelayNodes: StateFlow<Set<Int>>,
    initialSortMode: SortMode,
    time: TimeSource = SystemTimeSource,
) {

    private sealed interface Command {
        data class Frame(val frame: TimestampedFrame) : Command
        data class SetPaused(val paused: Boolean) : Command
        data class SetSort(val mode: SortMode) : Command
        data class SetSkipped(val skipped: Set<Int>) : Command
        data object Reset : Command

        /**
         * Nothing to apply - the loop's own build step is the whole point. Sent when
         * a screen subscribes, so it sees the state the engine already holds rather
         * than the empty snapshot it was left at while nobody was watching.
         */
        data object Refresh : Command
    }

    // Bounded rather than UNLIMITED so that a producer which outruns the loop meets
    // backpressure instead of growing the heap: attach() uses the suspending send.
    // The user actions below use trySend, which cannot suspend from a click handler;
    // losing one would need 256 commands to be pending at that instant, and the only
    // producer fast enough to queue that many is a radio delivering tens of frames a
    // second while the loop - which drains the whole queue per iteration - is blocked
    // on a single snapshot build. That does not happen.
    private val commands = Channel<Command>(capacity = COMMAND_CAPACITY)

    // Everything below is touched only from the consumer coroutine started in init.
    // LinkedHashMap rather than HashMap: relays and neighbours that tie on the sort
    // key then keep first-heard order between rebuilds, instead of jumping around the
    // screen as a hash order that nothing controls changes shape.
    private val relays = LinkedHashMap<Int, RelayStats>()
    private val neighbours = LinkedHashMap<Int, NeighbourStats>()
    private val directory = NodeDirectory(time)
    private var counterState = Counters.EMPTY
    private var paused = false
    private var sortMode = initialSortMode
    private var skipped: Set<Int> = emptySet()
    private var lastPacketAtMillis: Long? = null
    private var lastRelayedPacketAtMillis: Long? = null

    // Not stateIn(WhileSubscribed): the build has to happen on the coroutine that
    // owns the state, and a shared upstream flow would run it on whichever coroutine
    // stateIn starts - the one thing this class is built to prevent. The property
    // WhileSubscribed buys is bought here instead, by asking subscriptionCount
    // directly, and without its grace period: an unsubscribed engine stops building
    // immediately rather than five seconds later.
    private val _snapshot = MutableStateFlow(StatsSnapshot.EMPTY)
    val snapshot: StateFlow<StatsSnapshot> = _snapshot.asStateFlow()

    /** Four integers for the foreground notification. Cheap enough to publish always. */
    private val _counters = MutableStateFlow(Counters.EMPTY)
    val counters: StateFlow<Counters> = _counters.asStateFlow()

    // This block hands `this` to a coroutine while the constructor is still
    // running, which is safe only because every field the loop touches is declared
    // *above* it and is therefore already initialised when the launch happens. A
    // new property added below this block would be read at its default value by a
    // loop that had already started - silently, with no warning and no test able to
    // see it, because the launch and the initialiser would be racing. Declare state
    // above this line.
    init {
        scope.launch {
            // The skip-list belongs to SettingsRepository, which touches storage.
            // It reaches the engine as a plain flow and becomes a command like any
            // other, so it lands on the owning coroutine rather than racing it.
            launch { skippedRelayNodes.collect { commands.send(Command.SetSkipped(it)) } }

            // A screen that has just opened must not see EMPTY while the engine
            // already holds an afternoon of statistics.
            launch {
                _snapshot.subscriptionCount
                    .map { it > 0 }
                    .distinctUntilChanged()
                    .collect { watched -> if (watched) commands.send(Command.Refresh) }
            }

            for (command in commands) {
                apply(command)
                // Drain whatever is already queued before building. A burst of thirty
                // packets must cost one snapshot, not thirty - at mesh traffic rates
                // the difference is dozens of rebuilds a second for no visible gain.
                while (true) {
                    apply(commands.tryReceive().getOrNull() ?: break)
                }
                counterState = counterState.copy(relayCount = relays.size)
                _counters.value = counterState
                // Nothing subscribed means nothing to build. With the screen off and
                // the service running, this is what keeps the app cheap: ingestion
                // continues, snapshot building stops entirely.
                if (_snapshot.subscriptionCount.value > 0) {
                    // One directory view per batch, shared by the name refresh, the
                    // neighbour name sort and the snapshot itself. Taking it copies
                    // every map in the directory, so it is taken here - once - rather
                    // than per packet, which would hand back the coalescing above.
                    val view = directory.snapshot(skipped)
                    refreshRelayNames(view)
                    _snapshot.value = buildSnapshot(view)
                }
            }
        }
    }

    fun attach(frames: Flow<TimestampedFrame>): Job =
        scope.launch { frames.collect { commands.send(Command.Frame(it)) } }

    fun setPaused(paused: Boolean) { commands.trySend(Command.SetPaused(paused)) }
    fun setSortMode(mode: SortMode) { commands.trySend(Command.SetSort(mode)) }
    fun reset() { commands.trySend(Command.Reset) }

    private fun apply(command: Command) {
        when (command) {
            is Command.Frame -> handleFrame(command.frame)
            is Command.SetPaused -> paused = command.paused
            is Command.SetSort -> sortMode = command.mode
            // Copied: the set crosses from another coroutine's flow, and the snapshot
            // hands it on to the interface, which holds it for as long as a
            // composition lives.
            is Command.SetSkipped -> skipped = command.skipped.toSet()
            Command.Reset -> resetStatistics()
            Command.Refresh -> Unit
        }
    }

    /**
     * Ports on_receive, mesh_stats.py:1001-1091. The order of the steps is the
     * original's and each one is load-bearing.
     */
    private fun handleFrame(timestamped: TimestampedFrame) {
        val frame = timestamped.frame

        frame.my_info?.let { directory.setLocalNodeNum(it.my_node_num) }
        frame.node_info?.let { directory.applyNodeInfo(it) }
        // Any completed want_config round, not only the node-database one: telling
        // them apart needs the nonces from transport/, which stats/ may not import,
        // and the two arrive in order anyway - the later, correct timestamp
        // overwrites the earlier one a moment afterwards.
        if (frame.config_complete_id != null) directory.markLoaded(timestamped.rxMillis)

        val packet = frame.packet ?: return

        // Ports mesh_stats.py:1012-1014, and its placement: paused means the packet
        // never happened. Not one counter moves. The handshake frames above are not
        // statistics, so the node database keeps learning while paused - otherwise a
        // pause held across a reconnect would leave every relay unnamed.
        if (paused) return

        counterState = counterState.copy(totalPackets = counterState.totalPackets + 1)
        lastPacketAtMillis = timestamped.rxMillis

        // `from` is not optional on the wire, so Wire hands over 0 for a field the
        // sender never set and the original's `if from_node is not None`
        // (mesh_stats.py:1046) has nothing left to test. Node 0 is not a node number:
        // recorded, it would open a neighbour row for a node that does not exist, put
        // a phantom remote node under a real relay, and - through a NODEINFO_APP
        // payload - add a directory record whose lastByteOfNodeNum is 0xff, making it
        // a naming candidate for every relay that identifies itself as ff. The packet
        // is still counted in totalPackets: the radio did receive it, and that total
        // is a tally of traffic heard, not of traffic understood.
        if (packet.from == 0) return

        decodePayload(packet.from, packet, timestamped.rxMillis)

        // relay_node is a uint32 in the schema and arrives as a plain Int, so a
        // malformed value can be anything at all, negative included - that is how a
        // uint32 above 2^31 reads here. Rejected rather than masked: `and 0xFF` would
        // not repair the value, it would invent one, and the byte it invented would
        // usually name a relay that really exists in this mesh, quietly polluting its
        // averages and its packet count with traffic that was never its. Downstream,
        // RelayStats.hexId formats "0x%02x", so an out-of-range byte would also print
        // wider than the two digits every screen is laid out for. A packet whose relay
        // field cannot be believed says nothing about relay topology; it is still
        // counted in totalPackets, exactly like the sender-less case above. The
        // payload was decoded first because what it says concerns the sender, not the
        // relay, and one broken field does not discredit the other.
        if (packet.relay_node !in 0..MAX_RELAY_BYTE) return

        when (val ingest = PacketClassifier.classify(packet, skipped)) {
            is Ingest.Relayed -> foldRelayed(ingest, timestamped.rxMillis)
            is Ingest.Direct -> foldDirect(ingest, timestamped.rxMillis)
            // Counted in totalPackets and nowhere else: mesh_stats.py:1063-1073.
            Ingest.Dropped -> Unit
        }
    }

    /**
     * Folds what the payload says about its sender into the node database.
     *
     * An encrypted packet has no payload at all and still matters - relay topology is
     * readable without reading the traffic - so an absent or unrecognised portnum is
     * not an error, it just teaches the directory nothing.
     */
    private fun decodePayload(fromNode: Int, packet: MeshPacket, atMillis: Long) {
        val decoded = packet.decoded ?: return
        try {
            when (decoded.portnum) {
                PortNum.POSITION_APP ->
                    directory.applyPosition(fromNode, Position.ADAPTER.decode(decoded.payload))

                PortNum.NODEINFO_APP ->
                    directory.applyUser(fromNode, User.ADAPTER.decode(decoded.payload))

                PortNum.TELEMETRY_APP ->
                    directory.applyTelemetry(fromNode, Telemetry.ADAPTER.decode(decoded.payload), atMillis)

                else -> Unit
            }
        } catch (malformed: Exception) {
            // Deliberately swallowed, and deliberately Exception rather than
            // IOException: a Wire-generated constructor validates how many of its
            // oneof fields are occupied with require(), so a payload with two
            // variants filled throws IllegalArgumentException straight past a
            // narrower catch. This is the only channel through which data reaches
            // the application, and one node sending a malformed position must not
            // end it. Nothing is logged because stats/ may not import android.util.
            //
            // Not Throwable: an OutOfMemoryError is not a malformed packet, and
            // carrying on would only keep the loop running inside a broken process.
            // Nothing in the block suspends or checks for cancellation, so no
            // CancellationException can originate here to be swallowed either.
        }
    }

    /** Ports the relayed branch of on_receive plus RelayNodeStats.update, mesh_stats.py:415-434. */
    private fun foldRelayed(relayed: Ingest.Relayed, atMillis: Long) {
        counterState = counterState.copy(totalRelayedPackets = counterState.totalRelayedPackets + 1)
        lastRelayedPacketAtMillis = atMillis

        val existing = relays[relayed.relayByte] ?: RelayStats(relayByte = relayed.relayByte)
        val signal = relayed.signal
        // hopsKnown restores the distinction Wire erased: hop_start is not optional,
        // so an unset field and a genuine 0 are the same bits, and folding an unset
        // packet's zeros in would pull every hop average toward zero.
        val remote = (existing.fromNodeStats[relayed.fromNode] ?: RemoteNodeStats())
            .plus(relayed.hopStart, relayed.hopLimit, hopsKnown = relayed.hopStart != 0)

        relays[relayed.relayByte] = existing.copy(
            snr = if (signal == null) existing.snr else existing.snr.plus(signal.snr),
            rssi = if (signal == null) existing.rssi else existing.rssi.plus(signal.rssi),
            packetCount = existing.packetCount + 1,
            // Only the first packet opens the window packetsPerHour is measured over.
            firstPacketAtMillis = if (existing.packetCount == 0) atMillis else existing.firstPacketAtMillis,
            lastPacketAtMillis = atMillis,
            fromNodeStats = existing.fromNodeStats + (relayed.fromNode to remote),
        )
    }

    /** Ports the direct branch of on_receive, mesh_stats.py:1044-1058. */
    private fun foldDirect(direct: Ingest.Direct, atMillis: Long) {
        counterState = counterState.copy(totalDirectPackets = counterState.totalDirectPackets + 1)

        val existing = neighbours[direct.fromNode] ?: NeighbourStats(nodeNum = direct.fromNode)
        val signal = direct.signal
        neighbours[direct.fromNode] = existing.copy(
            snr = if (signal == null) existing.snr else existing.snr.plus(signal.snr),
            rssi = if (signal == null) existing.rssi else existing.rssi.plus(signal.rssi),
            packetCount = existing.packetCount + 1,
            lastPacketAtMillis = atMillis,
        )
    }

    /**
     * Ports reset, mesh_stats.py:1100-1113: a fresh measurement, not a fresh mesh.
     *
     * The node list and the skip-list survive, and so do the pause and sort states -
     * reloading the database costs a round trip to the radio, and forgetting which
     * nodes the user ruled out as relays would undo work they did by hand.
     */
    private fun resetStatistics() {
        relays.clear()
        neighbours.clear()
        counterState = Counters.EMPTY
        lastPacketAtMillis = null
        lastRelayedPacketAtMillis = null
        directory.clearRuntimeData()
    }

    /**
     * A relay identifies itself by one byte, so whether it can be named at all
     * depends on the node database and the skip-list, both of which move under it.
     * Recomputed for every relay immediately before the snapshot that will show
     * them - which is strictly more often, and more complete, than the original's
     * refresh of the one relay a packet just arrived on (mesh_stats.py:572-575), and
     * costs one directory view for the whole batch instead of one per packet.
     */
    private fun refreshRelayNames(view: NodeDirectorySnapshot) {
        for (relayByte in relays.keys.toList()) {
            val stats = relays.getValue(relayByte)
            val name = view.uniqueRelayName(relayByte)
            if (stats.nodeName != name) relays[relayByte] = stats.copy(nodeName = name)
        }
    }

    private fun buildSnapshot(view: NodeDirectorySnapshot) = StatsSnapshot(
        relays = sortedRelays(),
        neighbours = sortedNeighbours(view),
        counters = counterState,
        paused = paused,
        sortMode = sortMode,
        lastPacketAtMillis = lastPacketAtMillis,
        lastRelayedPacketAtMillis = lastRelayedPacketAtMillis,
        directory = view,
        skippedRelayNodes = skipped,
    )

    /** Ports get_sorted_nodes, mesh_stats.py:1120-1140. */
    private fun sortedRelays(): List<RelayStats> {
        val values = relays.values.toList()
        val total = counterState.totalRelayedPackets
        return when (sortMode) {
            SortMode.PACKETS -> values.sortedByDescending { it.packetCount }
            SortMode.PERCENT -> values.sortedByDescending { share(it.packetCount, total) }
            SortMode.AVG_SNR -> values.sortedByDescending { rank(it.snr) }
            SortMode.AVG_RSSI -> values.sortedByDescending { rank(it.rssi) }
            SortMode.NAME -> values.sortedBy { it.nodeName.ifEmpty { it.hexId } }
            SortMode.KNOWN_NODES -> values.sortedByDescending { it.knownNodesCount }
            SortMode.LATEST_PACKET -> values.sortedByDescending { it.lastPacketAtMillis }
        }
    }

    /** Ports get_sorted_neighbours, mesh_stats.py:770-791. */
    private fun sortedNeighbours(view: NodeDirectorySnapshot): List<NeighbourStats> {
        val values = neighbours.values.toList()
        val total = counterState.totalDirectPackets
        return when (sortMode.forNeighbours()) {
            SortMode.PACKETS -> values.sortedByDescending { it.packetCount }
            SortMode.PERCENT -> values.sortedByDescending { share(it.packetCount, total) }
            SortMode.AVG_SNR -> values.sortedByDescending { rank(it.snr) }
            SortMode.AVG_RSSI -> values.sortedByDescending { rank(it.rssi) }
            // A neighbour is a whole node number, so unlike a relay byte it always has
            // an identity to fall back on when the database has not named it.
            SortMode.NAME -> values.sortedBy {
                view.shortName(it.nodeNum).ifEmpty { NodeId.format(it.nodeNum) }
            }
            SortMode.LATEST_PACKET -> values.sortedByDescending { it.lastPacketAtMillis }
            // forNeighbours() has already mapped this away; the branch exists because
            // the `when` is exhaustive over the enum and a silent `else` would swallow
            // the next mode someone adds.
            SortMode.KNOWN_NODES -> values.sortedByDescending { it.packetCount }
        }
    }

    /**
     * Share of the relevant total. The divisor is the same for every row, so this
     * orders exactly as [SortMode.PACKETS] does - kept because it is what the
     * original computes and because the divisor is what the screen prints beside it.
     */
    private fun share(count: Int, total: Int): Float =
        if (total > 0) count.toFloat() / total else 0f

    /**
     * Sort key for an average that may not exist.
     *
     * Float.NEGATIVE_INFINITY, never 0f: a relay whose packets all arrived with
     * rx_rssi == 0 has no measurement at all, and 0.0 dB SNR - or worse, 0 dBm RSSI -
     * would outrank every real reading on the screen. Ports the `float('-inf')`
     * fallbacks at mesh_stats.py:1131-1134.
     */
    private fun rank(stats: SignalStats): Float =
        if (stats.hasData) stats.avg else Float.NEGATIVE_INFINITY

    private companion object {
        const val COMMAND_CAPACITY = 256

        /** relay_node carries the low byte of a NodeNum and nothing wider. */
        const val MAX_RELAY_BYTE = 0xFF
    }
}
