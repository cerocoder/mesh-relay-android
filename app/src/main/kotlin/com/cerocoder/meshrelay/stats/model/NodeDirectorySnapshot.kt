package com.cerocoder.meshrelay.stats.model

import com.cerocoder.meshrelay.stats.Geo

/**
 * An immutable view of the node directory, taken at one instant and safe to hand
 * to the interface thread. Every map here is already a copy made by
 * `NodeDirectory.snapshot`; nothing in this class mutates.
 *
 * The three maps behind the constructor are not exposed: callers ask questions
 * ([locationInfo], [matchingNodeNums], [telemetry]) rather than reading raw
 * history, so the precedence rules below live in exactly one place.
 */
class NodeDirectorySnapshot(
    val nodes: Map<Int, NodeRecord>,
    /**
     * Identity heard over the air, keyed by node number - what nodes have said about
     * themselves in NODEINFO_APP packets, as opposed to what the radio's own database
     * says in [nodes]. The two are separate accounts of the same mesh and are kept
     * apart so the interface can name which one it is showing.
     */
    val airNodes: Map<Int, AirNodeRecord>,
    val loadedAtMillis: Long?,
    val localNodeNum: Int?,
    positions: Map<Int, PositionHistory>,
    telemetry: Map<Int, TelemetryRecord>,
    skipped: Set<Int>,
) {
    private val positionsByNode = positions
    private val telemetryByNode = telemetry
    private val skippedNodes = skipped

    val count: Int get() = nodes.size

    fun node(nodeNum: Int): NodeRecord? = nodes[nodeNum]

    /** The node's short name, or `""` when the database has never named it. */
    fun shortName(nodeNum: Int): String = nodes[nodeNum]?.shortName ?: ""

    fun telemetry(nodeNum: Int): TelemetryRecord? = telemetryByNode[nodeNum]

    /**
     * Every node whose number could have produced this relay byte, skip-list
     * applied. Ports find_matching_node_nums, mesh_stats.py:704-712.
     *
     * A relay identifies itself by one byte, so several nodes can answer to it;
     * [Geo.lastByteOfNodeNum] decides which, including the firmware's substitution
     * of `0xff` for a low byte of `0x00`.
     *
     * Sorted ascending, which the original had no reason to do: the detail screen
     * numbers the candidates `[1]`, `[2]`, and drawing them in a hash map's order
     * would let the numbering change between recompositions.
     */
    fun matchingNodeNums(relayByte: Int): List<Int> = nodes.keys
        .filter { Geo.lastByteOfNodeNum(it) == relayByte && it !in skippedNodes }
        .sorted()

    /**
     * The relay's short name when exactly one node can be behind the byte, `""`
     * otherwise. Ports get_node_name, mesh_stats.py:734-750.
     *
     * With two candidates there is no name to show; naming either would present a
     * guess as a fact. Skipping candidates is how the user resolves the ambiguity,
     * so the skip list is applied before uniqueness is judged.
     */
    fun uniqueRelayName(relayByte: Int): String {
        val matches = matchingNodeNums(relayByte)
        return if (matches.size == 1) shortName(matches[0]) else ""
    }

    /** Where this device is, as far as the mesh has told it. */
    fun localPosition(): LatLon? = localPositionOf(localNodeNum, positionsByNode, nodes)

    /**
     * Where [nodeNum] is relative to [from]. Ports get_node_location_info,
     * mesh_stats.py:830-919, and its precedence is exact:
     *
     * 1. A position heard during the session wins - the database entry can be days
     *    old, and the source label is what tells the user which they are reading.
     * 2. Otherwise the node database entry, whose timestamp is when the node was
     *    last heard and which carries no precision at all.
     * 3. The altitude is reported either way, even when the coordinates are missing.
     * 4. Without coordinates there is no distance and no direction.
     * 5. With coordinates and a local position, the distance is the haversine one -
     *    but the direction is withheld when the obfuscation radius is at least as
     *    large as the separation, because a node 300 m away that published to 13
     *    bits of precision is somewhere inside a 2.9 km circle and any arrow drawn
     *    would be invented.
     *
     * [PositionHistory.newestWithCoordinates] returns the newest report carrying
     * coordinates, altitude required or not, so a live report is preferred over
     * the database the moment it has coordinates at all. The quirk this used to
     * preserve from the Python original - requiring both fields, so a live report
     * with coordinates alone fell through to the database and was labelled `DB` -
     * has been removed deliberately, at the owner's instruction: a fixed node that
     * never broadcasts an altitude was showing a stale database position for ever.
     */
    fun locationInfo(nodeNum: Int, from: LatLon?): LocationInfo {
        val live = positionsByNode[nodeNum]?.newestWithCoordinates
        val stored = if (live == null) nodes[nodeNum]?.dbPosition else null
        val report = live ?: stored ?: return LocationInfo.EMPTY

        val source = if (live != null) PositionSource.CURRENT else PositionSource.DB
        // The node database does not carry precision_bits; only a heard report does.
        val precisionBits = if (live != null) report.precisionBits else null

        val lat = report.latitude
        val lon = report.longitude
        if (lat == null || lon == null) {
            // Only the database entry can reach here now: a live report already
            // passed newestWithCoordinates' hasCoordinates test, so lat and lon are
            // both non-null whenever live is what won above.
            return LocationInfo.EMPTY.copy(
                altitude = report.altitude,
                source = source,
                atMillis = report.atMillis,
            )
        }

        // Describes the node's own position, so it does not depend on where the
        // observer is and is filled in before the observer is even consulted.
        val obfuscationRadiusMeters = Geo.obfuscationRadiusMeters(precisionBits)

        var distanceKm: Double? = null
        var direction = Direction.UNKNOWN
        if (from != null) {
            val distance = Geo.haversineKm(from.lat, from.lon, lat, lon)
            distanceKm = distance
            direction = if (obfuscationRadiusMeters != null && obfuscationRadiusMeters >= distance * 1000.0) {
                Direction.UNKNOWN
            } else {
                Geo.directionOf(Geo.bearingDegrees(from, LatLon(lat, lon)))
            }
        }

        return LocationInfo(
            lat = lat,
            lon = lon,
            altitude = report.altitude,
            distanceKm = distanceKm,
            obfuscationRadiusMeters = obfuscationRadiusMeters,
            direction = direction,
            source = source,
            atMillis = report.atMillis,
        )
    }
}
