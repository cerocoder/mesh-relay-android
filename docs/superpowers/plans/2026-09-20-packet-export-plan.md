# Packet Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the owner export a relay's, a neighbour's, or a whole list's recorded RSSI/SNR/position history to a CSV file from each screen's own overflow menu.

**Architecture:** Two data-model additions (per-sample observer altitude, per-sample packet source) to the `SignalSeriesBuffer`/`SignalSeries` pair the Graph feature already built; a new one-shot `MeshStatsEngine` command that copies several subjects' series at once (distinct from the Graph's continuous single-subject watch); a new `export/` package holding pure, Android-free row-building and CSV-writing logic; and UI wiring that reuses the existing overflow-menu patterns (`StatsTopBar`, `DetailScreen.menuItems`) plus a `CreateDocument` document-picker launcher in `MainActivity.kt`.

**Tech Stack:** Kotlin, Jetpack Compose, `kotlinx.coroutines`, `java.time` (already used elsewhere in this codebase for `Instant`/`LocalDateTime`), JUnit4. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-20-packet-export-design.md`

## Global Constraints

- CSV, one row per sample. Field separator is always `,`, never `;` (spec requirement 12).
- Decimal separator is always `.` (`Locale.ROOT` everywhere a number is formatted; spec requirement 11).
- Timestamps are ISO-8601, UTC, seconds precision (`timestamp_utc`).
- Rows are sorted by `atMillis` ascending, stably, across the whole file - not only within one node's block (spec requirement 10).
- Only currently-retained samples are exportable (the 5000-sample ring buffer cap already limits the Graph screen; export inherits it).
- No altitude for anything other than the observer. The packet's source node gets an id/name, never an altitude.
- No storage permission is requested; the system "Save As" picker (`ActivityResultContracts.CreateDocument`) is the only save mechanism.
- This project has no local Android SDK and no Gradle wrapper (`README.md`, `docs/verifying.md`) - nothing here can be built or run on the development machine. Every task's tests are written and reasoned through here, then verified together on a CI push per `docs/verifying.md`, not one task at a time.

---

## Task 1: `StampedPosition` gains an altitude field

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/StampedPosition.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/model/StampedPositionTest.kt` (new file)

**Interfaces:**
- Produces: `StampedPosition(latI: Int, lonI: Int, origin: PositionOrigin, altitude: Int? = null)`, `StampedPosition.fromDegrees(lat: Double, lon: Double, origin: PositionOrigin, altitude: Int? = null): StampedPosition`, `StampedPosition.NO_ALTITUDE: Int` (= `Int.MIN_VALUE`).

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/cerocoder/meshrelay/stats/model/StampedPositionTest.kt`:

```kotlin
package com.cerocoder.meshrelay.stats.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StampedPositionTest {

    @Test
    fun `fromDegrees defaults to no altitude`() {
        val position = StampedPosition.fromDegrees(40.3057734, -3.7325611, PositionOrigin.PHONE)
        assertNull(position.altitude)
    }

    @Test
    fun `fromDegrees carries the altitude it is given`() {
        val position = StampedPosition.fromDegrees(40.3057734, -3.7325611, PositionOrigin.NODE, altitude = 612)
        assertEquals(612, position.altitude)
    }

    @Test
    fun `NO_ALTITUDE is outside any real altitude a sample could carry`() {
        assertEquals(Int.MIN_VALUE, StampedPosition.NO_ALTITUDE)
    }
}
```

- [ ] **Step 2: Verify it fails to compile**

`StampedPosition` has no `altitude` property, no `altitude` parameter on `fromDegrees`, and no `NO_ALTITUDE` constant yet, so this file fails to compile (`unresolved reference`). That is the expected failure - not a typo, a missing feature.

- [ ] **Step 3: Implement**

Replace the whole file `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/StampedPosition.kt` with:

```kotlin
package com.cerocoder.meshrelay.stats.model

import kotlin.math.roundToInt

/**
 * Which device produced a measurement's coordinates.
 *
 * Deliberately not [PositionSource], which already exists and means something
 * else entirely: `CURRENT` vs `DB` is how *fresh a node's own* position is, not
 * whose receiver measured it.
 *
 * [code] is what the series buffer stores, one byte per sample. `0` is reserved
 * for "this sample has no position" and is therefore not an origin - which is
 * what lets the latitude and longitude arrays keep their default `0` with no
 * sentinel value and no ambiguity with the Gulf of Guinea.
 */
enum class PositionOrigin(val code: Byte) {
    NODE(1),
    PHONE(2);

    companion object {
        /** The stored code for a sample that carries no position at all. */
        const val NONE: Byte = 0

        fun ofCode(code: Byte): PositionOrigin? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Where the observer was standing, as one measurement recorded it.
 *
 * Coordinates are scaled integers at 1e-7 degrees - the protobuf's own
 * representation (`Position.latitude_i`). Four bytes, lossless at the
 * resolution the mesh transmits, and it respects the standing rule against
 * `Float` coordinates recorded at [PositionReport]: a `Float` loses roughly ten
 * metres at these magnitudes.
 *
 * [altitude] is independently optional from the coordinates: a 2D fix, or a
 * node broadcasting lat/lon with no altitude field at all, is a real position
 * with no altitude - see [PositionHistory.newestWithCoordinates]'s own KDoc.
 */
data class StampedPosition(val latI: Int, val lonI: Int, val origin: PositionOrigin, val altitude: Int? = null) {

    /** Multiplied in `Double`, the conversion [PositionReport] already documents. */
    val latitude: Double get() = latI * COORD_SCALE
    val longitude: Double get() = lonI * COORD_SCALE

    companion object {
        /** Coordinates travel as integers scaled by ten million. */
        const val COORD_SCALE = 1e-7

        /**
         * The sentinel [SignalSeriesBuffer]/[SignalSeries] store for "this sample's
         * position, if any, carried no altitude" - a value no real altitude reaches,
         * so no second boolean array is needed to track presence separately from
         * [PositionOrigin.NONE], which already tracks presence of the position
         * itself.
         */
        const val NO_ALTITUDE = Int.MIN_VALUE

        /**
         * Rounded, not truncated: truncation biases every coordinate toward the
         * equator and the prime meridian by up to 1.1 cm, which is meaningless in
         * itself but is a bias rather than a wobble, and there is no reason to
         * introduce one.
         *
         * The full coordinate system fits: 180 degrees scales to 1.8e9 and `Int`
         * holds 2.147e9. `roundToInt` saturates rather than wrapping, so even a
         * nonsense value from a broken provider clamps instead of appearing on the
         * other side of the world.
         */
        fun fromDegrees(lat: Double, lon: Double, origin: PositionOrigin, altitude: Int? = null): StampedPosition =
            StampedPosition(
                latI = (lat / COORD_SCALE).roundToInt(),
                lonI = (lon / COORD_SCALE).roundToInt(),
                origin = origin,
                altitude = altitude,
            )
    }
}
```

- [ ] **Step 4: Verify the tests pass**

The three new tests pass; nothing else in the codebase calls `fromDegrees` positionally past `origin`, so this is a purely additive change and every existing call site still compiles unchanged.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/StampedPosition.kt app/src/test/kotlin/com/cerocoder/meshrelay/stats/model/StampedPositionTest.kt
git commit -m "feat(export): add an optional altitude to StampedPosition"
```

---

## Task 2: `SignalSeriesBuffer`/`SignalSeries` gain altitude and per-sample source

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/SignalSeriesBuffer.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/SignalSeries.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngine.kt:471,491` (the two `.append(...)` call sites in `foldRelayed`/`foldDirect`)
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/SignalSeriesBufferTest.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/model/SignalSeriesTest.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngineTest.kt`

**Interfaces:**
- Consumes: `StampedPosition.NO_ALTITUDE` (Task 1).
- Produces: `SignalSeriesBuffer.append(atMillis: Long, rssi: Float, snr: Float, position: StampedPosition?, fromNode: Int)` (was 4 args, now 5 - **breaking**, every call site must be updated in this task). `SignalSeries.altitudeMeters(index: Int): Int?`, `SignalSeries.sourceNodeNum(index: Int): Int`.

- [ ] **Step 1: Update existing tests to the new 5-argument `append`, and add new tests**

The two existing test files call `.append(atMillis, rssi, snr, position)` with no `fromNode` - a required parameter, not optional, because every real sample has a genuine sender and defaulting it would let a caller silently forget to pass the real one. Update every call site mechanically first:

```bash
sed -i \
  -e 's/, null)/, null, 0x11111111)/g' \
  -e 's/, getafe)/, getafe, 0x11111111)/g' \
  -e 's/, toledo)/, toledo, 0x11111111)/g' \
  app/src/test/kotlin/com/cerocoder/meshrelay/stats/SignalSeriesBufferTest.kt

sed -i 's/, null)/, null, 0x11111111)/g' \
  app/src/test/kotlin/com/cerocoder/meshrelay/stats/model/SignalSeriesTest.kt
```

Then add these new tests to `SignalSeriesBufferTest.kt` (append after the existing `` `a slot reused after the wrap does not keep the evicted sample's position` `` test, before `` `totalAppended counts every sample ever appended, not the ones retained` ``):

```kotlin
    @Test
    fun `an altitude survives the arrays it is split across`() {
        val buffer = SignalSeriesBuffer(capacity = 4)
        val withAltitude = getafe.copy(altitude = 612)
        buffer.append(1_000L, -90f, 5f, withAltitude, 0x11111111)
        assertEquals(612, buffer.snapshot().positionOf(0)?.altitude)
    }

    @Test
    fun `a position with no altitude reads back as no altitude, not zero`() {
        // 0 is a real altitude at sea level. Only a genuinely absent one is null.
        val buffer = SignalSeriesBuffer(capacity = 4)
        buffer.append(1_000L, -90f, 5f, getafe, 0x11111111)
        assertNull(buffer.snapshot().positionOf(0)?.altitude)
    }

    @Test
    fun `a slot reused after the wrap does not keep the evicted sample's altitude`() {
        val buffer = SignalSeriesBuffer(capacity = 2)
        buffer.append(1_000L, -90f, 5f, getafe.copy(altitude = 612), 0x11111111)
        buffer.append(2_000L, -90f, 5f, getafe.copy(altitude = 612), 0x11111111)
        buffer.append(3_000L, -90f, 5f, getafe, 0x11111111)

        val series = buffer.snapshot()
        assertEquals(612, series.positionOf(0)?.altitude)
        assertNull(series.positionOf(1)?.altitude)
    }

