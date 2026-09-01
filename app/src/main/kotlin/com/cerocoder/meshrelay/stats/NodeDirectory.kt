package com.cerocoder.meshrelay.stats

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
    private val positions = HashMap<Int, PositionHistory>()
    private val telemetryRecords = HashMap<Int, TelemetryRecord>()
    private var localNodeNum: Int? = null
    private var loadedAtMillis: Long? = null

    /**
     * Records one node database entry, **merged** onto whatever is already known
     * rather than replacing it: a field this message leaves absent keeps the value
     * it already had. The radio sends node info in several shapes - a full entry at
     * connect, a thinner one later - and a replace would make the record oscillate.
     */
    fun applyNodeInfo(info: NodeInfo) {
        val incoming = NodeRecord.fromProto(info)
        val existing = nodes[info.num]
        nodes[info.num] = if (existing == null) incoming else merge(existing, incoming)
    }

    /**
     * Records the identity half of a node, creating a bare record when the database
     * has never mentioned it - nodes turn up in traffic before they turn up in the
     * database, and a relay candidate nobody has heard of is still a candidate.
     *
     * A NODEINFO_APP packet carries a [User] and nothing else, so only the identity
     * fields move; the position, signal and hop count learned elsewhere stay put.
     */
    fun applyUser(nodeNum: Int, user: User) {
        val existing = nodes[nodeNum] ?: NodeRecord(
            num = nodeNum,
            longName = null,
            shortName = null,
            hwModel = null,
            role = null,
            dbPosition = null,
            dbSnr = null,
            lastHeardEpochSeconds = null,
            hopsAway = null,
            hasPublicKey = false,
        )
        nodes[nodeNum] = existing.copy(
            longName = user.long_name,
            shortName = user.short_name,
            hwModel = user.hw_model?.name,
            role = user.role?.name,
            hasPublicKey = (user.public_key?.size ?: 0) > 0,
        )
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

    fun markLoaded(atMillis: Long) {
        loadedAtMillis = atMillis
    }

    /**
     * Starts a fresh measurement: positions and telemetry heard during the session
     * go, the node database stays. Ports reset, mesh_stats.py:1100-1113 - resetting
     * the statistics is not the same as forgetting who is out there, and reloading
     * the database costs a round trip to the radio.
     */
    fun clearRuntimeData() {
        positions.clear()
        telemetryRecords.clear()
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
     * published is not something this mesh does. [applyUser] takes the opposite
     * view, for the opposite reason: a `User` message *is* the identity record, so
     * what it says about a key is authoritative there.
     *
     * Both halves of that asymmetry are pinned by tests; it is a decision, not an
     * accident, and flattening this `||` into a straight assignment would fail
     * them.
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
    )
}
