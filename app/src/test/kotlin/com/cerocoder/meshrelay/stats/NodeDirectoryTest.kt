package com.cerocoder.meshrelay.stats

import com.cerocoder.meshrelay.stats.model.Direction
import com.cerocoder.meshrelay.stats.model.LatLon
import com.cerocoder.meshrelay.stats.model.PositionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.Config
import org.meshtastic.proto.DeviceMetrics
import org.meshtastic.proto.EnvironmentMetrics
import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.PowerMetrics
import org.meshtastic.proto.Position
import org.meshtastic.proto.Telemetry
import org.meshtastic.proto.User

// Real Zona Centro geography: every distance and bearing below is the one the
// mesh actually has, so a wrong formula shows up as a wrong place on the map.
private val MADRID_LOCAL = LatLon(40.4168, -3.7038)
private const val MADRID_LAT_I = 404168000
private const val MADRID_LON_I = -37038000

private const val GETAFE_LAT = 40.3083
private const val GETAFE_LON = -3.7325
private const val GETAFE_LAT_I = 403083000
private const val GETAFE_LON_I = -37325000

private const val TOLEDO_LAT = 39.8628
private const val TOLEDO_LAT_I = 398628000
private const val TOLEDO_LON_I = -40273000

// Madrid -> Getafe, by the haversine in Geo.
private const val MADRID_TO_GETAFE_KM = 12.30726

// 23905784 / 2^bits, the firmware's obfuscation radius.
private const val RADIUS_13_BITS = 2918.1865234375
private const val RADIUS_16_BITS = 364.7733154296875

// Points due north of Madrid, straddling the 16-bit radius by about four metres:
// 361.128 m is inside it, 368.422 m is outside it.
private const val INSIDE_RADIUS_LAT_I = 404200477
private const val OUTSIDE_RADIUS_LAT_I = 404201133

// 300.004 m due north of Madrid - the brief's node that published to 13 bits.
private const val THREE_HUNDRED_METRES_LAT_I = 404194980

// Node numbers are hand-picked, not sequential, for three engineered properties:
//  - GETAFE_ROUTER, TOLEDO_ESTACION and TOLEDO_ALTO share the low byte 0x2a, so
//    one relay byte has three candidates and no unique name;
//  - ILLESCAS_ZERO ends in 0x00, which Geo.lastByteOfNodeNum reports as 0xff;
//  - inserted in the order used below, their HashMap iteration order is
//    descending, so a matchingNodeNums that returned the map's own order would
//    fail the ascending assertion rather than accidentally pass it.
private val GETAFE_ROUTER = 0xA1000A2A.toInt()
private val TOLEDO_ESTACION = 0xA1001A2A.toInt()
private val TOLEDO_ALTO = 0xA1002A2A.toInt()
private val GETAFE_VECINO = 0xA1000C33.toInt()
private val ILLESCAS_ZERO = 0xA1001B00.toInt()
private val BARE_NODE = 0xA1004459.toInt()
private val PINTO = 0xA100119C.toInt()
private val LOCAL_NODE = 0xA1FFEE01.toInt()
private const val SENDER = 0x9e75f1a4.toInt()

// Only its presence reaches NodeRecord.hasPublicKey, so the bytes are arbitrary;
// a real Curve25519 key would be 32 of them.
private val PUBLIC_KEY = okio.ByteString.of(1, 2, 3)

private const val DB_LAST_HEARD_SECONDS = 1_700_000_000
private const val DB_AT_MILLIS = 1_700_000_000_000L
private const val LIVE_AT_MILLIS = 1_700_000_500_000L
private const val LOADED_AT_MILLIS = 1_700_000_100_000L
private const val TELEMETRY_AT_MILLIS = 1_700_000_600_000L

class NodeDirectoryTest {

    private var now = DB_AT_MILLIS
    private val directory = NodeDirectory(TimeSource { now })

