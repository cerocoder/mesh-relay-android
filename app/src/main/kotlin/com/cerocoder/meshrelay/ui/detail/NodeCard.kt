package com.cerocoder.meshrelay.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.stats.NodeId
import com.cerocoder.meshrelay.stats.model.LocationInfo
import com.cerocoder.meshrelay.stats.model.NodeRecord
import com.cerocoder.meshrelay.stats.model.TelemetryRecord
import com.cerocoder.meshrelay.ui.common.LocalRelativeClock
import com.cerocoder.meshrelay.ui.common.NodeIdText
import com.cerocoder.meshrelay.ui.common.PositionLine
import com.cerocoder.meshrelay.ui.common.StatsFormat
import com.cerocoder.meshrelay.ui.preview.SampleData
import com.cerocoder.meshrelay.ui.theme.MeshRelayTheme
import java.util.Locale

/**
 * One node, everything known about it. Ports the per-candidate block of
 * `build_detail_lines`, mesh_stats.py:1849-1910, as a card rather than a run of
 * indented text lines.
 *
 * This card serves two different callers, and [index]/[onSkip] are both
 * nullable because of it:
 * - [MatchingNodesTab] (this file's sibling) draws one of these per candidate
 *   behind a relay's one-byte guess - [index] is that candidate's `[n]`
 *   position in the list, and [onSkip] lets the user rule the candidate out.
 * - Task 24's neighbour detail tab draws exactly one of these for a node whose
 *   identity is already known from direct reception, not guessed from a byte -
 *   there is nothing to number and nothing to rule out, so both are `null`
 *   there. This card renders one candidate; it never claims [record] *is* the
 *   relay - that judgment is the whole point of the tab it lives in, and stays
 *   with the human looking at the list, not this component.
 *
 * Two things this card does not do, both spelled out in this task's brief:
 * - **Role.** [NodeRecord.role] already reads `"CLIENT"` for a heard-but-unset
 *   role - the protocol's own default - so this card adds no second default on
 *   top of it. A `null` role (no `User` message ever heard at all) hides the
 *   row instead.
 * - **Firmware.** `NodeInfo` - what [NodeRecord] is built from - carries no
 *   `firmware_version` field at all, unlike `DeviceMetadata`. The terminal
 *   tool reads one from its own local-node info and can never find it for a
 *   remote node (`node_info.get("firmwareVersion") or
 *   node_info.get("firmware_version")`, mesh_stats.py:1894). [firmwareVersion]
 *   below is `null` unconditionally, with nothing in [NodeRecord] to ever
 *   change that - the row is kept, in its ported position, for a future task
 *   that threads real firmware data through [NodeRecord] to fill in, not
 *   invented here.
 */
