package com.cerocoder.meshrelay.stats.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeAnnotationsTest {

    @Test
    fun `learnt source maps to its file spelling and back`() {
        assertEquals("node", LearntSource.NODE.fileName)
        assertEquals("air", LearntSource.AIR.fileName)
        assertEquals(LearntSource.NODE, LearntSource.fromFileName("node"))
        assertEquals(LearntSource.AIR, LearntSource.fromFileName("air"))
        assertNull(LearntSource.fromFileName("x"))
    }

    @Test
    fun `annotations default to no relay, empty notes, no watch points, no averages`() {
        val annotations = NodeAnnotations(learntSource = LearntSource.AIR)

        assertFalse(annotations.knownAsRelay)
        assertEquals("", annotations.notes)
        assertTrue(annotations.watchPoints.isEmpty())
        assertNull(annotations.avgRssi)
        assertNull(annotations.avgSnr)
    }
}
