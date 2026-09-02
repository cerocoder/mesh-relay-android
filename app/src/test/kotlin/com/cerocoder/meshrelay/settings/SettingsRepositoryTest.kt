package com.cerocoder.meshrelay.settings

import com.cerocoder.meshrelay.stats.NodeId
import com.cerocoder.meshrelay.stats.SortMode
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

    private fun repo(store: SettingsStore) = SettingsRepository(store)

    @Test
    fun `defaults match the terminal tool and the local mesh`() {
        val settings = repo(FakeStore()).settings.value
        assertEquals(LanguageOption.SYSTEM, settings.language)
        assertEquals(GaugeMode.SIMPLE, settings.gaugeMode)
        assertEquals(SortMode.PACKETS, settings.defaultSortMode)
        assertEquals(MapProvider.GOOGLE, settings.mapProvider)
        assertEquals("https://meshview.meshtastic.es", settings.meshviewUrl)
        // 24 hour by default: this mesh is based in Spain, where the 24-hour
        // clock is standard.
        assertEquals(TimeFormat.TWENTY_FOUR_HOUR, settings.timeFormat)
        assertEquals(false, settings.keepScreenOn)
        assertEquals(true, settings.backgroundCollection)
        // On by default: the phone is what the surveyor is carrying, and the node's
        // position is the coarser answer. Off is the escape hatch, not the norm.
        assertEquals(true, settings.usePhoneLocation)
    }

    @Test
    fun `use phone location persists both ways`() {
        // Both directions, because a default of true means "off" is the only value a
        // write can actually be seen to carry: a store that dropped the write would
        // still read back true and pass a one-directional test.
        val store = FakeStore()
        repo(store).update { it.copy(usePhoneLocation = false) }
        assertEquals(false, repo(store).settings.value.usePhoneLocation)

        repo(store).update { it.copy(usePhoneLocation = true) }
        assertEquals(true, repo(store).settings.value.usePhoneLocation)
    }

    @Test
    fun `map provider persists both ways`() {
        // Both directions, for the same reason the phone-location test above gives:
        // the default is GOOGLE, so a store that dropped the write would still read
        // back GOOGLE and pass a one-directional test.
        val store = FakeStore()
        repo(store).update { it.copy(mapProvider = MapProvider.OPEN_STREET_MAP) }
        assertEquals(MapProvider.OPEN_STREET_MAP, repo(store).settings.value.mapProvider)

        repo(store).update { it.copy(mapProvider = MapProvider.GOOGLE) }
        assertEquals(MapProvider.GOOGLE, repo(store).settings.value.mapProvider)
    }

    @Test
    fun `time format persists both ways`() {
        // Both directions, for the same reason the map-provider test above gives:
        // the default is TWENTY_FOUR_HOUR, so a store that dropped the write
        // would still read back TWENTY_FOUR_HOUR and pass a one-directional test.
        val store = FakeStore()
        repo(store).update { it.copy(timeFormat = TimeFormat.TWELVE_HOUR) }
        assertEquals(TimeFormat.TWELVE_HOUR, repo(store).settings.value.timeFormat)

        repo(store).update { it.copy(timeFormat = TimeFormat.TWENTY_FOUR_HOUR) }
        assertEquals(TimeFormat.TWENTY_FOUR_HOUR, repo(store).settings.value.timeFormat)
    }

    @Test
    fun `an update reaches both the flow and storage before it returns`() {
        // The flow must not wait on disk: a settings toggle that lags behind the tap
        // by a write reads as a broken control. Nor may the write lag behind the
        // flow - it used to be posted to a coroutine scope, and a change made in the
        // moment before the process died was simply lost (field issue F-2).
        val store = FakeStore()
        val subject = repo(store)
        subject.update { it.copy(gaugeMode = GaugeMode.COMPLEX) }
        assertEquals(GaugeMode.COMPLEX, subject.settings.value.gaugeMode)
        assertEquals(1, store.writes)
        assertEquals("COMPLEX", store.getString("gauge_mode", "unwritten"))
    }

    @Test
    fun `skipped nodes round trip through storage`() {
        val store = FakeStore()
        val first = repo(store)
        first.addSkippedRelayNode(0x9e75f1a4.toInt())

        val reopened = repo(store)
        assertTrue(reopened.skippedRelayNodes.value.contains(0x9e75f1a4.toInt()))
    }

    @Test
    fun `skipped nodes are stored in the notation the terminal tool accepts`() {
        // --skip-relay takes !xxxxxxxx, and a value should be movable between the
        // two tools by hand.
        val store = FakeStore()
        repo(store).addSkippedRelayNode(0x9e75f1a4.toInt())
        assertEquals(setOf("!9e75f1a4"), store.getStringSet("skipped_relay_nodes", emptySet()))
    }

    @Test
    fun `clearing by relay byte removes only nodes sharing that low byte`() {
        val subject = repo(FakeStore())
        subject.addSkippedRelayNode(0x9e75f1a4.toInt())
        subject.addSkippedRelayNode(0x11223344)
        subject.clearSkippedForRelay(0xa4)
        assertEquals(setOf(0x11223344), subject.skippedRelayNodes.value)
    }

    @Test
    fun `clearing relay ff also releases a node whose number ends in the byte zero`() {
        // Geo.lastByteOfNodeNum maps a trailing 0x00 to 0xFF, a firmware convention
        // also used by candidate matching elsewhere in the app. A plain
        // "nodeNum and 0xFF" here would let such a node be skipped against relay
        // 0xFF but never un-skipped from it.
        val subject = repo(FakeStore())
        subject.addSkippedRelayNode(0x9e75f100.toInt())
        subject.clearSkippedForRelay(0xFF)
        assertEquals(emptySet<Int>(), subject.skippedRelayNodes.value)
    }

    @Test
    fun `clearing all skipped nodes empties the list regardless of relay byte`() {
        // The settings screen's global clear. Fails on an implementation that
        // delegates to clearSkippedForRelay for one byte - the two nodes below end
        // in different bytes on purpose - and on one that only mutates the flow
        // without persisting, which would bring the list back on the next launch.
        val store = FakeStore()
        val subject = repo(store)
        subject.addSkippedRelayNode(0x9e75f1a4.toInt())
        subject.addSkippedRelayNode(0x11223344)

        subject.clearAllSkippedNodes()

        assertEquals(emptySet<Int>(), subject.skippedRelayNodes.value)
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
        assertEquals(setOf(0x9e75f1a4.toInt()), SettingsRepository(store).skippedRelayNodes.value)
    }
}
