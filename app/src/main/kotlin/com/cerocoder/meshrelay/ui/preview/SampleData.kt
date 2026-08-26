package com.cerocoder.meshrelay.ui.preview

import com.cerocoder.meshrelay.stats.SortMode
import com.cerocoder.meshrelay.stats.model.Counters
import com.cerocoder.meshrelay.stats.model.NeighbourStats
import com.cerocoder.meshrelay.stats.model.NodeDirectorySnapshot
import com.cerocoder.meshrelay.stats.model.NodeRecord
import com.cerocoder.meshrelay.stats.model.PositionHistory
import com.cerocoder.meshrelay.stats.model.PositionReport
import com.cerocoder.meshrelay.stats.model.RelayStats
import com.cerocoder.meshrelay.stats.model.RemoteNodeStats
import com.cerocoder.meshrelay.stats.model.SignalHistory
import com.cerocoder.meshrelay.stats.model.SignalStats
import com.cerocoder.meshrelay.stats.model.StatsSnapshot
import com.cerocoder.meshrelay.stats.model.TelemetryRecord

/**
 * Fixture data for Compose previews. There is no build toolchain in this
 * repository, so previews are the only way any of Round 4's nine screens can be
 * looked at before they ship - and every one of them is built by an agent who
 * cannot see what the other eight wrote. A fixture of only tidy cases would let
 * all nine look correct while mishandling exactly the packets this tool exists
 * to show, so this object is deliberately awkward. Geography is the real
 * Madrid-Getafe-Toledo corridor (see the project's root CLAUDE.md); node numbers
 * carry the `0xB1...` prefix so they are never mistaken for the emulator demo
 * scenario's `0xA1...` node numbers or the test suite's own fixtures.
 *
 * Case index - where to look for each of the twelve awkward cases the brief asks
 * for, so a screen author can find the one they need without reading the whole
 * file:
 *
 *  1. Exactly one matching node (name + distance appear): [RELAY_ONE_MATCH_BYTE],
 *     matching [NUM_GETAFE_ROUTER].
 *  2. Three matching nodes (no name, count reads `[3]`): [RELAY_THREE_MATCH_BYTE],
 *     matching [NUM_TOLEDO_ALTA], [NUM_TOLEDO_BAJA] and [NUM_TOLEDO_NIEBLA].
 *  3. No matching node at all: [RELAY_NO_MATCH_BYTE].
 *  4. One skipped candidate (non-empty skipped list): [RELAY_SKIPPED_CANDIDATE_BYTE],
 *     whose only candidate [NUM_ILLESCAS_MUDO] is in `snapshot.skippedRelayNodes`.
 *  5. Heard once (`packetsPerHour == 0`, single-point gauge): [relayHeardOnce].
 *  6. Never heard (age reads "Never"): [relayNeverHeard] - `packetCount` and
 *     `lastPacketAtMillis` at their zero defaults.
 *  7. Position with altitude / with position but no altitude / with none:
 *     [NUM_GETAFE_ROUTER], [NUM_TOLEDO_ALTA], [NUM_TOLEDO_BAJA] respectively.
 *  8. Obfuscation radius exceeding the distance (`Direction.UNKNOWN`):
 *     [NUM_TOLEDO_NIEBLA] - see its comment for the exact numbers.
 *  9. Telemetry with a restart count / with none: [NUM_YUNCOS_REINICIO] has one;
 *     [NUM_GETAFE_ROUTER] (and every other node here) has no entry in the
 *     directory's telemetry map at all.
 * 10. A very long node name: [NUM_SIERRA_LARGA].
 * 11. Empty-string `longName`/`shortName` rather than null: [NUM_ILLESCAS_MUDO] -
 *     contrast with [NUM_TOLEDO_BAJA], whose names are null because no `User` was
 *     ever heard from it at all.
 * 12. A relay with no signal information at all (`SignalStats.EMPTY`,
 *     `hasData == false`): [relayNoSignal].
 */
object SampleData {

    // ------------------------------------------------------------------
    // Time. Computed once at class load rather than hard-coded, so every
    // preview always shows plausible recent ages instead of drifting into
    // "3 months ago" as real time passes.
    // ------------------------------------------------------------------

