package com.cerocoder.meshrelay.stats.model

import org.meshtastic.proto.Config
import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.User

/**
 * One node's identity as it announced itself over the air, folded from every
 * NODEINFO_APP packet heard from it.
 *
 * Identity only. Nothing measured lives here: a position heard over the air is
 * already in [PositionHistory], and signal is already in the relay and neighbour
 * statistics. A NODEINFO_APP payload is a `User` message, which carries no
 * position, no SNR and no last-heard time - those four fields exist only in the
 * radio's own database and stay in [NodeRecord].
 */
data class AirNodeRecord(
    val num: Int,
    val longName: String?,
    val shortName: String?,
    val hwModel: String?,
    val role: String?,
    val hasPublicKey: Boolean,
    /** When the most recent NODEINFO_APP for this node was folded in. */
    val receivedAtMillis: Long,
) {
    companion object {
        /**
         * [existing] updated with what [user] carries, or a new record when there is
         * none. The stamp moves on every call: it records when this node was last
         * heard identifying itself, not when its identity last changed.
         *
         * **A field the packet does not carry keeps the value it had.** [User]'s
         * scalar fields are Wire-generated as non-null with their proto3 defaults
         * (`""` for the two name fields, [HardwareModel.UNSET] for `hw_model`,
         * [Config.DeviceConfig.Role.CLIENT] for `role`, an empty `ByteString` for
         * `public_key`), so every rule below is about reading a default as absence:
         *
         *  - `long_name`, `short_name`: empty means unset. Unset and empty are the
         *    same bytes on the wire.
         *  - `hw_model`: [HardwareModel.UNSET] is the schema's own "not known".
         *  - `public_key`: empty means absent. Presence is remembered once observed
         *    and never unlearned - the same rule, for the same reason, as
         *    `NodeDirectory.merge()`.
         *  - `role`: **never** treated as absent. `CLIENT` is proto3's default and
         *    cannot be told from an omitted field. Treating it as absent would mean a
         *    node that genuinely changes ROUTER to CLIENT never updates. A real role
         *    change is worth more than a `User` that omitted the field.
         *
         * Merging rather than replacing is what makes the per-record precedence rule
         * safe: the panel shows an air record whole, so a thin broadcast that blanked
         * a field would blank the panel even though the database still knew it.
         */
        fun folding(existing: AirNodeRecord?, num: Int, user: User, atMillis: Long): AirNodeRecord =
            AirNodeRecord(
                num = num,
                longName = user.long_name.orBlankKeep(existing?.longName),
                shortName = user.short_name.orBlankKeep(existing?.shortName),
                hwModel = user.hw_model.takeIf { it != HardwareModel.UNSET }?.name
                    ?: existing?.hwModel,
                role = user.role.name,
                hasPublicKey = user.public_key.size > 0 || existing?.hasPublicKey == true,
                receivedAtMillis = atMillis,
            )

        /** This string when it carries anything, [previous] when it is null or empty. */
        private fun String?.orBlankKeep(previous: String?): String? =
            if (this.isNullOrEmpty()) previous else this
    }
}
