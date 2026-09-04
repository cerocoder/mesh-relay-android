package com.cerocoder.meshrelay.stats.model

import kotlin.math.abs

/**
 * How well a candidate's own signal matches the signal its supposed relay byte
 * delivers. Declared in the order candidates are listed, but the order is taken
 * from [sortRank] rather than from `ordinal` - reordering this enum for any other
 * reason must not silently reorder the interface.
 */
enum class CandidateVerdict(val sortRank: Int) {
    CONSISTENT(0),
    UNCERTAIN(1),

    /**
     * Nothing to compare - this node has not been heard directly this session, or
     * the relay has no average yet.
     *
     * Ranked **ahead of** [INCONSISTENT] on purpose: absence of evidence outranks
     * evidence of absence. A router forwards rather than talks, so the likeliest
     * candidate of all may be one we have never heard speak for itself.
     */
    UNKNOWN(2),
    INCONSISTENT(3),
}

/** What the caller can gather about one candidate, before any judgement. */
data class CandidateSource(
    val nodeNum: Int,
    val shortName: String,
    /** The schema's own spelling, or null when neither store has said. */
    val role: String?,
    /** Direct reception this session; [SignalStats.EMPTY] when never heard. */
    val directRssi: SignalStats,
    val dbSnr: Float?,
    val hopsAway: Int?,
)

/** One candidate, judged. */
data class RelayCandidate(
    val nodeNum: Int,
    val shortName: String,
    val role: String?,
    val directRssiAvg: Float?,
    val directPacketCount: Int,
    val gapDb: Float?,
    val verdict: CandidateVerdict,
    val dbSnr: Float?,
    val hopsAway: Int?,
    val cannotForward: Boolean,
)

object RelayCandidates {
    /**
     * Within ordinary packet-to-packet variation on one link, so a gap this small
     * says the two measurements could be of the same transmitter.
     */
    const val CONSISTENT_MAX_GAP_DB = 6.0f

    /**
     * Beyond this, roughly a fivefold difference in path distance - more than
     * fading explains, so the two measurements are of different transmitters.
     */
    const val UNCERTAIN_MAX_GAP_DB = 15.0f

    /** The only role that cannot forward: `FloodingRouter::isRebroadcaster()`. */
    private const val NON_FORWARDING_ROLE = "CLIENT_MUTE"

    fun rank(relayRssiAvg: Float?, sources: List<CandidateSource>): List<RelayCandidate> =
        sources.map { evaluate(relayRssiAvg, it) }.sortedWith(ORDER)

    private fun evaluate(relayRssiAvg: Float?, source: CandidateSource): RelayCandidate {
        val directAvg = source.directRssi.avg.takeIf { source.directRssi.hasData }
        val gap = if (directAvg != null && relayRssiAvg != null) abs(directAvg - relayRssiAvg) else null
        return RelayCandidate(
            nodeNum = source.nodeNum,
            shortName = source.shortName,
            role = source.role,
            directRssiAvg = directAvg,
            directPacketCount = source.directRssi.count,
            gapDb = gap,
            verdict = verdictFor(gap),
            dbSnr = source.dbSnr,
            hopsAway = source.hopsAway,
            cannotForward = source.role == NON_FORWARDING_ROLE,
        )
    }

    private fun verdictFor(gap: Float?): CandidateVerdict = when {
        gap == null -> CandidateVerdict.UNKNOWN
        gap <= CONSISTENT_MAX_GAP_DB -> CandidateVerdict.CONSISTENT
        gap <= UNCERTAIN_MAX_GAP_DB -> CandidateVerdict.UNCERTAIN
        else -> CandidateVerdict.INCONSISTENT
    }

    // Node number last so the order cannot change between recompositions. An
    // unknown candidate has no gap; Float.MAX_VALUE keeps it inside its own group
    // rather than letting a null sort arbitrarily.
    private val ORDER = compareBy<RelayCandidate>(
        { it.verdict.sortRank },
        { it.gapDb ?: Float.MAX_VALUE },
        { it.nodeNum },
    )
}
