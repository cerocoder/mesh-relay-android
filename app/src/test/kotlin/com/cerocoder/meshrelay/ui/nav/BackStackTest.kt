package com.cerocoder.meshrelay.ui.nav

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BackStack] is plain snapshot-backed state with no composer involved, so all of
 * this runs on the JVM.
 *
 * Node numbers below are the fixtures the rest of this project already uses
 * (`!9e75f1a4`, relay byte `0x69`) rather than invented ones, so a failure here
 * reads against the same values as a failure anywhere else.
 */
class BackStackTest {

    private val relayDetail = Screen.Detail(DetailSubject.Relay(0x69))
    private val neighbourDetail = Screen.Detail(DetailSubject.Neighbour(0x9e75f1a4.toInt()))

    @Test
    fun `push and pop walk the stack`() {
        val stack = BackStack(Screen.Devices)
        stack.push(Screen.Main(MainTab.RELAYS))
        stack.push(relayDetail)

        // Fails if `current` reads the wrong end of the list.
        assertEquals(relayDetail, stack.current)
        assertEquals(3, stack.entries.size)

        assertTrue(stack.pop())
        // Fails if pop removes from the bottom: the stack would still end in the
        // detail screen, and the user would never leave it.
        assertEquals(Screen.Main(MainTab.RELAYS), stack.current)
        // Fails if pop reports success without removing anything.
        assertEquals(2, stack.entries.size)

        assertTrue(stack.pop())
        assertEquals(Screen.Devices, stack.current)
        assertFalse(stack.canGoBack)
    }

    @Test
    fun `pop at the root does nothing and reports it`() {
        // The activity's BackHandler needs to tell "I handled it" from "let the
        // system close the app". Swallowing the press here traps the user inside.
        val stack = BackStack(Screen.Devices)

        assertFalse(stack.canGoBack)
        // Fails if pop returns true unconditionally.
        assertFalse(stack.pop())
        // Fails if pop empties the stack - `current` would throw, or the host would
        // have nothing to render.
        assertEquals(Screen.Devices, stack.current)
        assertEquals(1, stack.entries.size)
    }

    @Test
    fun `selecting a tab replaces the root instead of pushing`() {
        val stack = BackStack(Screen.Main(MainTab.RELAYS))

        stack.selectTab(MainTab.NEIGHBOURS)
        stack.selectTab(MainTab.RELAYS)
        stack.selectTab(MainTab.NEIGHBOURS)

        // Fails if selectTab pushes: three taps would leave four entries, and a
        // minute of tab-switching would take forty back presses to leave.
        assertEquals(1, stack.entries.size)
        assertEquals(Screen.Main(MainTab.NEIGHBOURS), stack.current)
        assertFalse(stack.canGoBack)
    }

    @Test
    fun `selecting a tab from a detail screen returns to the list`() {
        val stack = BackStack(Screen.Main(MainTab.RELAYS))
        stack.push(neighbourDetail)

        stack.selectTab(MainTab.NEIGHBOURS)

        // Fails if selectTab only rewrites the top entry (leaving the detail
        // screen showing, or leaving Main(RELAYS) stranded underneath) instead of
        // dropping everything above the main entry.
        assertEquals(listOf(Screen.Main(MainTab.NEIGHBOURS)), stack.entries)
        assertFalse(stack.canGoBack)
    }

    @Test
    fun `selecting a tab keeps the device list underneath`() {
        // The device list is the app's root and the back button is the only way
        // back to it, so a tab tap must not take it away.
        val stack = BackStack(Screen.Devices)
        stack.push(Screen.Main(MainTab.RELAYS))
        stack.push(relayDetail)

        stack.selectTab(MainTab.NEIGHBOURS)

        // Fails on the naive reading of "a tab replaces the root":
        // screens.clear(); screens.add(Main(tab)) - which leaves the user with no
        // way to reach the device list and pick a different node.
        assertEquals(listOf(Screen.Devices, Screen.Main(MainTab.NEIGHBOURS)), stack.entries)
        assertTrue(stack.canGoBack)
        assertTrue(stack.pop())
        assertEquals(Screen.Devices, stack.current)
    }