@Composable
fun NodeCard(
    index: Int?,
    record: NodeRecord,
    location: LocationInfo,
    telemetry: TelemetryRecord?,
    meshviewUrl: String?,
    onSkip: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val locale = displayLocale()
    var skipDialogVisible by remember { mutableStateOf(false) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header: [n] (candidate list only) + node id, skip action trailing.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (index != null) {
                    Text(text = StatsFormat.candidateIndex(index, locale), style = MaterialTheme.typography.bodySmall)
                }
                NodeIdText(nodeNum = record.num)
                Spacer(modifier = Modifier.weight(1f))
                if (onSkip != null) {
                    TextButton(onClick = { skipDialogVisible = true }) {
                        Text(stringResource(R.string.action_skip_node))
                    }
                }
            }

            // User fields all arrive (or not) together - NodeRecord.fromProto reads
            // every one of them off the same optional `user` submessage - but each
            // row is gated on its own field independently, exactly as the original
            // gates each of its four `if "x" in user:` lines separately
            // (mesh_stats.py:1860-1870).
            if (record.longName != null) {
                LabelValueRow(stringResource(R.string.node_long_name), record.longName)
            }
            if (record.shortName != null) {
                LabelValueRow(stringResource(R.string.node_short_name), record.shortName)
            }
            if (record.role != null) {
                LabelValueRow(stringResource(R.string.node_role), record.role)
            }
            if (record.hwModel != null) {
                LabelValueRow(stringResource(R.string.node_hardware), record.hwModel)
            }

            // No preceding "Position" label - PositionLine renders nothing at all
            // when there is truly nothing to say (no coordinates, no altitude, no
            // Meshview link), and a label with nothing under it would look like a
            // missing row rather than an absent one. Matches
            // [com.cerocoder.meshrelay.ui.neighbours.NeighbourListScreen]'s own
            // `LocalNodeLine`, which calls this composable the same bare way.
            PositionLine(info = location, nodeNum = record.num, meshviewUrl = meshviewUrl)

            record.dbSnr?.let { snr ->
                LabelValueRow(
                    label = stringResource(R.string.node_last_snr_db),
                    value = stringResource(R.string.format_snr_db, StatsFormat.nodeDatabaseSnr(snr, locale)),
                )
            }

            // Absolute, unlike every other "when" in this app - the one deliberate
            // exception. mesh_stats.py:1891 renders this exact field ("Last Heard in
            // DB") as an absolute `%Y-%m-%d %H:%M:%S`, while :1639-1648's *live*
            // last-packet age (what AgeLabel ports elsewhere in this app) renders as
            // clock time plus "Xs ago" - the original itself draws this line between
            // a session age (never more than hours old) and a database timestamp
            // (which can be weeks old, well past anything AgeText's buckets cover).
            // StatsFormat.nodeDatabaseLastHeard does the conversion and the
            // locale-aware formatting, in the device's own zone (ZoneId.systemDefault,
            // matching the original's naive datetime.fromtimestamp); nothing here does
            // arithmetic on the raw epoch-seconds value. format_last_heard_db appends
            // "(local time)" to the rendered value itself, not just a code comment -
            // the ambiguity ("is this UTC or local?") is exactly what a user
            // cross-checking against another Meshtastic tool would otherwise ask.
            record.lastHeardEpochSeconds?.let { epochSeconds ->
                LabelValueRow(
                    label = stringResource(R.string.node_last_heard_db),
                    value = stringResource(
                        R.string.format_last_heard_db,
                        StatsFormat.nodeDatabaseLastHeard(epochSeconds, locale),
                    ),
                )
            }

            firmwareVersion?.let { firmware ->
                LabelValueRow(stringResource(R.string.node_firmware), firmware)
            }

            if (telemetry != null && telemetry.lastUptimeSeconds != null) {
                val uptime = StatsFormat.uptimeParts(telemetry.lastUptimeSeconds)
                LabelValueRow(
                    label = stringResource(R.string.node_uptime),
                    value = stringResource(R.string.format_uptime, uptime.days, uptime.hours, uptime.minutes),
                )
                LabelValueRow(
                    label = stringResource(R.string.node_restarts),
                    value = telemetry.observedRestartCount.toString(),
                )
            }

            // Sorted by key, ports `sorted(trec.history_metrics.items())`
            // (mesh_stats.py:1908); only a metric with at least one sample is shown,
            // ports its `if hist.count > 0` guard (mesh_stats.py:1909).
            val telemetryRows = telemetry?.metrics
                ?.filterValues { it.stats.hasData }
                ?.toSortedMap()
                .orEmpty()
            if (telemetryRows.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = stringResource(R.string.node_telemetry), style = MaterialTheme.typography.labelSmall)
                    telemetryRows.forEach { (key, history) ->
                        // The key is the protobuf field name itself, shown verbatim -
                        // NodeDirectory.applyTelemetry's own KDoc documents why there
                        // is no translation table for these.
                        LabelValueRow(label = key, value = StatsFormat.telemetryMetricValue(history.stats.lastVal, locale))
                    }
                }
            }

            LabelValueRow(
                label = stringResource(R.string.node_public_key_present),
                value = stringResource(if (record.hasPublicKey) R.string.common_yes else R.string.common_no),
            )
        }
    }

    // onSkip?.let rather than an outer `onSkip != null` check: this dialog's
    // buttons are built as nested lambdas AlertDialog stores and invokes later,
    // and `skip` below is a fresh, statically non-null binding those lambdas
    // close over - simpler than leaning on a smart cast surviving into them.
    if (skipDialogVisible) {
        onSkip?.let { skip ->
            AlertDialog(
                onDismissRequest = { skipDialogVisible = false },
                title = { Text(stringResource(R.string.action_skip_confirm_title)) },
                text = { Text(stringResource(R.string.action_skip_confirm_body, NodeId.format(record.num))) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            skipDialogVisible = false
                            skip()
                        },
                    ) {
                        Text(stringResource(R.string.action_skip_node))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { skipDialogVisible = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
            )
        }
    }
}

/**
 * See this file's own KDoc: `NodeInfo` has no `firmware_version` field, so
 * there is nothing in [NodeRecord] this could ever read - kept as its own
 * binding, rather than inlined at the call site, so the single place that
 * would need to change (once a future task threads real firmware data
 * through) is obvious.
 */
private val firmwareVersion: String? = null

/** One label/value pair, label leading and value trailing on the same line.
 *  Mirrors [com.cerocoder.meshrelay.ui.detail.DetailSummary]'s private
 *  `SummaryRow` of the same shape - that one is not public, so this is a
 *  second, independent copy. */
