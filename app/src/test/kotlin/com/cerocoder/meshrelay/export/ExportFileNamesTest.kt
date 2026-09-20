package com.cerocoder.meshrelay.export

import com.cerocoder.meshrelay.stats.SeriesKey
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportFileNamesTest {

    private val nowMillis = 1_789_502_524_000L // 2026-09-20T18:32:04Z

    @Test
    fun `a relay detail export names itself after the relay's byte`() {
        val name = ExportFileNames.suggest(ExportKind.RELAY_DETAIL, listOf(SeriesKey.Relay(0x1a)), nowMillis)
        assertEquals("meshrelay_relay_0x1a_20260920_183204.csv", name)
    }

    @Test
    fun `a neighbour detail export names itself after the node id, without the leading bang`() {
        val name = ExportFileNames.suggest(ExportKind.NEIGHBOUR_DETAIL, listOf(SeriesKey.Neighbour(0x9e75f1a4.toInt())), nowMillis)
        assertEquals("meshrelay_neighbour_9e75f1a4_20260920_183204.csv", name)
    }

    @Test
    fun `a relay list export names itself generically, even with nothing tracked yet`() {
        val name = ExportFileNames.suggest(ExportKind.RELAY_LIST, emptyList(), nowMillis)
        assertEquals("meshrelay_relays_20260920_183204.csv", name)
    }

    @Test
    fun `a neighbour list export names itself generically, even with nothing tracked yet`() {
        val name = ExportFileNames.suggest(ExportKind.NEIGHBOUR_LIST, emptyList(), nowMillis)
        assertEquals("meshrelay_neighbours_20260920_183204.csv", name)
    }
}