    @Test
    fun `sourceNodeNum survives the arrays it is split across`() {
        val buffer = SignalSeriesBuffer(capacity = 4)
        buffer.append(1_000L, -90f, 5f, getafe, 0x11111111)
        buffer.append(2_000L, -91f, 4f, toledo, 0x22222222)

        val series = buffer.snapshot()
        assertEquals(0x11111111, series.sourceNodeNum(0))
        assertEquals(0x22222222, series.sourceNodeNum(1))
    }

    @Test
    fun `a slot reused after the wrap does not keep the evicted sample's source`() {
        val buffer = SignalSeriesBuffer(capacity = 2)
        buffer.append(1_000L, -90f, 5f, getafe, 0x11111111)
        buffer.append(2_000L, -90f, 5f, getafe, 0x11111111)
        buffer.append(3_000L, -90f, 5f, getafe, 0x22222222)

        val series = buffer.snapshot()
        assertEquals(0x11111111, series.sourceNodeNum(0))
        assertEquals(0x22222222, series.sourceNodeNum(1))
    }
```

`SignalSeriesBufferTest.kt` will need `assertNull` imported if it is not already (it is - the existing `` `a sample with no position reads back as no position` `` test already uses it).

- [ ] **Step 2: Verify it fails to compile**

The sed'd calls now pass 5 arguments to a 4-argument function (fails to compile: "too many arguments"), and the new tests reference `sourceNodeNum` and altitude round-tripping that does not exist yet. Confirm this is a missing-feature failure, not a typo, by reading the compiler's exact complaint once the change reaches CI (§ "Global Constraints" above - this project has no local build).

- [ ] **Step 3: Implement**

Replace the whole file `app/src/main/kotlin/com/cerocoder/meshrelay/stats/SignalSeriesBuffer.kt` with:

```kotlin
package com.cerocoder.meshrelay.stats

import com.cerocoder.meshrelay.stats.model.PositionOrigin
import com.cerocoder.meshrelay.stats.model.SignalSeries
import com.cerocoder.meshrelay.stats.model.StampedPosition

/**
 * One relay's or one neighbour's measurements, oldest first, capped at
 * [MAX_SAMPLES].
 *
 * Mutable and confined to the engine's coroutine, exactly as `relays` and
 * `neighbours` already are. Nothing outside the engine ever holds one; what
 * leaves is [snapshot], which copies.
 *
 * **Eight parallel primitive arrays, not a list of objects.** A measurement is
 * 33 bytes here - 8 for the timestamp, 4 each for RSSI, SNR, latitude,
 * longitude, altitude and the source node number, 1 for the position's origin -
 * so a full buffer is 165 KB. An object per measurement, with its header and
 * its reference, costs more than twice that in the same worst case, and this is
 * already the largest allocation in the application: a typical session of
 * sixty subjects holds about 9.9 MB, and the theoretical worst case (every
 * relay byte seen, plus neighbours) about 42 MB. That figure is stated in the
 * design rather than discovered in a heap dump, and [MAX_SAMPLES] is the one
 * line to change if the field says 5000 is too many.
 *
 * A ring rather than a list that trims: trimming a 5000-element list on every
 * packet copies 5000 elements per packet, at mesh traffic rates, per subject.
 */
class SignalSeriesBuffer(private val capacity: Int = MAX_SAMPLES) {

    init {
        require(capacity > 0) { "a series buffer needs room for at least one sample" }
    }

    private val times = LongArray(capacity)
    private val rssiValues = FloatArray(capacity)
    private val snrValues = FloatArray(capacity)
    private val latI = IntArray(capacity)
    private val lonI = IntArray(capacity)
    private val altitudeI = IntArray(capacity)

    /** The packet's own sender for this sample - see [SignalSeries.sourceNodeNum]. */
    private val fromNode = IntArray(capacity)

    /** `0` is "no position"; see [PositionOrigin.NONE]. */
    private val source = ByteArray(capacity)

    /** Index of the oldest retained sample. */
    private var head = 0

    /** How many of [capacity] slots are in use. */
    private var size = 0

    /**
     * Every sample ever appended, retained or evicted. See
     * [SignalSeries.totalAppended] for what reads it and why `size` cannot serve.
     */
    var totalAppended = 0L
        private set

    fun append(atMillis: Long, rssi: Float, snr: Float, position: StampedPosition?, fromNode: Int) {
        val slot = (head + size) % capacity
        times[slot] = atMillis
        rssiValues[slot] = rssi
        snrValues[slot] = snr
        // Written unconditionally, including the "no position" case. Writing them
        // only when a position exists would leave the evicted sample's coordinates
        // in a reused slot, and the new measurement would inherit somebody else's
        // hillside.
        latI[slot] = position?.latI ?: 0
        lonI[slot] = position?.lonI ?: 0
        altitudeI[slot] = position?.altitude ?: StampedPosition.NO_ALTITUDE
        source[slot] = position?.origin?.code ?: PositionOrigin.NONE
        // Unconditional for the same reason, though there is no "absent" case to
        // encode here: every sample that exists came from some packet, so this
        // only guards against a reused slot's *previous* sender leaking forward.
        this.fromNode[slot] = fromNode

        if (size < capacity) size++ else head = (head + 1) % capacity
        totalAppended++
    }

    /**
     * A copy, oldest first, trimmed to [size].
     *
     * Copied slot by slot rather than with two `copyOfRange` calls: the ring wraps,
     * so the retained window is one or two runs depending on where the head sits,
     * and one loop that is always right beats two paths of which one is exercised
     * only after 5000 packets.
     */
    fun snapshot(): SignalSeries {
        val outTimes = LongArray(size)
        val outRssi = FloatArray(size)
        val outSnr = FloatArray(size)
        val outLat = IntArray(size)
        val outLon = IntArray(size)
        val outAltitude = IntArray(size)
        val outSource = ByteArray(size)
        val outFromNode = IntArray(size)
        for (i in 0 until size) {
            val slot = (head + i) % capacity
            outTimes[i] = times[slot]
            outRssi[i] = rssiValues[slot]
            outSnr[i] = snrValues[slot]
            outLat[i] = latI[slot]
            outLon[i] = lonI[slot]
            outAltitude[i] = altitudeI[slot]
            outSource[i] = source[slot]
            outFromNode[i] = fromNode[slot]
        }
        return SignalSeries(outTimes, outRssi, outSnr, outLat, outLon, outAltitude, outSource, outFromNode, totalAppended)
    }

    /**
     * Forgets everything, including the count.
     *
     * The arrays themselves are not zeroed: nothing reads past [size], and
     * [append] overwrites every field of a slot before it becomes readable again.
     */
    fun clear() {
        head = 0
        size = 0
        totalAppended = 0L
    }

    companion object {
        /**
         * 5000 measurements per relay and per neighbour. At 33 bytes each that is
         * 165 KB per subject; see this class's own KDoc for the session and
         * worst-case totals that follow from it.
         */
        const val MAX_SAMPLES = 5000
    }
}
```

Replace the whole file `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/SignalSeries.kt` with:

```kotlin
package com.cerocoder.meshrelay.stats.model

/**
 * One subject's measurements, as an immutable value safe to hand to the
 * interface thread. Built only by
 * [com.cerocoder.meshrelay.stats.SignalSeriesBuffer.snapshot], which copies its
 * arrays trimmed to the number of samples actually held.
 *
 * The arrays are private and the accessors are the whole interface, the same
 * rule [NodeDirectorySnapshot] follows: handing out a `LongArray` would hand out
 * something a caller can write to, and this value's entire purpose is to be
 * safe to read while the engine keeps appending to the buffer it came from.
 *
 * Ordered **oldest first**, index `0` being the earliest retained measurement.
 * That is storage's natural append order; turning it into "newest at the top" is
 * `ChartGeometry`'s job, and doing it in one place is what keeps the two from
 * disagreeing.
 *
 * The accessors do not bounds-check. Every caller derives its index from
 * `ChartGeometry.visibleRows`, which is clamped to `size`, and a check per pixel
 * row is not free; an out-of-range index is a defect in the geometry, and an
 * exception is how it should read.
 */
class SignalSeries(
    private val times: LongArray,
    private val rssiValues: FloatArray,
    private val snrValues: FloatArray,
    private val latI: IntArray,
    private val lonI: IntArray,
    private val altitudeI: IntArray,
    private val source: ByteArray,
    private val fromNode: IntArray,
    /**
     * Every sample ever appended to the buffer this came from, retained or
     * evicted. The chart's scroll anchor is the difference between two of these
     * (spec section 8.6); `size` cannot serve, because it stops growing the moment
     * the ring saturates while measurements keep arriving.
     *
     * Reset to zero by `clear()`, so a decrease is how a chart learns the
     * statistics were reset under it.
     */
    val totalAppended: Long,
) {
    val size: Int get() = times.size

    fun atMillis(index: Int): Long = times[index]

    fun rssi(index: Int): Float = rssiValues[index]

    fun snr(index: Int): Float = snrValues[index]

    /** Where the observer was for this measurement, or `null` if nothing was known then. */
    fun positionOf(index: Int): StampedPosition? {
        val origin = PositionOrigin.ofCode(source[index]) ?: return null
        val altitude = altitudeI[index].takeIf { it != StampedPosition.NO_ALTITUDE }
        return StampedPosition(latI[index], lonI[index], origin, altitude)
    }

    /**
     * The node number this measurement's packet actually came from - distinct
     * from the relay byte a [com.cerocoder.meshrelay.stats.SeriesKey.Relay] series
     * is filed under, and usually different from it. For a
     * [com.cerocoder.meshrelay.stats.SeriesKey.Neighbour] series this is always
     * that same neighbour's own node number, by definition.
     *
     * Needs no sentinel and no null case: every sample that exists at all came
     * from some packet, and every packet has a `from`.
     */
    fun sourceNodeNum(index: Int): Int = fromNode[index]

    companion object {
        val EMPTY = SignalSeries(
            times = LongArray(0),
            rssiValues = FloatArray(0),
            snrValues = FloatArray(0),
            latI = IntArray(0),
            lonI = IntArray(0),
            altitudeI = IntArray(0),
            source = ByteArray(0),
            fromNode = IntArray(0),
            totalAppended = 0L,
        )
    }
}
```

In `app/src/main/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngine.kt`, change the two `.append(...)` calls (in `foldRelayed` and `foldDirect`) to pass the sender that is already in scope at each call site:

```kotlin
// foldRelayed, was: .append(atMillis, signal.rssi, signal.snr, positionForSample())
seriesBuffers.getOrPut(SeriesKey.Relay(relayed.relayByte)) { SignalSeriesBuffer() }
    .append(atMillis, signal.rssi, signal.snr, positionForSample(), relayed.fromNode)
```

```kotlin
// foldDirect, was: .append(atMillis, signal.rssi, signal.snr, positionForSample())
seriesBuffers.getOrPut(SeriesKey.Neighbour(direct.fromNode)) { SignalSeriesBuffer() }
    .append(atMillis, signal.rssi, signal.snr, positionForSample(), direct.fromNode)
```

Also add a regression test proving the whole chain (engine → buffer → series) threads the real sender through, not the relay byte. Add to `app/src/test/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngineTest.kt`, near the other `foldRelayed`/series tests:

```kotlin
    @Test
    fun `each relayed sample records who actually sent it, not the relay byte`() = runTest(StandardTestDispatcher()) {
        val subject = engine(backgroundScope)
        val seen = collectSeries(subject)
        subject.watchSeries(SeriesKey.Relay(0x69))
        subject.attach(
            flowOf(
                relayed(relay = 0x69, from = 0x11111111, snr = -15f),
                relayed(relay = 0x69, from = 0x22222222, snr = -10f),
            ),
        )
        runCurrent()

        val series = seen.last()!!
        assertEquals(0x11111111, series.sourceNodeNum(0))
        assertEquals(0x22222222, series.sourceNodeNum(1))
    }