    @Test
    fun `a stack survives being saved and restored`() {
        // Rotation. Every Screen variant is in here, including both detail
        // subjects and both shapes of RemoteNode.
        val all = listOf(
            Screen.Devices,
            Screen.Main(MainTab.RELAYS),
            Screen.Main(MainTab.NEIGHBOURS),
            Screen.Settings,
            Screen.Detail(DetailSubject.Relay(0x69)),
            Screen.Detail(DetailSubject.Neighbour(0x9e75f1a4.toInt())),
            Screen.RemoteNode(42, 0x69),
            Screen.RemoteNode(42, null),
        )
        val stack = BackStack(all.first())
        all.drop(1).forEach(stack::push)

        val saved = with(backStackSaver) { SaverScope { true }.save(stack) }
        assertNotNull(saved)
        val restored = backStackSaver.restore(saved!!)
        assertNotNull(restored)

        // Fails if the two detail subjects share an encoding (0x69 would come back
        // as a Neighbour, or 0x9e75f1a4 as a Relay), if viaRelayByte is dropped
        // (both RemoteNode entries would restore identically), or if the order is
        // not preserved.
        assertEquals(all, restored!!.entries)
        assertEquals(Screen.RemoteNode(42, null), restored.current)
        assertTrue(restored.canGoBack)
    }

    @Test
    fun `the saved form is made of primitives a Bundle accepts`() {
        // rememberSaveable writes into a Bundle, which refuses anything that is
        // not a primitive or a Parcelable - and refuses it at rotation time, on a
        // device. Fails if the saver ever starts storing Screen objects, which a
        // JVM round-trip alone would happily accept.
        val stack = BackStack(Screen.Devices)
        stack.push(Screen.RemoteNode(42, null))

        val saved = with(backStackSaver) { SaverScope { true }.save(stack) }

        assertTrue(saved is List<*>)
        (saved as List<*>).forEach { assertTrue("not a primitive: $it", it is Int) }
    }

    @Test
    fun `saved data this version cannot read degrades to the device list`() {
        // An app updated while its activity was in the background is restored from
        // state an older version wrote. Fails on a decoder that maps unknown data
        // with `map { decode(it)!! }`, or one that hands back an empty stack whose
        // `current` then throws.
        val unknownTag = backStackSaver.restore(listOf(Int.MAX_VALUE, 0, 0))
        assertEquals(listOf(Screen.Devices), unknownTag?.entries)

        val truncated = backStackSaver.restore(save(Screen.Main(MainTab.RELAYS)).dropLast(1))
        assertEquals(listOf(Screen.Devices), truncated?.entries)

        val empty = backStackSaver.restore(emptyList<Any>())
        assertEquals(listOf(Screen.Devices), empty?.entries)
    }

    @Test
    fun `an out-of-range tab ordinal degrades to the relay list`() {
        // Same origin as the test above: a MainTab constant removed in a later
        // version must not crash the restore of a stack that still names it. The
        // tag is taken from a real encoding rather than written out here, so this
        // keeps testing the intended thing if the tag numbering ever changes.
        val encoded = save(Screen.Main(MainTab.NEIGHBOURS)).toMutableList()
        encoded[1] = 7

        assertEquals(listOf(Screen.Main(MainTab.RELAYS)), backStackSaver.restore(encoded)?.entries)
    }

    /** The saved form of a one-entry stack, as a list of its raw fields. */
    private fun save(screen: Screen): List<Any> {
        val saved = with(backStackSaver) { SaverScope { true }.save(BackStack(screen)) }
        assertNotNull(saved)
        @Suppress("UNCHECKED_CAST")
        return saved as List<Any>
    }
}
