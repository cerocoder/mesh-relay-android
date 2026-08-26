package com.cerocoder.meshrelay.ui.common

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.stats.AgeText
import com.cerocoder.meshrelay.stats.RelativeAge

/**
 * Shows how long ago [atMillis] was, against the ticking [LocalRelativeClock].
 *
 * Does no arithmetic beyond the subtraction and no formatting of its own -
 * every branch of [RelativeAge] is rendered through a string resource, so the
 * only untestable-on-JVM part of this feature is the subtraction itself; the
 * branching and number formatting live in [AgeText], which is unit tested.
 */
@Composable
fun AgeLabel(atMillis: Long, modifier: Modifier = Modifier) {
    val now = LocalRelativeClock.current
    val text = when (val age = AgeText.relative(now - atMillis)) {
        is RelativeAge.Seconds -> stringResource(R.string.format_ago_seconds, age.seconds)
        is RelativeAge.Minutes -> stringResource(R.string.format_ago_minutes, age.minutes, age.seconds)
        is RelativeAge.Hours -> stringResource(R.string.format_ago_hours, age.hours, age.minutes)
        RelativeAge.Never -> stringResource(R.string.common_never)
    }
    Text(text = text, modifier = modifier)
}
