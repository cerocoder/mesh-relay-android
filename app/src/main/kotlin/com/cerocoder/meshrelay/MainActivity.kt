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
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cerocoder.meshrelay.ble.BleReadiness
import com.cerocoder.meshrelay.location.LocationAvailability
import com.cerocoder.meshrelay.stats.SystemTimeSource
import com.cerocoder.meshrelay.settings.LanguageOption
import com.cerocoder.meshrelay.transport.DeviceListEntry
import com.cerocoder.meshrelay.ui.MeshRelayNavHost
import com.cerocoder.meshrelay.ui.common.LocalAppResumed
import com.cerocoder.meshrelay.ui.common.ProvideRelativeClock
import com.cerocoder.meshrelay.ui.theme.MeshRelayTheme
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

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

    /**
     * The language this activity's resources were built with, so the composition
     * can tell a change from the value it starts with.
     */
    private var attachedLanguage: LanguageOption = LanguageOption.SYSTEM

    /**
     * The chosen language is applied here, under everything.
     *
     * `attachBaseContext` rather than a `LocalContext` override around the UI:
     * every `Popup` and `Dialog` - so every dropdown menu and every confirmation
     * - is its own `AbstractComposeView` and provides `LocalContext` afresh from
     * its own window, shadowing whatever an ancestor provided. The app ran in
     * Spanish with all of its menus in English (field issue F-5). A base context
     * has nothing above it to be shadowed by.
     *
     * The language is read once, here. A change made later recreates the
     * activity - see [onCreate] - which comes back through this method.
     */
    override fun attachBaseContext(newBase: Context) {
        attachedLanguage = (newBase.applicationContext as? MeshRelayApp)
            ?.containerOrNull?.settings?.settings?.value?.language
            ?: LanguageOption.SYSTEM
        super.attachBaseContext(newBase.withChosenLanguage())
    }

    override fun onResume() {
        super.onResume()
        readinessState.value = container.availability.check()
        // For the same reason the line above is here, and for location rather than
        // Bluetooth: a grant made in system Settings produces no callback inside
        // this app. The permission launcher only fires on a dialog this app raised,
        // and `AndroidPhoneLocationSource.start()` returns early while the
        // permission is missing - so a user who declines at first connect, grants
        // it later in Settings and comes back would have the foreground service's
        // `location` type (ruling 39) and still no fixes requested. `onResume` is
        // the only moment the app learns about it, and re-applying the current
        // setting is idempotent: start()/stop() are @Synchronized and both
        // early-return when there is nothing to do.
        container.refreshLocationUpdates()
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

            // A language chosen in Settings takes effect by rebuilding the
            // activity, because that is what rebuilds its resources - see
            // attachBaseContext for why the resources rather than a composition
            // local. Nothing is lost: the navigation stack is rememberSaveable
            // and everything with a longer life than a screen lives in
            // AppContainer, which is exactly what a rotation already relies on.
            LaunchedEffect(settings.language) {
                if (settings.language != attachedLanguage) recreate()
            }

            MeshRelayTheme {
                CompositionLocalProvider(LocalAppResumed provides resumed) {
                    ProvideRelativeClock(SystemTimeSource) {
                        MeshRelayContent(
                            container = container,
                            readinessState = readinessState,
                            backgroundCollection = settings.backgroundCollection,
                            onExit = {
                                // Ordered, and the order is the point: await the GATT
                                // disconnect, stop the service that is holding this
                                // process up, drop the task so the app does not sit in
                                // recents looking alive, and only then end the process.
                                // exitProcess before the disconnect completes would
                                // leave the radio with a half-open link.
                                //
                                // lifecycleScope, not the composition-scoped
                                // rememberCoroutineScope() the disconnect-only path
                                // below uses: it is the scope this method (onCreate)
                                // has, and there is no suspension point between
                                // container.shutdown() returning and exitProcess(0) -
                                // finishAndRemoveTask() is a fire-and-forget call to the
                                // system, not something this coroutine waits on - so the
                                // lifecycle event that finishing eventually raises has no
                                // chance to cancel anything before the process is gone
                                // anyway. Belt and braces: the disconnect itself runs
                                // inside RadioConnectionManager's own NonCancellable
                                // block, so it would complete even if this coroutine were
                                // cancelled mid-await.
                                lifecycleScope.launch {
                                    container.shutdown()
                                    finishAndRemoveTask()
                                    exitProcess(0)
                                }
                            },
                        )
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
    onExit: () -> Unit,
) {
    // The application context, not LocalContext.current: a broadcast receiver has
    // to be unregistered against the very context instance it was registered on,
    // and LocalContext is the activity, which is replaced by every rotation and
    // every language change. This is the one context in the app that never changes
    // identity.
    val appContext = LocalContext.current.applicationContext

    val scope = rememberCoroutineScope()

    var readiness by readinessState
    val found = remember { mutableStateMapOf<String, DeviceListEntry.Ble>() }

    // The user's intent to be connected. Read from the container, not held here:
    // the foreground service can outlive this activity, so a flag saved in the
    // activity's own state would come back false while GATT was still live. See
    // AppContainer.connectRequested.
    val connectRequested by container.connectRequested.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        readiness = container.availability.check()
        // A location grant does not change BleReadiness, so nothing else here
        // would notice it.
        container.refreshLocationUpdates()
    }

    // Bluetooth, location and - from Android 13 - notifications, asked for in one
    // dialog sequence at first connect. Location is not part of BleReadiness: a
    // refusal is not an error, the setting stays on, no fix ever arrives, and every
    // measurement falls back to the node's position. Distinct, because below
    // Android 12 BluetoothAvailability already names ACCESS_FINE_LOCATION for
    // scanning and RequestMultiplePermissions should not be handed it twice.
    val requested = remember {
        val base = container.availability.requiredPermissions + LocationAvailability.REQUIRED_PERMISSIONS
        val all = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            base + Manifest.permission.POST_NOTIFICATIONS
        } else {
            base
        }
        all.distinct().toTypedArray()
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

    // Stopping the service when attempts cease is AppContainer's job, not this
    // composition's: the activity can be gone while the service still runs, and a
    // link that dies for good after that must still release the process.
    //
    // What is left here is the one direction that has to happen in the foreground:
    // the background-collection switch, which must take effect at once rather than
    // at the next connection - turned off it releases the process now, turned back
    // on over a link that is already up it protects that link rather than the next
    // one. Both happen while the user is looking at the settings screen, so this is
    // still a foreground start. connectRequested is enough of a guard on its own:
    // the container clears it the moment attempts cease, so it is never true over a
    // connection that has already been given up on.
    LaunchedEffect(backgroundCollection) {
        if (backgroundCollection && connectRequested) {
            container.startForegroundService()
        } else if (!backgroundCollection) {
            container.stopForegroundService()
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
            if (backgroundCollection) container.startForegroundService()
            container.requestConnect(device.address)
        },
        onDisconnect = {
            // The intent is withdrawn now, not when the suspending disconnect
            // finishes: the scanner is gated on it, and the container's own watcher
            // will stop the service when the state lands on a final Disconnected.
            container.requestDisconnect()
            scope.launch { container.connectionManager.disconnect() }
        },
        onExit = onExit,
    )
}
