package com.cerocoder.meshrelay

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.cerocoder.meshrelay.ble.BleReadiness
import com.cerocoder.meshrelay.connection.ConnectionState
import com.cerocoder.meshrelay.service.MeshForegroundService
import com.cerocoder.meshrelay.stats.SystemTimeSource
import com.cerocoder.meshrelay.transport.DeviceListEntry
import com.cerocoder.meshrelay.ui.LocalizedApp
import com.cerocoder.meshrelay.ui.MeshRelayNavHost
import com.cerocoder.meshrelay.ui.common.LocalAppResumed
import com.cerocoder.meshrelay.ui.common.ProvideRelativeClock
import com.cerocoder.meshrelay.ui.theme.MeshRelayTheme
import kotlinx.coroutines.launch

/**
 * The only activity. Everything that outlives it - the connection, the
 * statistics - lives in [AppContainer]; what stays here is the handful of facts
 * only an activity can know.
 */
class MainActivity : ComponentActivity() {

    /**
     * Bluetooth readiness. Lives on the activity rather than in the composition
     * because it has to be re-read every time the user comes back to the screen:
     * permissions are granted in system settings and the adapter is switched on
     * from the shade, both outside this app. Without that, one refusal in the
     * dialog locked the user on the explanatory text until the process restarted.
     */
    private val readinessState = mutableStateOf(BleReadiness.UNSUPPORTED)

    /**
     * Whether the activity is between `onResume` and `onPause`, published as
     * [LocalAppResumed] so the relative-time ticker - the only periodic work in
     * the app - stops the moment the screen is no longer showing.
     */
    private val resumedState = mutableStateOf(false)

    private val container: AppContainer get() = (application as MeshRelayApp).container

    override fun onResume() {
        super.onResume()
        readinessState.value = container.availability.check()
        resumedState.value = true
    }

