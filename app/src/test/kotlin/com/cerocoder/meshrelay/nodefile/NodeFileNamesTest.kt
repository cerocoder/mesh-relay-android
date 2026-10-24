package com.cerocoder.meshrelay.nodefile

import org.junit.Assert.assertEquals
import org.junit.Test

class NodeFileNamesTest {

    @Test
    fun `file name is dated and ends with json`() {
        // 2026-09-20T18:32:04Z, the instant ExportFileNamesTest uses.
        assertEquals("meshrelay_nodes_20260920_183204.json", NodeFileNames.suggest(1_789_929_124_000L))
    }
}
