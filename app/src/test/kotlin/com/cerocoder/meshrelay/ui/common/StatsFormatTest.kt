package com.cerocoder.meshrelay.ui.common

import com.cerocoder.meshrelay.stats.model.SignalStats
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun stats(vararg values: Float) = values.fold(SignalStats.EMPTY) { acc, v -> acc.plus(v) }

/**
 * Independently reconstructs what [StatsFormat.nodeDatabaseLastHeard] should
 * produce for [epochSeconds]/[locale]/[zone], built here from the same public
 * `java.time` primitives rather than pinned to a literal string. CLDR locale
 * data is not part of this project's contract - it changed between the JDK 17
 * this suite was checked against locally and the JDK 21 CI actually runs on
 * (most visibly, `en-US` gained a narrow no-break space before AM/PM) - so a
 * hardcoded expected string from either JVM breaks on the other. Comparing
 * against this reference instead pins the wiring (locale in, zone in,
 * [FormatStyle.MEDIUM] used) while letting the running JVM's own CLDR data
 * decide the exact glyphs on both sides of the assertion.
 */
private fun referenceLastHeard(epochSeconds: Int, locale: Locale, zone: ZoneId): String {
    val localDateTime = LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds.toLong()), zone)
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).format(localDateTime)
}

class StatsFormatTest {

    @Test
    fun `signalTriple is null before any packet has arrived`() {
        // Kills a mutant that drops the hasData guard and formats the EMPTY
        // sentinel's minVal/maxVal (+-Infinity) and avg (0) as if they were
        // real readings, instead of leaving this to the caller's fallback text.
        assertNull(StatsFormat.signalTriple(SignalStats.EMPTY, Locale.US))
    }

    @Test
    fun `signalLast is null before any packet has arrived`() {
        // The same guard, but on the sibling function - a mutant could drop
        // this one while leaving signalTriple's intact.
        assertNull(StatsFormat.signalLast(SignalStats.EMPTY, Locale.US))
    }

    @Test
    fun `signalTriple formats min and max as whole numbers but the average to one decimal`() {
        // min=-6, max=8, avg=(-6+8-2.5)/3=-0.1666...7. Kills a mutant that
        // swaps the min/max pattern with the average's: using the average's
        // one-decimal pattern for min/max would print "-6.0/-0.2/8.0", and
        // using the spot pattern for the average would round -0.1666... down
        // to "0" instead of "-0.2" - either swap changes this assertion.
        assertEquals("-6/-0.2/8", StatsFormat.signalTriple(stats(-6f, 8f, -2.5f), Locale.US))
    }

    @Test
    fun `signalLast is a spot reading, not the average, at the same precision as min and max`() {
        // avg and lastVal are both -2.5 for these inputs (min=-20, max=15,
        // last=-2.5, avg=(-20+15-2.5)/3=-2.5) - the only thing that can tell
        // signalLast's spot precision apart from the average's one-decimal
        // precision here is which pattern is actually used. A mutant that
        // formatted the average instead of lastVal would still pass this
        // input through unchanged in value but not in precision: "-3", not
        // "-2.5".
        val s = stats(-20f, 15f, -2.5f)
        assertEquals("-3", StatsFormat.signalLast(s, Locale.US))
    }

    @Test
    fun `signalTriple's average follows the given locale, not a fixed one`() {
        // Only the average carries a decimal point at all - min and max are
        // whole numbers here - so this is where a hard-coded Locale.US or
        // Locale.ROOT in signalTriple would first show up to a Spanish reader
        // expecting a decimal comma.
        val s = stats(1.5f)

        assertEquals("2/1.5/2", StatsFormat.signalTriple(s, Locale.US))
        assertEquals("2/1,5/2", StatsFormat.signalTriple(s, Locale("es", "ES")))
    }

    @Test
    fun `percentageOf is computed against the given total, not some other number`() {
        // 3/8 = 37.5% exactly, in floating point with no rounding ambiguity.
        // Kills a mutant that computes against the wrong total (a swapped
        // part/total would give 266.7%, not 37.5%) or against a total held
        // somewhere else than the parameter actually passed in.
        assertEquals("37.5", StatsFormat.percentageOf(part = 3, total = 8, locale = Locale.US))
    }

    @Test
    fun `percentageOf is zero rather than infinite when the total is zero`() {
        // A literal port of packet_count / total_relayed * 100 with no guard
        // divides a positive Float by zero, which does not throw - it
        // produces Float.POSITIVE_INFINITY, and String.format renders that as
        // the literal text "Infinity", not "0.0". A mutant that drops the
        // `total > 0` guard makes this assertion fail on that text, not on a
        // crash.
        assertEquals("0.0", StatsFormat.percentageOf(part = 5, total = 0, locale = Locale.US))
    }

