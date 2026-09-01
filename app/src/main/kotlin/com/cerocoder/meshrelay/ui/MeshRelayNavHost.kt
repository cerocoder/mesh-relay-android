package com.cerocoder.meshrelay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cerocoder.meshrelay.AppContainer
import com.cerocoder.meshrelay.BuildConfig
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.ble.BleReadiness
import com.cerocoder.meshrelay.connection.ConnectionState
import com.cerocoder.meshrelay.settings.AppSettings
import com.cerocoder.meshrelay.stats.Geo
import com.cerocoder.meshrelay.stats.model.RelayStats
import com.cerocoder.meshrelay.stats.model.StatsSnapshot
import com.cerocoder.meshrelay.transport.DeviceListEntry
import com.cerocoder.meshrelay.ui.detail.DetailScreen
import com.cerocoder.meshrelay.ui.detail.MatchingNodesTab
import com.cerocoder.meshrelay.ui.detail.NodeCard
import com.cerocoder.meshrelay.ui.detail.RemoteNodeScreen
import com.cerocoder.meshrelay.ui.detail.RemoteNodesTab
import com.cerocoder.meshrelay.ui.devices.DeviceListScreen
import com.cerocoder.meshrelay.ui.mynode.MyNodeScreen
import com.cerocoder.meshrelay.ui.nav.BackStack
import com.cerocoder.meshrelay.ui.nav.DetailSubject
import com.cerocoder.meshrelay.ui.nav.MainTab
import com.cerocoder.meshrelay.ui.nav.Screen
import com.cerocoder.meshrelay.ui.nav.backStackSaver
import com.cerocoder.meshrelay.ui.neighbours.NeighbourListScreen
import com.cerocoder.meshrelay.ui.relays.RelayListScreen
import com.cerocoder.meshrelay.ui.settings.SettingsScreen

/**
 * The one place that knows every screen and how they connect.
 *
 * Every screen in this app is stateless - it takes what it shows as parameters
 * and hands every action back as a lambda - so this function is where those two
 * halves meet: the container's flows on one side, the [BackStack] and the
 * container's commands on the other.
 *
 * [readiness] and [onRequestPermissions] are parameters rather than something
 * read from [container] because only the activity can own them. Readiness has to
 * be re-read in `onResume` and from a Bluetooth state broadcast - both are
 * activity lifecycle facts - and a permission launcher must be registered
 * against an `ActivityResultRegistry` before the activity starts. The same goes
 * for [devices]: the scanner's results are collected under the activity's
 * "does the user want to be connected" rule, which the connection state alone
 * cannot express.
 */
