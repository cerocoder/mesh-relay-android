package com.cerocoder.meshrelay.stats

import com.cerocoder.meshrelay.stats.model.Counters
import com.cerocoder.meshrelay.stats.model.NeighbourStats
import com.cerocoder.meshrelay.stats.model.NodeDirectorySnapshot
import com.cerocoder.meshrelay.stats.model.PositionOrigin
import com.cerocoder.meshrelay.stats.model.RelayStats
import com.cerocoder.meshrelay.stats.model.RemoteNodeStats
import com.cerocoder.meshrelay.stats.model.SignalSeries
import com.cerocoder.meshrelay.stats.model.SignalStats
import com.cerocoder.meshrelay.stats.model.StampedPosition
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
    positionMode: StateFlow<PositionMode> = MutableStateFlow(PositionMode.PHONE),
    phoneFix: StateFlow<StampedPosition?> = MutableStateFlow(null),
    time: TimeSource = SystemTimeSource,
) {

    private sealed interface Command {
        data class Frame(val frame: TimestampedFrame) : Command
        data class SetPaused(val paused: Boolean) : Command
        data class SetSort(val mode: SortMode) : Command
        data class SetSkipped(val skipped: Set<Int>) : Command
        data class SetPositionMode(val mode: PositionMode) : Command
        data class SetPhoneFix(val fix: StampedPosition?) : Command
        data class WatchSeries(val key: SeriesKey?) : Command
        data object Reset : Command

        /**
         * A different local node: not a fresh measurement of the same mesh, but a
         * different vantage point on it. Reuses [resetStatistics] for everything
         * Reset already clears, and additionally forgets the node database - see
         * [NodeDirectory.clearAll].
         */
        data object ResetForNewNode : Command

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

    // One buffer per subject, from the first packet, whether or not a chart is open
    // (requirement 14). LinkedHashMap for the same reason relays and neighbours are:
    // a stable order between rebuilds.
    private val seriesBuffers = LinkedHashMap<SeriesKey, SignalSeriesBuffer>()

    // The two inputs the interface owns, arriving as commands so they land on this
    // coroutine rather than racing it - exactly as skippedRelayNodes already does.
    private var positionModeState = PositionMode.PHONE
    private var phoneFixState: StampedPosition? = null

    // Which subject's chart is open, or null. At most one at a time: this is a
    // full-screen destination.
    private var watchedSeries: SeriesKey? = null

    // Bumped by every reset. The publish guard needs it because totalAppended alone
    // is ambiguous across a reset: a reset plus the same number of packets again, in
    // one drained batch, would look identical to no change at all.
    private var resetEpoch = 0

    // What was last put on the wire, so an unchanged buffer is not re-copied. At mesh
    // traffic rates the alternative copies 125 KB and recomposes the chart several
    // times a second because some *other* relay heard a packet.
    private var publishedKey: SeriesKey? = null
    private var publishedEpoch = -1
    private var publishedTotal = -1L

    private val _series = MutableStateFlow<SignalSeries?>(null)

    /**
     * The watched subject's measurements, or null when nothing is watched.
     *
     * A channel of its own rather than a field on [StatsSnapshot]: widening the
     * snapshot would copy every subject's series several times a second for the list
     * screens, which need none of it. This copies one subject's 125 KB, and only
     * while a chart is open.
     */
    val series: StateFlow<SignalSeries?> = _series.asStateFlow()

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

            launch { positionMode.collect { commands.send(Command.SetPositionMode(it)) } }
            launch { phoneFix.collect { commands.send(Command.SetPhoneFix(it)) } }

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
                publishWatchedSeries()
            }
        }
    }

    fun attach(frames: Flow<TimestampedFrame>): Job =
        scope.launch { frames.collect { commands.send(Command.Frame(it)) } }

    fun setPaused(paused: Boolean) { commands.trySend(Command.SetPaused(paused)) }
    fun setSortMode(mode: SortMode) { commands.trySend(Command.SetSort(mode)) }
    fun reset() { commands.trySend(Command.Reset) }

    /** A different local node: see [Command.ResetForNewNode]. */
    fun resetForNewNode() { commands.trySend(Command.ResetForNewNode) }

    /** Open a chart on [key], or pass null when it closes. */
    fun watchSeries(key: SeriesKey?) { commands.trySend(Command.WatchSeries(key)) }

    private fun apply(command: Command) {
        when (command) {
            is Command.Frame -> handleFrame(command.frame)
            is Command.SetPaused -> paused = command.paused
            is Command.SetSort -> sortMode = command.mode
            // Copied: the set crosses from another coroutine's flow, and the snapshot
            // hands it on to the interface, which holds it for as long as a
            // composition lives.
            is Command.SetSkipped -> skipped = command.skipped.toSet()
            is Command.SetPositionMode -> positionModeState = command.mode
            is Command.SetPhoneFix -> phoneFixState = command.fix
            is Command.WatchSeries -> {
                watchedSeries = command.key
                // Dropped here rather than at the next build: a closed chart's series must
                // not be held for as long as the process lives, and a *different* subject's
                // series must never be what the next chart draws for one frame.
                if (command.key == null) _series.value = null
                publishedKey = null
                publishedTotal = -1L
            }
            Command.Reset -> resetStatistics()
            Command.ResetForNewNode -> {
                resetStatistics()
                directory.clearAll()
            }
            Command.Refresh -> Unit
        }
    }

    /**
     * Ports on_receive, mesh_stats.py:1001-1091. The order of the steps is the
     * original's and each one is load-bearing.
     */
    private fun handleFrame(timestamped: TimestampedFrame) {
        val frame = timestamped.frame

        // my_info is where the firmware starts every want_config_id reply from the
        // top (see NodeDirectory.beginRound's KDoc for the one round it does not
        // open), so it also doubles as the signal that any round left over from
        // before belongs to a connection that is gone.
        frame.my_info?.let {
            directory.beginRound()
            directory.setLocalNodeNum(it.my_node_num)
        }
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

        // `from` is not optional on the wire, so Wire hands over 0 for a field the
        // sender never set and the original's `if from_node is not None`
        // (mesh_stats.py:1046) has nothing left to test. Node 0 is not a node number:
        // recorded, it would open a neighbour row for a node that does not exist, put
        // a phantom remote node under a real relay, and - through a NODEINFO_APP
        // payload - add a directory record whose lastByteOfNodeNum is 0xff, making it
        // a naming candidate for every relay that identifies itself as ff. The packet
        // is still counted in totalPackets: the radio did receive it, and that total
        // is a tally of traffic heard, not of traffic understood.
        //
        // Counted, and nothing else - see the comment above on why node 0 is not a
        // node. Unchanged behaviour, just hoisted into a helper now that the count
        // is no longer the first thing that happens.
        if (packet.from == 0) { countPacket(timestamped.rxMillis); return }

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
        if (packet.relay_node !in 0..MAX_RELAY_BYTE) { countPacket(timestamped.rxMillis); return }

        val ingest = PacketClassifier.classify(packet, skipped)

        // Our own transmission, heard directly rather than through a relay: it is
        // us, not a neighbour. We never received it over the air, so it carries no
        // SNR and no RSSI, and folding it in would put a signal-less row in a list
        // about signal and inflate the divisor every other neighbour's percentage
        // is computed against. At the owner's decision it is counted nowhere at all,
        // so this returns before countPacket.
        //
        // Deliberately NOT every packet from us. One that a relay rebroadcast and we
        // heard back classifies as Relayed, and its signal measures that relay's link
        // to us - dropping those would throw away real relay data.
        //
        // Also deliberately after decodePayload: our own POSITION_APP packets are how
        // localPosition() learns where we are, which stamps every Graph measurement
        // when Use phone location is off.
        if (ingest is Ingest.Direct && ingest.fromNode == directory.localNodeNum) return

        countPacket(timestamped.rxMillis)

        when (ingest) {
            is Ingest.Relayed -> foldRelayed(ingest, timestamped.rxMillis)
            is Ingest.Direct -> foldDirect(ingest, timestamped.rxMillis)
            // Counted in totalPackets and nowhere else: mesh_stats.py:1063-1073.
            Ingest.Dropped -> Unit
        }
    }

    /**
     * The two things every packet that counts as traffic moves, in one place.
     *
     * Called from three sites in [handleFrame], not symmetrically: the `from == 0`
     * and the out-of-range `relay_node` early returns both call it before
     * returning - the packet was heard, even though it cannot be attributed. The
     * own-node guard just above is an early return too, but deliberately does
     * *not* call it - our own transmission heard back is not traffic, by the
     * owner's decision, so it must move no counter at all. The call after that
     * guard is not a return; it is the fallthrough for every packet that survived
     * every check, immediately before folding it into a relay or a neighbour. Do
     * not add a call to the own-node guard for symmetry - that would silently
     * start counting exactly the packets it exists to exclude.
     */
    private fun countPacket(atMillis: Long) {
        counterState = counterState.copy(totalPackets = counterState.totalPackets + 1)
        lastPacketAtMillis = atMillis
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
                    directory.applyUser(fromNode, User.ADAPTER.decode(decoded.payload), atMillis)

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

        // Guarded by the same signal != null test that already guards the statistics: a
        // measurement exists only when the packet carried decodable signal information,
        // which is what lets the crosshair report both values with no "not available"
        // case.
        if (signal != null) {
            seriesBuffers.getOrPut(SeriesKey.Relay(relayed.relayByte)) { SignalSeriesBuffer() }
                .append(atMillis, signal.rssi, signal.snr, positionForSample())
        }
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

        // Same guard, same reason as foldRelayed.
        if (signal != null) {
            seriesBuffers.getOrPut(SeriesKey.Neighbour(direct.fromNode)) { SignalSeriesBuffer() }
                .append(atMillis, signal.rssi, signal.snr, positionForSample())
        }
    }

    /**
     * Where the observer was, for the measurement being folded right now
     * (requirement 16: captured at fold time, never searched for afterwards).
     *
     * | Mode  | First choice | Fallback           | Neither |
     * | PHONE | the phone fix| the node's position| null    |
     * | NODE  | the node's position | none         | null    |
     *
     * PHONE falls back because a blank globe for the first minute of every session -
     * before the first fix lands, or after the permission was refused - is worse than
     * a slightly-less-precise pin, and the origin flag recorded with the sample keeps
     * it honest. NODE does not fall back, because turning the setting off is a
     * request that the phone's GPS not be used, and quietly using it anyway would
     * break that request.
     */
    private fun positionForSample(): StampedPosition? = when (positionModeState) {
        PositionMode.PHONE -> phoneFixState ?: nodePosition()
        PositionMode.NODE -> nodePosition()
    }

    private fun nodePosition(): StampedPosition? {
        val local = directory.localPosition() ?: return null
        return StampedPosition.fromDegrees(local.lat, local.lon, PositionOrigin.NODE)
    }

    /**
     * Publishes the watched subject's measurements, if anything changed since the
     * last publish.
     *
     * One gate, not two: `watchedSeries != null`. Spec section 6.4 names a second
     * gate mirroring `_snapshot`'s - nothing subscribed to `series` means nothing
     * published - and this deliberately does not have it; do not restore it.
     * `_series.subscriptionCount` is a *conflating* StateFlow, and that is exactly
     * the problem: a chart can unsubscribe and resubscribe (screen rotation, a
     * quick back-and-forth) between two dispatches of the collector that used to
     * watch it here, so the count moves 1 -> 0 -> 1 while that collector only ever
     * observes the final 1 - `map { it > 0 }.distinctUntilChanged()` sees
     * `true -> true` and emits nothing, no `Refresh` is sent, and on a quiet relay
     * the chart is left showing a stale series indefinitely. The two gates were
     * redundant to begin with: `watchedSeries` is non-null only while a chart is
     * open, which is exactly when publishing is wanted, and every subscribe is
     * already followed by a fresh build regardless, because `StateFlow` replays its
     * latest value to a new collector and this function now keeps that value
     * current on every batch whether or not anyone is listening. Dropping the
     * count gate removes the class of defect structurally rather than depending on
     * collector dispatch order in a later task.
     *
     * The change check is what stops a packet heard on some other relay from
     * copying this one's arrays and recomposing its chart; `totalAppended` moves
     * on every append and only on an append, so it is exact at every fill level -
     * unlike `size`, which stops moving once the ring saturates.
     *
     * `totalAppended` alone is not enough to guard on, though: `resetStatistics`
     * clears [seriesBuffers], so a fresh buffer restarts its `totalAppended` at 0 -
     * and the command loop drains every queued command before building. A `Reset`
     * plus the same number of packets again, arriving in the same drained batch,
     * would take `totalAppended` from N to 0 back to N with entirely different
     * contents, and a guard over key and total alone would see no change at all and
     * leave the chart drawing the session that was just reset away. [resetEpoch] -
     * bumped by every reset - breaks that tie.
     */
    private fun publishWatchedSeries() {
        val key = watchedSeries ?: return
        val buffer = seriesBuffers[key]
        val total = buffer?.totalAppended ?: 0L
        val epoch = resetEpoch
        if (key == publishedKey && epoch == publishedEpoch && total == publishedTotal) return
        publishedKey = key
        publishedEpoch = epoch
        publishedTotal = total
        _series.value = buffer?.snapshot() ?: SignalSeries.EMPTY
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
        seriesBuffers.clear()
        resetEpoch++
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
