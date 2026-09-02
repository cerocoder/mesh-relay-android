package com.cerocoder.meshrelay.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.settings.AppSettings
import com.cerocoder.meshrelay.settings.GaugeMode
import com.cerocoder.meshrelay.settings.LanguageOption
import com.cerocoder.meshrelay.settings.MapProvider
import com.cerocoder.meshrelay.stats.SortMode
import com.cerocoder.meshrelay.ui.common.NodeIdText
import com.cerocoder.meshrelay.ui.preview.SampleData
import com.cerocoder.meshrelay.ui.common.SortModeLabels
import com.cerocoder.meshrelay.ui.theme.MeshRelayTheme

/**
 * Everything the terminal tool configured with command-line flags and single
 * keypresses (`--meshview-url`, `--skip-relay`, the `[M]` gauge-mode toggle,
 * the default sort mode passed on the command line) lives here instead, as one
 * persistent settings surface rather than options that reset each run.
 *
 * Stateless like every other screen in this port: [settings] and
 * [skippedRelayNodes] are the entire truth this screen shows, every mutation
 * goes back out through [onUpdate] and the two skip-list callbacks, and the
 * only local state is the clear-all confirmation dialog's visibility - the
 * same shape [com.cerocoder.meshrelay.ui.detail.MatchingNodesTab]'s own
 * clear-all dialog already uses. [onUpdate] mirrors
 * [com.cerocoder.meshrelay.settings.SettingsRepository.update]'s own
 * `(AppSettings) -> AppSettings` transform shape exactly, so a caller can wire
 * this screen straight to the repository without an adapter in between.
 *
 * The Meshview URL field is deliberately allowed to be blank: an empty
 * [AppSettings.meshviewUrl] is what every other screen's own `meshviewUrl:
 * String?` parameter treats as "no Meshview links anywhere" (see
 * [com.cerocoder.meshrelay.ui.detail.MatchingNodesTab] and
 * [com.cerocoder.meshrelay.ui.detail.RemoteNodeScreen], both of which take
 * that parameter through unchanged), not an error state to block on.
 *
 * Skipped node numbers render through [NodeIdText] - [NodeIdText] wraps
 * [com.cerocoder.meshrelay.stats.NodeId.format], and the whole point of
 * showing the `!xxxxxxxx` form here is that it is exactly what the terminal
 * tool's own `--skip-relay` flag accepts, so a value read off this list can be
 * typed into that flag unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    skippedRelayNodes: Set<Int>,
    appVersion: String,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onRemoveSkipped: (nodeNum: Int) -> Unit,
    onClearAllSkipped: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var clearAllDialogVisible by remember { mutableStateOf(false) }

    // Set<Int> has no defined iteration order; sorted() gives every
    // recomposition (and every itemsIndexed-free `items(..., key = ...)` call
    // below) the same stable order to key and diff against, exactly as
    // RemoteNodesTab's own sortedByDescending does for its packet-count rows.
    val sortedSkipped = skippedRelayNodes.sorted()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.settings_title)) },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item(key = "language-header") {
                SectionHeader(stringResource(R.string.settings_language))
            }
            items(items = LanguageOption.entries, key = { "language-${it.name}" }) { option ->
                RadioOptionRow(
                    label = stringResource(languageLabelRes(option)),
                    selected = settings.language == option,
                    onClick = { onUpdate { current -> current.copy(language = option) } },
                )
            }

            item(key = "gauge-header") {
                SectionHeader(stringResource(R.string.settings_gauge_mode))
            }
            items(items = GaugeMode.entries, key = { "gauge-${it.name}" }) { mode ->
                RadioOptionRow(
                    label = stringResource(gaugeModeLabelRes(mode)),
                    selected = settings.gaugeMode == mode,
                    onClick = { onUpdate { current -> current.copy(gaugeMode = mode) } },
                )
            }

            item(key = "map-provider-header") {
                SectionHeader(stringResource(R.string.settings_map_provider))
            }
            items(items = MapProvider.entries, key = { "map-provider-${it.name}" }) { provider ->
                RadioOptionRow(
                    label = stringResource(mapProviderLabelRes(provider)),
                    selected = settings.mapProvider == provider,
                    onClick = { onUpdate { current -> current.copy(mapProvider = provider) } },
                )
            }

            item(key = "sort-header") {
                SectionHeader(stringResource(R.string.settings_default_sort))
            }
            items(items = SortMode.entries, key = { "sort-${it.name}" }) { mode ->
                RadioOptionRow(
                    label = stringResource(SortModeLabels.labelOf(mode)),
                    selected = settings.defaultSortMode == mode,
                    onClick = { onUpdate { current -> current.copy(defaultSortMode = mode) } },
                )
            }

            item(key = "meshview-url") {
                OutlinedTextField(
                    value = settings.meshviewUrl,
                    onValueChange = { newValue -> onUpdate { current -> current.copy(meshviewUrl = newValue) } },
                    label = { Text(stringResource(R.string.settings_meshview_url)) },
                    placeholder = { Text(stringResource(R.string.settings_meshview_url_hint)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            item(key = "keep-screen-on") {
                SwitchRow(
                    label = stringResource(R.string.settings_keep_screen_on),
                    checked = settings.keepScreenOn,
                    onCheckedChange = { checked -> onUpdate { current -> current.copy(keepScreenOn = checked) } },
                )
            }

            item(key = "background-collection") {
                SwitchRow(
                    label = stringResource(R.string.settings_background_collection),
                    checked = settings.backgroundCollection,
                    onCheckedChange = { checked ->
                        onUpdate { current -> current.copy(backgroundCollection = checked) }
                    },
                )
            }
            item(key = "background-collection-summary") {
                Text(
                    text = stringResource(R.string.settings_background_collection_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            item(key = "use-phone-location") {
                SwitchRow(
                    label = stringResource(R.string.settings_use_phone_location),
                    checked = settings.usePhoneLocation,
                    onCheckedChange = { checked ->
                        onUpdate { current -> current.copy(usePhoneLocation = checked) }
                    },
                )
            }
            item(key = "use-phone-location-summary") {
                Text(
                    text = stringResource(R.string.settings_use_phone_location_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            item(key = "skipped-header") {
                SectionHeader(stringResource(R.string.settings_skipped_nodes))
            }
            if (sortedSkipped.isEmpty()) {
                item(key = "skipped-empty") {
                    Text(
                        text = stringResource(R.string.settings_skipped_nodes_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            } else {
                items(items = sortedSkipped, key = { "skipped-$it" }) { nodeNum ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        NodeIdText(nodeNum = nodeNum)
                        IconButton(onClick = { onRemoveSkipped(nodeNum) }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.action_remove_skipped_node),
                            )
                        }
                    }
                }
                item(key = "skipped-clear-all") {
                    TextButton(
                        onClick = { clearAllDialogVisible = true },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(stringResource(R.string.settings_clear_all_skipped))
                    }
                }
            }

            item(key = "about-header") {
                SectionHeader(stringResource(R.string.settings_about))
            }
            item(key = "about-version") {
                LabelValueRow(label = stringResource(R.string.settings_version), value = appVersion)
            }
            item(key = "about-licence") {
                LabelValueRow(
                    label = stringResource(R.string.settings_licence),
                    value = stringResource(R.string.settings_upstream),
                )
            }
        }
    }

    // Same dialog shape as MatchingNodesTab's own clear-all confirmation, but
    // NOT its string pair: action_clear_skipped_confirm_title/body say "for
    // this relay", which is only true there. This screen's clear-all is
    // global - every skipped node, for every relay - so it gets its own
    // settings_clear_all_skipped_confirm_title/body instead. A dialog whose
    // text understates what the button actually does is worse than no
    // dialog: it reads as a safeguard while giving false reassurance about
    // the blast radius, and skip decisions are judgement work accumulated
    // relay by relay across a survey - not something to lose by surprise.
    // The confirm button still reuses this screen's own trigger label rather
    // than introducing a third piece of clear-all wording; dismissing never
    // mutates anything.
    if (clearAllDialogVisible) {
        AlertDialog(
            onDismissRequest = { clearAllDialogVisible = false },
            title = { Text(stringResource(R.string.settings_clear_all_skipped_confirm_title)) },
            text = { Text(stringResource(R.string.settings_clear_all_skipped_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearAllDialogVisible = false
                        onClearAllSkipped()
                    },
                ) {
                    Text(stringResource(R.string.settings_clear_all_skipped))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearAllDialogVisible = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

private fun languageLabelRes(option: LanguageOption): Int = when (option) {
    LanguageOption.SYSTEM -> R.string.settings_language_system
    LanguageOption.EN -> R.string.settings_language_english
    LanguageOption.ES -> R.string.settings_language_spanish
}

private fun gaugeModeLabelRes(mode: GaugeMode): Int = when (mode) {
    GaugeMode.SIMPLE -> R.string.gauge_simple
    GaugeMode.COMPLEX -> R.string.gauge_complex
}

private fun mapProviderLabelRes(provider: MapProvider): Int = when (provider) {
    MapProvider.GOOGLE -> R.string.map_provider_google
    MapProvider.OPEN_STREET_MAP -> R.string.map_provider_osm
}

/** One `RadioButton` plus its label, both toggled by tapping anywhere in the
 *  row - the row itself carries the `Role.RadioButton` semantics so a screen
 *  reader announces it correctly, and the button's own `onClick` stays `null`
 *  so the row is the single source of the click exactly once. */
