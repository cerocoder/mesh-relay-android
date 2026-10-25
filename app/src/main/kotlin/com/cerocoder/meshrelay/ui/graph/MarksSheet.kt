package com.cerocoder.meshrelay.ui.graph

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.stats.model.PositionOrigin
import com.cerocoder.meshrelay.stats.model.WatchPoint
import com.cerocoder.meshrelay.ui.common.LocalMapProvider
import com.cerocoder.meshrelay.ui.common.LocalPreferInstalledMapApp
import com.cerocoder.meshrelay.ui.common.LocalTimeFormat
import com.cerocoder.meshrelay.ui.common.StatsFormat
import com.cerocoder.meshrelay.ui.common.openPosition
import com.cerocoder.meshrelay.ui.theme.RssiTrack
import com.cerocoder.meshrelay.ui.theme.SnrTrack
import java.util.Locale

private val MarkIconSize = 40.dp

/**
 * Every bookmark held for the node on screen, oldest-session or newest, in a sheet - the one
 * place a bookmark can be reached when its measurement is no longer in the chart (a node file
 * was loaded, or the ring evicted the sample).
 *
 * Sorted by timestamp, newest first, the order the chart itself reads in. Each entry carries
 * the same three pictograms as the crosshair - globe, `i`, `-` - and the same pop-ups, through
 * [RemoveWatchPointDialog] and [WatchPointNoteDialog]. [onChange] receives the node's whole new
 * list, as the crosshair's own edits do. The sheet closes itself in the screen when the last
 * bookmark is removed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarksSheet(
    points: List<WatchPoint>,
    locale: Locale,
    /** True when the bookmark's measurement is in the chart now, so a tap can take the crosshair there. */
    inChart: (WatchPoint) -> Boolean,
    /** Puts the crosshair on the bookmark's measurement; only called for a bookmark [inChart] accepts. */
    onJump: (WatchPoint) -> Unit,
    onChange: (List<WatchPoint>) -> Unit,
    onDismiss: () -> Unit,
) {
    var removing by remember { mutableStateOf<WatchPoint?>(null) }
    var editing by remember { mutableStateOf<WatchPoint?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.graph_marks_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn {
            items(points.sortedByDescending { it.atMillis }, key = { it.atMillis }) { point ->
                MarkEntry(
                    point = point,
                    locale = locale,
                    jumpable = inChart(point),
                    onJump = { onJump(point) },
                    onEdit = { editing = point },
                    onRemove = { removing = point },
                )
                HorizontalDivider()
            }
        }
    }

    removing?.let { point ->
        RemoveWatchPointDialog(
            point = point,
            locale = locale,
            onConfirm = {
                removing = null
                onChange(points.filterNot { it.atMillis == point.atMillis })
            },
            onDismiss = { removing = null },
        )
    }
    editing?.let { point ->
        WatchPointNoteDialog(
            point = point,
            onSave = { note ->
                editing = null
                onChange(points.map { if (it.atMillis == point.atMillis) it.copy(note = note) else it })
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun MarkEntry(
    point: WatchPoint,
    locale: Locale,
    jumpable: Boolean,
    onJump: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val mapProvider = LocalMapProvider.current
    val preferInstalledApp = LocalPreferInstalledMapApp.current
    val position = point.position
    val pinLabel = when (position?.origin) {
        PositionOrigin.NODE -> stringResource(R.string.graph_pin_from_node)
        PositionOrigin.PHONE -> stringResource(R.string.graph_pin_from_phone)
        null -> ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Only the text block is the tap target, so the three pictograms keep their own meaning.
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = jumpable, onClick = onJump)
                .padding(vertical = 8.dp),
        ) {
            Text(
                text = StatsFormat.graphTimestamp(point.atMillis, locale, LocalTimeFormat.current),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The observer's altitude at that measurement, worded as the status strip and the
                // crosshair word it; absent when the bookmark's position had none.
                position?.altitude?.let { altitude ->
                    Text(
                        text = stringResource(
                            R.string.format_altitude_field,
                            stringResource(R.string.format_altitude_m, altitude),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(R.string.format_rssi_dbm, StatsFormat.sampleRssi(point.rssi, locale)),
                    color = RssiTrack,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.format_snr_db, StatsFormat.sampleSnr(point.snr, locale)),
                    color = SnrTrack,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (point.note.isNotEmpty()) {
                Text(
                    text = point.note,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(
            onClick = {
                position?.let {
                    openPosition(
                        uriHandler = uriHandler,
                        preferInstalledApp = preferInstalledApp,
                        provider = mapProvider,
                        lat = it.latitude,
                        lon = it.longitude,
                        label = pinLabel,
                    )
                }
            },
            enabled = position != null,
            modifier = Modifier.size(MarkIconSize),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_field_coordinates),
                contentDescription = stringResource(
                    when (position?.origin) {
                        PositionOrigin.NODE -> R.string.graph_position_from_node
                        PositionOrigin.PHONE -> R.string.graph_position_from_phone
                        null -> R.string.graph_open_map
                    },
                ),
            )
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(MarkIconSize)) {
            Icon(painter = painterResource(R.drawable.ic_watch_info), contentDescription = stringResource(R.string.graph_watch_info))
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(MarkIconSize)) {
            Icon(painter = painterResource(R.drawable.ic_watch_remove), contentDescription = stringResource(R.string.graph_watch_remove))
        }
    }
}
