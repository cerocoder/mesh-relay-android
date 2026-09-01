package com.cerocoder.meshrelay.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.stats.model.NodeDirectorySnapshot

/**
 * This device's own line, above the relay or neighbour list: short name plus its
 * position with no distance figure (there is no "self" to measure a distance
 * from), or [R.string.relays_local_node_unknown] before the node database has
 * told this app its own node number at all. Ports `render_my_info`,
 * mesh_stats.py:1319-1346 - called by `render_header` regardless of
 * `neighbours_mode`, so it belongs on both list screens exactly alike.
 *
 * It used to be a private composable copied verbatim into each of those screens.
 * One shared copy in `ui/common` is what keeps the two headers from drifting;
 * the label-and-name change below would otherwise have had to be made twice.
 *
 * The label and the name share a line (field issue F-4). Stacked, a two-word
 * caption cost a whole line on the screen with the least vertical room in the
 * app, and the label read as hovering above the value rather than naming it -
 * against the `LabelValueRow` pattern the node card uses everywhere else.
 */
@Composable
fun LocalNodeLine(
    directory: NodeDirectorySnapshot,
    meshviewUrl: String?,
    modifier: Modifier = Modifier,
) {
    val localNodeNum = directory.localNodeNum
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        if (localNodeNum == null) {
            Text(
                text = stringResource(R.string.relays_local_node_unknown),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.relays_local_node),
                    style = MaterialTheme.typography.labelMedium,
                )
                // The node database can know this node's number without ever having
                // heard its own User message - shortName is "" then, not null, and
                // isNotEmpty() (rather than a null check) is what hides it correctly.
                // On one line that has to leave the label standing alone, with no
                // separator left dangling after it - which is why the name is a
                // sibling in a spaced Row rather than text appended to the label.
                val shortName = directory.shortName(localNodeNum)
                if (shortName.isNotEmpty()) {
                    Text(text = shortName, style = MaterialTheme.typography.bodyMedium)
                }
            }
            PositionLine(
                info = directory.locationInfo(localNodeNum, from = null),
                nodeNum = localNodeNum,
                meshviewUrl = meshviewUrl,
            )
        }
    }
}