    private val NOW = System.currentTimeMillis()

    /** When the relay heard-once and the two "most recent" fields below fired. */
    private val MOST_RECENT_PACKET_AT = NOW - 5_000L

    // ------------------------------------------------------------------
    // Node numbers. The 0xB1... prefix sets bit 31, so every one of these
    // literals overflows Int and is narrowed with .toInt() - the same pattern
    // Scenarios.kt and the stats test suite already use for hand-picked node
    // numbers.
    //
    // Low bytes are chosen on purpose, not sequentially:
    //  - NUM_TOLEDO_ALTA, NUM_TOLEDO_BAJA and NUM_TOLEDO_NIEBLA all end in
    //    0x2c, so RELAY_THREE_MATCH_BYTE has exactly those three candidates.
    //  - every other node below ends in a byte no other node shares, so its
    //    relay byte (where it has one) matches it alone.
    //  - 0x99 (RELAY_NO_MATCH_BYTE) is not the low byte of anything here.
    // ------------------------------------------------------------------

    /** This device. Its own entry is how [NodeDirectorySnapshot.localPosition] resolves. */
    val NUM_LOCAL_DEVICE = 0xB1000001.toInt()

    /** The sole match for [RELAY_ONE_MATCH_BYTE]; also the "position with altitude" case. */
    val NUM_GETAFE_ROUTER = 0xB100101E.toInt()

    /** Three-match sibling #1: a position, but no altitude. */
    val NUM_TOLEDO_ALTA = 0xB100112C.toInt()

    /** Three-match sibling #2: no position at all, and no user ever heard - a bare
     *  node known only because routing mentioned its number. */
    val NUM_TOLEDO_BAJA = 0xB100122C.toInt()

    /** Three-match sibling #3: a live, heavily obfuscated position - see below. */
    val NUM_TOLEDO_NIEBLA = 0xB100132C.toInt()

    /** The skipped candidate for [RELAY_SKIPPED_CANDIDATE_BYTE]; also the empty-name case. */
    val NUM_ILLESCAS_MUDO = 0xB100144D.toInt()

    /** The sole match for [RELAY_HEARD_ONCE_BYTE]; also the very-long-name case. */
    val NUM_SIERRA_LARGA = 0xB1001507.toInt()

    /** The sole match for [RELAY_NEVER_HEARD_BYTE]: known by name, never yet relayed through. */
    val NUM_VALDEMORO_QUIETO = 0xB10016B7.toInt()

    /** The sole match for [RELAY_NO_SIGNAL_BYTE]. */
    val NUM_PINTO_SINDATOS = 0xB100175A.toInt()

    /** Carries the telemetry restart-count case. Not a candidate for any relay byte here. */
    val NUM_YUNCOS_REINICIO = 0xB1001863.toInt()

    // ------------------------------------------------------------------
    // Relay bytes. Seven, all distinct, one per RelayStats fixture below.
    // ------------------------------------------------------------------

    const val RELAY_ONE_MATCH_BYTE = 0x1E
    const val RELAY_THREE_MATCH_BYTE = 0x2C
    const val RELAY_NO_MATCH_BYTE = 0x99
    const val RELAY_SKIPPED_CANDIDATE_BYTE = 0x4D
    const val RELAY_HEARD_ONCE_BYTE = 0x07
    const val RELAY_NEVER_HEARD_BYTE = 0xB7
    const val RELAY_NO_SIGNAL_BYTE = 0x5A

    // ------------------------------------------------------------------
    // Node database. dbPosition never carries a precisionBits value - the real
    // node database has no such field, exactly as NodeRecord.fromProto strips
    // it - so only a live (positions map) report below is ever obfuscated.
    // ------------------------------------------------------------------

    private val localDevice = NodeRecord(
        num = NUM_LOCAL_DEVICE,
        longName = "Mi Nodo Local",
        shortName = "LOC1",
        hwModel = "T_ECHO",
        role = "CLIENT",
        // Madrid, ~667 m: the same point and altitude used throughout this
        // project's own tests (see NodeRecordTest and NodeDirectoryTest).
        dbPosition = PositionReport(
            atMillis = NOW - 60_000L,
            latitude = 40.4168,
            longitude = -3.7038,
            altitude = 667,
            precisionBits = null,
        ),
        dbSnr = null,
        lastHeardEpochSeconds = null,
        hopsAway = 0,
        hasPublicKey = true,
    )