    @Test
    fun `a direct sample's source is the neighbour itself`() = runTest(StandardTestDispatcher()) {
        val subject = engine(backgroundScope)
        val seen = collectSeries(subject)
        subject.watchSeries(SeriesKey.Neighbour(0x11111111))
        subject.attach(flowOf(direct(from = 0x11111111)))
        runCurrent()

        assertEquals(0x11111111, seen.last()?.sourceNodeNum(0))
    }
```

- [ ] **Step 4: Verify the tests pass**

All of `SignalSeriesBufferTest.kt`, `SignalSeriesTest.kt`, and the two new `MeshStatsEngineTest.kt` cases pass; every pre-existing test in all three files still passes unchanged.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/stats/SignalSeriesBuffer.kt app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/SignalSeries.kt app/src/main/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngine.kt app/src/test/kotlin/com/cerocoder/meshrelay/stats/SignalSeriesBufferTest.kt app/src/test/kotlin/com/cerocoder/meshrelay/stats/model/SignalSeriesTest.kt app/src/test/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngineTest.kt
git commit -m "feat(export): record each sample's altitude and actual sender"
```

---

## Task 3: `NodeDirectory` gains `localAltitude()`

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/LocalPosition.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/NodeDirectory.kt:288` (add a sibling method next to `localPosition()`)
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/NodeDirectoryTest.kt`

**Interfaces:**
- Produces: `localAltitudeOf(localNodeNum: Int?, positions: Map<Int, PositionHistory>, nodes: Map<Int, NodeRecord>): Int?` (internal, in `stats.model`), `NodeDirectory.localAltitude(): Int?`.

- [ ] **Step 1: Write the failing tests**

Add to `app/src/test/kotlin/com/cerocoder/meshrelay/stats/NodeDirectoryTest.kt`, immediately after the existing `` `the local position is resolved through the local node number` `` test:

```kotlin
    @Test
    fun `the local altitude is resolved through the local node number, same precedence as position`() {
        assertNull(directory.localAltitude())

        directory.setLocalNodeNum(LOCAL_NODE)
        assertNull(directory.localAltitude())

        // A decoy, exactly as the position test above uses one.
        directory.applyPosition(GETAFE_ROUTER, position(GETAFE_LAT_I, GETAFE_LON_I, altitude = 622))
        directory.applyPosition(LOCAL_NODE, position(MADRID_LAT_I, MADRID_LON_I, altitude = 667))

        assertEquals(667, directory.localAltitude())
    }

    @Test
    fun `with no local node number there is no local altitude`() {
        assertNull(NodeDirectory(TimeSource { 5_000L }).localAltitude())
    }
```

And extend the existing `` `the directory and its snapshot agree on where we are` `` test (it already builds a `directory` with `SENDER` as the local node and two position reports) by adding, right after its final `assertEquals(40.3057734, directory.localPosition()!!.lat, 1e-7)` line:

```kotlin
        assertEquals(610, directory.localAltitude())
```

- [ ] **Step 2: Verify it fails to compile**

`NodeDirectory` has no `localAltitude()` method yet.

- [ ] **Step 3: Implement**

Add to `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/LocalPosition.kt`, after `localPositionOf`:

```kotlin
/**
 * This device's own altitude, as far as the mesh has told it - the same
 * precedence as [localPositionOf], resolved from the identical winning report
 * (`positions[num]?.newestWithCoordinates ?: nodes[num]?.dbPosition`) so the two
 * functions can never disagree about which report they are reading from, even
 * though a caller wanting both position and altitude calls each separately.
 */
internal fun localAltitudeOf(
    localNodeNum: Int?,
    positions: Map<Int, PositionHistory>,
    nodes: Map<Int, NodeRecord>,
): Int? {
    val num = localNodeNum ?: return null
    val report = positions[num]?.newestWithCoordinates ?: nodes[num]?.dbPosition ?: return null
    return report.altitude
}
```

In `app/src/main/kotlin/com/cerocoder/meshrelay/stats/NodeDirectory.kt`, add right after `fun localPosition(): LatLon? = localPositionOf(localNodeNum, positions, nodes)`:

```kotlin
    /** This device's own altitude, without building a snapshot to ask - see [localPosition]. */
    fun localAltitude(): Int? = localAltitudeOf(localNodeNum, positions, nodes)
```

- [ ] **Step 4: Verify the tests pass**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/LocalPosition.kt app/src/main/kotlin/com/cerocoder/meshrelay/stats/NodeDirectory.kt app/src/test/kotlin/com/cerocoder/meshrelay/stats/NodeDirectoryTest.kt
git commit -m "feat(export): resolve the local node's own altitude"
```

---

## Task 4: `MeshStatsEngine.nodePosition()` carries the local node's altitude

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngine.kt:515-518` (`nodePosition()`)
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngineTest.kt`

**Interfaces:**
- Consumes: `NodeDirectory.localAltitude()` (Task 3), `StampedPosition.fromDegrees(..., altitude)` (Task 1).

- [ ] **Step 1: Write the failing test**

Add to `MeshStatsEngineTest.kt`, right after the existing `` `phone mode falls back to the node when no fix has arrived` `` test (it already establishes the exact `myInfoFrame` + `positionFrame` + `relayed` shape this needs):

```kotlin
    @Test
    fun `a node-position sample records the local node's altitude`() = runTest(StandardTestDispatcher()) {
        val subject = MeshStatsEngine(
            backgroundScope, MutableStateFlow(emptySet()), SortMode.PACKETS,
            positionMode = MutableStateFlow(PositionMode.NODE),
        ) { 1_000L }
        val seen = collectSeries(subject)
        subject.watchSeries(SeriesKey.Relay(0x69))
        // positionFrame's own KDoc: altitude is fixed at 600 in the proto it builds.
        subject.attach(flowOf(myInfoFrame(SENDER), positionFrame(SENDER, 398628316, -40273231), relayed()))
        runCurrent()

        assertEquals(600, seen.last()?.positionOf(0)?.altitude)
    }
```

- [ ] **Step 2: Verify it fails**

`nodePosition()` does not pass an altitude yet, so `positionOf(0)?.altitude` is `null`, not `600`. This fails on the assertion, not a compile error - `StampedPosition.altitude` already exists from Task 1.

- [ ] **Step 3: Implement**

In `app/src/main/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngine.kt`, change:

```kotlin
    private fun nodePosition(): StampedPosition? {
        val local = directory.localPosition() ?: return null
        return StampedPosition.fromDegrees(local.lat, local.lon, PositionOrigin.NODE)
    }
```

to:

```kotlin
    private fun nodePosition(): StampedPosition? {
        val local = directory.localPosition() ?: return null
        return StampedPosition.fromDegrees(local.lat, local.lon, PositionOrigin.NODE, directory.localAltitude())
    }
```

- [ ] **Step 4: Verify the test passes**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngine.kt app/src/test/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngineTest.kt
git commit -m "feat(export): carry the local node's altitude into node-position samples"
```

---

## Task 5: The phone's fix carries its own altitude

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/location/AndroidPhoneLocationSource.kt:48-55`

**Interfaces:**
- Consumes: `StampedPosition.fromDegrees(..., altitude)` (Task 1).

No unit test for this file: it is a thin wrapper over `android.location.LocationManager`/`Location`, and no test file exists for it today (this project unit-tests pure JVM logic and verifies Android-framework-touching classes on the phone instead - `docs/verifying.md`). Verified in Task 13's on-device pass instead.

- [ ] **Step 1: Implement**

In `app/src/main/kotlin/com/cerocoder/meshrelay/location/AndroidPhoneLocationSource.kt`, change:

```kotlin
        override fun onLocationChanged(location: Location) {
            _fix.value = StampedPosition.fromDegrees(
                location.latitude,
                location.longitude,
                PositionOrigin.PHONE,
            )
        }
```

to:

```kotlin
        override fun onLocationChanged(location: Location) {
            _fix.value = StampedPosition.fromDegrees(
                location.latitude,
                location.longitude,
                PositionOrigin.PHONE,
                // hasAltitude() guards this: not every provider reports one on every fix.
                altitude = location.altitude.roundToInt().takeIf { location.hasAltitude() },
            )
        }
```

Add the import `kotlin.math.roundToInt` at the top of the file, alongside the existing imports.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/location/AndroidPhoneLocationSource.kt
git commit -m "feat(export): record the phone's own altitude in its position fix"
```

---

## Task 6: `MeshStatsEngine` gains a one-shot export command

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngine.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngineTest.kt`

**Interfaces:**
- Produces: `MeshStatsEngine.requestExport(keys: List<SeriesKey>)`, `MeshStatsEngine.exportConsumed()`, `MeshStatsEngine.exportResult: StateFlow<Map<SeriesKey, SignalSeries>?>`.

- [ ] **Step 1: Write the failing tests**

Add to `MeshStatsEngineTest.kt`, near the other series-related tests:

```kotlin
    @Test
    fun `requestExport returns the buffered series for exactly the requested keys`() = runTest(StandardTestDispatcher()) {
        val subject = engine(backgroundScope)
        collectSnapshots(subject)
        subject.attach(flowOf(relayed(relay = 0x69, snr = -15f), direct(from = 0x11111111, snr = 2f)))
        runCurrent()

        val results = mutableListOf<Map<SeriesKey, SignalSeries>?>()
        val job = launch { subject.exportResult.collect { results += it } }
        subject.requestExport(listOf(SeriesKey.Relay(0x69), SeriesKey.Neighbour(0x11111111)))
        runCurrent()

        val bundle = results.last()
        assertEquals(setOf(SeriesKey.Relay(0x69), SeriesKey.Neighbour(0x11111111)), bundle?.keys)
        assertEquals(1, bundle?.get(SeriesKey.Relay(0x69))?.size)
        assertEquals(1, bundle?.get(SeriesKey.Neighbour(0x11111111))?.size)
        job.cancel()
    }

