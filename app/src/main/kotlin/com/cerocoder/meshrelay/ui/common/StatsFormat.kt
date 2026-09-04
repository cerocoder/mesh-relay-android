package com.cerocoder.meshrelay.ui.common

import com.cerocoder.meshrelay.settings.TimeFormat
import com.cerocoder.meshrelay.stats.model.SignalStats
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Pure, locale-aware number formatting shared by every card that shows a
 * signal triple or one quantity's share of a total - the relay list's
 * `RelayCard` today, Task 23's neighbour cards next (both need the same
 * triple and the same percentage shape, just over different node
 * collections). Ports the fixed-width formatting mesh_stats.py:1412-1413,
 * :1471-1472 (the rxSnr/rxRssi triple) and :1461-1462 (the percentage) build
 * for the terminal, minus the column padding a touch screen has no use for.
 *
 * No Compose import, on the same principle [PositionLineText] already
 * follows: every string this object's callers wrap its output in (a unit
 * suffix, a "not available" fallback) is resolved from resources by the
 * caller, not here, so this stays testable on the JVM without a Composable
 * host.
 */
object StatsFormat {

    /**
     * A single instantaneous reading's precision (`:>4.0f` in the original) -
     * min, max and the latest value are all "spot" readings, not averages,
     * so whole units are all any of them claims.
     */
    private const val SPOT_PATTERN = "%.0f"

    /** The average's own precision (`:>4.1f` in the original). */
    private const val AVG_PATTERN = "%.1f"

    private const val PERCENT_PATTERN = "%.1f"

    /** A rate's own precision (`:.1f` in the original's `packets_per_hour` line,
     *  mesh_stats.py:1824) - numerically the same pattern as [AVG_PATTERN], kept
     *  as its own named constant because the two format unrelated quantities. */
    private const val RATE_PATTERN = "%.1f"

    /** A node database's own last-known SNR reading (`:.1f` at mesh_stats.py:1886) -
     *  numerically the same pattern as [AVG_PATTERN], kept as its own named constant
     *  because it formats a plain already-known value, not a [SignalStats] average. */
    private const val DB_SNR_PATTERN = "%.1f"

    /** A telemetry metric's latest value (`:.2f` at mesh_stats.py:1910) - one more
     *  digit than a signal reading, since telemetry covers everything from a
     *  battery percentage to a voltage. */
    private const val TELEMETRY_PATTERN = "%.2f"

    /** A remote node's average hop count, made or left (`:3.1f` at
     *  mesh_stats.py:1938-1939) - numerically the same pattern as [AVG_PATTERN],
     *  kept as its own named constant because it formats a distinct quantity
     *  ([com.cerocoder.meshrelay.stats.model.RemoteNodeStats.avgHopsMade] /
     *  `.avgHopsLeft`), not a [SignalStats] average. */
    private const val HOP_AVERAGE_PATTERN = "%.1f"

    /** A relay candidate's own average direct RSSI
     *  ([com.cerocoder.meshrelay.stats.model.RelayCandidate.directRssiAvg]) -
     *  numerically [AVG_PATTERN], kept as its own named constant because,
     *  unlike [signalAvg], it formats a bare `Float` rather than a live
     *  [SignalStats]: `RelayCandidate` keeps only the derived average, not the
     *  statistics object it was taken from. */
    private const val CANDIDATE_RSSI_AVG_PATTERN = "%.1f"

    /** The gap between a candidate's own average and the relay's
     *  ([com.cerocoder.meshrelay.stats.model.RelayCandidate.gapDb]) -
     *  numerically [AVG_PATTERN] as well, since it is a difference of two such
     *  averages, but its own named constant for the same reason every other
     *  "%.1f" figure in this file gets one: it formats a distinct quantity. */
    private const val CANDIDATE_GAP_PATTERN = "%.1f"

