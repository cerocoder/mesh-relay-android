package com.cerocoder.meshrelay.stats

import com.cerocoder.meshrelay.stats.model.AirNodeRecord
import com.cerocoder.meshrelay.stats.model.LatLon
import com.cerocoder.meshrelay.stats.model.NodeDirectorySnapshot
import com.cerocoder.meshrelay.stats.model.NodeRecord
import com.cerocoder.meshrelay.stats.model.PositionHistory
import com.cerocoder.meshrelay.stats.model.PositionReport
import com.cerocoder.meshrelay.stats.model.TelemetryRecord
import com.cerocoder.meshrelay.stats.model.localPositionOf
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.Position
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.User

/**
 * What is known about every node on the mesh: the database the radio handed over
 * at connect, the positions heard since, and the telemetry heard since. Ports the
 * database half of StatsCollector, mesh_stats.py:704-762, :793-919 and :921-999.
 *
 * **Confined to the engine's single coroutine, and therefore unsynchronised on
 * purpose.** There is no lock, no `@Volatile` and no atomic in this class. The
 * Python original guarded every one of these maps with `self.lock` because it ran
 * a reader thread beside a curses thread; here the engine owns the directory
 * outright and nothing else ever touches it. [snapshot] is the only thing that
 * crosses to another thread, and it copies, so what the interface reads can never
 * be mutated underneath it. Adding a lock here would not make anything safer - it
 * would only suggest that sharing this object is allowed.
 *
 * `_local_stats_last` (mesh_stats.py:527, :999) is deliberately not ported: the
 * original writes it and never reads it.
 */
class NodeDirectory(private val time: TimeSource) {

    private val nodes = HashMap<Int, NodeRecord>()
    private val nodesFromAir = HashMap<Int, AirNodeRecord>()
    private val positions = HashMap<Int, PositionHistory>()
    private val telemetryRecords = HashMap<Int, TelemetryRecord>()

    /**
     * A node-database refresh in progress. The radio streams one `node_info` frame
     * per entry and terminates the round with `config_complete_id`; entries land
     * here and replace [nodes] wholesale at [markLoaded], so a node the firmware has
     * evicted disappears from this application too rather than lingering for ever.
     */
    private val pendingNodes = HashMap<Int, NodeRecord>()

    /**
     * Whether the round in progress has carried a `node_info` frame.
     *
     * `config_complete_id` terminates **any** want_config round, and this package may
     * not import `transport/` to tell the nonces apart. Without this flag a completed
     * *config* round - which carries no node info at all - would commit an empty
     * buffer and replace an eighty-entry database with nothing. A node-database round
     * always carries at least the local node's own entry.
     *
     * A flag rather than `pendingNodes.isNotEmpty()`, so the rule reads as "a frame
     * arrived" rather than "the buffer happens to be non-empty", and a future change
     * cannot make an empty-but-real round wipe the store.
     */
    private var pendingReceivedAny = false

    /**
     * Which node is ours, or null before the handshake.
     *
     * Readable because the engine consults it per packet to recognise our own
     * traffic; writable only through [setLocalNodeNum], so the handshake stays the
     * one way it is set. A field read, never a snapshot - this is on the path of
     * every packet.
     */
    var localNodeNum: Int? = null
        private set

    private var loadedAtMillis: Long? = null

    /**
     * Records one node database entry **into the refresh in progress**, not into
     * [nodes] - nothing is visible until [markLoaded] commits the round.
     *
     * Each round stands alone: the buffer starts empty, so a node the radio no longer
     * reports is simply absent from the next commit. The merge below therefore only
     * ever combines two frames *within one round*, which the radio does not currently
     * send; it is kept because it costs nothing, because a thinner follow-up frame
     * would otherwise erase a fuller one, and because [merge] carries a recorded
     * decision about `hasPublicKey` that would be lost with its last caller.
     */
    fun applyNodeInfo(info: NodeInfo) {
        val incoming = NodeRecord.fromProto(info)
        val existing = pendingNodes[info.num]
        pendingNodes[info.num] = if (existing == null) incoming else merge(existing, incoming)
        pendingReceivedAny = true
    }