    override fun onPause() {
        resumedState.value = false
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = this.container
        readinessState.value = container.availability.check()
        // Captured here: inside a nested composable `window` no longer resolves to
        // the activity's.
        val activityWindow = window

        setContent {
            val settings by container.settings.settings.collectAsState()
            val resumed by resumedState

            DisposableEffect(settings.keepScreenOn) {
                if (settings.keepScreenOn) {
                    activityWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    activityWindow.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                // Cleared on dispose as well: the flag belongs to the window, not to
                // the composition, and would otherwise survive the setting being
                // turned off across an activity recreation.
                onDispose { activityWindow.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }

            LocalizedApp(settings.language) {
                MeshRelayTheme {
                    CompositionLocalProvider(LocalAppResumed provides resumed) {
                        ProvideRelativeClock(SystemTimeSource) {
                            MeshRelayContent(
                                container = container,
                                readinessState = readinessState,
                                backgroundCollection = settings.backgroundCollection,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The scanning, permission and foreground-service rules, all of which are about
 * the phone rather than about the mesh, kept out of the navigation host.
 */
@Composable
private fun MeshRelayContent(
    container: AppContainer,
    readinessState: MutableState<BleReadiness>,
    backgroundCollection: Boolean,
) {
    // LocalContext here is the locale-overridden wrapper LocalizedApp provides, and
    // a new one of those appears every time the language changes. Everything below
    // takes the application context underneath it instead: a receiver has to be
    // unregistered against the very context it was registered on, and an Intent has
    // no use for a display locale anyway - the service resolves its own strings
    // from its own context.
    val appContext = LocalContext.current.applicationContext

    val state by container.connectionManager.connectionState.collectAsState()
    val scope = rememberCoroutineScope()

    var readiness by readinessState
    val found = remember { mutableStateMapOf<String, DeviceListEntry.Ble>() }

    // The user's intent to be connected - not the connection state. Saved, so a
    // rotation does not read as "the user gave up" and restart the scan.
    var connectRequested by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { readiness = container.availability.check() }

    // The notification permission is asked for alongside Bluetooth but is not part
    // of readiness: without it the app works completely, only the foreground
    // service's notification is not shown.
    val requested = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            container.availability.requiredPermissions + Manifest.permission.POST_NOTIFICATIONS
        } else {
            container.availability.requiredPermissions
        }
    }

    // The adapter is switched on from the shade, and the shade does not stop the
    // activity - onResume never fires for it, so without this subscription the
    // screen would sit there with stale readiness.
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                readinessState.value = container.availability.check()
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { appContext.unregisterReceiver(receiver) }
    }

    // Scanning stops as soon as the user has picked a node. A low-latency scan
    // running alongside a live GATT connection breaks that connection outright on
    // many phones - and the reconnect loop would get the blame. The key is the
    // user's intent, not the connection state: the state cycles on every attempt,
    // and restarting the scan each time would run into the system's limit of five
    // scan starts per thirty seconds.
    val scanning = readiness == BleReadiness.READY && !connectRequested

    LaunchedEffect(readiness, scanning) {
        when {
            readiness == BleReadiness.PERMISSIONS_MISSING -> permissionLauncher.launch(requested)

            // Deduplicated by address: a device repeats with every advertisement.
            scanning -> container.scanner.scan().collect { found[it.mac] = it }
        }
    }

    // The service is stopped only once attempts have ceased altogether. While the
    // reconnect loop is still trying, the process has to stay protected - the
    // pauses between attempts are exactly when the system would suspend it. The
    // flag has to come from the state, because an ordinary failed attempt also
    // carries a reason: the reason alone cannot tell surrender from persistence.
    LaunchedEffect(state) {
        val current = state
        if (current is ConnectionState.Disconnected && !current.retrying) {
            connectRequested = false
            MeshForegroundService.stop(appContext)
            container.onForegroundServiceStopped()
        }
    }

    // The background-collection switch has to take effect at once, not at the next
    // connection: turned off it must release the process now, and turned back on
    // over a link that is already up it must protect that link rather than the
    // next one. Both directions happen while the user is looking at the settings
    // screen, so the start below is still a foreground start.
    val stateNow = state
    val linkAlive = stateNow !is ConnectionState.Disconnected || stateNow.retrying
    LaunchedEffect(backgroundCollection) {
        when {
            !backgroundCollection -> {
                MeshForegroundService.stop(appContext)
                container.onForegroundServiceStopped()
            }

            // The last clause is what keeps a rotation quiet: this effect re-runs on
            // the fresh composition, and re-starting a service that is already up
            // would reset its notification back to the generic body.
            connectRequested && linkAlive && !container.foregroundServiceActive -> {
                MeshForegroundService.start(appContext)
                container.onForegroundServiceStarted()
            }
        }
    }

    MeshRelayNavHost(
        container = container,
        devices = container.devices + found.values.sortedBy { it.name },
        readiness = readiness,
        onRequestPermissions = { permissionLauncher.launch(requested) },
        onSelectDevice = { device ->
            // The service is tied to the user's intent to be connected, not to the
            // current connection state, and for two reasons. First,
            // startForegroundService from the background throws
            // ForegroundServiceStartNotAllowedException on Android 12+, and the
            // transport reconnects on a loop of its own that passes through
            // Connecting whenever it likes - including while the app is minimised.
            // Starting from here, from the user's tap, is always in the foreground.
            // Second, every failed attempt inside that loop passes through
            // Disconnected, and tying the service to the state would shut it down
            // for exactly the length of each backoff - that is, precisely when the
            // process needs protecting.
            connectRequested = true
            if (backgroundCollection) {
                MeshForegroundService.start(appContext)
                container.onForegroundServiceStarted()
            }
            container.connectionManager.connect(device.address)
        },
        onDisconnect = {
            connectRequested = false
            scope.launch {
                container.connectionManager.disconnect()
                MeshForegroundService.stop(appContext)
                container.onForegroundServiceStopped()
            }
        },
    )
}