@Composable
fun MeshRelayNavHost(
    container: AppContainer,
    devices: List<DeviceListEntry>,
    readiness: BleReadiness,
    onRequestPermissions: () -> Unit,
    onSelectDevice: (DeviceListEntry) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val backStack = rememberSaveable(saver = backStackSaver) { BackStack(Screen.Devices) }

    val snapshot by container.engine.snapshot.collectAsState()
    val connectionState by container.connectionManager.connectionState.collectAsState()
    val settings by container.settings.settings.collectAsState()
    val skippedRelayNodes by container.settings.skippedRelayNodes.collectAsState()
    val nodeDbReloading by container.connectionManager.nodeDbReloading.collectAsState()
    val requestedAddress by container.requestedAddress.collectAsState()

    val meshviewUrl = meshviewUrlOrNull(settings)

    // At the root the press belongs to the system, which closes the app. Guarding
    // on canGoBack rather than swallowing the press and ignoring it is the whole
    // reason BackStack.pop reports what it did.
    BackHandler(enabled = backStack.canGoBack) { backStack.pop() }

    // Picking a device does not leave the device list: there is nothing to show
    // until the handshake finishes, and the device list is the one screen that
    // names a failure and offers the next step (the relay list takes the
    // connection state but only uses it to gate the reload spinner). So the hand
    // over happens when there is actually a connection to show statistics for.
    //
    // The latch, not the state, is what decides. It is rememberSaveable, so
    // rotating the phone on the device list while connected does not bounce the
    // user into the relay list; and it is cleared only when attempts stop
    // altogether, so a link that drops and recovers by itself does not either.
    var handedOverToStats by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(connectionState) {
        val state = connectionState
        when {
            state == ConnectionState.Connected -> {
                if (!handedOverToStats) {
                    handedOverToStats = true
                    if (backStack.current == Screen.Devices) backStack.push(Screen.Main(MainTab.RELAYS))
                }
            }

            state is ConnectionState.Disconnected && !state.retrying -> handedOverToStats = false
        }
    }

    when (val screen = backStack.current) {
        Screen.Devices -> DeviceListScreen(
            devices = devices,
            state = connectionState,
            readiness = readiness,
            onSelect = { entry ->
                // Tapping the node you are already connected to is how you get back
                // to its statistics after backing out of them. Nothing else can do
                // it: the hand-over above fires on the edge into Connected, and a
                // repeat tap on the same address is short-circuited by the
                // connection manager as already-connected, so there is no edge left
                // to fire on and the user would be stranded on the device list until
                // they disconnected and handshaked again.
                //
                // Deliberately narrowed to *this* node rather than "any tap while
                // connected": tapping a different one is a request to switch nodes,
                // and that belongs on the device list until its handshake finishes,
                // where a failure has somewhere to be explained.
                if (connectionState == ConnectionState.Connected && entry.address == requestedAddress) {
                    backStack.push(Screen.Main(MainTab.RELAYS))
                }
                onSelectDevice(entry)
            },
            onDisconnect = onDisconnect,
            onRequestPermissions = onRequestPermissions,
            modifier = modifier,
        )

        is Screen.Main -> MainScaffold(
            tab = screen.tab,
            snapshot = snapshot,
            connectionState = connectionState,
            settings = settings,
            meshviewUrl = meshviewUrl,
            nodeDbReloading = nodeDbReloading,
            container = container,
            backStack = backStack,
            modifier = modifier,
        )

        Screen.Settings -> SettingsScreen(
            settings = settings,
            skippedRelayNodes = skippedRelayNodes,
            appVersion = BuildConfig.VERSION_NAME,
            onUpdate = { transform -> container.settings.update(transform) },
            onRemoveSkipped = { nodeNum -> container.settings.removeSkippedRelayNode(nodeNum) },
            onClearAllSkipped = { container.clearAllSkippedNodes() },
            onBack = { backStack.pop() },
            modifier = modifier,
        )

        is Screen.Detail -> DetailDestination(
            subject = screen.subject,
            snapshot = snapshot,
            settings = settings,
            meshviewUrl = meshviewUrl,
            container = container,
            backStack = backStack,
            modifier = modifier,
        )

        is Screen.RemoteNode -> RemoteNodeScreen(
            nodeNum = screen.nodeNum,
            viaRelayByte = screen.viaRelayByte,
            snapshot = snapshot,
            meshviewUrl = meshviewUrl,
            onBack = { backStack.pop() },
            onOpenRelay = { relayByte -> backStack.push(Screen.Detail(DetailSubject.Relay(relayByte))) },
            modifier = modifier,
        )

        // Task 12 replaces this with the real destination. Until then nothing
        // pushes Screen.Graph, so the branch is unreachable - it exists because a
        // non-exhaustive `when` statement over a sealed interface has been a
        // compile error since Kotlin 1.7, and an `else` here would silently
        // swallow the next destination someone adds instead.
        is Screen.Graph -> Unit
    }
}

/**
 * The only screen with a bottom bar. Every other destination is full-bleed: a
 * detail screen or the settings screen is somewhere the user went *from* a tab,
 * and leaving the bar on them would offer a sideways move where the only
 * sensible one is back.
 *
 * Icon note, following the rule the relay list's own app bar already set: only
 * the core icon set is available, and an icon that misrepresents the action is
 * worse than a dull one. `Share` is the core set's one glyph showing nodes
 * joined by links, which is what a relay is; nothing in the set says
 * "directly-heard node", so the neighbours tab takes a plain list glyph, which
 * claims nothing its label does not. `Person` is the core set's glyph for an
 * identity, and the My node tab is this device's own identity - the one node in
 * the mesh that is not somebody else.
 */
