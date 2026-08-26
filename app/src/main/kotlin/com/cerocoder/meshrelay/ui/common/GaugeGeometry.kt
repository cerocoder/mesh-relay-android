package com.cerocoder.meshrelay.ui.common

import com.cerocoder.meshrelay.settings.GaugeMode
import com.cerocoder.meshrelay.stats.SignalScales
import com.cerocoder.meshrelay.stats.model.SignalStats

/**
 * Fractional positions of one gauge's marks along its track, each in `0f..1f`.
 *
 * [avg] and [last] are `null` in [GaugeMode.SIMPLE] - that mode shows only the
 * current level - and whenever [stats] has no data yet. When both are present
 * and coincide, this does not pick a winner: [SignalGauge] draws [avg] first
 * and [last] second, so the latest value covers the average when they land on
 * the same position. That is the original's "latest wins" priority rule,
 * expressed as draw order instead of a branch.
 */
data class GaugeMarks(
    val fillStart: Float,
    val fillEnd: Float,
    val avg: Float?,
    val last: Float?,
)

/**
 * Pure geometry for [SignalGauge]. Ports `render_bar_simple` and
 * `render_bar_complex`, mesh_stats.py:1167-1263, replacing the terminal's
 * discrete character columns with continuous fractions of the track - the
 * composable is the one that knows how many pixels wide the track is.
 */
object GaugeGeometry {
    fun marks(stats: SignalStats, scaleMin: Float, scaleMax: Float, mode: GaugeMode): GaugeMarks {
        if (!stats.hasData) return GaugeMarks(0f, 0f, null, null)
        fun at(value: Float) = SignalScales.fraction(value, scaleMin, scaleMax)
        return when (mode) {
            GaugeMode.SIMPLE -> GaugeMarks(0f, at(stats.lastVal), null, null)
            GaugeMode.COMPLEX -> GaugeMarks(at(stats.minVal), at(stats.maxVal), at(stats.avg), at(stats.lastVal))
        }
    }
}
