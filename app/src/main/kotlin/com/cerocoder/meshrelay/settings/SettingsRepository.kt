package com.cerocoder.meshrelay.settings

import android.content.Context
import com.cerocoder.meshrelay.stats.Geo
import com.cerocoder.meshrelay.stats.NodeId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val PREFS_NAME = "mesh_relay"

private const val KEY_LANGUAGE = "language"
private const val KEY_GAUGE_MODE = "gauge_mode"
private const val KEY_DEFAULT_SORT_MODE = "default_sort_mode"
private const val KEY_MESHVIEW_URL = "meshview_url"
private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
private const val KEY_BACKGROUND_COLLECTION = "background_collection"
private const val KEY_USE_PHONE_LOCATION = "use_phone_location"
private const val KEY_SKIPPED_RELAY_NODES = "skipped_relay_nodes"

/**
 * The persistence boundary [SettingsRepository] talks to. SharedPreferences
 * sits behind this interface so the repository is unit-testable on the JVM,
 * without Robolectric.
 */
interface SettingsStore {
    fun getString(key: String, default: String): String
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getStringSet(key: String, default: Set<String>): Set<String>

    /** Applies all three maps as a single write. */
    fun put(strings: Map<String, String>, bools: Map<String, Boolean>, sets: Map<String, Set<String>>)
}

/** Thin wrapper around this app's SharedPreferences file. */
class AndroidSettingsStore(context: Context) : SettingsStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getString(key: String, default: String): String = prefs.getString(key, default) ?: default

    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    override fun getStringSet(key: String, default: Set<String>): Set<String> =
        prefs.getStringSet(key, default) ?: default

    override fun put(strings: Map<String, String>, bools: Map<String, Boolean>, sets: Map<String, Set<String>>) {
        val editor = prefs.edit()
        strings.forEach { (key, value) -> editor.putString(key, value) }
        bools.forEach { (key, value) -> editor.putBoolean(key, value) }
        sets.forEach { (key, value) -> editor.putStringSet(key, value) }
        editor.apply()
    }
}

/**
 * Reads [store] once, at construction, into in-memory [MutableStateFlow]s;
 * nothing on a screen ever touches disk. [update] and the skip-list mutators
 * change the flow and hand the new state to [store] before returning.
 *
 * Statistics deliberately do not live here: a measurement session is a
 * snapshot, and this is the only part of the app meant to survive a restart.
 */
class SettingsRepository(private val store: SettingsStore) {

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _skippedRelayNodes = MutableStateFlow(readSkippedRelayNodes())
    val skippedRelayNodes: StateFlow<Set<Int>> = _skippedRelayNodes.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        _settings.value = transform(_settings.value)
        persist()
    }

    fun addSkippedRelayNode(nodeNum: Int) {
        _skippedRelayNodes.value = _skippedRelayNodes.value + nodeNum
        persist()
    }

    fun removeSkippedRelayNode(nodeNum: Int) {
        _skippedRelayNodes.value = _skippedRelayNodes.value - nodeNum
        persist()
    }

    /**
     * Removes every skipped node whose low byte - as the relay field would
     * carry it - equals [relayByte]. Matches via [Geo.lastByteOfNodeNum]
     * rather than a plain `nodeNum and 0xFF`: the firmware never reports a
     * relay of 0x00, substituting 0xFF instead, so a node whose number ends
     * in 0x00 must be reachable from relay 0xFF's skip list too, or it could
     * be skipped but never un-skipped.
     */
    fun clearSkippedForRelay(relayByte: Int) {
        _skippedRelayNodes.value = _skippedRelayNodes.value
            .filterNot { Geo.lastByteOfNodeNum(it) == relayByte }
            .toSet()
        persist()
    }

    /**
     * Empties the skip list entirely - every relay's, not one byte's.
     *
     * The settings screen's global clear. [clearSkippedForRelay] is the same
     * operation narrowed to the nodes one relay byte could be, offered on the
     * relay's own detail screen; this one is what the settings screen guards
     * behind a confirmation, because from there the user cannot see which relays
     * they are about to un-resolve.
     */
    fun clearAllSkippedNodes() {
        _skippedRelayNodes.value = emptySet()
        persist()
    }

    /**
     * Hands the current in-memory state to [store], on the caller's thread.
     *
     * This used to be `ioScope.launch { ... }`, and the coroutine hop was a loss
     * window with nothing to gain: [AndroidSettingsStore] ends in
     * `SharedPreferences.Editor.apply()`, which is itself the platform's
     * non-blocking write and is documented as safe to call from the main thread.
     * All the hop added was a stretch of time in which a setting had been changed
     * on screen but was not yet in the editor - and if the process ended inside
     * it, the change was simply gone. It cost an install once (field issue F-2:
     * whether a crashing language setting survived a restart came down to which
     * side of this hop the process died on).
     */
    private fun persist() {
        val settingsSnapshot = _settings.value
        val skippedSnapshot = _skippedRelayNodes.value
        store.put(
            strings = mapOf(
                KEY_LANGUAGE to settingsSnapshot.language.name,
                KEY_GAUGE_MODE to settingsSnapshot.gaugeMode.name,
                KEY_DEFAULT_SORT_MODE to settingsSnapshot.defaultSortMode.name,
                KEY_MESHVIEW_URL to settingsSnapshot.meshviewUrl,
            ),
            bools = mapOf(
                KEY_KEEP_SCREEN_ON to settingsSnapshot.keepScreenOn,
                KEY_BACKGROUND_COLLECTION to settingsSnapshot.backgroundCollection,
                KEY_USE_PHONE_LOCATION to settingsSnapshot.usePhoneLocation,
            ),
            sets = mapOf(
                KEY_SKIPPED_RELAY_NODES to skippedSnapshot.map { NodeId.format(it) }.toSet(),
            ),
        )
    }

    private fun readSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            language = readEnum(KEY_LANGUAGE, defaults.language),
            gaugeMode = readEnum(KEY_GAUGE_MODE, defaults.gaugeMode),
            defaultSortMode = readEnum(KEY_DEFAULT_SORT_MODE, defaults.defaultSortMode),
            meshviewUrl = store.getString(KEY_MESHVIEW_URL, defaults.meshviewUrl),
            keepScreenOn = store.getBoolean(KEY_KEEP_SCREEN_ON, defaults.keepScreenOn),
            backgroundCollection = store.getBoolean(KEY_BACKGROUND_COLLECTION, defaults.backgroundCollection),
            usePhoneLocation = store.getBoolean(KEY_USE_PHONE_LOCATION, defaults.usePhoneLocation),
        )
    }

    /** Unparseable entries are dropped rather than thrown: the preference file can be hand-edited. */
    private fun readSkippedRelayNodes(): Set<Int> =
        store.getStringSet(KEY_SKIPPED_RELAY_NODES, emptySet())
            .mapNotNull { NodeId.parse(it) }
            .toSet()

    /** A renamed or removed enum constant degrades to [default] instead of crashing at launch. */
    private inline fun <reified T : Enum<T>> readEnum(key: String, default: T): T {
        val stored = store.getString(key, default.name)
        return runCatching { enumValueOf<T>(stored) }.getOrDefault(default)
    }
}