    /**
     * One measurement's own SNR, as the Graph screen's crosshair prints it.
     *
     * Numerically [AVG_PATTERN], and deliberately not [SPOT_PATTERN] even though
     * a crosshair reading is a spot reading in every other sense. The two metrics
     * arrive at different precisions and are printed at the precision each
     * carries: `PacketClassifier` reads `rx_rssi` as an `Int` and widens it
     * (`PacketClassifier.kt:69`), so an RSSI has no fractional part to print,
     * while `rx_snr` is a `Float` on the wire and its fractional part is real.
     * SNR's whole useful span is the 35 dB of
     * [com.cerocoder.meshrelay.stats.SignalScales.SNR_MIN]`..SNR_MAX` against
     * RSSI's 100 dBm, and the packet-to-packet differences this chart exists to
     * make visible are fractions of a dB; rounding them to whole units would
     * flatten exactly the variation the reader came for. The design's own layout
     * sketch shows the pair as `-92 dBm  4.5 dB`, which is these two patterns.
     */
    private const val SAMPLE_SNR_PATTERN = "%.1f"

    /**
     * Structural glue, not translatable prose - the same treatment
     * [PositionLineText]'s direction separator gets.
     */
    private const val TRIPLE_SEPARATOR = "/"

    /**
     * The minimum out of a [signalTriple], on its own - the detail screen's
     * signal block labels min/avg/max/last/count separately (mesh_stats.py:
     * 1826-1830's `Min:`/`Avg:`/`Max:`/`Last:`/`Count:` rows) rather than the
     * card's compact slashed form, so each needs its own formatter instead of
     * a string this object would have to split back apart. `null` before
     * [stats] has any data, on the same terms as [signalTriple] and [signalLast].
     */
    fun signalMin(stats: SignalStats, locale: Locale): String? =
        if (stats.hasData) String.format(locale, SPOT_PATTERN, stats.minVal) else null

    /** The average out of a [signalTriple], on its own. See [signalMin]. */
    fun signalAvg(stats: SignalStats, locale: Locale): String? =
        if (stats.hasData) String.format(locale, AVG_PATTERN, stats.avg) else null

    /** The maximum out of a [signalTriple], on its own. See [signalMin]. */
    fun signalMax(stats: SignalStats, locale: Locale): String? =
        if (stats.hasData) String.format(locale, SPOT_PATTERN, stats.maxVal) else null

    /**
     * `"min/avg/max"`, ports the triple mesh_stats.py:1412-1413 (and
     * :1471-1472 for rxRssi) builds as `f"{min:>4.0f}/{avg:>4.1f}/{max:>4.0f}"`.
     * `null` before [stats] has any data - the same case the original's
     * `"  --/  --/  --"` placeholder covers; the caller supplies its own
     * localized fallback text for that case.
     *
     * Built from [signalMin]/[signalAvg]/[signalMax] rather than duplicating
     * their `String.format` calls, so the two shapes of this data (slashed
     * triple, five separately labelled stats) can never drift apart.
     */
    fun signalTriple(stats: SignalStats, locale: Locale): String? {
        if (!stats.hasData) return null
        val min = signalMin(stats, locale)
        val avg = signalAvg(stats, locale)
        val max = signalMax(stats, locale)
        return "$min$TRIPLE_SEPARATOR$avg$TRIPLE_SEPARATOR$max"
    }

    /**
     * The latest reading, at [SPOT_PATTERN] precision like the min/max either
     * side of it in [signalTriple] - a single instantaneous value, not an
     * average. `null` before [stats] has any data.
     */
    fun signalLast(stats: SignalStats, locale: Locale): String? =
        if (stats.hasData) String.format(locale, SPOT_PATTERN, stats.lastVal) else null

