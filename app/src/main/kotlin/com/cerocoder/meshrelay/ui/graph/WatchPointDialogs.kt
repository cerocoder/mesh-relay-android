package com.cerocoder.meshrelay.ui.graph

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.stats.model.WatchPoint
import com.cerocoder.meshrelay.ui.common.LocalTimeFormat
import com.cerocoder.meshrelay.ui.common.StatsFormat
import java.util.Locale

/**
 * The Yes/No pop-up that guards removing a bookmark. Shared by the crosshair's `-` and the
 * Marks list's `-`, so the two can never ask different questions. Nothing is removed until
 * [onConfirm]; both buttons and a tap outside dismiss.
 */
@Composable
fun RemoveWatchPointDialog(
    point: WatchPoint,
    locale: Locale,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.graph_watch_remove)) },
        text = {
            Text(
                stringResource(
                    R.string.graph_watch_remove_body,
                    StatsFormat.graphTimestamp(point.atMillis, locale, LocalTimeFormat.current),
                ),
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_yes)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_no)) } },
    )
}

/**
 * The bookmark's info pop-up: its text, editable, with Ok and Cancel. The draft is seeded from
 * the stored note each time the pop-up opens, so Cancel discards the edit.
 */
@Composable
fun WatchPointNoteDialog(
    point: WatchPoint,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(point.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.graph_watch_info_title)) },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text(stringResource(R.string.graph_watch_note_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text(stringResource(R.string.common_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