    private fun position(
        latitudeI: Int,
        longitudeI: Int,
        altitude: Int? = null,
        precisionBits: Int = 0,
    ) = Position(
        latitude_i = latitudeI,
        longitude_i = longitudeI,
        altitude = altitude,
        precision_bits = precisionBits,
    )

    private fun getafeInDatabase() = NodeInfo(
        num = GETAFE_ROUTER,
        user = User(long_name = "Getafe Router", short_name = "gt2a", hw_model = HardwareModel.T_ECHO),
        position = position(GETAFE_LAT_I, GETAFE_LON_I, altitude = 622),
        snr = 6.25f,
        last_heard = DB_LAST_HEARD_SECONDS,
        hops_away = 2,
    )

    private fun toledoInDatabase() = NodeInfo(
        num = GETAFE_ROUTER,
        user = User(short_name = "gt2a"),
        position = position(TOLEDO_LAT_I, TOLEDO_LON_I, altitude = 529),
        last_heard = DB_LAST_HEARD_SECONDS,
    )

    /** The five nodes whose low bytes the candidate-matching tests are built on. */
    private fun loadRelayCandidates() {
        listOf(
            TOLEDO_ALTO to "ta2a",
            TOLEDO_ESTACION to "to2a",
            GETAFE_ROUTER to "gt2a",
            GETAFE_VECINO to "gv33",
            ILLESCAS_ZERO to "il00",
        ).forEach { (num, shortName) ->
            directory.applyNodeInfo(NodeInfo(num = num, user = User(short_name = shortName)))
        }
    }

    @Test
    fun `a live position beats the database position`() {
        // Ports the CUR-over-DB precedence at mesh_stats.py:862-880. The database
        // entry can be days old; a position packet heard a minute ago is the better
        // answer, and the source label is what tells the user which they are
        // looking at.
        directory.applyNodeInfo(toledoInDatabase())
        now = LIVE_AT_MILLIS
        directory.applyPosition(
            GETAFE_ROUTER,
            position(GETAFE_LAT_I, GETAFE_LON_I, altitude = 622, precisionBits = 16),
        )

        val info = directory.snapshot(emptySet()).locationInfo(GETAFE_ROUTER, MADRID_LOCAL)

        assertEquals(PositionSource.CURRENT, info.source)
        assertEquals(GETAFE_LAT, info.lat!!, 1e-9)
        assertEquals(GETAFE_LON, info.lon!!, 1e-9)
        assertEquals(622, info.altitude)
        assertEquals(LIVE_AT_MILLIS, info.atMillis)
        // The precision travels with the live report; the database has none.
        assertEquals(RADIUS_16_BITS, info.obfuscationRadiusMeters!!, 1e-9)
    }

    @Test
    fun `the database position is used when nothing has been heard live`() {
        directory.applyNodeInfo(
            NodeInfo(
                num = GETAFE_ROUTER,
                user = User(short_name = "gt2a"),
                // precision_bits set here on purpose: NodeRecord strips it, because
                // the node database does not really carry one.
                position = position(GETAFE_LAT_I, GETAFE_LON_I, altitude = 622, precisionBits = 16),
                last_heard = DB_LAST_HEARD_SECONDS,
            ),
        )
        now = LIVE_AT_MILLIS

        val info = directory.snapshot(emptySet()).locationInfo(GETAFE_ROUTER, MADRID_LOCAL)

        assertEquals(PositionSource.DB, info.source)
        assertEquals(GETAFE_LAT, info.lat!!, 1e-9)
        assertEquals(622, info.altitude)
        // Timed by when the node was last heard, not by when the snapshot was taken.
        assertEquals(DB_AT_MILLIS, info.atMillis)
        assertNull(info.obfuscationRadiusMeters)
        // With no radius there is nothing to suppress the direction.
        assertEquals(Direction.S, info.direction)
    }