@Composable
private fun LabelValueRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The configured display locale. A second, independent copy of the identical
 *  private helper every other card in this app already carries - see
 *  [com.cerocoder.meshrelay.ui.relays.RelayCard]'s copy for the full reasoning. */
@Composable
private fun displayLocale(): Locale {
    val locales = LocalConfiguration.current.locales
    return if (locales.isEmpty()) Locale.getDefault() else locales.get(0)
}

/**
 * Supplies a stable "now" to [LocalRelativeClock] for previews - without it,
 * this composition local defaults to `0L`, and [PositionLine] (called
 * directly by [NodeCard], not through a screen that would otherwise provide
 * one) computes its own source-aged text
 * ([com.cerocoder.meshrelay.ui.common.PositionLineText.parts]'s
 * `AgeBucket.of(nowMillis - atMillis)`) against that instead of a real clock -
 * every real timestamp below would then look like it arrived before `1970`
 * and land in [com.cerocoder.meshrelay.stats.AgeBucket.UNKNOWN] ("??"),
 * regardless of how fresh it actually is. Mirrors the identical private
 * helper every screen-level preview in this app already carries -
 * [com.cerocoder.meshrelay.ui.relays.RelayListScreen]'s copy documents the
 * full reasoning.
 */
@Composable
private fun PreviewClock(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalRelativeClock provides System.currentTimeMillis()) {
        content()
    }
}

@Preview(showBackground = true, name = "Candidate, full data")
@Composable
private fun NodeCardCandidatePreview() {
    MeshRelayTheme {
        PreviewClock {
            NodeCard(
                index = 1,
                record = SampleData.directory.node(SampleData.NUM_GETAFE_ROUTER)!!,
                location = SampleData.directory.locationInfo(SampleData.NUM_GETAFE_ROUTER, SampleData.directory.localPosition()),
                telemetry = SampleData.directory.telemetry(SampleData.NUM_GETAFE_ROUTER),
                meshviewUrl = "https://meshview.meshtastic.es",
                onSkip = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Candidate, telemetry with restarts")
@Composable
private fun NodeCardTelemetryPreview() {
    MeshRelayTheme {
        PreviewClock {
            NodeCard(
                index = 2,
                record = SampleData.directory.node(SampleData.NUM_YUNCOS_REINICIO)!!,
                location = SampleData.directory.locationInfo(SampleData.NUM_YUNCOS_REINICIO, SampleData.directory.localPosition()),
                telemetry = SampleData.directory.telemetry(SampleData.NUM_YUNCOS_REINICIO),
                meshviewUrl = "https://meshview.meshtastic.es",
                onSkip = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Candidate, no position, no name ever heard")
@Composable
private fun NodeCardNoPositionPreview() {
    MeshRelayTheme {
        PreviewClock {
            NodeCard(
                index = 3,
                record = SampleData.directory.node(SampleData.NUM_TOLEDO_BAJA)!!,
                location = SampleData.directory.locationInfo(SampleData.NUM_TOLEDO_BAJA, SampleData.directory.localPosition()),
                telemetry = SampleData.directory.telemetry(SampleData.NUM_TOLEDO_BAJA),
                meshviewUrl = "https://meshview.meshtastic.es",
                onSkip = {},
            )
        }
    }
}

@Preview(showBackground = true, name = "Neighbour identity - no index, no skip")
@Composable
private fun NodeCardNeighbourPreview() {
    MeshRelayTheme {
        PreviewClock {
            NodeCard(
                index = null,
                record = SampleData.directory.node(SampleData.NUM_ILLESCAS_MUDO)!!,
                location = SampleData.directory.locationInfo(SampleData.NUM_ILLESCAS_MUDO, SampleData.directory.localPosition()),
                telemetry = SampleData.directory.telemetry(SampleData.NUM_ILLESCAS_MUDO),
                meshviewUrl = "https://meshview.meshtastic.es",
                onSkip = null,
            )
        }
    }
}

@Preview(showBackground = true, name = "Dark theme", uiMode = 0x20)
@Composable
private fun NodeCardDarkPreview() {
    MeshRelayTheme(darkTheme = true) {
        PreviewClock {
            NodeCard(
                index = 1,
                record = SampleData.directory.node(SampleData.NUM_GETAFE_ROUTER)!!,
                location = SampleData.directory.locationInfo(SampleData.NUM_GETAFE_ROUTER, SampleData.directory.localPosition()),
                telemetry = SampleData.directory.telemetry(SampleData.NUM_GETAFE_ROUTER),
                meshviewUrl = "https://meshview.meshtastic.es",
                onSkip = {},
            )
        }
    }
}