    private val getafeRouter = NodeRecord(
        num = NUM_GETAFE_ROUTER,
        longName = "Getafe Router",
        shortName = "GETR",
        hwModel = "HELTEC_V3",
        role = "ROUTER",
        // Getafe, full data: lat/lon and altitude both present - the plain,
        // fully-working "position with altitude" case.
        dbPosition = PositionReport(
            atMillis = NOW - 900_000L,
            latitude = 40.3083,
            longitude = -3.7325,
            altitude = 623,
            precisionBits = null,
        ),
        dbSnr = 8.5f,
        lastHeardEpochSeconds = ((NOW - 900_000L) / 1000L).toInt(),
        hopsAway = 1,
        hasPublicKey = true,
    )

    private val toledoAlta = NodeRecord(
        num = NUM_TOLEDO_ALTA,
        longName = "Toledo Alta",
        shortName = "TALT",
        hwModel = "RAK4631",
        role = "CLIENT",
        // Coordinates present, altitude absent - PositionHistory.best would
        // reject this as a "live" report for lacking altitude, but it never
        // gets the chance: this is the *database* position, read straight
        // through, altitude and all (here, none).
        dbPosition = PositionReport(
            atMillis = NOW - 7_200_000L,
            latitude = 39.8650,
            longitude = -4.0300,
            altitude = null,
            precisionBits = null,
        ),
        dbSnr = 6.0f,
        lastHeardEpochSeconds = ((NOW - 7_200_000L) / 1000L).toInt(),
        hopsAway = 2,
        hasPublicKey = false,
    )

    private val toledoBaja = NodeRecord(
        num = NUM_TOLEDO_BAJA,
        // No User has ever been heard from this node - it is known only
        // because a relay byte or a routed packet mentioned its number.
        // longName/shortName are null here, in contrast with NUM_ILLESCAS_MUDO
        // below, whose User *was* heard but was empty.
        longName = null,
        shortName = null,
        hwModel = null,
        role = null,
        dbPosition = null,
        dbSnr = null,
        lastHeardEpochSeconds = null,
        hopsAway = 3,
        hasPublicKey = false,
    )

    private val toledoNiebla = NodeRecord(
        num = NUM_TOLEDO_NIEBLA,
        longName = "Toledo Niebla",
        shortName = "TNIE",
        hwModel = "STATION_G2",
        role = "CLIENT",
        // No database position - its only known position is the live,
        // obfuscated one in the positions map below, which is what wins.
        dbPosition = null,
        dbSnr = 5.5f,
        lastHeardEpochSeconds = ((NOW - 45_000L) / 1000L).toInt(),
        hopsAway = 2,
        hasPublicKey = false,
    )

    private val illescasMudo = NodeRecord(
        num = NUM_ILLESCAS_MUDO,
        // A User submessage WAS present - Wire's proto3 default for an unset
        // string field is "", not null - so a screen writing `name ?: "..."`
        // must not fire its fallback here. Compare with NUM_TOLEDO_BAJA above,
        // whose absence is a real null because no User was ever heard at all.
        longName = "",
        shortName = "",
        hwModel = null,
        role = "CLIENT",
        dbPosition = null,
        dbSnr = null,
        lastHeardEpochSeconds = ((NOW - 300_000L) / 1000L).toInt(),
        hopsAway = 2,
        hasPublicKey = false,
    )

    private val sierraLarga = NodeRecord(
        num = NUM_SIERRA_LARGA,
        // Deliberately far past any reasonable column width or avatar chip,
        // in both fields - whichever one a screen renders, truncation has to
        // show up here rather than get discovered on a phone.
        longName = "Repetidor Provisional de la Sierra de Guadarrama, Zona Norte, Instalacion Temporal Numero Siete",
        shortName = "SIERRA-NORTE-TEMP-07",
        hwModel = "T_ECHO",
        role = "CLIENT",
        dbPosition = null,
        dbSnr = 4.0f,
        lastHeardEpochSeconds = ((NOW - 5_000L) / 1000L).toInt(),
        hopsAway = 1,
        hasPublicKey = true,
    )