    @Test
    fun `a live position with no altitude falls back to the database`() {
        // Consequence of PositionHistory.best returning null unless a report has
        // both coordinates and altitude. Fixing the quirk here would make the source
        // label disagree with the terminal tool.
        directory.applyNodeInfo(toledoInDatabase())
        now = LIVE_AT_MILLIS
        directory.applyPosition(GETAFE_ROUTER, position(GETAFE_LAT_I, GETAFE_LON_I))

        val info = directory.snapshot(emptySet()).locationInfo(GETAFE_ROUTER, MADRID_LOCAL)

        assertEquals(PositionSource.DB, info.source)
        assertEquals(TOLEDO_LAT, info.lat!!, 1e-9)
        assertEquals(529, info.altitude)
        assertEquals(DB_AT_MILLIS, info.atMillis)
    }

    @Test
    fun `distance and direction are computed from the local position`() {
        // Madrid -> Getafe: 12.307 km, bearing 191.4, direction S.
        directory.applyPosition(
            GETAFE_ROUTER,
            position(GETAFE_LAT_I, GETAFE_LON_I, altitude = 622, precisionBits = 16),
        )

        val info = directory.snapshot(emptySet()).locationInfo(GETAFE_ROUTER, MADRID_LOCAL)

        assertEquals(MADRID_TO_GETAFE_KM, info.distanceKm!!, 0.001)
        // Getafe is south of Madrid. The reverse bearing would read N, so this also
        // pins which of the two points is the observer.
        assertEquals(Direction.S, info.direction)
        // 364.77 m of uncertainty over 12.3 km of separation suppresses nothing.
        // Comparing the radius against kilometres rather than metres would.
        assertEquals(RADIUS_16_BITS, info.obfuscationRadiusMeters!!, 1e-9)
    }

    @Test
    fun `direction is unknown when the obfuscation radius reaches the distance`() {
        // A node 300 m away that published its position to 13 bits of precision has
        // an uncertainty of 2.9 km. Any direction printed would be invented.
        directory.applyPosition(
            GETAFE_ROUTER,
            position(THREE_HUNDRED_METRES_LAT_I, MADRID_LON_I, altitude = 655, precisionBits = 13),
        )
        // The same boundary from both sides, four metres apart, at 16 bits: the
        // radius is 364.7733 m, the two nodes are 361.128 m and 368.422 m away.
        directory.applyPosition(
            TOLEDO_ESTACION,
            position(INSIDE_RADIUS_LAT_I, MADRID_LON_I, altitude = 655, precisionBits = 16),
        )
        directory.applyPosition(
            TOLEDO_ALTO,
            position(OUTSIDE_RADIUS_LAT_I, MADRID_LON_I, altitude = 655, precisionBits = 16),
        )
        val snapshot = directory.snapshot(emptySet())

        val near = snapshot.locationInfo(GETAFE_ROUTER, MADRID_LOCAL)
        assertEquals(Direction.UNKNOWN, near.direction)
        // The distance is still reported - only the direction is withheld.
        assertEquals(0.300, near.distanceKm!!, 0.001)
        assertEquals(RADIUS_13_BITS, near.obfuscationRadiusMeters!!, 1e-9)

        assertEquals(Direction.UNKNOWN, snapshot.locationInfo(TOLEDO_ESTACION, MADRID_LOCAL).direction)
        // Four metres further out the direction is real, and due north.
        assertEquals(Direction.N, snapshot.locationInfo(TOLEDO_ALTO, MADRID_LOCAL).direction)
    }

    @Test
    fun `distance is absent when the local position is unknown`() {
        directory.applyPosition(
            GETAFE_ROUTER,
            position(GETAFE_LAT_I, GETAFE_LON_I, altitude = 622, precisionBits = 16),
        )

        val info = directory.snapshot(emptySet()).locationInfo(GETAFE_ROUTER, from = null)

        assertNull(info.distanceKm)
        assertEquals(Direction.UNKNOWN, info.direction)
        // Everything that does not need a second point is still there.
        assertEquals(GETAFE_LAT, info.lat!!, 1e-9)
        assertEquals(622, info.altitude)
        assertEquals(PositionSource.CURRENT, info.source)
        // The radius describes the node's own position, so it does not depend on
        // where the observer is - mesh_stats.py:905 fills it in before the check.
        assertEquals(RADIUS_16_BITS, info.obfuscationRadiusMeters!!, 1e-9)
    }