    /**
     * Folds one NODEINFO_APP payload into the air store, creating the record when
     * this node has not identified itself before - nodes turn up in traffic before
     * they turn up in the database, and a relay candidate nobody has heard of is
     * still a candidate.
     *
     * **It does not touch [nodes].** That map is what the radio's own database says
     * and nothing else. What a node broadcasts is a separate account of the same
     * node, and keeping the two apart is what lets the interface say which one it is
     * showing, and when it learned it.
     *
     * The merge rule, and the reason this no longer assigns fields directly, is
     * [AirNodeRecord.folding]'s: `User`'s string fields are non-`optional` in proto3
     * and arrive as `""` when omitted, so the direct assignment this replaced wrote
     * an empty name over a good one every time a node broadcast a thin identity.
     *
     * [atMillis] is a parameter because this class may not read the clock outside
     * [TimeSource].
     */
    fun applyUser(nodeNum: Int, user: User, atMillis: Long) {
        nodesFromAir[nodeNum] = AirNodeRecord.folding(nodesFromAir[nodeNum], nodeNum, user, atMillis)
    }

    /** Appends one heard position, timestamped by [time] - the packet carries none. */
    fun applyPosition(nodeNum: Int, position: Position) {
        val report = PositionReport.fromProto(position, time.nowMillis())
        val history = positions[nodeNum] ?: PositionHistory(nodeNum = nodeNum)
        positions[nodeNum] = history.plus(report)
    }

    /**
     * Folds one telemetry packet into [nodeNum]'s record. Ports
     * _process_telemetry_packet, mesh_stats.py:921-999.
     *
     * Metric keys are the protobuf field names spelled exactly as the schema
     * spells them, because the interface shows them verbatim rather than through a
     * translation table. Absent optional fields are skipped rather than recorded as
     * zero: every one of these is `optional` in the schema, so Wire hands over
     * `null` for a field the sender left out, and a zero reading would drag the
     * metric's average and minimum down for the rest of the session.
     *
     * `uptime_seconds` is not a metric but a restart detector, and goes through
     * [TelemetryRecord.withUptime].
     */
    fun applyTelemetry(nodeNum: Int, telemetry: Telemetry, atMillis: Long) {
        var record = telemetryRecords[nodeNum] ?: TelemetryRecord()

        telemetry.device_metrics?.let { device ->
            device.uptime_seconds?.let { record = record.withUptime(it) }
            device.battery_level?.let { record = record.withMetric("battery_level", atMillis, it.toFloat()) }
            device.voltage?.let { record = record.withMetric("voltage", atMillis, it) }
            device.channel_utilization?.let { record = record.withMetric("channel_utilization", atMillis, it) }
            device.air_util_tx?.let { record = record.withMetric("air_util_tx", atMillis, it) }
        }

        telemetry.environment_metrics?.let { environment ->
            environment.temperature?.let { record = record.withMetric("temperature", atMillis, it) }
            environment.voltage?.let { record = record.withMetric("voltage", atMillis, it) }
            environment.current?.let { record = record.withMetric("current", atMillis, it) }
        }

        // Written out rather than looped: Wire generates eight named fields and
        // there is no way to reach them by name without reflection.
        telemetry.power_metrics?.let { power ->
            power.ch1_voltage?.let { record = record.withMetric("ch1_voltage", atMillis, it) }
            power.ch1_current?.let { record = record.withMetric("ch1_current", atMillis, it) }
            power.ch2_voltage?.let { record = record.withMetric("ch2_voltage", atMillis, it) }
            power.ch2_current?.let { record = record.withMetric("ch2_current", atMillis, it) }
            power.ch3_voltage?.let { record = record.withMetric("ch3_voltage", atMillis, it) }
            power.ch3_current?.let { record = record.withMetric("ch3_current", atMillis, it) }
            power.ch4_voltage?.let { record = record.withMetric("ch4_voltage", atMillis, it) }
            power.ch4_current?.let { record = record.withMetric("ch4_current", atMillis, it) }
            power.ch5_voltage?.let { record = record.withMetric("ch5_voltage", atMillis, it) }
            power.ch5_current?.let { record = record.withMetric("ch5_current", atMillis, it) }
            power.ch6_voltage?.let { record = record.withMetric("ch6_voltage", atMillis, it) }
            power.ch6_current?.let { record = record.withMetric("ch6_current", atMillis, it) }
            power.ch7_voltage?.let { record = record.withMetric("ch7_voltage", atMillis, it) }
            power.ch7_current?.let { record = record.withMetric("ch7_current", atMillis, it) }
            power.ch8_voltage?.let { record = record.withMetric("ch8_voltage", atMillis, it) }
            power.ch8_current?.let { record = record.withMetric("ch8_current", atMillis, it) }
        }

        telemetryRecords[nodeNum] = record
    }

