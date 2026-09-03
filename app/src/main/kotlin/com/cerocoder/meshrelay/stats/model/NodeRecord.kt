package com.cerocoder.meshrelay.stats.model

import org.meshtastic.proto.NodeInfo

/**
 * The app's own view of one node from the local node database. The UI never sees
 * Wire's generated [NodeInfo] directly - everything it needs is copied out here
 * so the interface layer does not depend on protobuf types.
 */
data class NodeRecord(
    val num: Int,
    val longName: String?,
    val shortName: String?,
    val hwModel: String?,
    val role: String?,
    val dbPosition: PositionReport?,
    val dbSnr: Float?,
    val lastHeardEpochSeconds: Int?,
    val hopsAway: Int?,
    val hasPublicKey: Boolean,
    /**
     * When the refresh that carried this record completed.
     *
     * Every record from one refresh shares one value, because they do: the radio
     * streams a whole database in a burst of a few seconds and the phone cannot tell
     * when the radio learned any of it.
     *
     * Set by [NodeDirectory.markLoaded] at commit, not here: [fromProto] has no clock
     * and must not acquire one. It constructs the record with `0L`, which is never
     * observable, because nothing outside the refresh buffer can see a record before
     * it is committed.
     */
    val receivedAtMillis: Long,
) {
    companion object {
        fun fromProto(info: NodeInfo): NodeRecord {
            val user = info.user
            return NodeRecord(
                num = info.num,
                longName = user?.long_name,
                shortName = user?.short_name,
                // Enum fields are stored as their schema name: the interface shows
                // the protocol's own vocabulary and needs no translation table.
                hwModel = user?.hw_model?.name,
                // Role itself is not an optional field on User - Wire already
                // defaults it to CLIENT, the protocol default (mesh_stats.py:1857-
                // 1869), so a user record with no role set reads as CLIENT here
                // with no special-casing.
                role = user?.role?.name,
                // The node database does not carry precision_bits, so any value
                // PositionReport.fromProto might read from the wire is discarded
                // here - leaving a stale one would suppress a direction arrow that
                // should be shown for a database-sourced position.
                dbPosition = info.position?.let { position ->
                    PositionReport.fromProto(position, atMillis = info.last_heard.toLong() * 1000L)
                        .copy(precisionBits = null)
                },
                // snr and last_heard are not optional fields, so 0 is what "unset"
                // looks like on the wire. Read literally, a node nobody has heard
                // from would claim a real 0 dB reading and a last-heard time at the
                // Unix epoch.
                dbSnr = info.snr.takeIf { it != 0f },
                lastHeardEpochSeconds = info.last_heard.takeIf { it != 0 },
                hopsAway = info.hops_away,
                hasPublicKey = (user?.public_key?.size ?: 0) > 0,
                receivedAtMillis = 0L,
            )
        }
    }
}