    /**
     * One single measurement's RSSI - the Graph screen's crosshair, which reads
     * a value straight out of a
     * [com.cerocoder.meshrelay.stats.model.SignalSeries] rather than out of a
     * running [SignalStats].
     *
     * At [SPOT_PATTERN], the same precision [signalMin]/[signalMax]/[signalLast]
     * print an RSSI at - a crosshair reading is a spot reading in exactly the
     * sense that constant already documents, and the graph and the bars above it
     * must not print the same dBm to different precisions. No `hasData` guard and
     * no nullable return: unlike a [SignalStats], a series index either exists or
     * is a defect in the geometry, and the caller has already resolved one.
     */
    fun sampleRssi(value: Float, locale: Locale): String = String.format(locale, SPOT_PATTERN, value)

    /** One single measurement's SNR. See [sampleRssi]; [SAMPLE_SNR_PATTERN] records
     *  why this one keeps its tenth of a dB where the RSSI beside it does not. */
    fun sampleSnr(value: Float, locale: Locale): String = String.format(locale, SAMPLE_SNR_PATTERN, value)

    /**
     * The clock half of an absolute timestamp.
     *
     * An explicit pattern rather than [DateTimeFormatter.ofLocalizedTime], and
     * that is deliberate: forcing a 12- or 24-hour clock through a localized
     * formatter means a `-u-hc-` Unicode extension on the locale, and whether
     * `java.time` honours it depends on the CLDR provider. This project's unit
     * tests run on the JVM and the app runs on Android, so that route could pass
     * every test and still be wrong on the phone - the one failure this project
     * has no way to catch. A pattern behaves identically on both.
     *
     * The cost is locale-specific time separators, which this app does not need:
     * it ships English and Spanish and both use `:`.
     *
     * `h`/`HH` rather than `kk`/`KK`: `KK` renders noon as `00 PM` and `kk`
     * renders midnight as `24`. Both are the classic form of this bug.
     */
    private fun clockPattern(timeFormat: TimeFormat): String = when (timeFormat) {
        TimeFormat.TWELVE_HOUR -> "h:mm:ss a"
        TimeFormat.TWENTY_FOUR_HOUR -> "HH:mm:ss"
    }