    @Test
    fun `percentageOf follows the given locale, not a fixed one`() {
        // 1/3 = 33.333...%, which never terminates, so the digit before the
        // decimal separator is never in doubt - only the separator itself
        // changes between locales. Kills a mutant that formats with
        // Locale.US or Locale.ROOT regardless of the locale parameter.
        assertEquals("33.3", StatsFormat.percentageOf(part = 1, total = 3, locale = Locale.US))
        assertEquals("33,3", StatsFormat.percentageOf(part = 1, total = 3, locale = Locale("es", "ES")))
    }

    @Test
    fun `signalMin, signalAvg and signalMax are each null before any packet has arrived`() {
        // The same EMPTY-sentinel guard as signalTriple and signalLast, but on
        // the three functions signalTriple is now built from - a mutant could
        // drop the guard on any one of them while leaving the others intact.
        assertNull(StatsFormat.signalMin(SignalStats.EMPTY, Locale.US))
        assertNull(StatsFormat.signalAvg(SignalStats.EMPTY, Locale.US))
        assertNull(StatsFormat.signalMax(SignalStats.EMPTY, Locale.US))
    }

    @Test
    fun `signalMin, signalAvg and signalMax match the components of signalTriple`() {
        // Same inputs as the "min and max as whole numbers but the average to
        // one decimal" case above: min=-6, max=8, avg=-0.1666...7. Kills a
        // mutant that lets the three standalone functions drift from the
        // triple they compose - e.g. one of them silently reverting to the
        // wrong pattern while signalTriple's own test still passes because it
        // reads from the same (now-wrong) function.
        val s = stats(-6f, 8f, -2.5f)
        assertEquals("-6", StatsFormat.signalMin(s, Locale.US))
        assertEquals("-0.2", StatsFormat.signalAvg(s, Locale.US))
        assertEquals("8", StatsFormat.signalMax(s, Locale.US))
        assertEquals(
            "${StatsFormat.signalMin(s, Locale.US)}/${StatsFormat.signalAvg(s, Locale.US)}/${StatsFormat.signalMax(s, Locale.US)}",
            StatsFormat.signalTriple(s, Locale.US),
        )
    }

    @Test
    fun `signalAvg follows the given locale, not a fixed one`() {
        // Only the average carries a decimal point at all, same reasoning as
        // signalTriple's own locale test above.
        val s = stats(1.5f)
        assertEquals("1.5", StatsFormat.signalAvg(s, Locale.US))
        assertEquals("1,5", StatsFormat.signalAvg(s, Locale("es", "ES")))
    }

    @Test
    fun `packetsPerHour formats to one decimal place`() {
        // Ports the ":.1f" precision mesh_stats.py:1824 formats packets_per_hour
        // at. Kills a mutant that formats to zero decimals (a whole number,
        // like the spot readings) or to some other precision.
        assertEquals("120.0", StatsFormat.packetsPerHour(120f, Locale.US))
        assertEquals("7.3", StatsFormat.packetsPerHour(7.3456f, Locale.US))
    }

    @Test
    fun `packetsPerHour renders a real zero rather than hiding it`() {
        // A relay heard only once, or not long enough to measure a rate, is a
        // real answer of 0.0 pkt/h - not the absence of a reading the signal
        // formatters above use null for. Kills a mutant that special-cases
        // zero into null or an empty string.
        assertEquals("0.0", StatsFormat.packetsPerHour(0f, Locale.US))
    }

    @Test
    fun `packetsPerHour follows the given locale, not a fixed one`() {
        assertEquals("7.3", StatsFormat.packetsPerHour(7.3f, Locale.US))
        assertEquals("7,3", StatsFormat.packetsPerHour(7.3f, Locale("es", "ES")))
    }

    @Test
    fun `nodeDatabaseSnr formats to one decimal place, including a negative reading`() {
        // Ports the ":.1f" precision mesh_stats.py:1886 formats a node database's
        // own last-known SNR at. Negative because a real SNR reading (unlike a
        // percentage or a packet count) is routinely negative.
        assertEquals("8.5", StatsFormat.nodeDatabaseSnr(8.5f, Locale.US))
        assertEquals("-6.0", StatsFormat.nodeDatabaseSnr(-6.0f, Locale.US))
    }

    @Test
    fun `nodeDatabaseSnr follows the given locale, not a fixed one`() {
        assertEquals("8.5", StatsFormat.nodeDatabaseSnr(8.5f, Locale.US))
        assertEquals("8,5", StatsFormat.nodeDatabaseSnr(8.5f, Locale("es", "ES")))
    }