    private val valdemoroQuieto = NodeRecord(
        num = NUM_VALDEMORO_QUIETO,
        longName = "Valdemoro Quieto",
        shortName = "VALQ",
        hwModel = "HELTEC_V3",
        role = "CLIENT",
        dbPosition = null,
        dbSnr = null,
        lastHeardEpochSeconds = null,
        hopsAway = 2,
        hasPublicKey = false,
    )

    private val pintoSinDatos = NodeRecord(
        num = NUM_PINTO_SINDATOS,
        longName = "Pinto Sin Datos",
        shortName = "PSIN",
        hwModel = "TBEAM",
        role = "CLIENT",
        dbPosition = null,
        dbSnr = -6.0f,
        lastHeardEpochSeconds = ((NOW - 60_000L) / 1000L).toInt(),
        hopsAway = 2,
        hasPublicKey = false,
    )

    private val yuncosReinicio = NodeRecord(
        num = NUM_YUNCOS_REINICIO,
        longName = "Yuncos Reinicio",
        shortName = "YREI",
        hwModel = "HELTEC_V3",
        role = "CLIENT",
        dbPosition = null,
        dbSnr = 2.0f,
        lastHeardEpochSeconds = ((NOW - 200_000L) / 1000L).toInt(),
        hopsAway = 2,
        hasPublicKey = false,
    )

    // ------------------------------------------------------------------
    // Live positions. Only NUM_TOLEDO_NIEBLA has one: a report heard this
    // session, with both coordinates and altitude (so PositionHistory.best
    // picks it up) and a precision coarse enough that its obfuscation radius
    // swallows the distance from the local node.
    //
    // Toledo is ~67.461 km from Madrid (the same haversine this project's own
    // GeoTest and NodeDirectoryTest pin). At 8 bits of precision the radius is
    // 23,905,784 / 2^8 = 93,381.97 m - well past that distance, so
    // NodeDirectorySnapshot.locationInfo must report Direction.UNKNOWN, not a
    // bearing it has no business printing.
    // ------------------------------------------------------------------

    private val toledoNieblaLivePosition = PositionReport(
        atMillis = NOW - 45_000L,
        latitude = 39.8628,
        longitude = -4.0273,
        altitude = 550,
        precisionBits = 8,
    )

    // ------------------------------------------------------------------
    // Telemetry. Only NUM_YUNCOS_REINICIO has an entry - every other node in
    // the directory has no key in the telemetry map at all, which is the "one
    // with none" half of that required case; NUM_GETAFE_ROUTER is called out
    // in the case index above as the concrete example.
    // ------------------------------------------------------------------

    private val yuncosTelemetry = TelemetryRecord()
        .withUptime(7_200) // running for two hours
        .withMetric("battery_level", NOW - 200_000L, 61f)
        .withUptime(900) // the counter fell - a reboot, observedRestartCount advances to 1

    // ------------------------------------------------------------------
    // Relays. Kept in descending packetCount order to match sortMode = PACKETS
    // below, the way StatsSnapshot's own contract expects.
    // ------------------------------------------------------------------

    /** Exactly one directory node matches this byte: name and distance both appear. */
    private val relayOneMatch = RelayStats(
        relayByte = RELAY_ONE_MATCH_BYTE,
        nodeName = "GETR", // == directory.uniqueRelayName(RELAY_ONE_MATCH_BYTE)
        snr = SignalStats.EMPTY.plus(4.0f).plus(6.5f).plus(8.5f).plus(7.2f),
        rssi = SignalStats.EMPTY.plus(-78f).plus(-70f).plus(-64f).plus(-69f),
        packetCount = 47,
        firstPacketAtMillis = NOW - 3_600_000L,
        lastPacketAtMillis = NOW - 30_000L,
        fromNodeStats = mapOf(
            NUM_TOLEDO_ALTA to RemoteNodeStats()
                .plus(hopStart = 3, hopLimit = 1, hopsKnown = true)
                .plus(hopStart = 3, hopLimit = 2, hopsKnown = true),
            NUM_SIERRA_LARGA to RemoteNodeStats()
                .plus(hopStart = 3, hopLimit = 2, hopsKnown = true),
        ),
    )