    @Test
    fun `a node with a position but no coordinates still reports its altitude`() {
        directory.applyNodeInfo(
            NodeInfo(
                num = GETAFE_ROUTER,
                user = User(short_name = "gt2a"),
                position = Position(altitude = 622),
                last_heard = DB_LAST_HEARD_SECONDS,
            ),
        )

        val info = directory.snapshot(emptySet()).locationInfo(GETAFE_ROUTER, MADRID_LOCAL)

        assertEquals(622, info.altitude)
        assertEquals(PositionSource.DB, info.source)
        assertEquals(DB_AT_MILLIS, info.atMillis)
        assertNull(info.lat)
        assertNull(info.lon)
        assertNull(info.distanceKm)
        assertEquals(Direction.UNKNOWN, info.direction)
    }

    @Test
    fun `a node with nothing known about it has no location at all`() {
        directory.applyNodeInfo(NodeInfo(num = GETAFE_ROUTER, user = User(short_name = "gt2a")))

        val info = directory.snapshot(emptySet()).locationInfo(GETAFE_ROUTER, MADRID_LOCAL)

        assertNull(info.source)
        assertNull(info.lat)
        assertNull(info.altitude)
        assertNull(info.atMillis)
        assertNull(info.distanceKm)
        assertEquals(Direction.UNKNOWN, info.direction)
    }

    @Test
    fun `matching nodes are those whose low byte equals the relay byte`() {
        loadRelayCandidates()

        val nums = directory.snapshot(emptySet()).matchingNodeNums(0x2a)

        // Ascending, so the numbered [1], [2] labels on the detail screen stay put
        // between recompositions. The three share a HashMap bucket order that runs
        // the other way, so an unsorted result would not match by luck.
        assertEquals(listOf(GETAFE_ROUTER, TOLEDO_ESTACION, TOLEDO_ALTO), nums)
    }

    @Test
    fun `a skipped node is not offered as a candidate`() {
        loadRelayCandidates()

        val nums = directory.snapshot(setOf(TOLEDO_ESTACION)).matchingNodeNums(0x2a)

        assertEquals(listOf(GETAFE_ROUTER, TOLEDO_ALTO), nums)
    }

    @Test
    fun `a node whose number ends in zero matches relay byte ff`() {
        loadRelayCandidates()
        val snapshot = directory.snapshot(emptySet())

        assertEquals(listOf(ILLESCAS_ZERO), snapshot.matchingNodeNums(0xFF))
        // And it is not also offered under 0x00: the relay field never carries 0.
        assertTrue(snapshot.matchingNodeNums(0x00).isEmpty())
    }

    @Test
    fun `the relay name is shown only when exactly one node matches`() {
        // Ports get_node_name, mesh_stats.py:734-750. With two candidates there is
        // no name to show, and showing either would present a guess as a fact.
        loadRelayCandidates()
        directory.applyNodeInfo(NodeInfo(num = BARE_NODE))
        val snapshot = directory.snapshot(emptySet())

        assertEquals("gv33", snapshot.uniqueRelayName(0x33))
        assertEquals("", snapshot.uniqueRelayName(0x2a))
        assertEquals("", snapshot.uniqueRelayName(0x77))
        // One match, but the node never told anyone its name.
        assertEquals("", snapshot.uniqueRelayName(0x59))

        // Skipping the other two candidates leaves one, and the name appears: the
        // skip list is applied before uniqueness is judged, which is the whole
        // point of being able to skip a node.
        val skipped = directory.snapshot(setOf(GETAFE_ROUTER, TOLEDO_ALTO))
        assertEquals("to2a", skipped.uniqueRelayName(0x2a))
    }

