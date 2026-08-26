package com.cerocoder.meshrelay.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.cerocoder.meshrelay.stats.TimeSource
import kotlinx.coroutines.delay

/** Milliseconds, refreshed about once a second while the app is on screen. */
val LocalRelativeClock: ProvidableCompositionLocal<Long> = compositionLocalOf { 0L }

/** Whether the activity is between onResume and onPause. Provided by MainActivity. */
val LocalAppResumed: ProvidableCompositionLocal<Boolean> = compositionLocalOf { true }

/**
 * The only periodic work in the application.
 *
 * Statistics are pushed, not polled - a snapshot is rebuilt when a packet arrives,
 * never on a timer. Relative ages are the one thing that changes with nothing
 * happening, so they get a ticker, and it stops the moment the app leaves the
 * screen. Keyed on LocalAppResumed rather than on the composition, because the
 * composition survives being backgrounded and a LaunchedEffect inside it would
 * keep ticking behind a dark screen.
 *
 * Deviation from spec: this ticks at a flat 1 Hz while resumed rather than
 * backing off to 30 s once every visible age exceeds a minute. Doing that would
 * require threading which ages are currently on screen into the ticker itself,
 * and with the display lit the screen already dominates power draw. What the
 * spec is actually protecting - no work while backgrounded - is kept in full.
 */
@Composable
fun ProvideRelativeClock(time: TimeSource, content: @Composable () -> Unit) {
    val resumed = LocalAppResumed.current
    var now by remember { mutableLongStateOf(time.nowMillis()) }
    LaunchedEffect(resumed) {
        while (resumed) {
            now = time.nowMillis()
            delay(1_000)
        }
    }
    CompositionLocalProvider(LocalRelativeClock provides now) { content() }
}
