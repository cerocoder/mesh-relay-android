package com.cerocoder.meshrelay.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cerocoder.meshrelay.stats.NodeId

/**
 * Renders a node number as [NodeId.format]'s `!xxxxxxxx` notation, in the
 * monospace `bodySmall` style so a column of these lines up.
 */
@Composable
fun NodeIdText(nodeNum: Int, modifier: Modifier = Modifier) {
    Text(
        text = NodeId.format(nodeNum),
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
    )
}
