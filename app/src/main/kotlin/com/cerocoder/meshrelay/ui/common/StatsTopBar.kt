package com.cerocoder.meshrelay.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.settings.GaugeMode
import com.cerocoder.meshrelay.stats.SortMode

/**
 * The node-database reload action, which only the relay screen has: the
 * neighbour list is built from live traffic rather than a database fetch, so
 * there is nothing there for a reload to refresh. Passing `null` leaves the item
 * out of the menu entirely rather than showing a disabled one.
 */
data class ReloadAction(val inProgress: Boolean, val onReload: () -> Unit)

/**
 * The sort control, or `null` on a screen that sorts nothing (My node).
 *
 * [available] is per-screen rather than always `SortMode.entries`: the neighbour
 * list leaves out [SortMode.KNOWN_NODES], which counts something a neighbour does
 * not have. [mode] is what the menu ticks, and on the neighbour list it is the
 * mode *after* [SortMode.forNeighbours] - so when an unofferable mode arrives from
 * the relay screen, the tick and the strip agree with the order actually applied.
 */
data class SortAction(
    val mode: SortMode,
    val available: List<SortMode>,
    val onSet: (SortMode) -> Unit,
)

/**
 * The app bar shared by the relay and neighbour lists.
 *
 * One composable rather than the two near-identical copies these screens carried
 * before, and with three actions in the bar rather than six. That count is field
 * issue F-1: `TopAppBar` measures its actions first and gives the title whatever
 * is left, and two of the six actions were `TextButton`s showing words (`Simple`,
 * `Pause`) rather than icons, so they were far wider than the component budgets
 * for. On a 1080 px phone the remainder was a few characters wide and the title
 * wrapped mid-word - `Rela` / `ys` - on the first screen after connecting. Spanish
 * would have been worse still (`Repetidores` against a wider action row).
 *
 * Constraining the title would have hidden that; the overload was the defect, so
 * what changed is the number of actions. Sort and pause stay in the bar, being
 * the two used while watching traffic. Gauge style, the database reload, reset
 * and settings moved into an overflow menu, which is also where a seventh action
 * should go rather than back onto the bar.
 *
 * The reset confirmation lives here too, so that dismissing or confirming it is
 * this component's business and not repeated on every screen that offers a reset.
 * [onReset] is called only after the user confirms.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsTopBar(
    title: String,
    sort: SortAction?,
    gaugeMode: GaugeMode,
    onSetGaugeMode: (GaugeMode) -> Unit,
    paused: Boolean,
    onTogglePause: () -> Unit,
    onReset: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    reload: ReloadAction? = null,
) {
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var overflowExpanded by remember { mutableStateOf(false) }
    var resetDialogVisible by remember { mutableStateOf(false) }

    TopAppBar(
        modifier = modifier,
        title = { Text(title) },
        actions = {
            if (sort != null) {
                val sortDescription = stringResource(R.string.action_sort)
                Box {
                    IconButton(
                        onClick = { sortMenuExpanded = true },
                        modifier = Modifier.semantics { contentDescription = sortDescription },
                    ) {
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false },
                    ) {
                        sort.available.forEach { mode ->
                            val selected = mode == sort.mode
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(SortModeLabels.labelOf(mode)),
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    )
                                },
                                leadingIcon = if (selected) {
                                    { Icon(Icons.Filled.Check, contentDescription = null) }
                                } else {
                                    null
                                },
                                onClick = {
                                    sortMenuExpanded = false
                                    sort.onSet(mode)
                                },
                            )
                        }
                    }
                }
            }

            // Pause keeps its place in the bar - it is the action taken while
            // reading the screen, and burying it behind two taps would defeat it -
            // but as an icon rather than the word it used to be. material-icons-core
            // has PlayArrow and no Pause, so the paused half of the toggle is a
            // hand-authored drawable; see res/drawable/ic_action_pause.xml.
            IconButton(onClick = onTogglePause) {
                if (paused) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.action_resume),
                    )
                } else {
                    Icon(
                        painter = painterResource(R.drawable.ic_action_pause),
                        contentDescription = stringResource(R.string.action_pause),
                    )
                }
            }

            Box {
                IconButton(onClick = { overflowExpanded = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_action_more),
                        contentDescription = stringResource(R.string.action_more),
                    )
                }
                DropdownMenu(
                    expanded = overflowExpanded,
                    onDismissRequest = { overflowExpanded = false },
                ) {
                    // No core icon distinguishes "simple" from "complex" gauge
                    // display without misrepresenting what the action does, so the
                    // current mode is named beside the item instead of guessed at.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_gauges)) },
                        trailingIcon = {
                            Text(
                                text = stringResource(
                                    if (gaugeMode == GaugeMode.SIMPLE) R.string.gauge_simple else R.string.gauge_complex,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        onClick = {
                            overflowExpanded = false
                            onSetGaugeMode(
                                if (gaugeMode == GaugeMode.SIMPLE) GaugeMode.COMPLEX else GaugeMode.SIMPLE,
                            )
                        },
                    )

                    if (reload != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_reload_db)) },
                            leadingIcon = {
                                if (reload.inProgress) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(Icons.Filled.Refresh, contentDescription = null)
                                }
                            },
                            enabled = !reload.inProgress,
                            onClick = {
                                overflowExpanded = false
                                reload.onReload()
                            },
                        )
                    }

                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_reset)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            overflowExpanded = false
                            resetDialogVisible = true
                        },
                    )

                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_settings)) },
                        leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        onClick = {
                            overflowExpanded = false
                            onOpenSettings()
                        },
                    )
                }
            }
        },
    )

    if (resetDialogVisible) {
        AlertDialog(
            onDismissRequest = { resetDialogVisible = false },
            title = { Text(stringResource(R.string.action_reset_confirm_title)) },
            text = { Text(stringResource(R.string.action_reset_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        resetDialogVisible = false
                        onReset()
                    },
                ) {
                    Text(stringResource(R.string.action_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { resetDialogVisible = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}