@Composable
private fun RadioOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** A label and a trailing `Switch` sharing a row, for the plain on/off settings
 *  ([AppSettings.keepScreenOn], [AppSettings.backgroundCollection],
 *  [AppSettings.usePhoneLocation]). */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** A section title, in the same role [MaterialTheme.colorScheme.primary]
 *  plays for headings elsewhere in this port. */
@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** A label/value pair on one row, used by the About section - the label reads
 *  in the muted `onSurfaceVariant` tone, the value in the ordinary body tone,
 *  matching the label/value convention
 *  [com.cerocoder.meshrelay.ui.detail.NodeCard]'s own rows already use. */
@Composable
private fun LabelValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Preview(showBackground = true, name = "Populated skip list")
@Composable
private fun SettingsScreenPopulatedSkipListPreview() {
    MeshRelayTheme {
        SettingsScreen(
            settings = AppSettings(),
            skippedRelayNodes = setOf(
                SampleData.NUM_TOLEDO_BAJA,
                SampleData.NUM_SIERRA_LARGA,
                SampleData.NUM_ILLESCAS_MUDO,
            ),
            appVersion = "1.0.0",
            onUpdate = {},
            onRemoveSkipped = {},
            onClearAllSkipped = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Empty skip list")
@Composable
private fun SettingsScreenEmptySkipListPreview() {
    MeshRelayTheme {
        SettingsScreen(
            settings = AppSettings(),
            skippedRelayNodes = emptySet(),
            appVersion = "1.0.0",
            onUpdate = {},
            onRemoveSkipped = {},
            onClearAllSkipped = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Empty Meshview URL")
@Composable
private fun SettingsScreenEmptyMeshviewUrlPreview() {
    MeshRelayTheme {
        SettingsScreen(
            settings = AppSettings(meshviewUrl = ""),
            skippedRelayNodes = setOf(SampleData.NUM_TOLEDO_BAJA),
            appVersion = "1.0.0",
            onUpdate = {},
            onRemoveSkipped = {},
            onClearAllSkipped = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "Dark theme", uiMode = 0x20)
@Composable
private fun SettingsScreenDarkPreview() {
    MeshRelayTheme(darkTheme = true) {
        SettingsScreen(
            settings = AppSettings(gaugeMode = GaugeMode.COMPLEX, defaultSortMode = SortMode.AVG_SNR),
            skippedRelayNodes = setOf(SampleData.NUM_TOLEDO_BAJA, SampleData.NUM_SIERRA_LARGA),
            appVersion = "1.0.0",
            onUpdate = {},
            onRemoveSkipped = {},
            onClearAllSkipped = {},
            onBack = {},
        )
    }
}
