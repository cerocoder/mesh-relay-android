package com.cerocoder.meshrelay.ui.common

import com.cerocoder.meshrelay.R
import com.cerocoder.meshrelay.stats.SortMode

/**
 * The string resource for each [SortMode], one lookup table shared by the relay
 * list's own sort menu (Task 22) and the settings screen's default-sort picker
 * (Task 28) so the two never drift apart on wording. It lives in `ui/common`
 * rather than `ui/relays` because [StatsTopBar], which builds the sort menu for
 * both list screens, is common too.
 */
object SortModeLabels {
    fun labelOf(mode: SortMode): Int = when (mode) {
        SortMode.PACKETS -> R.string.sort_packets
        SortMode.PERCENT -> R.string.sort_percent
        SortMode.AVG_SNR -> R.string.sort_avg_snr
        SortMode.AVG_RSSI -> R.string.sort_avg_rssi
        SortMode.NAME -> R.string.sort_name
        SortMode.KNOWN_NODES -> R.string.sort_known_nodes
        SortMode.LATEST_PACKET -> R.string.sort_latest_packet
    }
}