    /** Three directory nodes match this byte: no name is shown, only the count. */
    private val relayThreeMatch = RelayStats(
        relayByte = RELAY_THREE_MATCH_BYTE,
        nodeName = "", // ambiguous: directory.uniqueRelayName(RELAY_THREE_MATCH_BYTE) == ""
        snr = SignalStats.EMPTY.plus(-2.0f).plus(1.5f).plus(3.0f),
        rssi = SignalStats.EMPTY.plus(-95f).plus(-88f).plus(-91f),
        packetCount = 19,
        firstPacketAtMillis = NOW - 1_800_000L,
        lastPacketAtMillis = NOW - 120_000L,
        fromNodeStats = mapOf(
            NUM_YUNCOS_REINICIO to RemoteNodeStats().plus(hopStart = 3, hopLimit = 1, hopsKnown = true),
        ),
    )

    /** No directory node's low byte equals this one: the "unheard-of relay" path. */
    private val relayNoMatch = RelayStats(
        relayByte = RELAY_NO_MATCH_BYTE,
        nodeName = "",
        snr = SignalStats.EMPTY.plus(-10.0f).plus(-4.0f).plus(0.5f),
        rssi = SignalStats.EMPTY.plus(-102f).plus(-97f).plus(-99f),
        packetCount = 12,
        firstPacketAtMillis = NOW - 600_000L,
        lastPacketAtMillis = NOW - 45_000L,
    )

    /**
     * One directory node (NUM_ILLESCAS_MUDO) has this low byte, but it is in
     * the skipped set - so matchingNodeNums filters it out and there is no
     * name, exactly as with [relayNoMatch], but for a different reason: the
     * candidate list a detail screen builds by re-including skipped nodes is
     * non-empty here and empty there.
     */
    private val relaySkippedCandidate = RelayStats(
        relayByte = RELAY_SKIPPED_CANDIDATE_BYTE,
        nodeName = "",
        snr = SignalStats.EMPTY.plus(2.0f).plus(1.0f),
        rssi = SignalStats.EMPTY.plus(-90f).plus(-88f),
        packetCount = 9,
        firstPacketAtMillis = NOW - 500_000L,
        lastPacketAtMillis = NOW - 20_000L,
    )

    /** Heard exactly once: packetsPerHour reads 0 and the gauge has nothing to span. */
    private val relayHeardOnce = RelayStats(
        relayByte = RELAY_HEARD_ONCE_BYTE,
        nodeName = "SIERRA-NORTE-TEMP-07", // == directory.uniqueRelayName(RELAY_HEARD_ONCE_BYTE)
        snr = SignalStats.EMPTY.plus(4.5f),
        rssi = SignalStats.EMPTY.plus(-82f),
        packetCount = 1,
        firstPacketAtMillis = MOST_RECENT_PACKET_AT,
        lastPacketAtMillis = MOST_RECENT_PACKET_AT,
    )

    /**
     * Never heard: packetCount and both timestamps stay at their zero
     * defaults, which is what AgeText.relative's "Never" branch is documented
     * to key off (see AgeFormatTest and AgeText's own KDoc).
     */
    private val relayNeverHeard = RelayStats(
        relayByte = RELAY_NEVER_HEARD_BYTE,
        nodeName = "VALQ", // == directory.uniqueRelayName(RELAY_NEVER_HEARD_BYTE)
    )

    /**
     * Packets were relayed, but none carried decodable signal information:
     * snr and rssi are both SignalStats.EMPTY (hasData == false), so the
     * gauge's empty-track path and the "no data" numeric path both have
     * somewhere to appear. Not exercised anywhere in the demo scenario -
     * this fixture is the only place it appears.
     */
    private val relayNoSignal = RelayStats(
        relayByte = RELAY_NO_SIGNAL_BYTE,
        nodeName = "PSIN", // == directory.uniqueRelayName(RELAY_NO_SIGNAL_BYTE)
        snr = SignalStats.EMPTY,
        rssi = SignalStats.EMPTY,
        packetCount = 6,
        firstPacketAtMillis = NOW - 600_000L,
        lastPacketAtMillis = NOW - 60_000L,
    )

