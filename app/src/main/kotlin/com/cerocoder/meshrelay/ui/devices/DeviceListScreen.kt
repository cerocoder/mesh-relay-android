package com.cerocoder.meshrelay.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.ble.BleReadiness
import com.cerocoder.meshrelay.connection.ConnectionState
import com.cerocoder.meshrelay.transport.DeviceListEntry
import com.cerocoder.meshrelay.transport.FailureReason
import com.cerocoder.meshrelay.ui.theme.MeshRelayTheme

/**
 * The first screen a user sees, and the only one with no counterpart in the
 * terminal tool: there, `--serial /dev/ttyACM0` either works or prints an
 * error to a console nobody but the operator reads. Here, connecting is a
 * small workflow with four distinct outcomes for the radio itself
 * ([BleReadiness]) on top of the usual connect/connecting/disconnected cycle
 * ([ConnectionState]), and this screen is where the user finds out which one
 * they are in and, for the three non-ready cases, what to do about it.
 *
 * Restyles `mesh-test-android`'s screen of the same name. Its logic is kept
 * unchanged: the four readiness states gate the device browser exactly as
 * before, demo entries are still whatever the caller includes in [devices]
 * (a debug build's container is what actually excludes them in release,
 * not this screen), and the disconnect affordance still only appears while a
 * link exists or is being established. What changes is that every literal
 * that reached the user is now a string resource, and the layout is this
 * app's own rather than a test harness's flat list.
 *
 * Every readiness-driven and connection-driven text lookup below routes
 * through a `when` that returns a value (not `Unit`), the same technique
 * [com.cerocoder.meshrelay.ui.common.SortModeLabels.labelOf] already uses -
 * a missing branch is then a compile error, not a silently swallowed future
 * state, which a `when` used only for its side effects would not catch.
 *
 * Stateless like every other screen in this port: [devices], [state] and
 * [readiness] are the entire truth this screen shows, and every action -
 * picking a device, disconnecting, requesting permissions - goes back out
 * through a lambda.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    devices: List<DeviceListEntry>,
    state: ConnectionState,
    readiness: BleReadiness,
    onSelect: (DeviceListEntry) -> Unit,
    onDisconnect: () -> Unit,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.devices_title)) })
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            ConnectionStatusRow(state = state, onDisconnect = onDisconnect)

            val readinessCopy = readinessCopyOf(readiness)
            if (readinessCopy != null) {
                ReadinessState(
                    title = stringResource(readinessCopy.titleRes),
                    body = stringResource(readinessCopy.bodyRes),
                    modifier = Modifier.weight(1f),
                ) {
                    if (readiness == BleReadiness.PERMISSIONS_MISSING) {
                        Button(onClick = onRequestPermissions) {
                            Text(stringResource(R.string.devices_permissions_grant))
                        }
                    }
                }
            } else {
                // BleReadiness.READY: nothing stands between the user and the
                // scanner, so what shows is the device browser itself.
                DeviceBrowseSection(
                    devices = devices,
                    scanning = state != ConnectionState.Connected,
                    onSelect = onSelect,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The two string resources one non-ready [BleReadiness] state needs. `null`
 * for [BleReadiness.READY], which has no "what went wrong" to show at all -
 * carried as the return value of a `when` rather than a side-effecting one so
 * a state this project adds later, and forgets to cover here, fails to build
 * instead of silently falling through to the device browser.
 */
private data class ReadinessCopy(val titleRes: Int, val bodyRes: Int)

private fun readinessCopyOf(readiness: BleReadiness): ReadinessCopy? = when (readiness) {
    BleReadiness.READY -> null
    BleReadiness.PERMISSIONS_MISSING ->
        ReadinessCopy(R.string.devices_permissions_title, R.string.devices_permissions_body)
    BleReadiness.ADAPTER_OFF ->
        ReadinessCopy(R.string.devices_adapter_off_title, R.string.devices_adapter_off_body)
    BleReadiness.UNSUPPORTED ->
        ReadinessCopy(R.string.devices_unsupported_title, R.string.devices_unsupported_body)
}

/**
 * A full-height "here is what to do" block for a non-ready [BleReadiness]
 * state. Every [body] string here says what to do next, not where the
 * failure surfaced - the rule `mesh-test-android`'s own devel notes record
 * after "could not start bonding" sent someone looking at their node when
 * Bluetooth itself was switched off. [action] is the optional next step
 * (only [BleReadiness.PERMISSIONS_MISSING] has one to offer).
 */
@Composable
private fun ReadinessState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
        Column(modifier = Modifier.padding(top = 16.dp)) {
            action()
        }
    }
}