    @Test
    fun `requestExport resolves a key nothing has been heard for to an empty series`() = runTest(StandardTestDispatcher()) {
        val subject = engine(backgroundScope)
        collectSnapshots(subject)

        val results = mutableListOf<Map<SeriesKey, SignalSeries>?>()
        val job = launch { subject.exportResult.collect { results += it } }
        subject.requestExport(listOf(SeriesKey.Relay(0xff)))
        runCurrent()

        assertEquals(0, results.last()?.get(SeriesKey.Relay(0xff))?.size)
        job.cancel()
    }

    @Test
    fun `exportConsumed clears the result so a stale export is never reread`() = runTest(StandardTestDispatcher()) {
        val subject = engine(backgroundScope)
        collectSnapshots(subject)
        subject.requestExport(listOf(SeriesKey.Relay(0x69)))
        runCurrent()
        assertNotNull(subject.exportResult.value)

        subject.exportConsumed()
        assertNull(subject.exportResult.value)
    }

    @Test
    fun `requesting an export does not disturb an unrelated in-progress watchSeries`() = runTest(StandardTestDispatcher()) {
        val subject = engine(backgroundScope)
        collectSnapshots(subject)
        subject.attach(flowOf(relayed(relay = 0x69, snr = -15f)))
        runCurrent()
        subject.watchSeries(SeriesKey.Relay(0x69))
        runCurrent()
        val watchedSizeBefore = subject.series.value?.size

        subject.requestExport(listOf(SeriesKey.Relay(0xa4)))
        runCurrent()

        assertEquals(watchedSizeBefore, subject.series.value?.size)
    }
```

- [ ] **Step 2: Verify it fails to compile**

`requestExport`, `exportConsumed` and `exportResult` do not exist yet.

- [ ] **Step 3: Implement**

In `app/src/main/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngine.kt`:

Add a new command to the `Command` sealed interface, next to `WatchSeries`:

```kotlin
        data class RequestExport(val keys: List<SeriesKey>) : Command
```

Add new state next to `_series`/`series` (after the `series` property's declaration):

```kotlin
    private val _exportResult = MutableStateFlow<Map<SeriesKey, SignalSeries>?>(null)

    /**
     * The result of the most recent [requestExport], until [exportConsumed] clears
     * it. Unlike [series], this is a one-shot snapshot of several subjects at
     * once - copying every subject's series continuously the way [series] does
     * for one subject would be the exact cost that mechanism was built to avoid,
     * which is why export gets its own command instead of reusing [watchSeries].
     */
    val exportResult: StateFlow<Map<SeriesKey, SignalSeries>?> = _exportResult.asStateFlow()
```

Add the two public entry points next to `watchSeries`:

```kotlin
    /** Copies the requested subjects' buffered series once. See [exportResult]. */
    fun requestExport(keys: List<SeriesKey>) { commands.trySend(Command.RequestExport(keys)) }