    private val allRelays: List<RelayStats> = listOf(
        relayOneMatch,
        relayThreeMatch,
        relayNoMatch,
        relaySkippedCandidate,
        relayNoSignal,
        relayHeardOnce,
        relayNeverHeard,
    )

    // ------------------------------------------------------------------
    // Neighbours: nodes heard directly, no relay in between.
    // ------------------------------------------------------------------

    private val neighbours: List<NeighbourStats> = listOf(
        NeighbourStats(
            nodeNum = NUM_GETAFE_ROUTER,
            snr = SignalHistory().plus(NOW - 500_000L, 9.0f).plus(NOW - 100_000L, 8.7f),
            rssi = SignalHistory().plus(NOW - 500_000L, -60f).plus(NOW - 100_000L, -58f),
            packetCount = 2,
            lastPacketAtMillis = NOW - 100_000L,
        ),
        NeighbourStats(
            nodeNum = NUM_ILLESCAS_MUDO,
            snr = SignalHistory().plus(NOW - 400_000L, 10.2f),
            rssi = SignalHistory().plus(NOW - 400_000L, -55f),
            packetCount = 1,
            lastPacketAtMillis = NOW - 400_000L,
        ),
    )

    // ------------------------------------------------------------------
    // Public API.
    // ------------------------------------------------------------------

    /**
     * The node directory backing [snapshot]. Built directly rather than by
     * driving a real `NodeDirectory` - the constructor takes the same maps
     * `NodeDirectory.snapshot` would hand over, and there is no engine here
     * to run.
     */
    val directory: NodeDirectorySnapshot = NodeDirectorySnapshot(
        nodes = mapOf(
            NUM_LOCAL_DEVICE to localDevice,
            NUM_GETAFE_ROUTER to getafeRouter,
            NUM_TOLEDO_ALTA to toledoAlta,
            NUM_TOLEDO_BAJA to toledoBaja,
            NUM_TOLEDO_NIEBLA to toledoNiebla,
            NUM_ILLESCAS_MUDO to illescasMudo,
            NUM_SIERRA_LARGA to sierraLarga,
            NUM_VALDEMORO_QUIETO to valdemoroQuieto,
            NUM_PINTO_SINDATOS to pintoSinDatos,
            NUM_YUNCOS_REINICIO to yuncosReinicio,
        ),
        loadedAtMillis = NOW - 900_000L,
        localNodeNum = NUM_LOCAL_DEVICE,
        positions = mapOf(
            NUM_TOLEDO_NIEBLA to PositionHistory(
                nodeNum = NUM_TOLEDO_NIEBLA,
                reports = listOf(toledoNieblaLivePosition),
            ),
        ),
        telemetry = mapOf(
            NUM_YUNCOS_REINICIO to yuncosTelemetry,
        ),
        skipped = setOf(NUM_ILLESCAS_MUDO),
    )

    /** The rich fixture: every one of the twelve cases indexed above is in here. */
    val snapshot: StatsSnapshot = StatsSnapshot(
        relays = allRelays,
        neighbours = neighbours,
        counters = Counters(
            totalRelayedPackets = allRelays.sumOf { it.packetCount },
            totalDirectPackets = neighbours.sumOf { it.packetCount },
            totalPackets = allRelays.sumOf { it.packetCount } + neighbours.sumOf { it.packetCount },
            relayCount = allRelays.size,
        ),
        paused = false,
        sortMode = SortMode.PACKETS,
        lastPacketAtMillis = MOST_RECENT_PACKET_AT,
        lastRelayedPacketAtMillis = MOST_RECENT_PACKET_AT,
        directory = directory,
        skippedRelayNodes = setOf(NUM_ILLESCAS_MUDO),
    )

    /** Before a single packet has arrived - what the screens render at startup. */
    val emptySnapshot: StatsSnapshot = StatsSnapshot.EMPTY

    /** [snapshot], with the engine paused - the same state the terminal tool's `P` key toggles. */
    val pausedSnapshot: StatsSnapshot = snapshot.copy(paused = true)

    /** Looks up one of the fixtures above by its relay byte, for a preview that
     *  wants to parameterise over a specific case rather than the whole list. */
    fun relay(byte: Int): RelayStats = allRelays.first { it.relayByte == byte }
}