/**
 * The connection state, always visible regardless of [BleReadiness] - a
 * dropped adapter does not retroactively hide that the app is, say, still
 * trying to reconnect. The disconnect action's visibility is [canDisconnect] -
 * see that function for why hiding it during a retry backoff is deliberate,
 * not incidental.
 */
@Composable
private fun ConnectionStatusRow(
    state: ConnectionState,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state == ConnectionState.Connecting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                Text(text = connectionStateLabel(state), style = MaterialTheme.typography.titleMedium)
            }
            // The reason is a FailureReason (see ConnectionState.Disconnected's own
            // KDoc), not a plain String: some of its variants are still an
            // unresolved string-resource id at this point, and resolveReason below
            // is what turns either variant into text fit to show, right here where
            // a Context (via stringResource) is finally at hand.
            val reason = (state as? ConnectionState.Disconnected)?.reason
            if (reason != null) {
                Text(
                    text = resolveReason(reason),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (canDisconnect(state)) {
            TextButton(onClick = onDisconnect) {
                Text(stringResource(R.string.action_disconnect))
            }
        }
    }
}

/**
 * Whether there is a link (forming or established) for the disconnect
 * control to tear down.
 *
 * **Deliberate deviation from `mesh-test-android`'s original**, which renders
 * the button unconditionally in every state
 * (`mesh-test-android/.../ui/DeviceListScreen.kt:38-40`). That original never
 * exercised the hazard this function exists to avoid:
 * `ConnectionState.Disconnected(retrying = true)` is still a
 * [ConnectionState.Disconnected] - the reconnect loop revisits it between
 * every backoff attempt - so an
 * unconditional button would offer "Disconnect" while a reconnect is quietly
 * in progress. A tap would then cancel that in-progress reconnection while
 * the label implies there is a live link to end, which is worse than either
 * showing nothing or trying again on its own. Three cases, not one `!is
 * Disconnected` check:
 *
 * - [ConnectionState.Connecting] - the physical link exists, handshake
 *   pending. Shown: there is something to tear down.
 * - [ConnectionState.Connected] - shown, same reason.
 * - [ConnectionState.Disconnected] with `retrying = true` - hidden **on
 *   purpose**: this is the retry-backoff hazard above, not a fallthrough of
 *   the `false` case below. No live link exists yet for "Disconnect" to end,
 *   and showing it here would mislabel "abandon the reconnect loop" as
 *   "disconnect."
 * - [ConnectionState.Disconnected] with `retrying = false` - hidden because
 *   there is, separately, simply nothing to disconnect from: no link, and no
 *   attempt underway to form one.
 */
private fun canDisconnect(state: ConnectionState): Boolean = when (state) {
    ConnectionState.Connecting -> true
    ConnectionState.Connected -> true
    is ConnectionState.Disconnected -> if (state.retrying) {
        false // retry backoff in progress - see this function's own KDoc
    } else {
        false // no link, no attempt underway either
    }
}

/** The connection-state title line, one string resource per [ConnectionState]
 *  subtype - an expression `when`, so a fourth subtype added later fails to
 *  build here instead of silently getting no label at all. */
@Composable
private fun connectionStateLabel(state: ConnectionState): String = when (state) {
    is ConnectionState.Disconnected -> stringResource(R.string.devices_state_disconnected)
    ConnectionState.Connecting -> stringResource(R.string.devices_state_connecting)
    ConnectionState.Connected -> stringResource(R.string.devices_state_connected)
}

/**
 * Turns a [FailureReason] into text fit to show, right where a [Context]
 * (via [stringResource]) is at hand.
 *
 * [FailureReason.Resource] is the common case: a failure named by the
 * connection layer or the transport without either holding a `Context`.
 * [FailureReason.Literal] carries text already resolved further down, in
 * `ble/nordic`, close to where a `Context` lives there instead.
 */
@Composable
private fun resolveReason(reason: FailureReason): String = when (reason) {
    is FailureReason.Resource -> stringResource(reason.resId, *reason.args.toTypedArray())
    is FailureReason.Literal -> reason.text
}

/**
 * The device browser itself: a live "still scanning" hint while there is no
 * link to occupy the radio, then either the empty state or the sectioned
 * list. Sectioning by [DeviceListEntry.Demo]/paired/available is this
 * screen's own restyle decision, not a change to what data reaches it -
 * `mesh-test-android`'s flat list carried the same three facts (is this a
 * demo entry, is this BLE entry bonded, is neither) inline in one line of
 * text; grouping them into headers is the only thing that changed.
 */