    @Test
    fun `a short name is empty for a node the database does not know`() {
        loadRelayCandidates()
        val snapshot = directory.snapshot(emptySet())

        assertEquals("gt2a", snapshot.shortName(GETAFE_ROUTER))
        assertEquals("", snapshot.shortName(PINTO))
    }

    @Test
    fun `a node info frame and a later node info packet merge rather than replace`() {
        // A NODEINFO_APP packet carries a User and nothing else. Replacing the
        // record would erase the position learned from the handshake.
        directory.applyNodeInfo(getafeInDatabase())

        directory.applyUser(
            GETAFE_ROUTER,
            User(long_name = "Getafe Router 2", short_name = "gt2b", hw_model = HardwareModel.T_ECHO),
        )
        val afterUser = directory.snapshot(emptySet()).node(GETAFE_ROUTER)!!

        assertEquals("gt2b", afterUser.shortName)
        assertEquals("Getafe Router 2", afterUser.longName)
        assertEquals("T_ECHO", afterUser.hwModel)
        assertNotNull(afterUser.dbPosition)
        assertEquals(GETAFE_LAT, afterUser.dbPosition!!.latitude!!, 1e-9)
        assertEquals(DB_LAST_HEARD_SECONDS, afterUser.lastHeardEpochSeconds)
        assertEquals(6.25f, afterUser.dbSnr!!, 1e-6f)
        assertEquals(2, afterUser.hopsAway)

        // A second NodeInfo that omits the position, the signal and the hop count
        // keeps the ones already learned, and updates what it does carry.
        directory.applyNodeInfo(
            NodeInfo(
                num = GETAFE_ROUTER,
                user = User(short_name = "gt2c"),
                last_heard = DB_LAST_HEARD_SECONDS + 60,
            ),
        )
        val merged = directory.snapshot(emptySet()).node(GETAFE_ROUTER)!!

        assertEquals("gt2c", merged.shortName)
        assertEquals(GETAFE_LAT, merged.dbPosition!!.latitude!!, 1e-9)
        assertEquals(6.25f, merged.dbSnr!!, 1e-6f)
        assertEquals(2, merged.hopsAway)
        assertEquals(DB_LAST_HEARD_SECONDS + 60, merged.lastHeardEpochSeconds)
    }

    @Test
    fun `a public key once seen survives a node info that carries no user at all`() {
        // hasPublicKey is the one field of the merge that cannot be written with
        // ?:, because a Boolean has no third state: absence and false are the same
        // value, so the rule has to be spelled as an ||. Nothing else in the file
        // exercises that line, and flattening it to a straight assignment - the
        // obvious tidy-up - would silently unlearn every key.

        // Routing knows this node exists before anything has described it.
        directory.applyNodeInfo(NodeInfo(num = GETAFE_ROUTER, last_heard = DB_LAST_HEARD_SECONDS))
        assertFalse(directory.snapshot(emptySet()).node(GETAFE_ROUTER)!!.hasPublicKey)

        // Then the full entry arrives and the key becomes known: the incoming half
        // of the || has to count for something.
        directory.applyNodeInfo(
            NodeInfo(
                num = GETAFE_ROUTER,
                user = User(short_name = "gt2a", public_key = PUBLIC_KEY),
                last_heard = DB_LAST_HEARD_SECONDS + 30,
            ),
        )
        assertTrue(directory.snapshot(emptySet()).node(GETAFE_ROUTER)!!.hasPublicKey)

        // And a later thin entry - no user submessage at all - says nothing about a
        // key, so it unsays nothing: the existing half has to count too.
        directory.applyNodeInfo(
            NodeInfo(num = GETAFE_ROUTER, last_heard = DB_LAST_HEARD_SECONDS + 60),
        )
        val record = directory.snapshot(emptySet()).node(GETAFE_ROUTER)!!

        assertTrue(record.hasPublicKey)
        // The rest of the record survives the same way, which is what makes the
        // hasPublicKey assertion above about the || rather than about the merge.
        assertEquals("gt2a", record.shortName)
        assertEquals(DB_LAST_HEARD_SECONDS + 60, record.lastHeardEpochSeconds)
    }