    @Test
    fun `telemetryMetricValue formats to two decimal places, one more than a signal reading`() {
        // Ports the ":.2f" precision mesh_stats.py:1910 formats every telemetry
        // history entry at - kills a mutant that reuses the one-decimal signal
        // pattern instead of telemetry's own.
        assertEquals("61.00", StatsFormat.telemetryMetricValue(61f, Locale.US))
        assertEquals("3.87", StatsFormat.telemetryMetricValue(3.87f, Locale.US))
    }

    @Test
    fun `telemetryMetricValue follows the given locale, not a fixed one`() {
        assertEquals("3.87", StatsFormat.telemetryMetricValue(3.87f, Locale.US))
        assertEquals("3,87", StatsFormat.telemetryMetricValue(3.87f, Locale("es", "ES")))
    }

    @Test
    fun `uptimeParts decomposes into days, hours and minutes with no seconds`() {
        // 1d 1h 0m: exercises all three non-zero fields (90000 = 86400 + 3600),
        // and pins that seconds are truncated away, not rounded - divmod(0, 60)
        // in the original leaves no seconds field to round in the first place.
        assertEquals(StatsFormat.UptimeParts(1, 1, 0), StatsFormat.uptimeParts(90_000))
    }

    @Test
    fun `uptimeParts is all zero for a freshly booted device`() {
        // The zero-day case the controller singled out: kills a mutant that
        // mishandles 0 in the division or modulo chain (e.g. a divide-by-zero
        // guard that fires and substitutes a wrong sentinel).
        assertEquals(StatsFormat.UptimeParts(0, 0, 0), StatsFormat.uptimeParts(0))
    }

    @Test
    fun `uptimeParts truncates seconds within the final minute rather than rounding`() {
        // 172799s = 1d 23h 59m 59s - the trailing 59s must disappear entirely,
        // not round the minutes field up to 1d 0h 0m.
        assertEquals(StatsFormat.UptimeParts(1, 23, 59), StatsFormat.uptimeParts(172_799))
    }

    @Test
    fun `uptimeParts does not let hours or minutes leak into the wrong field`() {
        // 900s is exactly 15 minutes - a mutant that swapped the hours/minutes
        // divisors (3_600 and 60) would report this as 0d 15h 0m instead of
        // 0d 0h 15m.
        assertEquals(StatsFormat.UptimeParts(0, 0, 15), StatsFormat.uptimeParts(900))
    }

    @Test
    fun `candidateIndex wraps the index in brackets`() {
        assertEquals("[1]", StatsFormat.candidateIndex(1, Locale.US))
        assertEquals("[12]", StatsFormat.candidateIndex(12, Locale.US))
    }

    @Test
    fun `candidateIndex follows the given locale, not a fixed one`() {
        // en/es-ES pin the ordinary path this app actually ships - but a bare
        // %d on a small integer renders identically under both, so neither
        // assertion can fail against a mutant that hardcodes Locale.US inside
        // candidateIndex and ignores the parameter entirely.
        assertEquals("[3]", StatsFormat.candidateIndex(3, Locale.US))
        assertEquals("[3]", StatsFormat.candidateIndex(3, Locale("es", "ES")))

        // ar-EG is not a locale this app ships - it appears here solely
        // because its digits genuinely diverge (Arabic-Indic, not
        // ASCII), which is what actually kills the hardcoded-locale mutant
        // the two assertions above cannot touch. Verified against real
        // String.format(Locale("ar","EG"), "[%d]", 3) output (OpenJDK 17)
        // before writing this assertion: "[٣]", i.e. '[' + U+0663
        // (ARABIC-INDIC DIGIT THREE) + ']', no direction marks.
        assertEquals("[٣]", StatsFormat.candidateIndex(3, Locale("ar", "EG")))
    }

    @Test
    fun `remoteNodeHopAverage formats to one decimal place, including a negative value`() {
        // Ports the ":3.1f" precision mesh_stats.py:1938-1939 formats a
        // remote node's average hops made/left at. Negative is exercised even
        // though a real hop average is never negative in practice - the same
        // defensive-precision check nodeDatabaseSnr's own test above runs -
        // so a mutant that swapped in a whole-number pattern would be caught
        // regardless of the sign of the input.
        assertEquals("1.5", StatsFormat.remoteNodeHopAverage(1.5f, Locale.US))
        assertEquals("2.0", StatsFormat.remoteNodeHopAverage(2.0f, Locale.US))
        assertEquals("-3.0", StatsFormat.remoteNodeHopAverage(-3.0f, Locale.US))
    }

