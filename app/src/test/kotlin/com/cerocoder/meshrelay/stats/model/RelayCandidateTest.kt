package com.cerocoder.meshrelay.stats.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayCandidateTest {

    private fun stats(vararg values: Float): SignalStats =
        values.fold(SignalStats.EMPTY) { acc, v -> acc.plus(v) }

    private fun source(
        nodeNum: Int,
        shortName: String = "n",
        role: String? = "CLIENT",
        direct: SignalStats = SignalStats.EMPTY,
    ) = CandidateSource(nodeNum, shortName, role, direct, dbSnr = null, hopsAway = null)

    @Test
    fun `a gap inside six decibels is consistent`() {
        val ranked = RelayCandidates.rank(-71f, listOf(source(1, direct = stats(-69f))))
        assertEquals(CandidateVerdict.CONSISTENT, ranked.single().verdict)
        assertEquals(2f, ranked.single().gapDb!!, 1e-4f)
    }

    @Test
    fun `exactly six decibels is still consistent`() {
        // The boundary is inclusive at the lower verdict. Pinned at the exact value
        // because a > / >= slip here is invisible in every other test.
        val ranked = RelayCandidates.rank(-71f, listOf(source(1, direct = stats(-65f))))
        assertEquals(CandidateVerdict.CONSISTENT, ranked.single().verdict)
    }

    @Test
    fun `exactly fifteen decibels is uncertain, not inconsistent`() {
        val ranked = RelayCandidates.rank(-71f, listOf(source(1, direct = stats(-56f))))
        assertEquals(CandidateVerdict.UNCERTAIN, ranked.single().verdict)
    }

    @Test
    fun `beyond fifteen decibels is inconsistent`() {
        val ranked = RelayCandidates.rank(-71f, listOf(source(1, direct = stats(-104f))))
        assertEquals(CandidateVerdict.INCONSISTENT, ranked.single().verdict)
    }

    @Test
    fun `a single direct packet still convicts on a large gap`() {
        // The owner's correction, pinned: a router forwards rather than talks, so a
        // sample-count gate would leave the likeliest candidate permanently unjudged.
        // Nothing but distance explains 33 dB, from one packet or from two hundred.
        val ranked = RelayCandidates.rank(-71f, listOf(source(1, direct = stats(-104f))))
        assertEquals(CandidateVerdict.INCONSISTENT, ranked.single().verdict)
        assertEquals(1, ranked.single().directPacketCount)
    }

    @Test
    fun `a node never heard directly is unknown, not excluded`() {
        val ranked = RelayCandidates.rank(-71f, listOf(source(1)))
        assertEquals(CandidateVerdict.UNKNOWN, ranked.single().verdict)
        assertNull(ranked.single().gapDb)
        assertNull(ranked.single().directRssiAvg)
    }

    @Test
    fun `every candidate is unknown when the relay has no average yet`() {
        val ranked = RelayCandidates.rank(null, listOf(source(1, direct = stats(-69f))))
        assertEquals(CandidateVerdict.UNKNOWN, ranked.single().verdict)
        assertNull(ranked.single().gapDb)
    }

    @Test
    fun `unknown outranks inconsistent`() {
        // Absence of evidence beats evidence of absence: a silent router is a better
        // prospect than a node measured 33 dB away.
        val ranked = RelayCandidates.rank(
            relayRssiAvg = -71f,
            sources = listOf(source(1, direct = stats(-104f)), source(2)),
        )
        assertEquals(listOf(2, 1), ranked.map { it.nodeNum })
    }

    @Test
    fun `the full order is consistent, uncertain, unknown, inconsistent`() {
        val ranked = RelayCandidates.rank(
            relayRssiAvg = -71f,
            sources = listOf(
                source(4, direct = stats(-104f)),   // inconsistent
                source(3),                          // unknown
                source(2, direct = stats(-61f)),    // uncertain, gap 10
                source(1, direct = stats(-69f)),    // consistent, gap 2
            ),
        )
        assertEquals(listOf(1, 2, 3, 4), ranked.map { it.nodeNum })
    }

    @Test
    fun `within a verdict the smaller gap comes first, then the node number`() {
        val ranked = RelayCandidates.rank(
            relayRssiAvg = -71f,
            sources = listOf(
                source(9, direct = stats(-66f)),   // gap 5
                source(2, direct = stats(-69f)),   // gap 2
                source(1, direct = stats(-69f)),   // gap 2, lower number
            ),
        )
        assertEquals(listOf(1, 2, 9), ranked.map { it.nodeNum })
    }

    @Test
    fun `only CLIENT_MUTE is marked as unable to forward`() {
        // Cited from firmware/src/mesh/FloodingRouter.cpp:129-133. An earlier draft
        // of the design also named CLIENT_HIDDEN; the firmware does not.
        val ranked = RelayCandidates.rank(
            relayRssiAvg = -71f,
            sources = listOf(
                source(1, role = "CLIENT_MUTE", direct = stats(-69f)),
                source(2, role = "CLIENT_HIDDEN", direct = stats(-69f)),
                source(3, role = "ROUTER", direct = stats(-69f)),
                source(4, role = null, direct = stats(-69f)),
            ),
        )
        assertEquals(listOf(true, false, false, false), ranked.sortedBy { it.nodeNum }.map { it.cannotForward })
    }
}