    @Test
    fun `a user message is authoritative about the identity it carries`() {
        // The other half of that asymmetry, pinned so it stays a decision. Unlike a
        // NodeInfo with no user submessage, a User message *is* the identity
        // record: what it says is what is true, including that this node has no key
        // and is not a router.
        directory.applyNodeInfo(
            NodeInfo(num = GETAFE_ROUTER, user = User(short_name = "gt2a", public_key = PUBLIC_KEY)),
        )
        val handshake = directory.snapshot(emptySet()).node(GETAFE_ROUTER)!!
        assertTrue(handshake.hasPublicKey)
        assertEquals("CLIENT", handshake.role)

        directory.applyUser(
            GETAFE_ROUTER,
            User(short_name = "gt2a", role = Config.DeviceConfig.Role.ROUTER),
        )
        val promoted = directory.snapshot(emptySet()).node(GETAFE_ROUTER)!!

        assertEquals("ROUTER", promoted.role)
        // An empty public_key inside a User that was populated is a statement, not
        // a silence - so here, and only here, the key is dropped.
        assertFalse(promoted.hasPublicKey)

        // And the protocol's own defaults are values rather than absences: CLIENT
        // means CLIENT, the reading NodeRecord.fromProto already settled. Reading
        // the sentinel as "unset" here would leave this node a ROUTER for ever.
        directory.applyUser(GETAFE_ROUTER, User(short_name = "gt2a", public_key = PUBLIC_KEY))
        val demoted = directory.snapshot(emptySet()).node(GETAFE_ROUTER)!!

        assertEquals("CLIENT", demoted.role)
        assertTrue(demoted.hasPublicKey)
    }

    @Test
    fun `a user heard in traffic creates a record the database never mentioned`() {
        // Nodes appear in traffic before they appear in the database.
        directory.applyUser(PINTO, User(long_name = "Pinto Norte", short_name = "pnt1"))

        val snapshot = directory.snapshot(emptySet())
        val record = snapshot.node(PINTO)!!

        assertEquals(1, snapshot.count)
        assertEquals(PINTO, record.num)
        assertEquals("pnt1", record.shortName)
        assertEquals("Pinto Norte", record.longName)
        assertNull(record.dbPosition)
        assertNull(record.lastHeardEpochSeconds)
    }

    @Test
    fun `an uptime that falls back is counted as a restart`() {
        directory.applyTelemetry(
            GETAFE_ROUTER,
            Telemetry(device_metrics = DeviceMetrics(uptime_seconds = 86_400)),
            TELEMETRY_AT_MILLIS,
        )
        directory.applyTelemetry(
            GETAFE_ROUTER,
            Telemetry(device_metrics = DeviceMetrics(uptime_seconds = 12)),
            TELEMETRY_AT_MILLIS + 60_000,
        )

        val record = directory.snapshot(emptySet()).telemetry(GETAFE_ROUTER)!!

        assertEquals(12, record.lastUptimeSeconds)
        assertEquals(1, record.observedRestartCount)
        // Uptime is a restart detector, not a metric: it is never charted.
        assertFalse(record.metrics.containsKey("uptime_seconds"))
    }