    fun setLocalNodeNum(num: Int) {
        localNodeNum = num
    }

    /**
     * Discards whatever a round left in the buffer without a commit. Called at
     * `my_info`, which is where the firmware starts every `want_config_id` reply
     * from the top - so a fresh `my_info` is the signal that whatever came before
     * it belongs to a round that is no longer in progress.
     *
     * Without this, a round abandoned mid-flight - the connection dropped before
     * its `config_complete_id` arrived - leaves [pendingNodes] holding a stale
     * partial result. The next round to commit would fold its own frames onto
     * that leftover rather than replacing it: a union where staged replace exists
     * specifically to prevent one, so a node the radio has since evicted would
     * reappear for a round.
     *
     * **Two independent callers reach this method, between them covering every
     * round.** A fresh `my_info` covers every reconnect, because the handshake
     * always redoes `CONFIG_NONCE` then `NODE_INFO_NONCE` and `my_info` precedes
     * both - that is the caller wired at `MeshStatsEngine.handleFrame`. It does
     * **not** cover a node-database *reload* requested mid-session
     * (`NODE_INFO_RELOAD_NONCE`, `RadioConnectionManager.reloadNodeDatabase`):
     * that round carries no `my_info` frame at all - see `FakeRadioTransport.send`'s
     * `NODE_INFO_RELOAD_NONCE` branch and `MeshScenario.nodeStageFrames`, which
     * answer it with `node_info` frames straight into `config_complete_id`. That
     * gap is closed by the second caller: `RadioConnectionManager` calls
     * `MeshStatsEngine.beginNodeDbRound()` before it asks the radio for node data,
     * at both sites - the handshake's node-info stage and the reload - and always
     * before the corresponding request goes out, never after, so a reply frame
     * can never be processed first. Calling this method twice for the same round,
     * or on an already-empty buffer, is a no-op, so the two callers never need to
     * coordinate with each other.
     */
    fun beginRound() {
        pendingNodes.clear()
        pendingReceivedAny = false
    }

    /**
     * A want_config round has completed. Commit the refresh it carried, if it
     * carried one.
     *
     * A round that carried no `node_info` frame leaves both the store and
     * [loadedAtMillis] untouched - see [pendingReceivedAny]. That guard is why this
     * function can be called for every completed round without knowing which nonce
     * finished.
     *
     * **The contents are copied; the buffer is never assigned.** `nodes =
     * pendingNodes` would alias one map under two names, and the `clear()` below
     * would then empty the store it had just filled.
     */
    fun markLoaded(atMillis: Long) {
        if (!pendingReceivedAny) return
        nodes.clear()
        pendingNodes.forEach { (num, record) -> nodes[num] = record.copy(receivedAtMillis = atMillis) }
        loadedAtMillis = atMillis
        pendingNodes.clear()
        pendingReceivedAny = false
    }

    /**
     * Starts a fresh measurement: positions and telemetry heard during the session
     * go, the node database stays. Ports reset, mesh_stats.py:1100-1113 - resetting
     * the statistics is not the same as forgetting who is out there, and reloading
     * the database costs a round trip to the radio.
     *
     * **Neither node store is touched, deliberately.** Not [nodes], for the reason
     * above, and not [nodesFromAir] either: an identity heard over the air is knowledge
     * about the mesh, not a measurement of it, and a Reset that forgot every name would
     * leave every relay unlabelled until each node next chose to announce itself -
     * which can be hours. This omission is the requirement, not an oversight.
     */
    fun clearRuntimeData() {
        positions.clear()
        telemetryRecords.clear()
    }