    /** Clears [exportResult], so a stale export is never read twice. */
    fun exportConsumed() { _exportResult.value = null }
```

Add a branch to `apply(command)`'s `when`, next to the `is Command.WatchSeries ->` branch:

```kotlin
            is Command.RequestExport -> {
                _exportResult.value = command.keys.associateWith { key -> seriesBuffers[key]?.snapshot() ?: SignalSeries.EMPTY }
            }
```

- [ ] **Step 4: Verify the tests pass**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngine.kt app/src/test/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngineTest.kt
git commit -m "feat(export): add a one-shot multi-subject export command to the engine"
```

---

## Task 7: `ExportRow` and the row-building function

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/export/ExportRow.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/export/ExportRows.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/export/ExportRowsTest.kt` (new file)

**Interfaces:**
- Consumes: `SeriesKey`, `SignalSeries` (incl. `sourceNodeNum`, `positionOf` from Task 2), `StatsSnapshot` (`relays`, `directory`), `NodeDirectorySnapshot.shortName`, `RelayStats.hexId`/`nodeName`, `NodeId.format`.
- Produces: `ExportRow` (data class), `ExportRows.build(seriesByKey: Map<SeriesKey, SignalSeries>, snapshot: StatsSnapshot): List<ExportRow>`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/main/kotlin/com/cerocoder/meshrelay/export/ExportRow.kt`:

```kotlin
package com.cerocoder.meshrelay.export

/**
 * One CSV row's worth of data, already resolved from the engine's types -
 * [com.cerocoder.meshrelay.export.CsvWriter] turns this into text and nothing
 * else does, so this type stays free of any formatting decision.
 *
 * [nodeId]/[nodeName] describe the relay or neighbour this row is filed under;
 * [sourceNodeId]/[sourceNodeName] describe the packet's actual sender, which can
 * differ from the relay for a relay row and is always the same node for a
 * neighbour row. See the design spec, section 7.
 */
data class ExportRow(
    val nodeId: String,
    val nodeName: String,
    val sourceNodeId: String,
    val sourceNodeName: String,
    val atMillis: Long,
    val rssi: Float,
    val snr: Float,
    val observerLat: Double?,
    val observerLon: Double?,
    val observerAltitudeM: Int?,
    val observerSource: String?,
)
```

This file has no logic of its own (a plain data class), so it needs no dedicated test - `ExportRowsTest.kt` below exercises it as the return type of every case.

Create `app/src/test/kotlin/com/cerocoder/meshrelay/export/ExportRowsTest.kt`:

```kotlin
package com.cerocoder.meshrelay.export

import com.cerocoder.meshrelay.stats.SeriesKey
import com.cerocoder.meshrelay.stats.SignalSeriesBuffer
import com.cerocoder.meshrelay.stats.model.Counters
import com.cerocoder.meshrelay.stats.model.NodeDirectorySnapshot
import com.cerocoder.meshrelay.stats.model.PositionOrigin
import com.cerocoder.meshrelay.stats.model.RelayStats
import com.cerocoder.meshrelay.stats.model.StampedPosition
import com.cerocoder.meshrelay.stats.model.StatsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportRowsTest {

    private fun emptyDirectory() = NodeDirectorySnapshot(
        nodes = emptyMap(),
        airNodes = emptyMap(),
        loadedAtMillis = null,
        localNodeNum = null,
        positions = emptyMap(),
        telemetry = emptyMap(),
        skipped = emptySet(),
    )

    private fun snapshotWith(relays: List<RelayStats>, directory: NodeDirectorySnapshot) = StatsSnapshot(
        relays = relays,
        neighbours = emptyList(),
        counters = Counters.EMPTY,
        paused = false,
        sortMode = com.cerocoder.meshrelay.stats.SortMode.PACKETS,
        lastPacketAtMillis = null,
        lastRelayedPacketAtMillis = null,
        directory = directory,
        skippedRelayNodes = emptySet(),
    )

    @Test
    fun `a relay row carries the relay's own id and name plus the packet's source id and name`() {
        val buffer = SignalSeriesBuffer()
        buffer.append(
            1_000L, -94f, -7.5f,
            StampedPosition.fromDegrees(40.330012, -3.750441, PositionOrigin.NODE, altitude = 612),
            0x9e75f1a4.toInt(),
        )
        val relay = RelayStats(relayByte = 0x1a, nodeName = "PQPL1")
        val directory = emptyDirectory()

        val rows = ExportRows.build(
            mapOf(SeriesKey.Relay(0x1a) to buffer.snapshot()),
            snapshotWith(listOf(relay), directory),
        )

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("0x1a", row.nodeId)
        assertEquals("PQPL1", row.nodeName)
        assertEquals("!9e75f1a4", row.sourceNodeId)
        assertEquals(1_000L, row.atMillis)
        assertEquals(-94f, row.rssi, 0.0001f)
        assertEquals(-7.5f, row.snr, 0.0001f)
        assertEquals(40.330012, row.observerLat!!, 1e-6)
        assertEquals(612, row.observerAltitudeM)
        assertEquals("node", row.observerSource)
    }

    @Test
    fun `a relay with several matching candidates gets a blank name but keeps its id`() {
        // RelayStats.nodeName is already "" when the byte is ambiguous - this
        // proves the row-building function passes that through rather than
        // inventing its own guess.
        val buffer = SignalSeriesBuffer()
        buffer.append(1_000L, -94f, -7.5f, null, 0x11111111)
        val relay = RelayStats(relayByte = 0x1a, nodeName = "")

        val rows = ExportRows.build(
            mapOf(SeriesKey.Relay(0x1a) to buffer.snapshot()),
            snapshotWith(listOf(relay), emptyDirectory()),
        )

        assertEquals("0x1a", rows.single().nodeId)
        assertEquals("", rows.single().nodeName)
    }

    @Test
    fun `a subject with no buffered samples produces zero rows`() {
        val rows = ExportRows.build(
            mapOf(SeriesKey.Relay(0x1a) to com.cerocoder.meshrelay.stats.model.SignalSeries.EMPTY),
            snapshotWith(listOf(RelayStats(relayByte = 0x1a)), emptyDirectory()),
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `a sample with no position produces blank observer fields`() {
        val buffer = SignalSeriesBuffer()
        buffer.append(1_000L, -94f, -7.5f, null, 0x11111111)

        val rows = ExportRows.build(
            mapOf(SeriesKey.Relay(0x1a) to buffer.snapshot()),
            snapshotWith(listOf(RelayStats(relayByte = 0x1a)), emptyDirectory()),
        )

        val row = rows.single()
        assertNull(row.observerLat)
        assertNull(row.observerLon)
        assertNull(row.observerAltitudeM)
        assertNull(row.observerSource)
    }

    @Test
    fun `rows are sorted chronologically across every subject in the map, not grouped by key`() {
        val laterFirstBuffer = SignalSeriesBuffer()
        laterFirstBuffer.append(5_000L, -90f, 1f, null, 0x11111111)
        val earlierSecondBuffer = SignalSeriesBuffer()
        earlierSecondBuffer.append(1_000L, -90f, 1f, null, 0x22222222)

        val rows = ExportRows.build(
            // Insertion order deliberately puts the later timestamp first, so a
            // function that merely concatenated per-key blocks would fail this.
            linkedMapOf(
                SeriesKey.Relay(0x1a) to laterFirstBuffer.snapshot(),
                SeriesKey.Relay(0x2b) to earlierSecondBuffer.snapshot(),
            ),
            snapshotWith(listOf(RelayStats(relayByte = 0x1a), RelayStats(relayByte = 0x2b)), emptyDirectory()),
        )

        assertEquals(listOf(1_000L, 5_000L), rows.map { it.atMillis })
    }

    @Test
    fun `two rows sharing a timestamp keep their original relative order`() {
        val buffer = SignalSeriesBuffer()
        buffer.append(1_000L, -90f, 1f, null, 0x11111111)
        buffer.append(1_000L, -80f, 2f, null, 0x22222222)

        val rows = ExportRows.build(
            mapOf(SeriesKey.Relay(0x1a) to buffer.snapshot()),
            snapshotWith(listOf(RelayStats(relayByte = 0x1a)), emptyDirectory()),
        )

        // Both timestamps tie; a stable sort must not swap them.
        assertEquals(listOf(-90f, -80f), rows.map { it.rssi })
    }
}
```

- [ ] **Step 2: Verify it fails to compile**

`ExportRows` does not exist yet.

- [ ] **Step 3: Implement**

Create `app/src/main/kotlin/com/cerocoder/meshrelay/export/ExportRows.kt`:

```kotlin
package com.cerocoder.meshrelay.export

import com.cerocoder.meshrelay.stats.NodeId
import com.cerocoder.meshrelay.stats.SeriesKey
import com.cerocoder.meshrelay.stats.model.PositionOrigin
import com.cerocoder.meshrelay.stats.model.RelayStats
import com.cerocoder.meshrelay.stats.model.SignalSeries
import com.cerocoder.meshrelay.stats.model.StatsSnapshot

/**
 * Turns one or more subjects' [SignalSeries] into the flat, chronological row
 * list [CsvWriter] writes. The only place that resolves a row's id/name pair -
 * the row-per-sample expansion and the identity lookups both live here so a
 * test can assert on either without touching Android or a ring buffer.
 */
object ExportRows {

    fun build(seriesByKey: Map<SeriesKey, SignalSeries>, snapshot: StatsSnapshot): List<ExportRow> {
        val rows = mutableListOf<ExportRow>()
        for ((key, series) in seriesByKey) {
            val (nodeId, nodeName) = identityOf(key, snapshot)
            for (i in 0 until series.size) {
                val sourceNodeNum = series.sourceNodeNum(i)
                val position = series.positionOf(i)
                rows += ExportRow(
                    nodeId = nodeId,
                    nodeName = nodeName,
                    sourceNodeId = NodeId.format(sourceNodeNum),
                    sourceNodeName = snapshot.directory.shortName(sourceNodeNum),
                    atMillis = series.atMillis(i),
                    rssi = series.rssi(i),
                    snr = series.snr(i),
                    observerLat = position?.latitude,
                    observerLon = position?.longitude,
                    observerAltitudeM = position?.altitude,
                    observerSource = when (position?.origin) {
                        PositionOrigin.NODE -> "node"
                        PositionOrigin.PHONE -> "phone"
                        null -> null
                    },
                )
            }
        }
        // Stable, so two samples sharing a timestamp keep the order they were
        // already in - each key's own samples oldest-first, keys visited in the
        // map's own iteration order. See the design spec's Row order decision.
        return rows.sortedBy { it.atMillis }
    }

    private fun identityOf(key: SeriesKey, snapshot: StatsSnapshot): Pair<String, String> = when (key) {
        is SeriesKey.Relay -> {
            val relay = snapshot.relays.find { it.relayByte == key.relayByte } ?: RelayStats(relayByte = key.relayByte)
            relay.hexId to relay.nodeName
        }
        is SeriesKey.Neighbour -> NodeId.format(key.nodeNum) to snapshot.directory.shortName(key.nodeNum)
    }
}
```

- [ ] **Step 4: Verify the tests pass**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/export/ExportRow.kt app/src/main/kotlin/com/cerocoder/meshrelay/export/ExportRows.kt app/src/test/kotlin/com/cerocoder/meshrelay/export/ExportRowsTest.kt
git commit -m "feat(export): build chronological CSV rows from one or more subjects' series"
```

---

## Task 8: `CsvWriter`

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/export/CsvWriter.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/export/CsvWriterTest.kt` (new file)

**Interfaces:**
- Consumes: `ExportRow` (Task 7), `com.cerocoder.meshrelay.ui.common.StatsFormat.sampleRssi`/`sampleSnr` (existing, already `Locale`-parameterised and already the app's own "one sample's RSSI/SNR" precision - `%.0f`/`%.1f` - so the CSV shows a measurement exactly as the Graph screen's own crosshair would).
- Produces: `CsvWriter.write(rows: List<ExportRow>): String`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/com/cerocoder/meshrelay/export/CsvWriterTest.kt`:

```kotlin
package com.cerocoder.meshrelay.export

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class CsvWriterTest {

    private val header = "node_id,node_name,source_node_id,source_node_name,timestamp_utc,rssi_dbm,snr_db,observer_lat,observer_lon,observer_altitude_m,observer_source"

    private fun row(
        nodeId: String = "0x1a",
        nodeName: String = "PQPL1",
        sourceNodeId: String = "!9e75f1a4",
        sourceNodeName: String = "1ce5",
        atMillis: Long = 1_758_000_000_000L,
        rssi: Float = -94f,
        snr: Float = -7.5f,
        observerLat: Double? = 40.330012,
        observerLon: Double? = -3.750441,
        observerAltitudeM: Int? = 612,
        observerSource: String? = "node",
    ) = ExportRow(nodeId, nodeName, sourceNodeId, sourceNodeName, atMillis, rssi, snr, observerLat, observerLon, observerAltitudeM, observerSource)

    @Test
    fun `an empty row list still writes the header`() {
        assertEquals("$header\n", CsvWriter.write(emptyList()))
    }

    @Test
    fun `one row writes exactly eleven comma-separated fields`() {
        val text = CsvWriter.write(listOf(row()))
        val dataLine = text.lines()[1]
        assertEquals(11, dataLine.split(",").size)
    }

    @Test
    fun `rssi is whole and snr keeps one decimal, matching the app's own single-sample precision`() {
        val text = CsvWriter.write(listOf(row(rssi = -94f, snr = -7.5f)))
        val fields = text.lines()[1].split(",")
        assertEquals("-94", fields[5])
        assertEquals("-7.5", fields[6])
    }

    @Test
    fun `coordinates are formatted to seven decimal places`() {
        val text = CsvWriter.write(listOf(row(observerLat = 40.330012, observerLon = -3.750441)))
        val fields = text.lines()[1].split(",")
        assertEquals("40.3300120", fields[7])
        assertEquals("-3.7504410", fields[8])
    }

    @Test
    fun `null observer fields render as blank, not the word null`() {
        val text = CsvWriter.write(
            listOf(row(observerLat = null, observerLon = null, observerAltitudeM = null, observerSource = null)),
        )
        val fields = text.lines()[1].split(",")
        assertEquals("", fields[7])
        assertEquals("", fields[8])
        assertEquals("", fields[9])
        assertEquals("", fields[10])
    }

    @Test
    fun `the timestamp is ISO-8601 UTC at seconds precision`() {
        // 2026-09-16T04:00:00Z
        val text = CsvWriter.write(listOf(row(atMillis = 1_789_531_200_000L)))
        assertEquals("2026-09-16T04:00:00Z", text.lines()[1].split(",")[4])
    }

    @Test
    fun `a node name containing a comma is quoted`() {
        val text = CsvWriter.write(listOf(row(nodeName = "Getafe, Router 2")))
        assertEquals("\"Getafe, Router 2\"", text.lines()[1].split(",", limit = 3).let {
            // Re-join is unnecessary: a correctly quoted comma keeps the field whole,
            // so splitting on "," naively would otherwise have produced 12 pieces.
            CsvWriter.write(listOf(row(nodeName = "Getafe, Router 2"))).lines()[1]
        }.let { line -> Regex("\"[^\"]*\"").find(line)!!.value })
    }

    @Test
    fun `a node name containing a double quote is escaped by doubling it`() {
        val text = CsvWriter.write(listOf(row(nodeName = """Node "One"""")))
        assertEquals("\"Node \"\"One\"\"\"", Regex("\"(?:[^\"]|\"\")*\"").find(text.lines()[1])!!.value)
    }

    @Test
    fun `a node name containing a newline is quoted`() {
        val text = CsvWriter.write(listOf(row(nodeName = "Line one\nLine two")))
        // RFC 4180 preserves a quoted field's literal embedded newline as-is - it
        // is not escaped away - so the raw text genuinely has one more "\n" than
        // there are logical rows, and a naive String.lines() (which has no CSV
        // awareness) would wrongly count it as an extra line. The real assertion
        // has to go through a CSV-aware split to prove the newline stayed inside
        // its field rather than starting a spurious third row.
        val dataLine = text.substringAfter('\n').trim('\n')
        val fields = splitCsvLine(dataLine)
        assertEquals(11, fields.size)
        assertEquals("Line one\nLine two", fields[1])
    }

    @Test
    fun `a quoted field's own comma does not create an extra column`() {
        val text = CsvWriter.write(listOf(row(nodeName = "Getafe, Router 2")))
        val dataLine = text.lines()[1]
        // A naive split on "," would see 12 pieces; a correct CSV reader sees 11.
        assertEquals(11, splitCsvLine(dataLine).size)
    }

    /** A minimal RFC 4180 field splitter, for this test file only - not production code. */
    private fun splitCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { current.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { fields += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        fields += current.toString()
        return fields
    }

    @Test
    fun `numbers use a decimal point even when the default locale uses a comma`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("es-ES"))
            val text = CsvWriter.write(listOf(row(rssi = -94f, snr = -7.5f, observerLat = 40.330012, observerLon = -3.750441)))
            val fields = text.lines()[1].split(",")
            assertEquals("-94", fields[5])
            assertEquals("-7.5", fields[6])
            assertEquals("40.3300120", fields[7])
            assertEquals("-3.7504410", fields[8])
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `the field separator is always a comma, never a locale semicolon`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("es-ES"))
            val text = CsvWriter.write(listOf(row()))
            assertEquals(header, text.lines()[0])
        } finally {
            Locale.setDefault(previous)
        }
    }
}
```

- [ ] **Step 2: Verify it fails to compile**

`CsvWriter` does not exist yet.

- [ ] **Step 3: Implement**

Create `app/src/main/kotlin/com/cerocoder/meshrelay/export/CsvWriter.kt`:

```kotlin
package com.cerocoder.meshrelay.export

import com.cerocoder.meshrelay.ui.common.StatsFormat
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Pure text formatting, and nothing else - [ExportRows] decides what a row
 * *is*, this decides how it *reads*. Android-free, like
 * [com.cerocoder.meshrelay.ui.common.PositionLineText] and
 * [com.cerocoder.meshrelay.ui.graph.ChartGeometry], so it is unit-testable on
 * the JVM without a `Uri` or a `ContentResolver` in sight.
 *
 * Every number is [Locale.ROOT], always - this is a data file, not prose, and
 * unlike [com.cerocoder.meshrelay.ui.common.PositionLineText] (which
 * deliberately uses the *display* locale) a decimal comma here would break
 * every spreadsheet's import. RSSI and SNR reuse
 * [StatsFormat.sampleRssi]/[StatsFormat.sampleSnr] - the same precision the
 * Graph screen's own crosshair already shows for one measurement - rather than
 * inventing a second formatting rule for the same numbers.
 */
object CsvWriter {

    private const val COORDINATE_PATTERN = "%.7f"

    private val HEADER = listOf(
        "node_id", "node_name", "source_node_id", "source_node_name",
        "timestamp_utc", "rssi_dbm", "snr_db",
        "observer_lat", "observer_lon", "observer_altitude_m", "observer_source",
    ).joinToString(",")

    fun write(rows: List<ExportRow>): String {
        val sb = StringBuilder()
        sb.append(HEADER).append('\n')
        for (row in rows) sb.append(lineOf(row)).append('\n')
        return sb.toString()
    }

    private fun lineOf(row: ExportRow): String = listOf(
        row.nodeId,
        row.nodeName,
        row.sourceNodeId,
        row.sourceNodeName,
        timestampOf(row.atMillis),
        StatsFormat.sampleRssi(row.rssi, Locale.ROOT),
        StatsFormat.sampleSnr(row.snr, Locale.ROOT),
        row.observerLat?.let { String.format(Locale.ROOT, COORDINATE_PATTERN, it) } ?: "",
        row.observerLon?.let { String.format(Locale.ROOT, COORDINATE_PATTERN, it) } ?: "",
        row.observerAltitudeM?.toString() ?: "",
        row.observerSource ?: "",
    ).joinToString(",") { quoteIfNeeded(it) }

    /** Seconds precision, always UTC - a file opened later must not depend on the writer's time zone. */
    private fun timestampOf(atMillis: Long): String =
        Instant.ofEpochMilli(atMillis).truncatedTo(ChronoUnit.SECONDS).toString()

    /** RFC 4180: quote a field that contains the delimiter, a quote, or a line break; double any quote inside it. */
    private fun quoteIfNeeded(field: String): String {
        if (field.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return field
        return "\"" + field.replace("\"", "\"\"") + "\""
    }
}
```

- [ ] **Step 4: Verify the tests pass**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/export/CsvWriter.kt app/src/test/kotlin/com/cerocoder/meshrelay/export/CsvWriterTest.kt
git commit -m "feat(export): write ExportRows as RFC 4180 CSV, always dot-decimal"
```

---

## Task 9: `ExportKind` and `ExportFileNames`

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/export/ExportKind.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/export/ExportFileNames.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/export/ExportFileNamesTest.kt` (new file)

**Interfaces:**
- Consumes: `SeriesKey`, `NodeId.format`.
- Produces: `enum class ExportKind { RELAY_DETAIL, NEIGHBOUR_DETAIL, RELAY_LIST, NEIGHBOUR_LIST }`, `ExportFileNames.suggest(kind: ExportKind, keys: List<SeriesKey>, nowMillis: Long): String`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/main/kotlin/com/cerocoder/meshrelay/export/ExportKind.kt`:

```kotlin
package com.cerocoder.meshrelay.export

/**
 * Which of the four export commands this is - carried explicitly rather than
 * inferred from the key list, because an empty list export (nothing tracked
 * yet on that screen) must still name itself correctly. See
 * [ExportFileNames.suggest].
 */
enum class ExportKind { RELAY_DETAIL, NEIGHBOUR_DETAIL, RELAY_LIST, NEIGHBOUR_LIST }
```

Create `app/src/test/kotlin/com/cerocoder/meshrelay/export/ExportFileNamesTest.kt`:

```kotlin
package com.cerocoder.meshrelay.export

import com.cerocoder.meshrelay.stats.SeriesKey
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportFileNamesTest {

    private val nowMillis = 1_789_502_524_000L // 2026-09-20T18:32:04Z

    @Test
    fun `a relay detail export names itself after the relay's byte`() {
        val name = ExportFileNames.suggest(ExportKind.RELAY_DETAIL, listOf(SeriesKey.Relay(0x1a)), nowMillis)
        assertEquals("meshrelay_relay_0x1a_20260920_183204.csv", name)
    }

    @Test
    fun `a neighbour detail export names itself after the node id, without the leading bang`() {
        val name = ExportFileNames.suggest(ExportKind.NEIGHBOUR_DETAIL, listOf(SeriesKey.Neighbour(0x9e75f1a4.toInt())), nowMillis)
        assertEquals("meshrelay_neighbour_9e75f1a4_20260920_183204.csv", name)
    }

    @Test
    fun `a relay list export names itself generically, even with nothing tracked yet`() {
        val name = ExportFileNames.suggest(ExportKind.RELAY_LIST, emptyList(), nowMillis)
        assertEquals("meshrelay_relays_20260920_183204.csv", name)
    }

    @Test
    fun `a neighbour list export names itself generically, even with nothing tracked yet`() {
        val name = ExportFileNames.suggest(ExportKind.NEIGHBOUR_LIST, emptyList(), nowMillis)
        assertEquals("meshrelay_neighbours_20260920_183204.csv", name)
    }
}
```

- [ ] **Step 2: Verify it fails to compile**

`ExportFileNames` does not exist yet.

- [ ] **Step 3: Implement**

Create `app/src/main/kotlin/com/cerocoder/meshrelay/export/ExportFileNames.kt`:

```kotlin
package com.cerocoder.meshrelay.export

import com.cerocoder.meshrelay.stats.NodeId
import com.cerocoder.meshrelay.stats.SeriesKey
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The document picker's suggested filename - never the final one, since the
 * picker always lets the person saving it rename or relocate it. [kind] decides
 * the shape rather than inspecting [keys], because a list export with nothing
 * tracked yet still has to name itself as a relay or a neighbour export.
 */
object ExportFileNames {

    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC)