    @Test
    fun `device, environment and power metrics are all recorded under their protobuf names`() {
        // Keys are shown verbatim, so they must be the schema's own.
        directory.applyTelemetry(
            GETAFE_ROUTER,
            Telemetry(
                device_metrics = DeviceMetrics(
                    battery_level = 87,
                    voltage = 4.05f,
                    channel_utilization = 12.5f,
                    air_util_tx = 3.2f,
                ),
            ),
            TELEMETRY_AT_MILLIS,
        )
        directory.applyTelemetry(
            TOLEDO_ESTACION,
            Telemetry(
                environment_metrics = EnvironmentMetrics(temperature = 21.5f, voltage = 3.9f, current = 0.42f),
            ),
            TELEMETRY_AT_MILLIS,
        )
        directory.applyTelemetry(
            TOLEDO_ALTO,
            Telemetry(
                power_metrics = PowerMetrics(
                    ch1_voltage = 3.9f,
                    ch1_current = 120f,
                    ch8_voltage = 12.1f,
                    ch8_current = 900f,
                ),
            ),
            TELEMETRY_AT_MILLIS,
        )
        val snapshot = directory.snapshot(emptySet())

        val device = snapshot.telemetry(GETAFE_ROUTER)!!.metrics
        // Exact key set: an absent optional field must not become a zero reading,
        // and no key may be spelled the way the JSON API spells it.
        assertEquals(setOf("battery_level", "voltage", "channel_utilization", "air_util_tx"), device.keys)
        assertEquals(87f, device.getValue("battery_level").stats.lastVal, 1e-6f)
        assertEquals(4.05f, device.getValue("voltage").stats.lastVal, 1e-6f)
        assertEquals(12.5f, device.getValue("channel_utilization").stats.lastVal, 1e-6f)
        assertEquals(3.2f, device.getValue("air_util_tx").stats.lastVal, 1e-6f)
        assertEquals(TELEMETRY_AT_MILLIS, device.getValue("air_util_tx").samples.single().atMillis)

        val environment = snapshot.telemetry(TOLEDO_ESTACION)!!.metrics
        assertEquals(setOf("temperature", "voltage", "current"), environment.keys)
        assertEquals(21.5f, environment.getValue("temperature").stats.lastVal, 1e-6f)
        assertEquals(3.9f, environment.getValue("voltage").stats.lastVal, 1e-6f)
        assertEquals(0.42f, environment.getValue("current").stats.lastVal, 1e-6f)

        val power = snapshot.telemetry(TOLEDO_ALTO)!!.metrics
        // Eight channels, not three: the last one has to be reachable too.
        assertEquals(setOf("ch1_voltage", "ch1_current", "ch8_voltage", "ch8_current"), power.keys)
        assertEquals(3.9f, power.getValue("ch1_voltage").stats.lastVal, 1e-6f)
        assertEquals(900f, power.getValue("ch8_current").stats.lastVal, 1e-6f)
    }

    @Test
    fun `clearing runtime data keeps the node database and the skip list`() {
        // Ports reset, mesh_stats.py:1100-1113. Reset is for starting a fresh
        // measurement, not for forgetting who is out there.
        directory.applyNodeInfo(getafeInDatabase())
        directory.applyNodeInfo(NodeInfo(num = TOLEDO_ESTACION, user = User(short_name = "to2a")))
        directory.setLocalNodeNum(LOCAL_NODE)
        directory.markLoaded(LOADED_AT_MILLIS)
        now = LIVE_AT_MILLIS
        directory.applyPosition(GETAFE_ROUTER, position(TOLEDO_LAT_I, TOLEDO_LON_I, altitude = 529))
        directory.applyTelemetry(
            GETAFE_ROUTER,
            Telemetry(device_metrics = DeviceMetrics(battery_level = 87)),
            LIVE_AT_MILLIS,
        )

        directory.clearRuntimeData()
        val after = directory.snapshot(setOf(TOLEDO_ESTACION))

        assertEquals(2, after.count)
        assertEquals("gt2a", after.shortName(GETAFE_ROUTER))
        assertEquals(LOCAL_NODE, after.localNodeNum)
        assertEquals(LOADED_AT_MILLIS, after.loadedAtMillis)
        assertNull(after.telemetry(GETAFE_ROUTER))
        // The live position is gone; what is left is what the database said.
        val info = after.locationInfo(GETAFE_ROUTER, MADRID_LOCAL)
        assertEquals(PositionSource.DB, info.source)
        assertEquals(GETAFE_LAT, info.lat!!, 1e-9)
        // And the skip list still decides which candidates are offered.
        assertEquals(listOf(GETAFE_ROUTER), after.matchingNodeNums(0x2a))
    }