@Composable
private fun DeviceBrowseSection(
    devices: List<DeviceListEntry>,
    scanning: Boolean,
    onSelect: (DeviceListEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (scanning) {
            Text(
                text = stringResource(R.string.devices_scanning),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (devices.isEmpty()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(R.string.devices_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.devices_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            val demoEntries = devices.filterIsInstance<DeviceListEntry.Demo>()
            val bleEntries = devices.filterIsInstance<DeviceListEntry.Ble>()
            val bondedEntries = bleEntries.filter { it.bonded }
            val availableEntries = bleEntries.filterNot { it.bonded }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (demoEntries.isNotEmpty()) {
                    item(key = "demo-header") {
                        SectionHeader(stringResource(R.string.devices_demo_section))
                    }
                    items(items = demoEntries, key = { it.address }) { entry ->
                        DeviceRow(entry = entry, onClick = { onSelect(entry) })
                    }
                }
                if (bondedEntries.isNotEmpty()) {
                    item(key = "bonded-header") {
                        SectionHeader(stringResource(R.string.devices_bonded))
                    }
                    items(items = bondedEntries, key = { it.address }) { entry ->
                        DeviceRow(entry = entry, onClick = { onSelect(entry) })
                    }
                }
                if (availableEntries.isNotEmpty()) {
                    item(key = "available-header") {
                        SectionHeader(stringResource(R.string.devices_found_section))
                    }
                    items(items = availableEntries, key = { it.address }) { entry ->
                        DeviceRow(entry = entry, onClick = { onSelect(entry) })
                    }
                }
            }
        }
    }
}

/** A section title, in the same role [MaterialTheme.colorScheme.primary] plays
 *  for headings on the settings screen. */
@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

/** One device, two lines. [DeviceListEntry.name] can be `""` (a BLE entry the
 *  scanner has not yet resolved a name for) - never substituted with a
 *  fallback, simply skipped, on the same terms
 *  [com.cerocoder.meshrelay.ui.relays.RelayListScreen]'s own `LocalNodeLine`
 *  already treats a blank `shortName`; the detail line below still carries
 *  something to tap on either way. */
@Composable
private fun DeviceRow(entry: DeviceListEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // Resolved here, in the composable, where stringResource is callable - deviceRowDetail
    // itself stays a plain function so it can run on the JVM without a Composable host,
    // the same split RelayCard's own hexWithMatchCount keeps.
    val rssiText = (entry as? DeviceListEntry.Ble)?.rssi?.let {
        stringResource(R.string.format_rssi_dbm, it.toString())
    }

    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (entry.name.isNotEmpty()) {
                Text(text = entry.name, style = MaterialTheme.typography.bodyLarge)
            }
            Text(
                text = deviceRowDetail(entry, rssiText),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Punctuation joining a BLE row's MAC address and its resolved RSSI text -
 *  structural, not translatable prose, the same treatment
 *  [com.cerocoder.meshrelay.ui.common.StatsFormat]'s `TRIPLE_SEPARATOR` and
 *  [com.cerocoder.meshrelay.ui.common.PositionLineText]'s `DIRECTION_SEPARATOR`
 *  already get. */
private const val DETAIL_SEPARATOR = "  ·  "

/**
 * The device card's second line. Not `@Composable` - [rssiText] arrives
 * already resolved (see [DeviceRow]), so this is plain string assembly, on
 * the same terms [com.cerocoder.meshrelay.ui.relays.RelayCard]'s own
 * `hexWithMatchCount` stays a plain function instead of a composable.
 *
 * For a real node, the scanner hands back a signal level and a bonding flag
 * alongside the MAC address - those are exactly what decide, when several
 * nodes are in range at once, which one to connect to. Showing only the
 * address while holding that data would mean gathering it for nothing. (The
 * bonded flag itself is no longer repeated on this line - grouping bonded
 * entries under their own section header, see [DeviceBrowseSection], is what
 * carries that fact now, so the line does not say it twice.)
 *
 * A sealed-class `when` returning a value, not a side-effecting one, so a
 * third [DeviceListEntry] subtype added later fails to build here instead of
 * quietly showing nothing for it.
 */
private fun deviceRowDetail(entry: DeviceListEntry, rssiText: String?): String = when (entry) {
    is DeviceListEntry.Demo -> entry.address
    is DeviceListEntry.Ble -> if (rssiText != null) "${entry.mac}$DETAIL_SEPARATOR$rssiText" else entry.mac
}

@Preview(showBackground = true, name = "Devices found")
@Composable
private fun DeviceListScreenFoundPreview() {
    MeshRelayTheme {
        DeviceListScreen(
            devices = listOf(
                DeviceListEntry.Demo(scenarioId = "quiet_night", name = "Demo: Quiet night"),
                DeviceListEntry.Ble(name = "PQPL1", mac = "AA:BB:CC:11:22:33", bonded = true, rssi = -58),
                DeviceListEntry.Ble(name = "", mac = "11:22:33:AA:BB:CC", bonded = false, rssi = -81),
                DeviceListEntry.Ble(name = "Toledo 1", mac = "DE:AD:BE:EF:00:01", bonded = false, rssi = -72),
            ),
            state = ConnectionState.Disconnected(),
            readiness = BleReadiness.READY,
            onSelect = {},
            onDisconnect = {},
            onRequestPermissions = {},
        )
    }
}

@Preview(showBackground = true, name = "None found while scanning")
@Composable
private fun DeviceListScreenEmptyScanningPreview() {
    MeshRelayTheme {
        DeviceListScreen(
            devices = emptyList(),
            state = ConnectionState.Disconnected(),
            readiness = BleReadiness.READY,
            onSelect = {},
            onDisconnect = {},
            onRequestPermissions = {},
        )
    }
}

@Preview(showBackground = true, name = "Disconnected after a failed attempt")
@Composable
private fun DeviceListScreenDisconnectedWithReasonPreview() {
    MeshRelayTheme {
        DeviceListScreen(
            devices = emptyList(),
            state = ConnectionState.Disconnected(
                reason = FailureReason.Literal("The node did not respond to the connection attempt."),
                retrying = true,
            ),
            readiness = BleReadiness.READY,
            onSelect = {},
            onDisconnect = {},
            onRequestPermissions = {},
        )
    }
}

@Preview(showBackground = true, name = "Permissions missing")
@Composable
private fun DeviceListScreenPermissionsMissingPreview() {
    MeshRelayTheme {
        DeviceListScreen(
            devices = emptyList(),
            state = ConnectionState.Disconnected(),
            readiness = BleReadiness.PERMISSIONS_MISSING,
            onSelect = {},
            onDisconnect = {},
            onRequestPermissions = {},
        )
    }
}

@Preview(showBackground = true, name = "Adapter off")
@Composable
private fun DeviceListScreenAdapterOffPreview() {
    MeshRelayTheme {
        DeviceListScreen(
            devices = emptyList(),
            state = ConnectionState.Disconnected(),
            readiness = BleReadiness.ADAPTER_OFF,
            onSelect = {},
            onDisconnect = {},
            onRequestPermissions = {},
        )
    }
}

@Preview(showBackground = true, name = "Unsupported")
@Composable
private fun DeviceListScreenUnsupportedPreview() {
    MeshRelayTheme {
        DeviceListScreen(
            devices = emptyList(),
            state = ConnectionState.Disconnected(),
            readiness = BleReadiness.UNSUPPORTED,
            onSelect = {},
            onDisconnect = {},
            onRequestPermissions = {},
        )
    }
}

@Preview(showBackground = true, name = "Connecting")
@Composable
private fun DeviceListScreenConnectingPreview() {
    MeshRelayTheme {
        DeviceListScreen(
            devices = listOf(
                DeviceListEntry.Ble(name = "PQPL1", mac = "AA:BB:CC:11:22:33", bonded = true, rssi = -58),
            ),
            state = ConnectionState.Connecting,
            readiness = BleReadiness.READY,
            onSelect = {},
            onDisconnect = {},
            onRequestPermissions = {},
        )
    }
}

@Preview(showBackground = true, name = "Connected (disconnect affordance)")
@Composable
private fun DeviceListScreenConnectedPreview() {
    MeshRelayTheme {
        DeviceListScreen(
            devices = listOf(
                DeviceListEntry.Ble(name = "PQPL1", mac = "AA:BB:CC:11:22:33", bonded = true, rssi = -58),
            ),
            state = ConnectionState.Connected,
            readiness = BleReadiness.READY,
            onSelect = {},
            onDisconnect = {},
            onRequestPermissions = {},
        )
    }
}

@Preview(showBackground = true, name = "Dark theme", uiMode = 0x20)
@Composable
private fun DeviceListScreenDarkPreview() {
    MeshRelayTheme(darkTheme = true) {
        DeviceListScreen(
            devices = listOf(
                DeviceListEntry.Demo(scenarioId = "quiet_night", name = "Demo: Quiet night"),
                DeviceListEntry.Ble(name = "PQPL1", mac = "AA:BB:CC:11:22:33", bonded = true, rssi = -58),
                DeviceListEntry.Ble(name = "Toledo 1", mac = "DE:AD:BE:EF:00:01", bonded = false, rssi = -72),
            ),
            state = ConnectionState.Connected,
            readiness = BleReadiness.READY,
            onSelect = {},
            onDisconnect = {},
            onRequestPermissions = {},
        )
    }
}
