package com.cerocoder.meshrelay.stats.model

/**
 * Where this device is, as far as the mesh has told it.
 *
 * Rules 1-2 of [NodeDirectorySnapshot.locationInfo]'s precedence, and the only
 * copy of them: a position heard during the session wins, because the database
 * entry can be days old; otherwise the node database entry. Requirement 19 of the
 * Graph design is exactly this and needs nothing new - no timers, no generated
 * mesh traffic.
 *
 * Two callers, deliberately. `NodeDirectory` asks it per packet, where taking a
 * snapshot to answer would copy every map in the directory. `NodeDirectorySnapshot`
 * asks it for the screens. `NodeDirectoryTest` pins that they agree.
 */
internal fun localPositionOf(
    localNodeNum: Int?,
    positions: Map<Int, PositionHistory>,
    nodes: Map<Int, NodeRecord>,
): LatLon? {
    val num = localNodeNum ?: return null
    val report = positions[num]?.newestWithCoordinates ?: nodes[num]?.dbPosition ?: return null
    val lat = report.latitude ?: return null
    val lon = report.longitude ?: return null
    return LatLon(lat, lon)
}