    /**
     * A measurement's own timestamp: local time, then local date, as the design's
     * layout sketch shows it - `13:01:13 21.08.2026`.
     *
     * The clock half is built from [clockPattern] - see its own KDoc for why a
     * pattern rather than a localized formatter - and governed by [timeFormat],
     * not by [locale]. The date half stays locale-resolved, the same argument
     * [nodeDatabaseLastHeard] makes: a Spanish reader expects day-before-month,
     * and the platform is what knows that. [FormatStyle.SHORT] is the only date
     * style that stays numeric, which is what the drawing shows and what fits
     * under a chart on a phone. The clock pattern always includes seconds -
     * measurements on a busy relay arrive seconds apart, and a chart whose two
     * Time fields read the same to the minute would say nothing.
     *
     * [zone] defaults to the device's own configured zone and is a parameter only
     * so a test can pin one.
     */
    fun graphTimestamp(atMillis: Long, locale: Locale, timeFormat: TimeFormat, zone: ZoneId = ZoneId.systemDefault()): String {
        val localDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(atMillis), zone)
        val time = DateTimeFormatter.ofPattern(clockPattern(timeFormat), locale)
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)
        return "${time.format(localDateTime)} ${date.format(localDateTime)}"
    }

    /**
     * A rate of packets per hour, ports the `:.1f` precision mesh_stats.py:1824
     * formats `packets_per_hour` at. Unlike the signal readings above, a rate of
     * zero (a relay heard only once, or not long enough to measure) is a real
     * answer, not an absence of data - so this always returns a value, never `null`.
     */
    fun packetsPerHour(value: Float, locale: Locale): String = String.format(locale, RATE_PATTERN, value)

    /**
     * [part]'s share of [total] as a percentage, ports the `pct` calculation
     * at mesh_stats.py:1461-1462 (`pct = packet_count / total_relayed * 100
     * if total_relayed > 0 else 0`). Zero when [total] is not positive,
     * rather than the division by zero a literal port would perform.
     */
    fun percentageOf(part: Int, total: Int, locale: Locale): String {
        val percent = if (total > 0) part.toFloat() / total * 100f else 0f
        return String.format(locale, PERCENT_PATTERN, percent)
    }

    /**
     * A node database's own last-known SNR reading (`NodeRecord.dbSnr`), ports the
     * `:.1f` precision at mesh_stats.py:1886. Unlike [signalAvg]/[signalLast], this
     * value is not backed by a running [SignalStats] - it is a single float already
     * read from the node database - so there is no `hasData` guard here: the caller
     * decides whether there is a reading at all (`NodeRecord.dbSnr` is nullable) and
     * only calls this once it knows there is.
     */
    fun nodeDatabaseSnr(value: Float, locale: Locale): String = String.format(locale, DB_SNR_PATTERN, value)

    /**
     * One telemetry metric's latest value, ports the `:.2f` precision
     * mesh_stats.py:1910 formats every `history_metrics` entry at
     * (`f"     {k}: {hist.last_val:.2f}"`).
     */
    fun telemetryMetricValue(value: Float, locale: Locale): String = String.format(locale, TELEMETRY_PATTERN, value)

    /** [uptimeParts]'s result: a node's last-known uptime broken into the three
     *  fields `R.string.format_uptime` renders, one placeholder each. */
    data class UptimeParts(val days: Int, val hours: Int, val minutes: Int)

    /**
     * Decomposes a device's uptime counter into days/hours/minutes, ports the
     * `divmod` chain at mesh_stats.py:1903-1905 (`d, r = divmod(secs, 86400); h, r =
     * divmod(r, 3600); m, _ = divmod(r, 60)`). Seconds within the final minute are
     * dropped, exactly as the original drops them with `_`.
     *
     * [totalSeconds] is a device's `uptime_seconds` telemetry field, which only
     * ever grows while the device stays up - never negative in practice, and this
     * function does not special-case a negative input the way [percentageOf] does
     * for a zero total, since there is no analogous real-world zero/negative case
     * to guard against here.
     */
    fun uptimeParts(totalSeconds: Int): UptimeParts {
        val days = totalSeconds / 86_400
        val afterDays = totalSeconds % 86_400
        val hours = afterDays / 3_600
        val minutes = (afterDays % 3_600) / 60
        return UptimeParts(days, hours, minutes)
    }

    /**
     * `"[n]"` - a matching-node candidate's position in its list
     * (`NodeCard`'s own `index` parameter), locale-aware on the same terms
     * every other function here is: small today, but a plain `"[$index]"`
     * interpolation would render Arabic-Indic digits for a future locale this
     * app does not yet ship while every numeric reading beside it in the same
     * card went through `String.format(locale, ...)`. The brackets themselves
     * are structural notation, not translatable prose - the same treatment
     * [PositionLineText]'s direction separator and
     * [com.cerocoder.meshrelay.ui.relays.RelayCard]'s own `hexWithMatchCount`
     * get - so this stays a formatter here rather than a string resource: two
     * locale files would gain an entry neither translation ever changes.
     */
    fun candidateIndex(index: Int, locale: Locale): String = String.format(locale, "[%d]", index)

    /**
     * A node database timestamp (`NodeRecord.lastHeardEpochSeconds`) as an
     * absolute local date and time, date first. Ports the `%Y-%m-%d %H:%M:%S`
     * mesh_stats.py:1891 builds via `datetime.fromtimestamp(ts)` - but
     * locale-aware rather than the original's fixed ISO-like pattern: a Spanish
     * reader expects day-before-month, which
     * [DateTimeFormatter.ofLocalizedDate] resolves from [locale] instead of a
     * hardcoded pattern string. The composition order (date, then clock) is
     * fixed by this function, not by [locale] - unlike [graphTimestamp]'s
     * running-log fields, this is the one field in the app where the year is
     * load-bearing (see below), so [FormatStyle.MEDIUM] is the date style used
     * here rather than [FormatStyle.SHORT]: `SHORT` truncates the year to two
     * digits under `Locale.US` (`8/26/25`, verified against real `java.time`
     * output, OpenJDK 17), and a database entry that can be weeks old needs the
     * full year to read unambiguously.
     *
     * The clock half is built from [clockPattern] and governed by [timeFormat],
     * not by [locale] - see [clockPattern]'s own KDoc for why a pattern rather
     * than a localized formatter. It always includes seconds, matching the
     * original's `%S`. This used to be one call to
     * [DateTimeFormatter.ofLocalizedDateTime]; splitting it into its own date
     * and clock halves is what lets [timeFormat] govern the clock at all,
     * without the date losing its shape - unlike [FormatStyle.LONG]/`FULL`,
     * neither half prints a zone name, which would require a zone-aware
     * temporal ([java.time.ZonedDateTime]) this function deliberately does not
     * carry past formatting (see below).
     *
     * This is the one field in this app that renders an absolute time rather
     * than a relative [AgeLabel] age - see `NodeCard`'s own KDoc for why: a
     * database entry, unlike every session-scoped signal history elsewhere in
     * this app, can genuinely be weeks old, and `AgeText` has no week/month
     * bucket because it was built for ages that never exceed a few hours. That
     * is also why the year is load-bearing here in a way it is not for
     * [graphTimestamp]'s own running log, whose measurements are never more
     * than a session old.
     *
     * [zone] defaults to [ZoneId.systemDefault] - the device's own configured
     * zone - matching the original's `datetime.fromtimestamp`, which reads
     * naive *local* time, not UTC. Exposed as a parameter (rather than read
     * directly in the body) purely so a test can pin a fixed zone instead of
     * depending on whatever zone happens to run the test JVM; every real
     * caller leaves it at its default.
     */
    fun nodeDatabaseLastHeard(epochSeconds: Int, locale: Locale, timeFormat: TimeFormat, zone: ZoneId = ZoneId.systemDefault()): String {
        val localDateTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds.toLong()), zone)
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        val time = DateTimeFormatter.ofPattern(clockPattern(timeFormat), locale)
        return "${date.format(localDateTime)}, ${time.format(localDateTime)}"
    }

    /**
     * A remote node's average hop count - either
     * [com.cerocoder.meshrelay.stats.model.RemoteNodeStats.avgHopsMade] or
     * `.avgHopsLeft` - ports the `:3.1f` precision mesh_stats.py:1938-1939
     * formats both averages at. Those properties are nullable precisely
     * because a packet can arrive with no hop information at all; this
     * function only ever receives the non-null case and never fabricates a
     * `0.0` for the other - the caller (`RemoteNodesTab`) is the one that
     * decides between calling this and rendering
     * [R.string.common_not_available], the same `?:` shape
     * [com.cerocoder.meshrelay.ui.neighbours.NeighbourCard]'s `SignalRow`
     * already uses for [signalTriple]/[signalLast].
     */
    fun remoteNodeHopAverage(value: Float, locale: Locale): String = String.format(locale, HOP_AVERAGE_PATTERN, value)

    /** See [CANDIDATE_RSSI_AVG_PATTERN]. No `hasData` guard and no nullable
     *  return, on the same terms as [sampleRssi]/[sampleSnr]: the caller
     *  already knows [com.cerocoder.meshrelay.stats.model.RelayCandidate.directRssiAvg]
     *  is non-null before it calls this. */
    fun candidateRssiAvg(value: Float, locale: Locale): String = String.format(locale, CANDIDATE_RSSI_AVG_PATTERN, value)

    /** See [CANDIDATE_GAP_PATTERN]. */
    fun candidateGapDb(value: Float, locale: Locale): String = String.format(locale, CANDIDATE_GAP_PATTERN, value)
}
