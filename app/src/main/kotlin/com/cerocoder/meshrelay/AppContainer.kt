package com.cerocoder.meshrelay

import android.content.Context
import android.util.Log
import com.cerocoder.meshrelay.ble.BleScanner
import com.cerocoder.meshrelay.ble.BluetoothAvailability
import com.cerocoder.meshrelay.ble.nordic.BleScannerImpl
import com.cerocoder.meshrelay.connection.ConnectionState
import com.cerocoder.meshrelay.connection.RadioConnectionManager
import com.cerocoder.meshrelay.emulator.Scenarios
import com.cerocoder.meshrelay.service.MeshForegroundService
import com.cerocoder.meshrelay.settings.AndroidSettingsStore
import com.cerocoder.meshrelay.settings.SettingsRepository
import com.cerocoder.meshrelay.stats.MeshStatsEngine
import com.cerocoder.meshrelay.stats.SystemTimeSource
import com.cerocoder.meshrelay.transport.DeviceListEntry
import com.cerocoder.meshrelay.transport.RadioTransportFactory
import com.cerocoder.meshrelay.transport.RadioTransportFactoryImpl
import com.cerocoder.meshrelay.ui.localeFor
import com.cerocoder.meshrelay.ui.withLocale
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds

/**
 * The application's dependency container, wired by hand.
 *
 * Lives as long as the process, which is the point: the connection and every
 * statistic collected over it survive the activity being recreated by a rotation.
 * No dependency-injection framework - there is one graph, it is built once, and
 * it fits on a screen.
 */
class AppContainer(private val context: Context, isDebugBuild: Boolean) {

    // A SupervisorJob so one failed child does not take the rest of the
    // application down with it, and a handler so the failure is at least visible
    // in the log rather than swallowed.
    private val errors = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "unhandled exception in the application scope", e)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errors)

    /** Permission and adapter state, for the device screen. */
    val availability = BluetoothAvailability(context)

    /** Finding nodes over the air. */
    val scanner: BleScanner = BleScannerImpl()

    val settings = SettingsRepository(AndroidSettingsStore(context), scope)

    private val factory: RadioTransportFactory = RadioTransportFactoryImpl(scope, isDebugBuild, context)

    val connectionManager = RadioConnectionManager(factory, scope, SystemTimeSource)

    val engine = MeshStatsEngine(
        scope = scope,
        skippedRelayNodes = settings.skippedRelayNodes,
        initialSortMode = settings.settings.value.defaultSortMode,
    )

    /** Demo devices in debug builds only; real ones come from the scanner. */
    val devices: List<DeviceListEntry> =
        if (isDebugBuild) Scenarios.all.map { DeviceListEntry.Demo(it.id, it.displayName) } else emptyList()

    /**
     * Whether the foreground service is up.
     *
     * [MeshForegroundService.updateText] reaches the service through `startService`,
     * which on a service that is not running would *start* it - from the
     * application scope, that is from the background, which Android refuses. So the
     * updater below has to know, and only the activity does: the service is started
     * from the user's tap and stopped when attempts cease.
     *
     * `@Volatile` because it is written from the main thread and read from the
     * updater coroutine on `Dispatchers.Default`.
     */
    @Volatile
    private var foregroundServiceRunning = false

    /**
     * @see foregroundServiceRunning - exposed so the activity can tell "start it"
     *   from "it is already up": re-issuing the start would run the service's
     *   `onStartCommand` again with no text extra, resetting the notification to
     *   its generic body and throwing away the counters already on it.
     */
    val foregroundServiceActive: Boolean get() = foregroundServiceRunning

    init {
        // The engine consumes a source-agnostic frame stream, so this is the only
        // line in the application that knows the frames come from a radio at all. A
        // file-backed source is added here later and nowhere else.
        engine.attach(connectionManager.frames)

        // The notification carries counters, not a snapshot: it is the one thing
        // that still updates with the screen off, and building a snapshot for it
        // would give back exactly the saving that subscription-scoped sharing buys.
        scope.launch {
            while (isActive) {
                delay(NOTIFICATION_INTERVAL)
                if (!foregroundServiceRunning) continue
                if (connectionManager.connectionState.value != ConnectionState.Connected) continue
                val counters = engine.counters.value
                val text = notificationContext().getString(
                    R.string.service_notification_counters,
                    counters.totalPackets,
                    counters.totalRelayedPackets,
                )
                MeshForegroundService.updateText(context, text)
            }
        }
    }

    fun skipRelayNode(nodeNum: Int) = settings.addSkippedRelayNode(nodeNum)

    fun clearSkippedForRelay(relayByte: Int) = settings.clearSkippedForRelay(relayByte)

    fun clearAllSkippedNodes() = settings.clearAllSkippedNodes()

    /** @see foregroundServiceRunning */
    fun onForegroundServiceStarted() {
        foregroundServiceRunning = true
    }

    /** @see foregroundServiceRunning */
    fun onForegroundServiceStopped() {
        foregroundServiceRunning = false
    }

    /**
     * A context resolving strings in the language chosen in Settings.
     *
     * The screens get theirs from `LocalizedApp`; this one is for the notification,
     * which is built outside any composition. Read fresh each time rather than
     * cached, so switching language takes effect on the next notification update
     * instead of at the next launch.
     */
    private fun notificationContext(): Context {
        val locale = localeFor(settings.settings.value.language) ?: return context
        return context.withLocale(locale)
    }

    private companion object {
        const val TAG = "AppContainer"

        /**
         * Thirty seconds, matching what the service's own contract asks for. Faster
         * would mean waking the process for a number nobody is looking at.
         */
        val NOTIFICATION_INTERVAL = 30.seconds
    }
}
