package com.cerocoder.meshrelay.ui.nav

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/**
 * The whole navigation model: a strict stack of [Screen]s, backed by Compose
 * state so the host recomposes when it changes.
 *
 * Hand-rolled rather than `navigation-compose`. Six destinations, no deep links,
 * no nested graphs and no arguments that are not two integers - against that, a
 * navigation library buys route strings, an argument-encoding scheme and a
 * second dependency in a version chain that already holds together only as a
 * whole.
 *
 * The stack always holds at least one entry. [pop] refuses to empty it and says
 * so, which is what lets `BackHandler` hand the press back to the system at the
 * root instead of trapping the user inside the app.
 */
class BackStack private constructor(initial: List<Screen>) {

    init {
        require(initial.isNotEmpty()) { "a back stack needs at least one entry" }
    }

    constructor(initial: Screen) : this(listOf(initial))

    private val screens = mutableStateListOf<Screen>().apply { addAll(initial) }

    /** Bottom-to-top, root first. A read-only view of the live list. */
    val entries: List<Screen> get() = screens

    val current: Screen get() = screens.last()

    val canGoBack: Boolean get() = screens.size > 1

    fun push(screen: Screen) {
        screens.add(screen)
    }

    /**
     * @return true if a screen was removed, false at the root. The caller needs
     *   the difference: at the root the back press belongs to the system, which
     *   closes the app, and swallowing it there would leave no way out.
     */
    fun pop(): Boolean {
        if (screens.size <= 1) return false
        screens.removeAt(screens.lastIndex)
        return true
    }

    /**
     * Switch the tabbed section to [tab].
     *
     * This **replaces** the [Screen.Main] entry rather than pushing a new one,
     * and drops everything stacked above it. Pushing instead would mean a minute
     * of tapping between Relays and Neighbours builds forty entries, and leaving
     * the section would then take forty back presses. Dropping what is above it
     * is the other half of the same rule: a tab tap from a detail screen means
     * "show me that list", so the detail screen goes.
     *
     * What sits *below* the main entry is deliberately untouched. The device
     * list is the app's root and there is no other way back to it - only the
     * back button leaves the tabbed section - so a `clear(); add(Main(tab))`
     * reading of "replaces the root" would strand the user with no way to pick
     * a different node.
     */
    fun selectTab(tab: MainTab) {
        val mainIndex = screens.indexOfFirst { it is Screen.Main }
        if (mainIndex < 0) {
            screens.add(Screen.Main(tab))
            return
        }
        while (screens.lastIndex > mainIndex) {
            screens.removeAt(screens.lastIndex)
        }
        screens[mainIndex] = Screen.Main(tab)
    }

    internal companion object {
        fun of(screens: List<Screen>): BackStack = BackStack(screens)
    }
}

// One entry is three integers: a tag saying which variant it is, then up to two
// arguments. Integers rather than the Screen objects themselves because a
// rememberSaveable value goes into a Bundle, which takes primitives and Parcelables
// and refuses anything else - and it refuses it at rotation time, on a device,
// which is the worst place to find out.
private const val FIELDS_PER_ENTRY = 3

private const val TAG_DEVICES = 0
private const val TAG_MAIN = 1
private const val TAG_SETTINGS = 2
private const val TAG_DETAIL_RELAY = 3
private const val TAG_DETAIL_NEIGHBOUR = 4
private const val TAG_REMOTE_NODE = 5

/**
 * [Screen.RemoteNode.viaRelayByte] is nullable and a relay byte never is: the
 * firmware reports it in one unsigned byte, and [com.cerocoder.meshrelay.stats.Geo]'s
 * own mapping substitutes `0xff` for `0x00`, so the value is always `0x01..0xff`.
 * A negative sentinel therefore cannot collide with a real byte.
 */
private const val NO_RELAY_BYTE = -1

/**
 * Encodes the stack for `rememberSaveable`, so a rotation - or a process death
 * with the activity restored from its saved state - comes back to the same
 * screen rather than to the device list.
 */
val backStackSaver: Saver<BackStack, Any> = listSaver(
    save = { stack -> stack.entries.flatMap { encode(it) } },
    restore = { saved ->
        @Suppress("UNCHECKED_CAST")
        val ints = saved as List<Int>
        val screens = ints.chunked(FIELDS_PER_ENTRY).mapNotNull(::decode)
        // A saved bundle can outlive the code that wrote it: an app updated while
        // its activity was in the background is restored from state an older
        // version produced. An unreadable entry is dropped rather than thrown on,
        // and a stack with nothing left in it falls back to the app's own root.
        BackStack.of(screens.ifEmpty { listOf(Screen.Devices) })
    },
)

private fun encode(screen: Screen): List<Int> = when (screen) {
    Screen.Devices -> listOf(TAG_DEVICES, 0, 0)
    is Screen.Main -> listOf(TAG_MAIN, screen.tab.ordinal, 0)
    Screen.Settings -> listOf(TAG_SETTINGS, 0, 0)
    is Screen.Detail -> when (val subject = screen.subject) {
        // The two subjects get their own tags rather than a shared tag plus a
        // discriminator field: a Relay's argument is a byte and a Neighbour's is a
        // full node number, and nothing about the numbers themselves tells them
        // apart if the tag does not.
        is DetailSubject.Relay -> listOf(TAG_DETAIL_RELAY, subject.relayByte, 0)
        is DetailSubject.Neighbour -> listOf(TAG_DETAIL_NEIGHBOUR, subject.nodeNum, 0)
    }
    is Screen.RemoteNode ->
        listOf(TAG_REMOTE_NODE, screen.nodeNum, screen.viaRelayByte ?: NO_RELAY_BYTE)
}

/** `null` for anything this version cannot read - see [backStackSaver]. */
private fun decode(fields: List<Int>): Screen? {
    if (fields.size != FIELDS_PER_ENTRY) return null
    val (tag, first, second) = fields
    return when (tag) {
        TAG_DEVICES -> Screen.Devices
        TAG_MAIN -> Screen.Main(MainTab.entries.getOrNull(first) ?: MainTab.RELAYS)
        TAG_SETTINGS -> Screen.Settings
        TAG_DETAIL_RELAY -> Screen.Detail(DetailSubject.Relay(first))
        TAG_DETAIL_NEIGHBOUR -> Screen.Detail(DetailSubject.Neighbour(first))
        TAG_REMOTE_NODE -> Screen.RemoteNode(first, second.takeIf { it != NO_RELAY_BYTE })
        else -> null
    }
}