    fun suggest(kind: ExportKind, keys: List<SeriesKey>, nowMillis: Long): String {
        val stamp = TIMESTAMP.format(Instant.ofEpochMilli(nowMillis))
        return when (kind) {
            ExportKind.RELAY_DETAIL -> {
                val relayByte = (keys.single() as SeriesKey.Relay).relayByte
                "meshrelay_relay_${String.format(Locale.ROOT, "0x%02x", relayByte)}_$stamp.csv"
            }
            ExportKind.NEIGHBOUR_DETAIL -> {
                val nodeNum = (keys.single() as SeriesKey.Neighbour).nodeNum
                "meshrelay_neighbour_${NodeId.format(nodeNum).removePrefix("!")}_$stamp.csv"
            }
            ExportKind.RELAY_LIST -> "meshrelay_relays_$stamp.csv"
            ExportKind.NEIGHBOUR_LIST -> "meshrelay_neighbours_$stamp.csv"
        }
    }
}
```

- [ ] **Step 4: Verify the tests pass**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/export/ExportKind.kt app/src/main/kotlin/com/cerocoder/meshrelay/export/ExportFileNames.kt app/src/test/kotlin/com/cerocoder/meshrelay/export/ExportFileNamesTest.kt
git commit -m "feat(export): suggest a filename for the document picker"
```

