package com.cerocoder.meshrelay.settings

import com.cerocoder.meshrelay.stats.NodeId
import com.cerocoder.meshrelay.stats.SortMode
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeStore(
    private val strings: MutableMap<String, String> = mutableMapOf(),
    private val bools: MutableMap<String, Boolean> = mutableMapOf(),
    private val sets: MutableMap<String, Set<String>> = mutableMapOf(),
) : SettingsStore {
    var writes = 0
        private set

    override fun getString(key: String, default: String) = strings[key] ?: default
    override fun getBoolean(key: String, default: Boolean) = bools[key] ?: default
    override fun getStringSet(key: String, default: Set<String>) = sets[key] ?: default

    override fun put(strings: Map<String, String>, bools: Map<String, Boolean>, sets: Map<String, Set<String>>) {
        writes++
        this.strings.putAll(strings)
        this.bools.putAll(bools)
        this.sets.putAll(sets)
    }
}

class SettingsRepositoryTest {

    private fun repo(store: SettingsStore, scope: TestScope) = SettingsRepository(store, scope)

    @Test
    fun `defaults match the terminal tool and the local mesh`() = runTest(StandardTestDispatcher()) {
        val settings = repo(FakeStore(), this).settings.value
        assertEquals(LanguageOption.SYSTEM, settings.language)
        assertEquals(GaugeMode.SIMPLE, settings.gaugeMode)
        assertEquals(SortMode.PACKETS, settings.defaultSortMode)
        assertEquals("https://meshview.meshtastic.es", settings.meshviewUrl)
        assertEquals(false, settings.keepScreenOn)
        assertEquals(true, settings.backgroundCollection)
    }

    @Test
    fun `an update is visible immediately, before it reaches storage`() = runTest(StandardTestDispatcher()) {
        // The flow must not wait on disk: a settings toggle that lags behind the tap
        // by a write reads as a broken control.
        val store = FakeStore()
        val subject = repo(store, this)
        subject.update { it.copy(gaugeMode = GaugeMode.COMPLEX) }
        assertEquals(GaugeMode.COMPLEX, subject.settings.value.gaugeMode)
        advanceUntilIdle()
        assertEquals(1, store.writes)
    }

    @Test
    fun `skipped nodes round trip through storage`() = runTest(StandardTestDispatcher()) {
        val store = FakeStore()
        val first = repo(store, this)
        first.addSkippedRelayNode(0x9e75f1a4.toInt())
        advanceUntilIdle()

        val reopened = repo(store, this)
        assertTrue(reopened.skippedRelayNodes.value.contains(0x9e75f1a4.toInt()))
    }

    @Test
    fun `skipped nodes are stored in the notation the terminal tool accepts`() = runTest(StandardTestDispatcher()) {
        // --skip-relay takes !xxxxxxxx, and a value should be movable between the
        // two tools by hand.
        val store = FakeStore()
        repo(store, this).addSkippedRelayNode(0x9e75f1a4.toInt())
        advanceUntilIdle()
        assertEquals(setOf("!9e75f1a4"), store.getStringSet("skipped_relay_nodes", emptySet()))
    }

    @Test
    fun `clearing by relay byte removes only nodes sharing that low byte`() = runTest(StandardTestDispatcher()) {
        val subject = repo(FakeStore(), this)
        subject.addSkippedRelayNode(0x9e75f1a4.toInt())
        subject.addSkippedRelayNode(0x11223344)
        subject.clearSkippedForRelay(0xa4)
        assertEquals(setOf(0x11223344), subject.skippedRelayNodes.value)
    }

    @Test
    fun `clearing relay ff also releases a node whose number ends in the byte zero`() = runTest(StandardTestDispatcher()) {
        // Geo.lastByteOfNodeNum maps a trailing 0x00 to 0xFF, a firmware convention
        // also used by candidate matching elsewhere in the app. A plain
        // "nodeNum and 0xFF" here would let such a node be skipped against relay
        // 0xFF but never un-skipped from it.
        val subject = repo(FakeStore(), this)
        subject.addSkippedRelayNode(0x9e75f100.toInt())
        subject.clearSkippedForRelay(0xFF)
        assertEquals(emptySet<Int>(), subject.skippedRelayNodes.value)
    }

    @Test
    fun `clearing all skipped nodes empties the list regardless of relay byte`() = runTest(StandardTestDispatcher()) {
        // The settings screen's global clear. Fails on an implementation that
        // delegates to clearSkippedForRelay for one byte - the two nodes below end
        // in different bytes on purpose - and on one that only mutates the flow
        // without persisting, which would bring the list back on the next launch.
        val store = FakeStore()
        val subject = repo(store, this)
        subject.addSkippedRelayNode(0x9e75f1a4.toInt())
        subject.addSkippedRelayNode(0x11223344)

        subject.clearAllSkippedNodes()

        assertEquals(emptySet<Int>(), subject.skippedRelayNodes.value)
        advanceUntilIdle()
        assertEquals(emptySet<String>(), store.getStringSet("skipped_relay_nodes", setOf("unwritten")))
    }

    @Test
    fun `node identifiers parse with and without the leading mark`() {
        assertEquals(0x9e75f1a4.toInt(), NodeId.parse("!9e75f1a4"))
        assertEquals(0x9e75f1a4.toInt(), NodeId.parse("9e75f1a4"))
        assertEquals(0x9e75f1a4.toInt(), NodeId.parse("  !9E75F1A4 "))
        assertEquals("!9e75f1a4", NodeId.format(0x9e75f1a4.toInt()))
    }

    @Test
    fun `an unparseable identifier is rejected rather than guessed`() {
        // Stored values can come from a hand-edited preference file.
        assertNull(NodeId.parse("not a node"))
        assertNull(NodeId.parse(""))
        assertNull(NodeId.parse("!"))
        assertNull(NodeId.parse("9e75f1a4ff"))
    }

    @Test
    fun `a corrupt stored entry is dropped without losing the rest`() {
        val store = FakeStore(sets = mutableMapOf("skipped_relay_nodes" to setOf("!9e75f1a4", "rubbish")))
        val scope = TestScope(StandardTestDispatcher())
        assertEquals(setOf(0x9e75f1a4.toInt()), SettingsRepository(store, scope).skippedRelayNodes.value)
    }
}
