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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    val settings = SettingsRepository(AndroidSettingsStore(context))

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
     * updater below has to know.
     *
     * It also decides whether a start is a no-op: re-issuing one runs the service's
     * `onStartCommand` again with no text extra, which resets the notification from
     * the live counters back to its generic body.
     *
     * `@Volatile` because it is touched from two threads: started from the main
     * thread (a tap, a settings toggle) and stopped from the surrender watcher and
     * the updater, both on `Dispatchers.Default`. The check-and-set in each of
     * [startForegroundService] and [stopForegroundService] is not atomic against the
     * other, and does not need to be - the two are driven by opposite, non-competing
     * events, and `startService`/`stopService` are themselves ordered by the system.
     */
    @Volatile
    private var foregroundServiceRunning = false

    private val _connectRequested = MutableStateFlow(false)

    /**
     * Whether the user wants to be connected - not whether the app currently is.
     *
     * This lives here, at process lifetime, rather than in the activity's saved
     * state, because that is the lifetime of the thing it describes. With the
     * foreground service holding the process up, swiping the app away and
     * relaunching it builds a *fresh* activity with no saved state while GATT is
     * still live. A `rememberSaveable` flag would read `false` there, the device
     * screen would decide it was free to scan, and a low-latency scan would run
     * alongside an active GATT connection - the exact failure the device screen's
     * own scanning rule exists to prevent, and one the sibling project hit on
     * hardware.
     *
     * Cleared by [connectionManager] surrendering, below - never by an activity
     * going away.
     */
    val connectRequested: StateFlow<Boolean> = _connectRequested.asStateFlow()

    private val _requestedAddress = MutableStateFlow<String?>(null)

    /**
     * The address the user last asked for, while that request still stands.
     *
     * The navigation host needs it to tell "the user tapped the node they are
     * already connected to" - a repeat tap the connection manager deliberately
     * short-circuits, producing no state change - from "the user picked a
     * different node".
     */
    val requestedAddress: StateFlow<String?> = _requestedAddress.asStateFlow()

    init {
        // The engine consumes a source-agnostic frame stream, so this is the only
        // line in the application that knows the frames come from a radio at all. A
        // file-backed source is added here later and nowhere else.
        engine.attach(connectionManager.frames)

        // Surrender - and only surrender - ends the request and releases the
        // process. While the reconnect loop is still trying, the process has to stay
        // protected: the pauses between attempts are exactly when the system would
        // suspend it. The distinction has to come from the state's own flag, because
        // an ordinary failed attempt carries a reason too, and the reason alone
        // cannot tell persistence from giving up.
        //
        // This watches the state here rather than from the composition for the same
        // reason connectRequested lives here: the activity can be gone while the
        // service still runs, and a link that dies for good after that would leave
        // nobody to stop it - a notification claiming a connection that no longer
        // exists, and a process held awake for nothing.
        scope.launch {
            connectionManager.connectionState.collect { state ->
                if (state is ConnectionState.Disconnected && !state.retrying) {
                    _connectRequested.value = false
                    _requestedAddress.value = null
                    stopForegroundService()
                }
            }
        }

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

    /**
     * Record the user's intent to be connected, and act on it.
     *
     * Named for the intent rather than for the call it makes, because the intent is
     * what outlives the call: [connectRequested] stays true across every retry the
     * transport makes on its own.
     */
    fun requestConnect(address: String) {
        _requestedAddress.value = address
        _connectRequested.value = true
        connectionManager.connect(address)
    }

    /**
     * Withdraw that intent at once, before the disconnect itself has finished.
     *
     * [RadioConnectionManager.disconnect] suspends, and the device screen must not
     * spend that time still believing the user wants to be connected - the scanner
     * is gated on exactly this flag.
     */
    fun requestDisconnect() {
        _connectRequested.value = false
        _requestedAddress.value = null
    }

    /**
     * Bring the foreground service up, if it is not already.
     *
     * Must be called from the foreground - a tap on a device, or the background
     * collection switch. `startForegroundService` from the background throws
     * `ForegroundServiceStartNotAllowedException` on Android 12+, and this class has
     * no way to check on the caller's behalf.
     *
     * @see foregroundServiceRunning for why a repeat start is not harmless.
     */
    fun startForegroundService() {
        if (foregroundServiceRunning) return
        MeshForegroundService.start(context)
        foregroundServiceRunning = true
    }

    /** Safe from anywhere, including the background, and a no-op if it is not up. */
    fun stopForegroundService() {
        if (!foregroundServiceRunning) return
        MeshForegroundService.stop(context)
        foregroundServiceRunning = false
    }

    /**
     * A context resolving strings in the language chosen in Settings.
     *
     * The activity gets its language in `attachBaseContext`; this one is for the
     * notification text, which is built here rather than in the activity. Read
     * fresh each time rather than cached, so switching language takes effect on
     * the next notification update instead of at the next launch.
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