    @Test
    fun `a snapshot does not change when the directory afterwards does`() {
        // The snapshot crosses to the UI thread. If it shared the directory's maps,
        // the engine would be mutating live data underneath a running composition.
        directory.applyNodeInfo(getafeInDatabase())
        val snapshot = directory.snapshot(emptySet())

        now = LIVE_AT_MILLIS
        directory.applyNodeInfo(NodeInfo(num = TOLEDO_ESTACION, user = User(short_name = "to2a")))
        directory.applyPosition(GETAFE_ROUTER, position(TOLEDO_LAT_I, TOLEDO_LON_I, altitude = 529))
        directory.applyTelemetry(
            GETAFE_ROUTER,
            Telemetry(device_metrics = DeviceMetrics(battery_level = 87)),
            LIVE_AT_MILLIS,
        )

        assertEquals(1, snapshot.count)
        assertNull(snapshot.node(TOLEDO_ESTACION))
        assertNull(snapshot.telemetry(GETAFE_ROUTER))
        assertEquals(PositionSource.DB, snapshot.locationInfo(GETAFE_ROUTER, MADRID_LOCAL).source)

        // The directory itself did move on, so the assertions above are about the
        // copy holding still, not about nothing having happened.
        val later = directory.snapshot(emptySet())
        assertEquals(2, later.count)
        assertNotNull(later.telemetry(GETAFE_ROUTER))
        assertEquals(PositionSource.CURRENT, later.locationInfo(GETAFE_ROUTER, MADRID_LOCAL).source)
    }

    @Test
    fun `the local position is resolved through the local node number`() {
        assertNull(directory.snapshot(emptySet()).localPosition())

        directory.setLocalNodeNum(LOCAL_NODE)
        // Known, but nothing has been heard from it yet.
        assertNull(directory.snapshot(emptySet()).localPosition())

        // A decoy, to catch a local position read from whichever node is handy.
        directory.applyPosition(GETAFE_ROUTER, position(GETAFE_LAT_I, GETAFE_LON_I, altitude = 622))
        directory.applyPosition(LOCAL_NODE, position(MADRID_LAT_I, MADRID_LON_I, altitude = 667))

        val local = directory.snapshot(emptySet()).localPosition()!!
        assertEquals(MADRID_LOCAL.lat, local.lat, 1e-9)
        assertEquals(MADRID_LOCAL.lon, local.lon, 1e-9)
    }

    @Test
    fun `the directory and its snapshot agree on where we are`() {
        // Two callers, one precedence rule. The engine asks the directory per packet
        // (a snapshot copies every map); the screens ask the snapshot. They must not
        // drift, and a live report must beat the database entry in both.
        val directory = NodeDirectory(TimeSource { 5_000L })
        directory.setLocalNodeNum(SENDER)
        directory.applyNodeInfo(
            NodeInfo(
                num = SENDER,
                position = Position(latitude_i = 398628316, longitude_i = -40273231, altitude = 600),
            ),
        )
        assertEquals(directory.localPosition(), directory.snapshot(emptySet()).localPosition())

        directory.applyPosition(SENDER, Position(latitude_i = 403057734, longitude_i = -37325611, altitude = 610))
        assertEquals(directory.localPosition(), directory.snapshot(emptySet()).localPosition())
        assertEquals(40.3057734, directory.localPosition()!!.lat, 1e-7)
    }

    @Test
    fun `with no local node number there is no local position`() {
        assertNull(NodeDirectory(TimeSource { 5_000L }).localPosition())
    }
}
