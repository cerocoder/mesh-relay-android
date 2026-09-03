package com.cerocoder.meshrelay.stats.model

/** Which store an identity was resolved from. */
enum class IdentitySource { AIR, DB, NONE }

/**
 * A node's identity as the interface should show it, and where it came from.
 *
 * Resolved **per record**, not per field: when the air store holds this node, all five
 * fields come from it, blanks included. That is the owner's ruling, taken against a
 * per-field alternative, and it is what lets one label and one timestamp describe the
 * whole block instead of five of each. [AirNodeRecord.folding]'s merge rule is what
 * keeps it safe - an air record accumulates, so a thin broadcast cannot empty one.
 */
data class NodeIdentity(
    val source: IdentitySource,
    val longName: String?,
    val shortName: String?,
    val hwModel: String?,
    val role: String?,
    val hasPublicKey: Boolean,
    /** Null only when [source] is [IdentitySource.NONE]. */
    val receivedAtMillis: Long?,
) {
    companion object {
        val NONE = NodeIdentity(IdentitySource.NONE, null, null, null, null, false, null)
    }
}