---

## Task 10: The export menu item on the two list screens

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/common/StatsTopBar.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/relays/RelayListScreen.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/neighbours/NeighbourListScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-es/strings.xml`

**Interfaces:**
- Produces: `StatsTopBar(..., onExport: () -> Unit, ...)`, `RelayListScreen(..., onExport: () -> Unit, ...)`, `NeighbourListScreen(..., onExport: () -> Unit, ...)`.

No unit test: these are Compose screens, and this project verifies Composables on the phone and in Previews rather than with JVM tests (no test file exists for `StatsTopBar`, `RelayListScreen`, or `NeighbourListScreen` today). Verified in Task 13.

- [ ] **Step 1: Add the string resource**

In `app/src/main/res/values/strings.xml`, in the `<!-- Actions -->` block, right after `action_graph`:

```xml
    <string name="action_export">Export data</string>
```

In `app/src/main/res/values-es/strings.xml`, at the same position:

```xml
    <string name="action_export">Exportar datos</string>
```

- [ ] **Step 2: Add `onExport` to `StatsTopBar`**

In `app/src/main/kotlin/com/cerocoder/meshrelay/ui/common/StatsTopBar.kt`, add `onExport: () -> Unit,` to the parameter list, right after `onSetGaugeMode: (GaugeMode) -> Unit,`:

```kotlin
fun StatsTopBar(
    title: String,
    sort: SortAction?,
    gaugeMode: GaugeMode,
    onSetGaugeMode: (GaugeMode) -> Unit,
    onExport: () -> Unit,
    paused: Boolean,
    onTogglePause: () -> Unit,
    onReset: () -> Unit,
    onOpenSettings: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    reload: ReloadAction? = null,
) {
```

Add a new `DropdownMenuItem` to the overflow menu, right after the "Gauges" item and before the `if (reload != null)` block:

```kotlin
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_export)) },
                        onClick = {
                            overflowExpanded = false
                            onExport()
                        },
                    )