@Composable
private fun MainScaffold(
    tab: MainTab,
    snapshot: StatsSnapshot,
    connectionState: ConnectionState,
    settings: AppSettings,
    meshviewUrl: String?,
    nodeDbReloading: Boolean,
    container: AppContainer,
    backStack: BackStack,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == MainTab.RELAYS,
                    onClick = { backStack.selectTab(MainTab.RELAYS) },
                    // The label carries the accessible name, so a second one on
                    // the icon would have TalkBack read every item twice.
                    icon = { Icon(Icons.Filled.Share, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_relays)) },
                )
                NavigationBarItem(
                    selected = tab == MainTab.NEIGHBOURS,
                    onClick = { backStack.selectTab(MainTab.NEIGHBOURS) },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_neighbours)) },
                )
                NavigationBarItem(
                    selected = tab == MainTab.MY_NODE,
                    onClick = { backStack.selectTab(MainTab.MY_NODE) },
                    icon = { Icon(Icons.Filled.Person, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_my_node)) },
                )
            }
        },
    ) { innerPadding ->
        when (tab) {
            MainTab.RELAYS -> RelayListScreen(
                snapshot = snapshot,
                connection = connectionState,
                gaugeMode = settings.gaugeMode,
                nodeDbReloading = nodeDbReloading,
                onOpenRelay = { relayByte -> backStack.push(Screen.Detail(DetailSubject.Relay(relayByte))) },
                onSetSortMode = { mode -> container.engine.setSortMode(mode) },
                onSetGaugeMode = { mode -> container.settings.update { it.copy(gaugeMode = mode) } },
                // The engine owns whether it is paused; this only asks for the
                // opposite of what the snapshot last reported.
                onTogglePause = { container.engine.setPaused(!snapshot.paused) },
                onReset = { container.engine.reset() },
                onReloadNodeDb = { container.connectionManager.reloadNodeDatabase() },
                onOpenSettings = { backStack.push(Screen.Settings) },
                modifier = Modifier.padding(innerPadding),
            )

            MainTab.NEIGHBOURS -> NeighbourListScreen(
                snapshot = snapshot,
                gaugeMode = settings.gaugeMode,
                onOpenNeighbour = { nodeNum -> backStack.push(Screen.Detail(DetailSubject.Neighbour(nodeNum))) },
                onSetSortMode = { mode -> container.engine.setSortMode(mode) },
                onSetGaugeMode = { mode -> container.settings.update { it.copy(gaugeMode = mode) } },
                onTogglePause = { container.engine.setPaused(!snapshot.paused) },
                onReset = { container.engine.reset() },
                onOpenSettings = { backStack.push(Screen.Settings) },
                modifier = Modifier.padding(innerPadding),
            )

            MainTab.MY_NODE -> MyNodeScreen(
                snapshot = snapshot,
                meshviewUrl = meshviewUrl,
                gaugeMode = settings.gaugeMode,
                onSetGaugeMode = { mode -> container.settings.update { it.copy(gaugeMode = mode) } },
                onTogglePause = { container.engine.setPaused(!snapshot.paused) },
                onReset = { container.engine.reset() },
                onOpenSettings = { backStack.push(Screen.Settings) },
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

/**
 * [DetailScreen] plus the two tab bodies it deliberately does not build itself.
 *
 * The shell was written before either tab existed and could never be edited
 * again to call them, so it takes them as slots - and this is the only place
 * that holds everything they need at once: the snapshot, the Meshview URL and
 * the container's skip commands. See `DetailScreen`'s own KDoc for the full
 * reasoning.
 *
 * The two subjects resolve their tabs differently. A relay is a one-byte guess,
 * so its first tab lists the candidates behind the byte and lets the user rule
 * them out. A neighbour was heard directly and its identity is already known, so
 * its first tab is that one node's card with nothing to number and nothing to
 * skip - `index` and `onSkip` are both null, exactly the second caller
 * [NodeCard] was written for.
 */
@Composable
private fun DetailDestination(
    subject: DetailSubject,
    snapshot: StatsSnapshot,
    settings: AppSettings,
    meshviewUrl: String?,
    container: AppContainer,
    backStack: BackStack,
    modifier: Modifier = Modifier,
) {
    // For a relay this is its own byte; for a neighbour it is the byte that
    // neighbour's node number would produce - the same question the shell asks to
    // decide whether a remote-nodes tab exists at all.
    val relayByte = when (subject) {
        is DetailSubject.Relay -> subject.relayByte
        is DetailSubject.Neighbour -> Geo.lastByteOfNodeNum(subject.nodeNum)
    }
    // A relay the user navigated to can be cleared by a reset while the screen is
    // open, exactly as the shell's own header resolution allows for; an all-zero
    // record renders an empty tab rather than crashing.
    val relay = snapshot.relays.find { it.relayByte == relayByte } ?: RelayStats(relayByte = relayByte)

    DetailScreen(
        subject = subject,
        snapshot = snapshot,
        gaugeMode = settings.gaugeMode,
        meshviewUrl = meshviewUrl,
        onBack = { backStack.pop() },
        onOpenRemoteNode = { nodeNum -> backStack.push(Screen.RemoteNode(nodeNum, relayByte)) },
        onSkipNode = { nodeNum -> container.skipRelayNode(nodeNum) },
        onClearSkipped = { container.clearSkippedForRelay(relayByte) },
        modifier = modifier,
        matchingNodesTab = {
            when (subject) {
                // relayByte above is already this subject's own byte in this branch.
                is DetailSubject.Relay -> MatchingNodesTab(
                    relayByte = relayByte,
                    snapshot = snapshot,
                    meshviewUrl = meshviewUrl,
                    onSkipNode = { nodeNum -> container.skipRelayNode(nodeNum) },
                    onClearSkipped = { container.clearSkippedForRelay(relayByte) },
                )

                is DetailSubject.Neighbour -> NeighbourNodeTab(
                    nodeNum = subject.nodeNum,
                    snapshot = snapshot,
                    meshviewUrl = meshviewUrl,
                )
            }
        },
        remoteNodesTab = {
            RemoteNodesTab(
                relay = relay,
                snapshot = snapshot,
                meshviewUrl = meshviewUrl,
                onOpenRemoteNode = { nodeNum -> backStack.push(Screen.RemoteNode(nodeNum, relayByte)) },
            )
        },
    )
}

/**
 * A neighbour's own card. Scrollable because the card is as tall as the node's
 * data makes it - a node with a position, telemetry and a public key does not
 * fit a short phone.
 */
@Composable
private fun NeighbourNodeTab(
    nodeNum: Int,
    snapshot: StatsSnapshot,
    meshviewUrl: String?,
    modifier: Modifier = Modifier,
) {
    val directory = snapshot.directory
    val record = directory.node(nodeNum)

    if (record == null) {
        // A node heard directly whose NodeInfo has never arrived. Real, and not an
        // error: the packet that made it a neighbour carried its number, not its
        // name.
        EmptyState(
            title = stringResource(R.string.node_not_in_db_title),
            body = stringResource(R.string.node_not_in_db_body),
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        NodeCard(
            index = null,
            record = record,
            location = directory.locationInfo(nodeNum, from = directory.localPosition()),
            telemetry = directory.telemetry(nodeNum),
            meshviewUrl = meshviewUrl,
            onSkip = null,
        )
    }
}

/** The same centred title-and-explanation block every empty tab in this app uses. */
@Composable
private fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * The Meshview URL as the screens expect it: `null` means "offer no Meshview
 * link", and every screen already treats it that way.
 *
 * [AppSettings.meshviewUrl] is a non-nullable String because the settings screen
 * edits a text field, and an emptied field is `""`, not absent. Passed through
 * unconverted, that empty string would build links like `/node/!9e75f1a4` and
 * render a button that goes nowhere - which is worse than the no-button state
 * the user was asking for by clearing the field. Blank rather than empty,
 * because a field holding only spaces means the same thing; trimmed, because a
 * URL with a trailing space does not resolve.
 *
 * This conversion belongs here and not in [AppSettings]: the setting genuinely
 * is a string with an empty value, and this is the boundary where it becomes a
 * screen's optional parameter.
 */
private fun meshviewUrlOrNull(settings: AppSettings): String? =
    settings.meshviewUrl.trim().takeIf { it.isNotEmpty() }