    @Test
    fun `remoteNodeHopAverage follows the given locale, not a fixed one`() {
        // 1.5 has an exact one-decimal representation under both locales, so
        // only the separator itself can differ - a mutant hardcoding
        // Locale.US or Locale.ROOT would fail the es-ES assertion here.
        assertEquals("1.5", StatsFormat.remoteNodeHopAverage(1.5f, Locale.US))
        assertEquals("1,5", StatsFormat.remoteNodeHopAverage(1.5f, Locale("es", "ES")))
    }

    @Test
    fun `nodeDatabaseLastHeard renders an absolute date, not a relative age`() {
        // Not pinned to a literal rendering - see referenceLastHeard's own
        // KDoc for why (CLDR output differs between the JDK 17 this was
        // checked against locally and the JDK 21 CI runs on). This only pins
        // that the result is an absolute calendar date: it must contain the
        // four-digit year, which no relative "n ago"/"Xs ago" rendering
        // (what AgeText produces elsewhere in this app) ever would, and must
        // not contain the word "ago" itself. Kills a mutant that reintroduces
        // relative, AgeText-style output for this one field (mesh_stats.py:
        // 1891 is the field that must render absolutely, not relatively).
        val result = StatsFormat.nodeDatabaseLastHeard(1_756_219_512, Locale.US, ZoneId.of("Europe/Madrid"))
        assertTrue("expected a non-blank result, got <$result>", result.isNotBlank())
        assertTrue("expected the year 2025 in <$result>", result.contains("2025"))
        assertTrue("expected no relative-age wording in <$result>", !result.contains("ago"))
    }

    @Test
    fun `nodeDatabaseLastHeard follows the given locale, not a fixed one`() {
        // Not pinned to literal en-US/es-ES strings - same CLDR-portability
        // reason as above. Instead this pins two properties that hold on any
        // CLDR version: (1) the function's output for each locale matches an
        // independently built reference using the same public
        // DateTimeFormatter.ofLocalizedDateTime API (so a mutant that ignores
        // the locale, or picks the wrong FormatStyle, produces a mismatch
        // against at least one of the two references); (2) the two locales'
        // outputs are non-blank and differ from each other - en-US and es-ES
        // diverge in word order, month spelling and clock format on every
        // JDK this project has run on, so a mutant hardcoding one locale
        // collapses this to a tautology-proof equality instead.
        val zone = ZoneId.of("Europe/Madrid")
        val enUs = StatsFormat.nodeDatabaseLastHeard(1_756_219_512, Locale.US, zone)
        val esEs = StatsFormat.nodeDatabaseLastHeard(1_756_219_512, Locale("es", "ES"), zone)

        assertEquals(referenceLastHeard(1_756_219_512, Locale.US, zone), enUs)
        assertEquals(referenceLastHeard(1_756_219_512, Locale("es", "ES"), zone), esEs)

        assertTrue("expected a non-blank en-US result, got <$enUs>", enUs.isNotBlank())
        assertTrue("expected a non-blank es-ES result, got <$esEs>", esEs.isNotBlank())
        assertNotEquals(enUs, esEs)
    }

    @Test
    fun `nodeDatabaseLastHeard follows the given zone, not a fixed one`() {
        // Not pinned to literal UTC/Europe-Madrid strings - same
        // CLDR-portability reason as the two tests above. 1_735_689_600 is
        // 2025-01-01T00:00:00Z exactly; Madrid is UTC+1 in January (standard
        // time, no DST), so the same instant must print a different hour
        // under each zone. As with the locale test above, this pins both
        // that each output matches an independently built reference (so a
        // mutant that ignores [zone] or converts it wrong is caught against
        // at least one reference) and that the two zones' outputs are
        // non-blank and differ from each other, rather than merely
        // asserting inequality between two values that could both be blank.
        val locale = Locale.US
        val utc = StatsFormat.nodeDatabaseLastHeard(1_735_689_600, locale, ZoneId.of("UTC"))
        val madrid = StatsFormat.nodeDatabaseLastHeard(1_735_689_600, locale, ZoneId.of("Europe/Madrid"))

        assertEquals(referenceLastHeard(1_735_689_600, locale, ZoneId.of("UTC")), utc)
        assertEquals(referenceLastHeard(1_735_689_600, locale, ZoneId.of("Europe/Madrid")), madrid)

        assertTrue("expected a non-blank UTC result, got <$utc>", utc.isNotBlank())
        assertTrue("expected a non-blank Europe/Madrid result, got <$madrid>", madrid.isNotBlank())
        assertNotEquals(utc, madrid)
    }
}