    /**
     * Forgets the mesh entirely - the node database included, and the identity of
     * the local node with it.
     *
     * [clearRuntimeData] deliberately keeps the node database because reloading it
     * costs a round trip to the radio, and a Reset is a fresh measurement of the
     * same mesh. This is the other case: a *different* local node, whose database
     * describes a different view. Every name, position and hop count in the old one
     * may be wrong, and a wrong name on a relay is worse than no name.
     */
    fun clearAll() {
        nodes.clear()
        nodesFromAir.clear()
        // An abandoned round must not be committed onto the new node's database: the
        // frames in it describe the mesh as the *previous* radio saw it.
        pendingNodes.clear()
        pendingReceivedAny = false
        positions.clear()
        telemetryRecords.clear()
        localNodeNum = null
        loadedAtMillis = null
    }

    /**
     * Where this device is, without building a snapshot to ask.
     *
     * The engine calls this once per measurement, and `snapshot()` copies every map
     * in the directory - which is why it is taken once per batch there, and not per
     * packet. The precedence rule itself is [localPositionOf]'s, shared with the
     * snapshot.
     */
    fun localPosition(): LatLon? = localPositionOf(localNodeNum, positions, nodes)

    /**
     * The one value that leaves this coroutine. Every map is copied, so the
     * interface can hold the result for as long as a composition lives while the
     * engine keeps folding packets into the originals.
     */
    fun snapshot(skipped: Set<Int>): NodeDirectorySnapshot = NodeDirectorySnapshot(
        nodes = HashMap(nodes),
        airNodes = HashMap(nodesFromAir),
        loadedAtMillis = loadedAtMillis,
        localNodeNum = localNodeNum,
        positions = HashMap(positions),
        telemetry = HashMap(telemetryRecords),
        skipped = HashSet(skipped),
    )

    /**
     * Field by field, the new value when the message carried one and the old value
     * when it did not.
     *
     * [NodeRecord.hasPublicKey] is the one field that cannot use `?:`, because it
     * is a `Boolean`: absence and `false` are the same value. Two different
     * situations reach here as `false`, and the `||` cannot tell them apart, so it
     * reads both as absence - a key is remembered once observed and no
     * [applyNodeInfo] ever unlearns it:
     *
     *  - the NodeInfo carried no `user` submessage at all, so it said nothing
     *    about a key. Keeping what was already known is plainly right.
     *  - the NodeInfo carried a `user` whose `public_key` was empty. That is real
     *    information, and this line deliberately ignores it.
     *
     * The second case is the accepted cost. Node database entries arrive in
     * several shapes and the thinner ones routinely omit the key, so believing an
     * empty one would make the field flip back and forth as full and thin entries
     * alternate - while a node genuinely withdrawing a key it has already
     * published is not something this mesh does. [AirNodeRecord.folding] applies
     * the identical never-unlearn rule to the air store's own `hasPublicKey`, for
     * the identical reason. There is no asymmetry between the two stores here -
     * only the same decision, applied twice because there are two stores to apply
     * it to.
     *
     * Pinned by `a public key once seen survives a node info that carries no user
     * at all` here, and by `a public key once observed is never unlearned` in
     * `AirNodeRecordTest`; flattening this `||` into a straight assignment would
     * fail the first of them.
     */
    private fun merge(existing: NodeRecord, incoming: NodeRecord): NodeRecord = NodeRecord(
        num = incoming.num,
        longName = incoming.longName ?: existing.longName,
        shortName = incoming.shortName ?: existing.shortName,
        hwModel = incoming.hwModel ?: existing.hwModel,
        role = incoming.role ?: existing.role,
        dbPosition = incoming.dbPosition ?: existing.dbPosition,
        dbSnr = incoming.dbSnr ?: existing.dbSnr,
        lastHeardEpochSeconds = incoming.lastHeardEpochSeconds ?: existing.lastHeardEpochSeconds,
        hopsAway = incoming.hopsAway ?: existing.hopsAway,
        hasPublicKey = incoming.hasPublicKey || existing.hasPublicKey,
        receivedAtMillis = incoming.receivedAtMillis,
    )
}