```

- [ ] **Step 3: Wire it through `RelayListScreen`**

In `app/src/main/kotlin/com/cerocoder/meshrelay/ui/relays/RelayListScreen.kt`, add `onExport: () -> Unit,` to the function signature right after `onSetGaugeMode: (GaugeMode) -> Unit,`, and pass it to `StatsTopBar`:

```kotlin
fun RelayListScreen(
    snapshot: StatsSnapshot,
    connection: ConnectionState,
    gaugeMode: GaugeMode,
    nodeDbReloading: Boolean,
    onOpenRelay: (relayByte: Int) -> Unit,
    onSetSortMode: (SortMode) -> Unit,
    onSetGaugeMode: (GaugeMode) -> Unit,
    onExport: () -> Unit,
    onTogglePause: () -> Unit,
    onReset: () -> Unit,
    onReloadNodeDb: () -> Unit,
    onOpenSettings: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
```

```kotlin
            StatsTopBar(
                title = stringResource(R.string.relays_title),
                sort = SortAction(snapshot.sortMode, SortMode.entries, onSetSortMode),
                gaugeMode = gaugeMode,
                onSetGaugeMode = onSetGaugeMode,
                onExport = onExport,
                paused = snapshot.paused,
                onTogglePause = onTogglePause,
                onReset = onReset,
                onOpenSettings = onOpenSettings,
                onExit = onExit,
                reload = ReloadAction(inProgress = isReloading, onReload = onReloadNodeDb),
            )
```

- [ ] **Step 4: Wire it through `NeighbourListScreen`**

Same shape, in `app/src/main/kotlin/com/cerocoder/meshrelay/ui/neighbours/NeighbourListScreen.kt`:

```kotlin
fun NeighbourListScreen(
    snapshot: StatsSnapshot,
    gaugeMode: GaugeMode,
    onOpenNeighbour: (nodeNum: Int) -> Unit,
    onSetSortMode: (SortMode) -> Unit,
    onSetGaugeMode: (GaugeMode) -> Unit,
    onExport: () -> Unit,
    onTogglePause: () -> Unit,
    onReset: () -> Unit,
    onOpenSettings: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
```

```kotlin
            StatsTopBar(
                title = stringResource(R.string.neighbours_title),
                sort = SortAction(
                    mode = snapshot.sortMode.forNeighbours(),
                    available = SortMode.entries - SortMode.KNOWN_NODES,
                    onSet = onSetSortMode,
                ),
                gaugeMode = gaugeMode,
                onSetGaugeMode = onSetGaugeMode,
                onExport = onExport,
                paused = snapshot.paused,
                onTogglePause = onTogglePause,
                onReset = onReset,
                onOpenSettings = onOpenSettings,
                onExit = onExit,
            )
```

- [ ] **Step 5: Fix every `@Preview` in these three files**

`StatsTopBar`, `RelayListScreen`, and `NeighbourListScreen` each have `@Preview` composables that call the function under test with named arguments (`onExport` was not in scope for any of them, so the Kotlin compiler forces a value at every call site with no default). Search each of the three files for every call to `StatsTopBar(`, `RelayListScreen(`, and `NeighbourListScreen(` respectively, and add `onExport = {},` to each one. There is no default value on `onExport` (every real caller must supply one; a silent no-op default would let a screen forget to wire it), so this step is mandatory for the files to compile.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/ui/common/StatsTopBar.kt app/src/main/kotlin/com/cerocoder/meshrelay/ui/relays/RelayListScreen.kt app/src/main/kotlin/com/cerocoder/meshrelay/ui/neighbours/NeighbourListScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-es/strings.xml
git commit -m "feat(export): add an Export command to both list screens' overflow menu"
```

---

## Task 11: Wire export through `MeshRelayNavHost`

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/MeshRelayNavHost.kt`

**Interfaces:**
- Consumes: `ExportKind` (Task 9), `SeriesKey`, `onExport: () -> Unit` (Task 10's list screens), `DetailMenuItem` (existing).
- Produces: `MeshRelayNavHost(..., onExportSeries: (ExportKind, List<SeriesKey>) -> Unit, ...)`.

No unit test: this is the navigation host, a Compose function with no test file today. Verified in Task 13.

- [ ] **Step 1: Add a shared `DetailSubject` → `SeriesKey` mapping**

`GraphDestination` already computes this inline (`when (subject) { is DetailSubject.Relay -> SeriesKey.Relay(...); ... }`); `DetailDestination` needs the identical mapping for its new export menu item, so this step extracts it once rather than duplicating the `when`. Add this private top-level function to `MeshRelayNavHost.kt`, near the other private helpers at file scope (not inside any composable):

```kotlin
/** [DetailSubject] and [SeriesKey] mirror each other; this is the one place that says how. */
private fun DetailSubject.toSeriesKey(): SeriesKey = when (this) {
    is DetailSubject.Relay -> SeriesKey.Relay(relayByte)
    is DetailSubject.Neighbour -> SeriesKey.Neighbour(nodeNum)
}
```

In `GraphDestination`, replace:

```kotlin
    val key = when (subject) {
        is DetailSubject.Relay -> SeriesKey.Relay(subject.relayByte)
        is DetailSubject.Neighbour -> SeriesKey.Neighbour(subject.nodeNum)
    }
```

with:

```kotlin
    val key = subject.toSeriesKey()
```

- [ ] **Step 2: Add `onExportSeries` to `MeshRelayNavHost`'s signature and thread it to `MainScaffold`/`DetailDestination`**

Add the import `com.cerocoder.meshrelay.export.ExportKind` at the top of the file, alongside the other imports.

Add `onExportSeries: (ExportKind, List<SeriesKey>) -> Unit,` to `MeshRelayNavHost`'s own parameter list, right after `onExit: () -> Unit,`:

```kotlin
fun MeshRelayNavHost(
    container: AppContainer,
    devices: List<DeviceListEntry>,
    readiness: BleReadiness,
    onRequestPermissions: () -> Unit,
    onSelectDevice: (DeviceListEntry) -> Unit,
    onDisconnect: () -> Unit,
    onExit: () -> Unit,
    onExportSeries: (ExportKind, List<SeriesKey>) -> Unit,
    modifier: Modifier = Modifier,
) {
```

Pass it into the `is Screen.Main ->` branch's call to `MainScaffold`:

```kotlin
        is Screen.Main -> MainScaffold(
            tab = screen.tab,
            snapshot = snapshot,
            connectionState = connectionState,
            settings = settings,
            meshviewUrl = meshviewUrl,
            nodeDbReloading = nodeDbReloading,
            container = container,
            backStack = backStack,
            onExit = onExit,
            onExportSeries = onExportSeries,
            modifier = modifier,
        )
```

And into the `is Screen.Detail ->` branch's call to `DetailDestination`:

```kotlin
        is Screen.Detail -> DetailDestination(
            subject = screen.subject,
            snapshot = snapshot,
            settings = settings,
            meshviewUrl = meshviewUrl,
            container = container,
            backStack = backStack,
            onExportSeries = onExportSeries,
            modifier = modifier,
        )
```

- [ ] **Step 3: Use it in `MainScaffold`**

Add `onExportSeries: (ExportKind, List<SeriesKey>) -> Unit,` to `MainScaffold`'s parameter list, right after `onExit: () -> Unit,`, and wire the two list screens' `onExport`:

```kotlin
private fun MainScaffold(
    tab: MainTab,
    snapshot: StatsSnapshot,
    connectionState: ConnectionState,
    settings: AppSettings,
    meshviewUrl: String?,
    nodeDbReloading: Boolean,
    container: AppContainer,
    backStack: BackStack,
    onExit: () -> Unit,
    onExportSeries: (ExportKind, List<SeriesKey>) -> Unit,
    modifier: Modifier = Modifier,
) {
```

```kotlin
            MainTab.RELAYS -> RelayListScreen(
                snapshot = snapshot,
                connection = connectionState,
                gaugeMode = settings.gaugeMode,
                nodeDbReloading = nodeDbReloading,
                onOpenRelay = { relayByte -> backStack.push(Screen.Detail(DetailSubject.Relay(relayByte))) },
                onSetSortMode = { mode -> container.engine.setSortMode(mode) },
                onSetGaugeMode = { mode -> container.settings.update { it.copy(gaugeMode = mode) } },
                onExport = { onExportSeries(ExportKind.RELAY_LIST, snapshot.relays.map { SeriesKey.Relay(it.relayByte) }) },
                onTogglePause = { container.engine.setPaused(!snapshot.paused) },
                onReset = { container.engine.reset() },
                onReloadNodeDb = { container.connectionManager.reloadNodeDatabase() },
                onOpenSettings = { backStack.push(Screen.Settings) },
                onExit = onExit,
                modifier = Modifier.padding(innerPadding),
            )

            MainTab.NEIGHBOURS -> NeighbourListScreen(
                snapshot = snapshot,
                gaugeMode = settings.gaugeMode,
                onOpenNeighbour = { nodeNum -> backStack.push(Screen.Detail(DetailSubject.Neighbour(nodeNum))) },
                onSetSortMode = { mode -> container.engine.setSortMode(mode) },
                onSetGaugeMode = { mode -> container.settings.update { it.copy(gaugeMode = mode) } },
                onExport = { onExportSeries(ExportKind.NEIGHBOUR_LIST, snapshot.neighbours.map { SeriesKey.Neighbour(it.nodeNum) }) },
                onTogglePause = { container.engine.setPaused(!snapshot.paused) },
                onReset = { container.engine.reset() },
                onOpenSettings = { backStack.push(Screen.Settings) },
                onExit = onExit,
                modifier = Modifier.padding(innerPadding),
            )
```

(`MainTab.MY_NODE`'s branch is unchanged - My Node has no export command, per the spec's four named entry points.)

- [ ] **Step 4: Use it in `DetailDestination`**

Add `onExportSeries: (ExportKind, List<SeriesKey>) -> Unit,` to `DetailDestination`'s parameter list, right after `backStack: BackStack,`, and add a second `DetailMenuItem`:

```kotlin
private fun DetailDestination(
    subject: DetailSubject,
    snapshot: StatsSnapshot,
    settings: AppSettings,
    meshviewUrl: String?,
    container: AppContainer,
    backStack: BackStack,
    onExportSeries: (ExportKind, List<SeriesKey>) -> Unit,
    modifier: Modifier = Modifier,
) {
```

Inside the function, compute the export kind alongside the existing `relayByte`/`relay` locals:

```kotlin
    val exportKind = when (subject) {
        is DetailSubject.Relay -> ExportKind.RELAY_DETAIL
        is DetailSubject.Neighbour -> ExportKind.NEIGHBOUR_DETAIL
    }
```

And change the `menuItems` list:

```kotlin
        // A relay's or neighbour's own chart data, exported to a file - the
        // second command in this list, per DetailMenuItem's own KDoc.
        menuItems = listOf(
            DetailMenuItem(R.string.action_graph) { backStack.push(Screen.Graph(subject)) },
            DetailMenuItem(R.string.action_export) { onExportSeries(exportKind, listOf(subject.toSeriesKey())) },
        ),
```

- [ ] **Step 5: Fix every `@Preview` that calls `MeshRelayNavHost`, `MainScaffold`, or `DetailDestination`**

If any exist (search the file and any preview-only files for these three names), add `onExportSeries = { _, _ -> },` to each call. As in Task 10, there is deliberately no default.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/ui/MeshRelayNavHost.kt
git commit -m "feat(export): add an Export command to both detail screens' overflow menu"
```

---

## Task 12: The Save As flow in `MainActivity`

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/MainActivity.kt`

**Interfaces:**
- Consumes: `MeshStatsEngine.requestExport`/`exportResult`/`exportConsumed` (Task 6), `ExportRows.build` (Task 7), `CsvWriter.write` (Task 8), `ExportFileNames.suggest` (Task 9), `MeshRelayNavHost(..., onExportSeries)` (Task 11).

No unit test: this is the activity-level Compose wiring and a real `ActivityResultLauncher`, neither of which this project unit-tests (no test file exists for `MainActivity` today). Verified in Task 13, which is this whole feature's actual proof: the picker opening and the file's contents are the thing no JVM test here can see.

- [ ] **Step 1: Add the imports**

In `app/src/main/kotlin/com/cerocoder/meshrelay/MainActivity.kt`, add:

```kotlin
import com.cerocoder.meshrelay.export.CsvWriter
import com.cerocoder.meshrelay.export.ExportFileNames
import com.cerocoder.meshrelay.export.ExportKind
import com.cerocoder.meshrelay.export.ExportRows
import com.cerocoder.meshrelay.stats.SeriesKey
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
```

- [ ] **Step 2: Add the launcher and the orchestration inside `MeshRelayContent`**

In `MeshRelayContent` (the private composable in this file, already hosting `permissionLauncher`), add - right after the existing `val permissionLauncher = rememberLauncherForActivityResult(...)` block:

```kotlin
    // The container's own snapshot, collected here too (MeshRelayNavHost already
    // collects it for rendering): export needs it to resolve a row's node id/name
    // at the moment the picker returns, which can be well after the tap that
    // started it.
    val exportSnapshot by container.engine.snapshot.collectAsState()

    var pendingExportText by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        val text = pendingExportText
        pendingExportText = null
        if (uri != null && text != null) {
            appContext.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            }
        }
    }

    // Known, accepted limitation carried over from the spec's own shape for
    // exportResult (design doc section 6): it is not correlated to a particular
    // request. Two Export taps started close enough together that the second
    // fires before the first's requestExport/exportConsumed round trip finishes
    // could each observe the other's result. The menu closes after one tap and
    // the round trip is in-memory and near-instant, so this needs two deliberate,
    // fast taps on two different screens to trigger - accepted rather than adding
    // a request-id correlation the spec did not ask for.
    val onExportSeries: (ExportKind, List<SeriesKey>) -> Unit = { kind, keys ->
        scope.launch {
            container.engine.requestExport(keys)
            val seriesByKey = container.engine.exportResult.filterNotNull().first()
            container.engine.exportConsumed()
            val rows = ExportRows.build(seriesByKey, exportSnapshot)
            pendingExportText = CsvWriter.write(rows)
            exportLauncher.launch(ExportFileNames.suggest(kind, keys, System.currentTimeMillis()))
        }
    }
```

- [ ] **Step 3: Pass it to `MeshRelayNavHost`**

At the bottom of `MeshRelayContent`, add `onExportSeries = onExportSeries,` to the existing `MeshRelayNavHost(...)` call, right after `onExit = onExit,`:

```kotlin
    MeshRelayNavHost(
        container = container,
        devices = container.devices + found.values.sortedBy { it.name },
        readiness = readiness,
        onRequestPermissions = { permissionLauncher.launch(requested) },
        onSelectDevice = { device ->
            if (backgroundCollection) container.startForegroundService()
            container.requestConnect(device.address)
        },
        onDisconnect = {
            container.requestDisconnect()
            scope.launch { container.connectionManager.disconnect() }
        },
        onExit = onExit,
        onExportSeries = onExportSeries,
    )
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/MainActivity.kt
git commit -m "feat(export): open the system Save As picker and write the CSV"
```

---

## Task 13: CI verification and on-device verification

This project has no local Android SDK and no Gradle wrapper - `docs/verifying.md` is the only way any of Tasks 1-12 gets built or run.

- [ ] **Step 1: Push and read CI**

Follow `docs/verifying.md` section 1 exactly: find the run for the pushed commit's SHA via the unauthenticated API, poll on a 60-second interval or slower, and read the failure comment (not the log API) if it goes red. Fix forward with a new commit; do not amend.

- [ ] **Step 2: Fetch the debug APK and install it**

Follow `docs/verifying.md` section 2: the artifact via `nightly.link`, checked for `com.cerocoder.meshrelay` before installing (this machine has a sibling project whose debug APK shares the same file name).

- [ ] **Step 3: On the phone, verify each of the four export commands**

Using `adb` and the view-hierarchy dump technique in `docs/verifying.md` section 3 (read the layout, do not rely on a screenshot):

1. **Relay detail** - open a relay with at least a few measurements, tap ⋮ → Export data, confirm the system picker opens, save, pull the file with `adb pull` (or `run-as` + `cat`, per the section's own pattern) and confirm: the header row matches §7 of the spec exactly; every data row's `node_id`/`node_name` are that one relay's; `source_node_id`/`source_node_name` vary if more than one node's traffic was carried; rows are non-decreasing by `timestamp_utc`; numbers use `.`, never `,`.
2. **Neighbour detail** - same, on a neighbour; confirm `source_node_id` equals `node_id` on every row (a neighbour's source is always itself).
3. **Relay list** - tap ⋮ on the relay list → Export data with at least two relays tracked; confirm the file contains rows from more than one `node_id`, still chronologically sorted across the whole file (not grouped by relay).
4. **Neighbour list** - same, on the neighbour list.
5. Tap Export on a screen with **nothing tracked yet** (a relay/neighbour just opened before any packet arrived, or a fresh session's list); confirm the picker still opens and the saved file has a header with zero data rows, rather than the command doing nothing.
6. Confirm the header line's altitude column (`observer_altitude_m`) is populated when a position is known (compare against the header's own `Alt(...)` reading on the same screen, which should agree - both resolve altitude the same way).
7. Confirm the app does not crash and stays responsive through all of the above (`adb shell pidof com.cerocoder.meshrelay` before and after).
8. Repeat step 1 (relay detail) with the app's language set to Spanish (`docs/verifying.md`'s `shared_prefs` technique), to confirm the CSV's numbers stay dot-decimal even though the menu item now reads "Exportar datos".

- [ ] **Step 4: Record the result**

If every check above passes, this feature is done. If anything owed a fix, make it as a new commit, and re-run Steps 1-3 of this task against the new commit before considering the feature complete.
