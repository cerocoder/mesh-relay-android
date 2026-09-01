package com.cerocoder.meshrelay.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * The small glyph that marks what a field is, drawn before the field's value.
 *
 * The drawables behind these are hand-authored strokes in `res/drawable`
 * rather than library icons: this app depends on `material-icons-core` only,
 * which carries about forty glyphs and none of the ones these fields need - no
 * globe, mountain, speedometer, clock, burst or key. Pulling in
 * `material-icons-extended` for six shapes would add several thousand vectors
 * to the APK to use six of them.
 *
 * [contentDescription] is `null` wherever a text label already names the field
 * beside the icon - a screen reader announcing "clock, Uptime, 2days 3hrs" says
 * the same thing twice. It is only given where the icon is the sole indication
 * of what the value means, as in a position line, where nothing spells out that
 * a number is an altitude.
 *
 * The size is fixed at 16.dp to sit with `bodySmall`, the app's monospace style
 * (see `ui/theme/Type.kt`), which is what the values these mark are rendered in.
 */
@Composable
fun FieldIcon(
    @DrawableRes icon: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    Icon(
        painter = painterResource(icon),
        contentDescription = contentDescription,
        modifier = modifier.size(16.dp),
    )
}
