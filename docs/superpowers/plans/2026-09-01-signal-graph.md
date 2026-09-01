# The Graph command — RSSI and SNR against time — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `Graph` command in an overflow menu on both detail screens, opening one shared
full-screen chart that plots a relay's or a neighbour's RSSI and SNR against time, with every
measurement stamped with where the observer was standing when it was taken.

**Architecture:** The engine gains a per-subject ring buffer of primitive arrays, folded at the same
point the statistics already are, and publishes **one** watched subject's series on a channel of its
own — never through `StatsSnapshot`, which every list screen collects. The chart is a virtualised
`Canvas` that owns its own scroll offset; all of its arithmetic lives in a pure `ChartGeometry`
object with unit tests, exactly as `GaugeGeometry` already does for the bars. Position comes from
the platform `LocationManager` (phone) or from what the node has already told us (node), decided by
one new setting.

**Tech Stack:** Unchanged. Kotlin 2.4.10, Compose BOM 2026.06.01, JUnit 4, `kotlinx-coroutines-test`.
**No new dependencies** — no charting library, no `play-services-location`.

**Spec:** `docs/superpowers/specs/2026-09-01-signal-graph-design.md`. Read it before Task 1; this
plan argues from it throughout and cites its section numbers. Where this plan overrides it, the
override is in *Decisions taken before writing this plan* below, with the reason.

---

## Global Constraints

Every task's requirements implicitly include this section.

- **No new dependencies.** Not a charting library, not `play-services-location`, not
  `material-icons-extended`, not `navigation-compose`.
- **`stats/**` must not import `android.*`**, in main or test sources. The new
  `SignalSeriesBuffer`, `SignalSeries`, `StampedPosition`, `SeriesKey` and `PositionMode` all live
  under `stats/` and are plain-JVM.
- **`stats/**` must not import `ui/**`.** `SeriesKey` mirrors `DetailSubject` and must not be it;
  the navigation host maps one to the other.
- **No user-facing literal strings in Kotlin.** Everything comes from `R.string`.
- **Both locales, always.** Every key added to `values/strings.xml` gets one in
  `values-es/strings.xml` **in the same commit**. `StringsParityTest` fails the build otherwise,
  and it also checks that `%1$s`-style placeholders match between the two.
- **No `@Composable` may contain arithmetic or formatting.** Computation lives in a pure function
  with its own test — that is what `ChartGeometry` is for.
- **No Russian anywhere** — code, comments, commits, resources, documents.
- **Never call `System.currentTimeMillis()` outside `SystemTimeSource`** (Compose `@Preview`
  fixtures excepted; `SampleData` and the existing previews already do this).
- **Coordinates are never stored as `Float`.** The standing rule at `PositionReport.kt:24`. They are
  stored as `Int` scaled by 1e-7 and converted in `Double`.
- **The verification loop is CI, then the phone.** There is no local Android SDK and no Gradle
  wrapper, so *nothing in this plan can be built or run on the development machine*. Every task ends
  at a green CI run; Tasks 10-12 are not done until they have been read off `uiautomator dump` on
  the device, **in Spanish as well as English**. Read `docs/verifying.md` before the first push, not
  after the first failure: `gh` is not installed here, the unauthenticated GitHub API allows sixty
  requests an hour for the whole machine, and neither fact is discoverable by trying.

---

## Decisions taken before writing this plan

Recorded because each one closes a question a reader will otherwise re-open, and three of them
override the spec.

1. **`SignalSeries` does not carry its own `SignalStats`.** Spec §5.4 says it should. Spec §8.4 says
   the auto scale range is "the same two figures the bars print beside themselves", and the bars
   print `RelayStats.snr`/`.rssi` (or `NeighbourStats.snr`/`.rssi`) straight from `StatsSnapshot`.
   Two copies of the same statistic, arriving on two different channels at two different instants,
   is exactly how the bar and the plot come to disagree — which is the one thing §8.4 promises
   cannot happen. So the series carries measurements only, and the screen takes the statistics it
   already has as parameters. This deletes state rather than adding it.

2. **`SignalSeries` carries `totalAppended: Long` instead of a version counter.** Spec §8.6 needs
   "how many samples arrived since the last frame" to re-anchor the scroll. Once the buffer
   saturates at 5000, `size` stops growing, so the delta cannot be derived from `size`. A
   monotonic total does derive it, exactly, at every fill level. The engine reuses the same field as
   its change token (see decision 3), so it is one field doing two jobs rather than two fields.

3. **The engine republishes a watched series only when it changed.** Spec §6.4 publishes in every
   build step. At mesh traffic rates that copies 125 KB and recomposes the chart several times a
   second even when the watched relay heard nothing — the neighbouring rows' packets would redraw
   this relay's chart. Comparing the buffer's `totalAppended` against the last published value is
   three lines and removes both the garbage and the pointless recomposition. Publishing is still
   gated on `subscriptionCount > 0` as §6.4 requires.

4. **The graph area's drag moves the crosshair; the scrollbar scrolls.** Spec §8.7 says a drag moves
   the crosshair and §8.2's drawing puts a scrollbar down the right-hand edge; a `Modifier.scrollable`
   under the same touch drag would fight it. The crosshair gesture consumes touch drags on the plot,
   the scrollbar owns dragging to scroll, and `Modifier.scrollable` stays on the plot so a mouse
   wheel or a hardware scroll still works. **This is the single most likely thing to feel wrong in
   the hand** and is item 8 of the hardware check in Task 13.

5. **`SignalBlock` gains scale parameters.** Requirement 5 says Auto scale moves *the bars'* borders
   as well as the plot's. `SignalBlock` today reads `SignalScales` internally. It gains four
   parameters defaulting to those same constants, so its three existing call sites are unchanged and
   the graph screen is the one caller that passes something else.

6. **The `Time` fields render local time then local date**, e.g. `13:01:13 21.08.2026` in `Pic1.pdf`.
   Built from `DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)` and `ofLocalizedDate(FormatStyle.SHORT)`
   joined by a space, both `.withLocale(locale)` — not a hardcoded pattern. `MEDIUM` is the shortest
   built-in time style that includes seconds, which the drawing shows; `SHORT` is the only date
   style that stays numeric, which is what the drawing shows and what fits under a chart. A Spanish
   reader gets `21/8/26`, an English one `21/08/2026` or `8/21/26` — the ordering is the platform's
   to decide, exactly as `StatsFormat.nodeDatabaseLastHeard` already argues.

7. **`localPositionOf` becomes one shared function.** The engine needs the local node's position
   *per packet*, and `NodeDirectorySnapshot.localPosition()` is only reachable by copying every map
   in the directory. Rather than a second copy of the precedence rule (whose own KDoc says it lives
   "in exactly one place"), rules 1-2 move into an internal function in `stats/model/` that both
   `NodeDirectory` and `NodeDirectorySnapshot` call, pinned by a test that they agree.

8. **Background location is out of scope and flagged, not silently added.** On API 29+ an app
   receives location updates while backgrounded only if its foreground service declares
   `android:foregroundServiceType="location"` — this app's declares `connectedDevice`. Adding the
   type also adds the `FOREGROUND_SERVICE_LOCATION` permission to the store-visible set, which is a
   second permission change the spec did not ask for. So: with the screen on, every sample is
   stamped; with the screen off and background collection running, samples on API 29+ will fall back
   to the node's position. Task 13 records this in `docs/deferred-work.md` and puts it on the
   hardware checklist; **the owner decides** whether to spend the permission.

---

## Model selection

If this plan is executed with `superpowers:subagent-driven-development`, dispatch each task on the
model named here. If it is executed inline, ignore this section.

| # | Task | Implementer | Reviewer |
|---|---|---|---|
| 1 | The vocabulary: keys, origins, modes | Sonnet | Sonnet |
| 2 | `SignalSeriesBuffer` and `SignalSeries` | Sonnet | **Opus** |
| 3 | `NeighbourStats` narrows to `SignalStats` | Sonnet | Sonnet |
| 4 | The engine collects, stamps and publishes | Sonnet | **Opus** |
| 5 | `location/`, the manifest, the permission request | Sonnet | **Opus** |
| 6 | The *Use phone location* setting | Sonnet | Sonnet |
| 7 | `AppContainer` wiring | Sonnet | Sonnet |
| 8 | `ChartGeometry` | Sonnet | **Opus** |
| 9 | `Screen.Graph` and the back stack | Sonnet | Sonnet |
| 10 | The overflow menu on `DetailScreen` | Sonnet | Sonnet |
| 11 | `SignalGraphScreen` and `SignalChart` | **Opus** | **Opus** |
| 12 | Navigation host wiring | Sonnet | **Opus** |
| 13 | Documents and the hardware run | Sonnet | Sonnet |
| — | Final whole-branch review | — | **Opus** |

**No Haiku anywhere**, for the reason the previous plan recorded: this project's Compose tasks have
no test harness, so their only gates are "it compiles" and "it was read off a `uiautomator dump`",
which makes their reviewers do *more* work than usual, not less.

**Task 2 gets an Opus reviewer** because a ring buffer's wrap arithmetic is the classic place for an
off-by-one that only shows after 5000 samples — that is, never in a test anyone thinks to write and
always in the field. **Task 4** touches the engine's declaration-order rule, whose violation is
silent and untestable. **Task 5** changes the application's permission set. **Task 8** is the
arithmetic every pixel on the screen depends on. **Task 11** is the whole feature, written once.
**Task 12** touches the `rememberSaveable` back stack, where a defect survives a rotation and
surfaces somewhere else entirely.

---

## File Structure

```
app/src/main/kotlin/com/cerocoder/meshrelay/
├── location/                                    NEW PACKAGE
│   ├── PhoneLocationSource.kt        CREATE  interface: fix/start/stop, plain JVM
│   ├── AndroidPhoneLocationSource.kt CREATE  platform LocationManager, 10 s / 10 m
│   └── LocationAvailability.kt       CREATE  the permission array + the granted check
├── settings/
│   ├── AppSettings.kt                MODIFY  +usePhoneLocation = true
│   └── SettingsRepository.kt         MODIFY  +one boolean key
├── stats/
│   ├── SeriesKey.kt                  CREATE  Relay(byte) | Neighbour(nodeNum)
│   ├── PositionMode.kt               CREATE  PHONE | NODE
│   ├── SignalSeriesBuffer.kt         CREATE  the ring buffer, engine-confined
│   ├── NodeDirectory.kt              MODIFY  +localPosition(), no snapshot needed
│   ├── MeshStatsEngine.kt            MODIFY  series state, 3 commands, folding, publishing
│   └── model/
│       ├── StampedPosition.kt        CREATE  +PositionOrigin, the 1e-7 conversion
│       ├── SignalSeries.kt           CREATE  the immutable value handed to the interface
│       ├── LocalPosition.kt          CREATE  the shared precedence rule (decision 7)
│       └── NeighbourStats.kt         MODIFY  SignalHistory -> SignalStats
├── ui/
│   ├── common/
│   │   └── StatsFormat.kt            MODIFY  +graphTimestamp()
│   ├── detail/
│   │   ├── SignalBlock.kt            MODIFY  +4 scale parameters, defaulted
│   │   └── DetailScreen.kt           MODIFY  +menuItems, +DetailMenuItem, stale KDoc out
│   ├── graph/                                   NEW PACKAGE
│   │   ├── ChartGeometry.kt          CREATE  pure, unit-tested
│   │   ├── SignalChart.kt            CREATE  the canvas + the crosshair + the scrollbar
│   │   └── SignalGraphScreen.kt      CREATE  the destination
│   ├── nav/{Screen,BackStack}.kt     MODIFY  +Screen.Graph, tags 6 and 7
│   ├── neighbours/NeighbourCard.kt   MODIFY  .snr.stats -> .snr
│   ├── preview/SampleData.kt         MODIFY  narrowing + a graph fixture
│   ├── settings/SettingsScreen.kt    MODIFY  +one SwitchRow and its summary
│   └── MeshRelayNavHost.kt           MODIFY  Graph destination, menu item, watch lifecycle
├── AppContainer.kt                   MODIFY  location source, position mode, engine params
├── MainActivity.kt                   MODIFY  location in the requested permission array
└── src/main/AndroidManifest.xml      MODIFY  unrestricted location permissions

app/src/test/kotlin/com/cerocoder/meshrelay/
├── location/LocationAvailabilityTest.kt   CREATE
├── settings/SettingsRepositoryTest.kt     MODIFY  +usePhoneLocation
├── stats/SignalSeriesBufferTest.kt        CREATE
├── stats/MeshStatsEngineTest.kt           MODIFY  +6 tests
├── stats/NodeDirectoryTest.kt             MODIFY  +the agreement test
├── stats/model/SignalSeriesTest.kt        CREATE
├── ui/graph/ChartGeometryTest.kt          CREATE
└── ui/nav/BackStackTest.kt                MODIFY  +Screen.Graph

app/src/main/res/values{,-es}/strings.xml  MODIFY  +11 keys each
```

---

### Task 1: The vocabulary — keys, origins, position modes

Three tiny plain-JVM types nothing else can be written without. Do this first; everything after it
names these.

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/SeriesKey.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/PositionMode.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/StampedPosition.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/model/PositionTest.kt` (exists; add to it)

**Interfaces:**
- Produces: `SeriesKey.Relay(relayByte: Int)`, `SeriesKey.Neighbour(nodeNum: Int)`;
  `PositionMode.PHONE`, `PositionMode.NODE`;
  `PositionOrigin.NODE`/`.PHONE` each with `code: Byte`, `PositionOrigin.NONE: Byte`,
  `PositionOrigin.ofCode(code: Byte): PositionOrigin?`;
  `StampedPosition(latI: Int, lonI: Int, origin: PositionOrigin)` with `latitude: Double`,
  `longitude: Double`, and `StampedPosition.fromDegrees(lat: Double, lon: Double, origin): StampedPosition`.

- [ ] **Step 1: Write the failing tests**

Append to `app/src/test/kotlin/com/cerocoder/meshrelay/stats/model/PositionTest.kt`:

```kotlin
@Test
fun `a stamped position round-trips through the scaled integer form`() {
    // Getafe, the mesh this app was built for. Seven decimal places is the
    // protobuf's own resolution, so nothing here should lose a digit.
    val stamped = StampedPosition.fromDegrees(40.3057734, -3.7325611, PositionOrigin.PHONE)
    assertEquals(403057734, stamped.latI)
    assertEquals(-37325611, stamped.lonI)
    assertEquals(40.3057734, stamped.latitude, 1e-9)
    assertEquals(-3.7325611, stamped.longitude, 1e-9)
    assertEquals(PositionOrigin.PHONE, stamped.origin)
}

@Test
fun `the extremes of the coordinate system stay inside an Int`() {
    // The whole reason the scaled form is four bytes rather than eight: 180
    // degrees scales to 1.8e9, and Int tops out at 2.147e9. One degree more of
    // headroom than the coordinate system has.
    assertEquals(900000000, StampedPosition.fromDegrees(90.0, 180.0, PositionOrigin.NODE).latI)
    assertEquals(1800000000, StampedPosition.fromDegrees(90.0, 180.0, PositionOrigin.NODE).lonI)
    assertEquals(-1800000000, StampedPosition.fromDegrees(-90.0, -180.0, PositionOrigin.NODE).lonI)
}

@Test
fun `an origin survives the byte it is stored as, and zero is not an origin`() {
    // Zero is the "no position" marker in SignalSeriesBuffer's source array, so it
    // must never decode to a real origin - that is what makes latI/lonI safe to
    // leave at their default when a sample has no position at all.
    PositionOrigin.entries.forEach { assertEquals(it, PositionOrigin.ofCode(it.code)) }
    assertNull(PositionOrigin.ofCode(PositionOrigin.NONE))
    assertNull(PositionOrigin.ofCode(99))
}
```

Add the imports `PositionOrigin`, `StampedPosition` and `org.junit.Assert.assertNull` if the file
does not already have them.

- [ ] **Step 2: Run them and watch them fail**

`gradle :app:testDebugUnitTest --tests '*PositionTest*'` — expect "unresolved reference:
StampedPosition". There is no local Android SDK, so this runs in CI; see `docs/verifying.md`. It is
acceptable for the fail/pass edge to be observed only at Step 4's green run, but do not skip
writing the test first.

- [ ] **Step 3: Implement**

`stats/SeriesKey.kt`:

```kotlin
package com.cerocoder.meshrelay.stats

/**
 * Which subject a signal series belongs to.
 *
 * Mirrors `ui.nav.DetailSubject` and deliberately is not it: `stats/` may not
 * import `ui/`, and the navigation host is the one place that maps one to the
 * other. The two must stay in step; a subject that can be opened but whose
 * measurements are filed under no key would draw an empty chart.
 */
sealed interface SeriesKey {
    /** One relay byte, `0x00..0xff` as the firmware reports it. */
    data class Relay(val relayByte: Int) : SeriesKey

    /** One whole node number, heard directly. */
    data class Neighbour(val nodeNum: Int) : SeriesKey
}
```

`stats/PositionMode.kt`:

```kotlin
package com.cerocoder.meshrelay.stats

/**
 * Where a measurement's coordinates are taken from.
 *
 * The engine's own vocabulary for the *Use phone location* setting, so that
 * `stats/` does not import `settings/` to ask a question it can be told the
 * answer to. [PHONE] falls back to the node when no fix has arrived; [NODE]
 * never falls back to the phone. See `MeshStatsEngine.positionForSample`.
 */
enum class PositionMode { PHONE, NODE }
```

`stats/model/StampedPosition.kt`:

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
 */
data class StampedPosition(val latI: Int, val lonI: Int, val origin: PositionOrigin) {

    /** Multiplied in `Double`, the conversion [PositionReport] already documents. */
    val latitude: Double get() = latI * COORD_SCALE
    val longitude: Double get() = lonI * COORD_SCALE

    companion object {
        /** Coordinates travel as integers scaled by ten million. */
        const val COORD_SCALE = 1e-7

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
        fun fromDegrees(lat: Double, lon: Double, origin: PositionOrigin): StampedPosition =
            StampedPosition(
                latI = (lat / COORD_SCALE).roundToInt(),
                lonI = (lon / COORD_SCALE).roundToInt(),
                origin = origin,
            )
    }
}
```

- [ ] **Step 4: Run the tests, green**

`gradle :app:testDebugUnitTest --tests '*PositionTest*'`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/stats/SeriesKey.kt \
        app/src/main/kotlin/com/cerocoder/meshrelay/stats/PositionMode.kt \
        app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/StampedPosition.kt \
        app/src/test/kotlin/com/cerocoder/meshrelay/stats/model/PositionTest.kt
git commit -m "feat(stats): a series key, a position mode and a stamped position"
```

---

### Task 2: `SignalSeriesBuffer` and `SignalSeries`

The storage half, whole. Plain JVM, no Compose, no Android — the largest allocation in the
application and the one place a wrap-around bug can hide.

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/SignalSeriesBuffer.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/SignalSeries.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/SignalSeriesBufferTest.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/model/SignalSeriesTest.kt`

**Interfaces:**
- Consumes: `StampedPosition`, `PositionOrigin` (Task 1).
- Produces:
  `SignalSeriesBuffer(capacity: Int = MAX_SAMPLES)` with
  `append(atMillis: Long, rssi: Float, snr: Float, position: StampedPosition?)`,
  `snapshot(): SignalSeries`, `clear()`, `val totalAppended: Long`,
  `companion object { const val MAX_SAMPLES = 5000 }`;
  `SignalSeries` with `val size: Int`, `atMillis(i: Int): Long`, `rssi(i: Int): Float`,
  `snr(i: Int): Float`, `positionOf(i: Int): StampedPosition?`, `val totalAppended: Long`,
  `companion object { val EMPTY: SignalSeries }`.

- [ ] **Step 1: Write the failing tests**

`app/src/test/kotlin/com/cerocoder/meshrelay/stats/SignalSeriesBufferTest.kt`:

```kotlin
package com.cerocoder.meshrelay.stats

import com.cerocoder.meshrelay.stats.model.PositionOrigin
import com.cerocoder.meshrelay.stats.model.StampedPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The buffer is a fixed-size ring, so every test here that matters is about what
 * happens at and past the wrap. A capacity of 4 is used rather than the real
 * 5000: the arithmetic is identical and a failure prints a list a human can read.
 */
class SignalSeriesBufferTest {

    private val getafe = StampedPosition.fromDegrees(40.3057734, -3.7325611, PositionOrigin.PHONE)
    private val toledo = StampedPosition.fromDegrees(39.8628316, -4.0273231, PositionOrigin.NODE)

    @Test
    fun `samples come back oldest first, in the order they were appended`() {
        val buffer = SignalSeriesBuffer(capacity = 4)
        buffer.append(1_000L, -90f, 5f, null)
        buffer.append(2_000L, -91f, 4f, null)

        val series = buffer.snapshot()
        assertEquals(2, series.size)
        assertEquals(1_000L, series.atMillis(0))
        assertEquals(2_000L, series.atMillis(1))
        assertEquals(-90f, series.rssi(0), 0.0001f)
        assertEquals(4f, series.snr(1), 0.0001f)
    }

    @Test
    fun `past capacity the oldest sample is evicted and the order still holds`() {
        val buffer = SignalSeriesBuffer(capacity = 4)
        // Six into a ring of four: 1 and 2 are gone, 3..6 survive in order. A
        // buffer that read its arrays from index 0 instead of from the head would
        // return 5, 6, 3, 4 here and pass every test that only appends twice.
        repeat(6) { i -> buffer.append((i + 1) * 1_000L, -90f - i, i.toFloat(), null) }

        val series = buffer.snapshot()
        assertEquals(4, series.size)
        assertEquals(listOf(3_000L, 4_000L, 5_000L, 6_000L), (0 until series.size).map { series.atMillis(it) })
        assertEquals(-92f, series.rssi(0), 0.0001f)
        assertEquals(5f, series.snr(3), 0.0001f)
    }

    @Test
    fun `a position and its origin survive the arrays they are split across`() {
        val buffer = SignalSeriesBuffer(capacity = 4)
        buffer.append(1_000L, -90f, 5f, getafe)
        buffer.append(2_000L, -91f, 4f, toledo)

        val series = buffer.snapshot()
        assertEquals(getafe, series.positionOf(0))
        assertEquals(toledo, series.positionOf(1))
        assertEquals(PositionOrigin.PHONE, series.positionOf(0)?.origin)
        assertEquals(PositionOrigin.NODE, series.positionOf(1)?.origin)
    }

    @Test
    fun `a sample with no position reads back as no position`() {
        // Not as 0,0. The globe on that measurement's crosshair must be disabled,
        // not pointed at the Gulf of Guinea.
        val buffer = SignalSeriesBuffer(capacity = 4)
        buffer.append(1_000L, -90f, 5f, null)
        assertNull(buffer.snapshot().positionOf(0))
    }

    @Test
    fun `a slot reused after the wrap does not keep the evicted sample's position`() {
        // The failure this forbids: latI/lonI/source are only written when a
        // position is present, so a positioned sample evicted by an unpositioned
        // one would leave its coordinates in the slot and the new sample would
        // inherit them.
        val buffer = SignalSeriesBuffer(capacity = 2)
        buffer.append(1_000L, -90f, 5f, getafe)
        buffer.append(2_000L, -90f, 5f, getafe)
        buffer.append(3_000L, -90f, 5f, null)

        val series = buffer.snapshot()
        assertEquals(getafe, series.positionOf(0))
        assertNull(series.positionOf(1))
    }

    @Test
    fun `totalAppended counts every sample ever appended, not the ones retained`() {
        // This is what the chart re-anchors its scroll by. Once the ring is full,
        // size stops moving and only this number still says how many arrived.
        val buffer = SignalSeriesBuffer(capacity = 4)
        repeat(10) { buffer.append(it.toLong(), -90f, 5f, null) }
        assertEquals(10L, buffer.totalAppended)
        assertEquals(10L, buffer.snapshot().totalAppended)
        assertEquals(4, buffer.snapshot().size)
    }

    @Test
    fun `clear empties the buffer and restarts the count`() {
        val buffer = SignalSeriesBuffer(capacity = 4)
        repeat(6) { buffer.append(it.toLong(), -90f, 5f, getafe) }
        buffer.clear()

        assertEquals(0, buffer.snapshot().size)
        assertEquals(0L, buffer.totalAppended)

        // And it is usable afterwards, from the head, not from wherever the ring
        // happened to stop.
        buffer.append(9_000L, -70f, 1f, null)
        assertEquals(1, buffer.snapshot().size)
        assertEquals(9_000L, buffer.snapshot().atMillis(0))
    }

    @Test
    fun `the default capacity is the one the memory budget was calculated for`() {
        // 25 bytes per measurement x 5000 = 125 KB per relay or neighbour. If this
        // number changes, the figure in the spec's section 5.3 is wrong.
        assertEquals(5000, SignalSeriesBuffer.MAX_SAMPLES)
    }
}
```

`app/src/test/kotlin/com/cerocoder/meshrelay/stats/model/SignalSeriesTest.kt`:

```kotlin
package com.cerocoder.meshrelay.stats.model

import com.cerocoder.meshrelay.stats.SignalSeriesBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SignalSeriesTest {

    @Test
    fun `the snapshot is trimmed to size, not to capacity`() {
        // A snapshot the length of the capacity would hand the interface 4998
        // zero-timestamped measurements at the start of a session, and the chart
        // would plot them.
        val buffer = SignalSeriesBuffer(capacity = 100)
        buffer.append(1_000L, -90f, 5f, null)
        buffer.append(2_000L, -91f, 4f, null)
        assertEquals(2, buffer.snapshot().size)
    }

    @Test
    fun `the snapshot does not change when the buffer does`() {
        // The whole reason snapshot() copies. The chart holds this value for as
        // long as a composition lives, on another thread, while the engine keeps
        // folding packets into the arrays behind it.
        val buffer = SignalSeriesBuffer(capacity = 100)
        buffer.append(1_000L, -90f, 5f, null)
        val taken = buffer.snapshot()

        buffer.append(2_000L, -91f, 4f, null)

        assertEquals(1, taken.size)
        assertEquals(2, buffer.snapshot().size)
    }

    @Test
    fun `EMPTY has nothing in it and answers every question`() {
        assertEquals(0, SignalSeries.EMPTY.size)
        assertEquals(0L, SignalSeries.EMPTY.totalAppended)
    }

    @Test
    fun `an out-of-range index is a programming error, not a silent zero`() {
        // The accessors index the arrays directly. That is deliberate - a bounds
        // check per pixel row is not free - and this test records that the failure
        // mode is a thrown exception rather than a plotted zero.
        val series = SignalSeriesBuffer(capacity = 4).also { it.append(1L, -90f, 5f, null) }.snapshot()
        try {
            series.rssi(1)
            throw AssertionError("expected an index out of bounds")
        } catch (expected: IndexOutOfBoundsException) {
            assertNull(null)
        }
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

`gradle :app:testDebugUnitTest --tests '*SignalSeries*'` — expect "unresolved reference:
SignalSeriesBuffer".

- [ ] **Step 3: Implement `SignalSeries`**

`stats/model/SignalSeries.kt`:

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
    private val source: ByteArray,
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
        return StampedPosition(latI[index], lonI[index], origin)
    }

    companion object {
        val EMPTY = SignalSeries(
            times = LongArray(0),
            rssiValues = FloatArray(0),
            snrValues = FloatArray(0),
            latI = IntArray(0),
            lonI = IntArray(0),
            source = ByteArray(0),
            totalAppended = 0L,
        )
    }
}
```

- [ ] **Step 4: Implement `SignalSeriesBuffer`**

`stats/SignalSeriesBuffer.kt`:

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
 * **Six parallel primitive arrays, not a list of objects.** A measurement is 25
 * bytes here - 8 for the timestamp, 4 each for RSSI, SNR, latitude and
 * longitude, 1 for the position's origin - so a full buffer is 125 KB. An object
 * per measurement, with its header and its reference, costs more than twice that
 * in the same worst case, and this is already the largest allocation in the
 * application: a typical session of sixty subjects holds about 7.5 MB, and the
 * theoretical worst case (every relay byte seen, plus neighbours) about 32 MB.
 * That figure is stated in the design rather than discovered in a heap dump, and
 * [MAX_SAMPLES] is the one line to change if the field says 5000 is too many.
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

    fun append(atMillis: Long, rssi: Float, snr: Float, position: StampedPosition?) {
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
        source[slot] = position?.origin?.code ?: PositionOrigin.NONE

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
        val outSource = ByteArray(size)
        for (i in 0 until size) {
            val slot = (head + i) % capacity
            outTimes[i] = times[slot]
            outRssi[i] = rssiValues[slot]
            outSnr[i] = snrValues[slot]
            outLat[i] = latI[slot]
            outLon[i] = lonI[slot]
            outSource[i] = source[slot]
        }
        return SignalSeries(outTimes, outRssi, outSnr, outLat, outLon, outSource, totalAppended)
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
         * 5000 measurements per relay and per neighbour. At 25 bytes each that is
         * 125 KB per subject; see this class's own KDoc for the session and
         * worst-case totals that follow from it.
         */
        const val MAX_SAMPLES = 5000
    }
}
```

- [ ] **Step 5: Run the tests, green**

`gradle :app:testDebugUnitTest --tests '*SignalSeries*'`

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/stats/SignalSeriesBuffer.kt \
        app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/SignalSeries.kt \
        app/src/test/kotlin/com/cerocoder/meshrelay/stats/SignalSeriesBufferTest.kt \
        app/src/test/kotlin/com/cerocoder/meshrelay/stats/model/SignalSeriesTest.kt
git commit -m "feat(stats): a ring buffer of measurements, and its immutable snapshot"
```

---

### Task 3: `NeighbourStats` narrows to `SignalStats`

Spec §5.6. `NeighbourStats.snr`/`.rssi` are `SignalHistory` today, and nothing in `main/` reads
their `samples` list. With Task 2's series in place those samples are duplicate storage — a second,
smaller, unread history of the same measurements. `TelemetryRecord` keeps `SignalHistory`, so the
type itself stays.

Do this before the chart exists, not after: it is what makes both detail subjects hand the graph
screen the same `SignalStats` type.

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/NeighbourStats.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngine.kt` (`foldDirect`, `sortedNeighbours`)
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/neighbours/NeighbourCard.kt:104,116`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/detail/DetailScreen.kt:251-252,321-322`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/preview/SampleData.kt:458-459,465-466`

**Interfaces:**
- Produces: `NeighbourStats(nodeNum, snr: SignalStats, rssi: SignalStats, packetCount, lastPacketAtMillis)`.

- [ ] **Step 1: Narrow the model**

```kotlin
package com.cerocoder.meshrelay.stats.model

/**
 * Signal statistics for one directly-heard neighbour (a node whose packets
 * reached this device with no relay in between). Ports NeighbourStat,
 * mesh_stats.py:439-452.
 *
 * [snr] and [rssi] were [SignalHistory] until the signal series arrived. Their
 * sample lists were never read by anything in `main/` and are now genuinely
 * duplicate storage - `MeshStatsEngine` keeps a `SignalSeriesBuffer` per
 * neighbour, capped ten times higher and carrying the position each sample was
 * taken at. [TelemetryRecord] still uses [SignalHistory]; telemetry really does
 * need a per-metric sample list, so the type stays.
 */
data class NeighbourStats(
    val nodeNum: Int,
    val snr: SignalStats = SignalStats.EMPTY,
    val rssi: SignalStats = SignalStats.EMPTY,
    val packetCount: Int = 0,
    val lastPacketAtMillis: Long = 0,
)
```

- [ ] **Step 2: Follow the compiler**

Five call sites, all mechanical. `MeshStatsEngine.foldDirect` — the timestamp argument goes, because
`SignalStats.plus` takes only a value:

```kotlin
snr = if (signal == null) existing.snr else existing.snr.plus(signal.snr),
rssi = if (signal == null) existing.rssi else existing.rssi.plus(signal.rssi),
```

`MeshStatsEngine.sortedNeighbours` — `rank(it.snr.stats)` becomes `rank(it.snr)`, same for `rssi`.

`NeighbourCard.kt:104,116` — `stats = neighbour.snr.stats` becomes `stats = neighbour.snr`, same for
`rssi`.

`DetailScreen.kt:251-252` — `snr = neighbour.snr.stats` becomes `snr = neighbour.snr`, same for
`rssi`. `DetailScreen.kt:321-322`, the `previewNeighbourNoRelayByte` fixture:

```kotlin
snr = SignalStats.EMPTY.plus(2.0f),
rssi = SignalStats.EMPTY.plus(-91f),
```

and drop the now-unused `import com.cerocoder.meshrelay.stats.model.SignalHistory`.

`SampleData.kt:458-459,465-466` — same shape:

```kotlin
snr = SignalStats.EMPTY.plus(9.0f).plus(8.7f),
rssi = SignalStats.EMPTY.plus(-60f).plus(-58f),
...
snr = SignalStats.EMPTY.plus(10.2f),
rssi = SignalStats.EMPTY.plus(-55f),
```

Keep `SampleData`'s `SignalHistory` import only if telemetry fixtures still use it; the compiler
says which.

- [ ] **Step 3: Check the existing tests still pin what they meant to**

`SignalStatsTest` at `:56-72` tests `SignalHistory` directly, not through `NeighbourStats`, so it
is unaffected and stays. Search for any assertion reading `.snr.stats` in
`app/src/test/`:

```bash
grep -rn '\.snr\.stats\|\.rssi\.stats\|\.snr\.samples\|\.rssi\.samples' app/src/test/
```

Fix whatever it finds by dropping the `.stats`.

- [ ] **Step 4: Green CI run**

Compilation is the gate here; there is no behaviour change to test. Push and read the run per
`docs/verifying.md`.

- [ ] **Step 5: Commit** — `refactor(stats): a neighbour keeps statistics, not a second history`

---

### Task 4: The engine collects, stamps and publishes

The heart of it. Spec §6 in full, plus decision 7's shared local-position rule.

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/LocalPosition.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/NodeDirectory.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/NodeDirectorySnapshot.kt` (`localPosition`)
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngine.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngineTest.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/NodeDirectoryTest.kt`

**Interfaces:**
- Consumes: `SeriesKey`, `PositionMode`, `StampedPosition`, `PositionOrigin` (Task 1);
  `SignalSeriesBuffer`, `SignalSeries` (Task 2).
- Produces:
  `MeshStatsEngine(scope, skippedRelayNodes, initialSortMode, positionMode: StateFlow<PositionMode> = MutableStateFlow(PositionMode.PHONE), phoneFix: StateFlow<StampedPosition?> = MutableStateFlow(null), time: TimeSource = SystemTimeSource)`;
  `MeshStatsEngine.series: StateFlow<SignalSeries?>`; `MeshStatsEngine.watchSeries(key: SeriesKey?)`;
  `NodeDirectory.localPosition(): LatLon?`;
  `internal fun localPositionOf(localNodeNum: Int?, positions: Map<Int, PositionHistory>, nodes: Map<Int, NodeRecord>): LatLon?`.

**The declaration-order rule.** `MeshStatsEngine`'s `init` block hands `this` to a coroutine while
the constructor is still running. Its own comment says so: a field declared *below* that block is
read at its default value by a loop that has already started, silently, with no test able to see it.
**Every new field in this task goes above the `init` block**, beside `relays` and `neighbours`.

- [ ] **Step 1: Write the failing tests**

Add to `MeshStatsEngineTest`. First, two helpers beside the existing fixtures at the top of the
file:

```kotlin
/** A POSITION_APP packet from [from], carrying both coordinates and an altitude -
 *  PositionHistory.best returns only reports that have both. */
private fun positionFrame(from: Int, latI: Int, lonI: Int, at: Long = 1_000L) = TimestampedFrame(
    rxMillis = at,
    frame = FromRadio(
        packet = MeshPacket(
            from = from, relay_node = 0, rx_snr = -3f, rx_rssi = -80,
            decoded = Data(
                portnum = PortNum.POSITION_APP,
                payload = ByteString.of(
                    *Position(latitude_i = latI, longitude_i = lonI, altitude = 600).encode(),
                ),
            ),
        ),
    ),
)

/** The handshake frame that tells the engine which node is ours. */
private fun myInfoFrame(num: Int) = TimestampedFrame(
    rxMillis = 1_000L,
    frame = FromRadio(my_info = MyNodeInfo(my_node_num = num)),
)
```

with `import org.meshtastic.proto.Position` and `import org.meshtastic.proto.MyNodeInfo`. Then, as a
sibling of `collectSnapshots`:

```kotlin
/** Subscribes to [MeshStatsEngine.series] the same unconfined way
 *  [collectSnapshots] subscribes to the snapshot, and for the same reason. */
private fun TestScope.collectSeries(subject: MeshStatsEngine): List<SignalSeries?> {
    val seen = mutableListOf<SignalSeries?>()
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        subject.series.collect { seen += it }
    }
    return seen
}
```

And the tests:

```kotlin
@Test
fun `measurements are collected with nobody watching`() = runTest(StandardTestDispatcher()) {
    // Spec requirement 14: the series exists from the first packet, whether or not
    // a chart is open. A chart opened an hour into a survey must show that hour.
    val subject = engine(backgroundScope)
    subject.attach(flowOf(relayed(), relayed(), relayed()))
    runCurrent()

    val seen = collectSeries(subject)
    subject.watchSeries(SeriesKey.Relay(0x69))
    runCurrent()

    assertEquals(3, seen.last()?.size)
}

@Test
fun `a neighbour's measurements land under a neighbour key`() = runTest(StandardTestDispatcher()) {
    val subject = engine(backgroundScope)
    val seen = collectSeries(subject)
    subject.watchSeries(SeriesKey.Neighbour(SENDER))
    subject.attach(flowOf(direct(), direct()))
    runCurrent()

    assertEquals(2, seen.last()?.size)
    assertEquals(-80f, seen.last()?.rssi(0)!!, 0.0001f)
    assertEquals(-3f, seen.last()?.snr(1)!!, 0.0001f)
}

@Test
fun `nothing is published while nothing is subscribed`() = runTest(StandardTestDispatcher()) {
    // The same rule the snapshot follows. A chart's 125 KB must not be copied for
    // a screen that is not on.
    val subject = engine(backgroundScope)
    subject.watchSeries(SeriesKey.Relay(0x69))
    subject.attach(flowOf(relayed()))
    runCurrent()

    assertNull(subject.series.value)
}

@Test
fun `phone mode stamps the phone's fix`() = runTest(StandardTestDispatcher()) {
    val fix = MutableStateFlow<StampedPosition?>(
        StampedPosition.fromDegrees(40.3057734, -3.7325611, PositionOrigin.PHONE),
    )
    val subject = MeshStatsEngine(
        backgroundScope, MutableStateFlow(emptySet()), SortMode.PACKETS,
        positionMode = MutableStateFlow(PositionMode.PHONE), phoneFix = fix,
    ) { 1_000L }
    val seen = collectSeries(subject)
    subject.watchSeries(SeriesKey.Relay(0x69))
    runCurrent()

    subject.attach(flowOf(relayed()))
    runCurrent()

    assertEquals(PositionOrigin.PHONE, seen.last()?.positionOf(0)?.origin)
    assertEquals(403057734, seen.last()?.positionOf(0)?.latI)
}

@Test
fun `phone mode falls back to the node when no fix has arrived`() = runTest(StandardTestDispatcher()) {
    // Spec section 6.3: a blank globe for the first minute of every session is
    // worse than a slightly-less-precise pin, and the origin flag keeps it honest.
    val subject = MeshStatsEngine(
        backgroundScope, MutableStateFlow(emptySet()), SortMode.PACKETS,
        positionMode = MutableStateFlow(PositionMode.PHONE),
        phoneFix = MutableStateFlow(null),
    ) { 1_000L }
    val seen = collectSeries(subject)
    subject.watchSeries(SeriesKey.Relay(0x69))
    subject.attach(flowOf(myInfoFrame(SENDER), positionFrame(SENDER, 398628316, -40273231), relayed()))
    runCurrent()

    assertEquals(PositionOrigin.NODE, seen.last()?.positionOf(0)?.origin)
    assertEquals(398628316, seen.last()?.positionOf(0)?.latI)
}

@Test
fun `node mode never falls back to the phone`() = runTest(StandardTestDispatcher()) {
    // Turning the setting off is a request that the phone's GPS not be used.
    // Quietly using it anyway would break that request; a sample with no position
    // is the honest answer.
    val subject = MeshStatsEngine(
        backgroundScope, MutableStateFlow(emptySet()), SortMode.PACKETS,
        positionMode = MutableStateFlow(PositionMode.NODE),
        phoneFix = MutableStateFlow(
            StampedPosition.fromDegrees(40.3057734, -3.7325611, PositionOrigin.PHONE),
        ),
    ) { 1_000L }
    val seen = collectSeries(subject)
    subject.watchSeries(SeriesKey.Relay(0x69))
    subject.attach(flowOf(relayed()))
    runCurrent()

    assertNull(seen.last()?.positionOf(0))
}

@Test
fun `a packet with no decodable signal adds no measurement`() = runTest(StandardTestDispatcher()) {
    // The chart's crosshair reports both values with no "not available" case,
    // which is only true because a measurement is never opened without both.
    val subject = engine(backgroundScope)
    val seen = collectSeries(subject)
    subject.watchSeries(SeriesKey.Relay(0x69))
    subject.attach(
        flowOf(
            TimestampedFrame(
                rxMillis = 1_000L,
                frame = FromRadio(
                    packet = MeshPacket(from = SENDER, relay_node = 0x69, hop_start = 3, hop_limit = 1),
                ),
            ),
        ),
    )
    runCurrent()

    assertEquals(0, seen.last()?.size ?: 0)
}

@Test
fun `reset clears the series with the statistics`() = runTest(StandardTestDispatcher()) {
    // A chart outliving a reset would be showing a session that no longer exists.
    val subject = engine(backgroundScope)
    val seen = collectSeries(subject)
    subject.watchSeries(SeriesKey.Relay(0x69))
    subject.attach(flowOf(relayed(), relayed()))
    runCurrent()
    assertEquals(2, seen.last()?.size)

    subject.reset()
    runCurrent()

    assertEquals(0, seen.last()?.size ?: 0)
}

@Test
fun `watching nothing drops the published series at once`() = runTest(StandardTestDispatcher()) {
    val subject = engine(backgroundScope)
    val seen = collectSeries(subject)
    subject.watchSeries(SeriesKey.Relay(0x69))
    subject.attach(flowOf(relayed()))
    runCurrent()
    assertEquals(1, seen.last()?.size)

    subject.watchSeries(null)
    runCurrent()

    assertNull(seen.last())
}

@Test
fun `switching the watched subject replaces the published series`() = runTest(StandardTestDispatcher()) {
    val subject = engine(backgroundScope)
    val seen = collectSeries(subject)
    subject.watchSeries(SeriesKey.Relay(0x69))
    subject.attach(flowOf(relayed(), relayed(), direct()))
    runCurrent()
    assertEquals(2, seen.last()?.size)

    subject.watchSeries(SeriesKey.Neighbour(SENDER))
    runCurrent()

    // One, not three: the neighbour's own series, not the relay's left behind.
    assertEquals(1, seen.last()?.size)
}
```

Add the imports these need: `SeriesKey`, `PositionMode`, `SignalSeries`, `StampedPosition`,
`PositionOrigin`.

And in `NodeDirectoryTest`:

```kotlin
@Test
fun `the directory and its snapshot agree on where we are`() {
    // Two callers, one precedence rule. The engine asks the directory per packet
    // (a snapshot copies every map); the screens ask the snapshot. They must not
    // drift, and a live report must beat the database entry in both.
    val directory = NodeDirectory(TimeSource { 5_000L })
    directory.setLocalNodeNum(SENDER)
    directory.applyNodeInfo(
        NodeInfo(
            num = SENDER,
            position = Position(latitude_i = 398628316, longitude_i = -40273231, altitude = 600),
        ),
    )
    assertEquals(directory.localPosition(), directory.snapshot(emptySet()).localPosition())

    directory.applyPosition(SENDER, Position(latitude_i = 403057734, longitude_i = -37325611, altitude = 610))
    assertEquals(directory.localPosition(), directory.snapshot(emptySet()).localPosition())
    assertEquals(40.3057734, directory.localPosition()!!.lat, 1e-7)
}

@Test
fun `with no local node number there is no local position`() {
    assertNull(NodeDirectory(TimeSource { 5_000L }).localPosition())
}
```

Use whatever `SENDER`-equivalent constant `NodeDirectoryTest` already defines; if it has none, add
`private const val SENDER = 0x9e75f1a4.toInt()` beside its other fixtures, matching the rest of the
project.

- [ ] **Step 2: Run them and watch them fail**

`gradle :app:testDebugUnitTest --tests '*MeshStatsEngineTest*' --tests '*NodeDirectoryTest*'` —
expect "unresolved reference: watchSeries".

- [ ] **Step 3: One precedence rule, one place**

`stats/model/LocalPosition.kt`:

```kotlin
package com.cerocoder.meshrelay.stats.model

/**
 * Where this device is, as far as the mesh has told it.
 *
 * Rules 1-2 of [NodeDirectorySnapshot.locationInfo]'s precedence, and the only
 * copy of them: a position heard during the session wins, because the database
 * entry can be days old; otherwise the node database entry. Requirement 19 of the
 * Graph design is exactly this and needs nothing new - no timers, no generated
 * mesh traffic.
 *
 * Two callers, deliberately. `NodeDirectory` asks it per packet, where taking a
 * snapshot to answer would copy every map in the directory. `NodeDirectorySnapshot`
 * asks it for the screens. `NodeDirectoryTest` pins that they agree.
 */
internal fun localPositionOf(
    localNodeNum: Int?,
    positions: Map<Int, PositionHistory>,
    nodes: Map<Int, NodeRecord>,
): LatLon? {
    val num = localNodeNum ?: return null
    val report = positions[num]?.best ?: nodes[num]?.dbPosition ?: return null
    val lat = report.latitude ?: return null
    val lon = report.longitude ?: return null
    return LatLon(lat, lon)
}
```

In `NodeDirectorySnapshot`, replace the body of `localPosition()`:

```kotlin
/** Where this device is, as far as the mesh has told it. */
fun localPosition(): LatLon? = localPositionOf(localNodeNum, positionsByNode, nodes)
```

This is behaviour-preserving: the old body went through `locationInfo(num, from = null)`, which
resolves `positions[num]?.best` first, falls back to `nodes[num]?.dbPosition`, and returns an empty
`LocationInfo` (hence `null` here) when either coordinate is missing — the same three outcomes.

In `NodeDirectory`, add beside `snapshot`:

```kotlin
/**
 * Where this device is, without building a snapshot to ask.
 *
 * The engine calls this once per measurement, and `snapshot()` copies every map
 * in the directory - which is why it is taken once per batch and not per packet.
 * The precedence rule itself is [localPositionOf]'s, shared with the snapshot.
 */
fun localPosition(): LatLon? = localPositionOf(localNodeNum, positions, nodes)
```

with `import com.cerocoder.meshrelay.stats.model.localPositionOf` and `...model.LatLon`.

- [ ] **Step 4: The engine's new state, commands and folding**

In `MeshStatsEngine`, the constructor gains two parameters. Both default, so the twenty existing
tests and `AppContainer` compile unchanged until Task 7 wires them:

```kotlin
class MeshStatsEngine(
    private val scope: CoroutineScope,
    skippedRelayNodes: StateFlow<Set<Int>>,
    initialSortMode: SortMode,
    positionMode: StateFlow<PositionMode> = MutableStateFlow(PositionMode.PHONE),
    phoneFix: StateFlow<StampedPosition?> = MutableStateFlow(null),
    time: TimeSource = SystemTimeSource,
) {
```

Three commands, in the existing `sealed interface Command`:

```kotlin
data class SetPositionMode(val mode: PositionMode) : Command
data class SetPhoneFix(val fix: StampedPosition?) : Command
data class WatchSeries(val key: SeriesKey?) : Command
```

New state, **above the `init` block**, beside `relays` and `neighbours`:

```kotlin
// One buffer per subject, from the first packet, whether or not a chart is open
// (requirement 14). LinkedHashMap for the same reason relays and neighbours are:
// a stable order between rebuilds.
private val seriesBuffers = LinkedHashMap<SeriesKey, SignalSeriesBuffer>()

// The two inputs the interface owns, arriving as commands so they land on this
// coroutine rather than racing it - exactly as skippedRelayNodes already does.
private var positionModeState = PositionMode.PHONE
private var phoneFixState: StampedPosition? = null

// Which subject's chart is open, or null. At most one at a time: this is a
// full-screen destination.
private var watchedSeries: SeriesKey? = null

// What was last put on the wire, so an unchanged buffer is not re-copied. At mesh
// traffic rates the alternative copies 125 KB and recomposes the chart several
// times a second because some *other* relay heard a packet.
private var publishedKey: SeriesKey? = null
private var publishedTotal = -1L

private val _series = MutableStateFlow<SignalSeries?>(null)

/**
 * The watched subject's measurements, or null when nothing is watched.
 *
 * A channel of its own rather than a field on [StatsSnapshot]: widening the
 * snapshot would copy every subject's series several times a second for the list
 * screens, which need none of it. This copies one subject's 125 KB, and only
 * while a chart is open.
 */
val series: StateFlow<SignalSeries?> = _series.asStateFlow()
```

Inside `init`'s `scope.launch { ... }`, beside the existing collectors:

```kotlin
launch { positionMode.collect { commands.send(Command.SetPositionMode(it)) } }
launch { phoneFix.collect { commands.send(Command.SetPhoneFix(it)) } }

// The same "a screen that has just opened must not see nothing" rule the
// snapshot follows. Without it, opening a chart on a quiet relay races: the
// WatchSeries command can be applied before the collector has subscribed, the
// publish is skipped, and with no further packets the chart stays blank forever.
launch {
    _series.subscriptionCount
        .map { it > 0 }
        .distinctUntilChanged()
        .collect { watched -> if (watched) commands.send(Command.Refresh) }
}
```

A public entry point, beside `setPaused`/`reset`:

```kotlin
/** Open a chart on [key], or pass null when it closes. */
fun watchSeries(key: SeriesKey?) { commands.trySend(Command.WatchSeries(key)) }
```

In `apply`:

```kotlin
is Command.SetPositionMode -> positionModeState = command.mode
is Command.SetPhoneFix -> phoneFixState = command.fix
is Command.WatchSeries -> {
    watchedSeries = command.key
    // Dropped here rather than at the next build: a closed chart's series must
    // not be held for as long as the process lives, and a *different* subject's
    // series must never be what the next chart draws for one frame.
    if (command.key == null) _series.value = null
    publishedKey = null
    publishedTotal = -1L
}
```

In the loop's build step, after the snapshot block:

```kotlin
publishWatchedSeries()
```

and the function itself:

```kotlin
/**
 * Publishes the watched subject's measurements, if anyone is looking and if
 * anything changed.
 *
 * Two gates, not one. `subscriptionCount` is the same rule the snapshot follows:
 * nothing subscribed means nothing built. The change check is what stops a
 * packet heard on some other relay from copying this one's arrays and
 * recomposing its chart; `totalAppended` moves on every append and only on an
 * append, so it is exact at every fill level - unlike `size`, which stops moving
 * once the ring saturates.
 */
private fun publishWatchedSeries() {
    val key = watchedSeries ?: return
    if (_series.subscriptionCount.value == 0) return
    val buffer = seriesBuffers[key]
    val total = buffer?.totalAppended ?: 0L
    if (key == publishedKey && total == publishedTotal) return
    publishedKey = key
    publishedTotal = total
    _series.value = buffer?.snapshot() ?: SignalSeries.EMPTY
}
```

`SignalSeries.EMPTY` rather than `null` for a watched subject with no measurements yet: `null` means
"nothing is watched", and the screen's empty state is a different thing from its closed state.

In `foldRelayed`, after the `relays[...] = ...` assignment:

```kotlin
// Guarded by the same signal != null test that already guards the statistics: a
// measurement exists only when the packet carried decodable signal information,
// which is what lets the crosshair report both values with no "not available"
// case.
if (signal != null) {
    seriesBuffers.getOrPut(SeriesKey.Relay(relayed.relayByte)) { SignalSeriesBuffer() }
        .append(atMillis, signal.rssi, signal.snr, positionForSample())
}
```

In `foldDirect`, the same with `SeriesKey.Neighbour(direct.fromNode)`.

The position rule:

```kotlin
/**
 * Where the observer was, for the measurement being folded right now
 * (requirement 16: captured at fold time, never searched for afterwards).
 *
 * | Mode  | First choice | Fallback           | Neither |
 * | PHONE | the phone fix| the node's position| null    |
 * | NODE  | the node's position | none         | null    |
 *
 * PHONE falls back because a blank globe for the first minute of every session -
 * before the first fix lands, or after the permission was refused - is worse than
 * a slightly-less-precise pin, and the origin flag recorded with the sample keeps
 * it honest. NODE does not fall back, because turning the setting off is a
 * request that the phone's GPS not be used, and quietly using it anyway would
 * break that request.
 */
private fun positionForSample(): StampedPosition? = when (positionModeState) {
    PositionMode.PHONE -> phoneFixState ?: nodePosition()
    PositionMode.NODE -> nodePosition()
}

private fun nodePosition(): StampedPosition? {
    val local = directory.localPosition() ?: return null
    return StampedPosition.fromDegrees(local.lat, local.lon, PositionOrigin.NODE)
}
```

And in `resetStatistics`, beside `relays.clear()`:

```kotlin
seriesBuffers.clear()
```

- [ ] **Step 5: Run the tests, green**

`gradle :app:testDebugUnitTest --tests '*MeshStatsEngineTest*' --tests '*NodeDirectoryTest*'`

- [ ] **Step 6: Commit**

```bash
git commit -am "feat(stats): the engine collects, stamps and publishes signal series"
```

---

### Task 5: `location/`, the manifest, and the permission request

Spec §7. This is the task that changes the application's store-visible permission set; the reviewer
is asked to check the manifest diff line by line.

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/location/PhoneLocationSource.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/location/LocationAvailability.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/location/AndroidPhoneLocationSource.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/MainActivity.kt` (the `requested` array)
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/location/LocationAvailabilityTest.kt`

**Interfaces:**
- Consumes: `StampedPosition`, `PositionOrigin` (Task 1).
- Produces: `PhoneLocationSource` with `val fix: StateFlow<StampedPosition?>`, `fun start()`,
  `fun stop()`; `AndroidPhoneLocationSource(context: Context) : PhoneLocationSource`;
  `LocationAvailability(context: Context)` with `fun granted(): Boolean` and
  `LocationAvailability.REQUIRED_PERMISSIONS: Array<String>`.

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/cerocoder/meshrelay/location/LocationAvailabilityTest.kt`:

```kotlin
package com.cerocoder.meshrelay.location

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The permission array is a plain constant with no Context and no
 * `Build.VERSION` branch, so it is testable on the JVM - which is the whole
 * reason it is a constant rather than an instance field the way
 * `BluetoothAvailability`'s is. `BluetoothAvailability` has no test today; this
 * adds one for the new type only.
 */
class LocationAvailabilityTest {

    @Test
    fun `both location permissions are requested, on every API level`() {
        // Fine alone is not enough to ask for: from Android 12 the user can
        // downgrade a fine request to approximate, and a request that names only
        // ACCESS_FINE_LOCATION gives the system no coarse permission to grant
        // instead - the dialog's "Approximate" button then grants nothing at all.
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            LocationAvailability.REQUIRED_PERMISSIONS.toList(),
        )
    }

    @Test
    fun `the array is not the Bluetooth one`() {
        // Guards against the copy-paste this file began life as: BLUETOOTH_SCAN
        // carries neverForLocation in the manifest, and requesting it here would
        // be asking for the wrong thing entirely.
        assertEquals(2, LocationAvailability.REQUIRED_PERMISSIONS.size)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

`gradle :app:testDebugUnitTest --tests '*LocationAvailabilityTest*'` — expect "unresolved
reference: LocationAvailability".

- [ ] **Step 3: The interface and the availability check**

`location/PhoneLocationSource.kt`:

```kotlin
package com.cerocoder.meshrelay.location

import com.cerocoder.meshrelay.stats.model.StampedPosition
import kotlinx.coroutines.flow.StateFlow

/**
 * The phone's own idea of where it is.
 *
 * **The latest fix only, and no history.** A measurement carries the position it
 * was taken at (`SignalSeriesBuffer`), so nothing ever needs to search backwards
 * through this - which is the whole reason storing the position per sample was
 * chosen over searching for one afterwards.
 *
 * Plain JVM, no `android.*`, so `AppContainer`'s wiring and the engine's
 * behaviour under it are testable without a device.
 */
interface PhoneLocationSource {

    /** null until the first fix arrives, and after a refused permission, forever. */
    val fix: StateFlow<StampedPosition?>

    /** Idempotent. A no-op when the permission has not been granted. */
    fun start()

    /** Idempotent. Stops the updates rather than merely ignoring them. */
    fun stop()
}
```

`location/LocationAvailability.kt`:

```kotlin
package com.cerocoder.meshrelay.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Whether this app may ask the platform where the phone is.
 *
 * The shape `BluetoothAvailability` established, with one difference: the
 * permission array does not vary by API level, so it is a constant rather than an
 * instance field - which also makes it testable without a Context.
 */
class LocationAvailability(private val context: Context) {

    /**
     * `any`, not `all`.
     *
     * From Android 12 the permission dialog offers "Precise" and "Approximate",
     * and the user choosing Approximate grants COARSE while denying FINE. That is
     * a working grant: `LocationManager`'s NETWORK_PROVIDER still delivers fixes,
     * and a coarse pin on a hillside is worth more than no pin. Requiring both
     * would treat the user's own choice as a refusal.
     */
    fun granted(): Boolean = REQUIRED_PERMISSIONS.any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        /**
         * Both, and in this order. Naming only FINE would leave the system with no
         * coarse permission to grant when the user picks Approximate.
         */
        val REQUIRED_PERMISSIONS: Array<String> = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}
```

- [ ] **Step 4: The platform source**

`location/AndroidPhoneLocationSource.kt`:

```kotlin
package com.cerocoder.meshrelay.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.cerocoder.meshrelay.stats.model.PositionOrigin
import com.cerocoder.meshrelay.stats.model.StampedPosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The platform `LocationManager`, and deliberately not `play-services-location`:
 * that is a new dependency and a proprietary blob, and this application is
 * otherwise F-Droid-clean.
 *
 * Both providers are requested. GPS is the one that answers the question this
 * tool asks - where exactly was I standing - and NETWORK is what answers at all
 * indoors, in a car park, or in the first seconds after the screen comes on.
 * Whichever delivers last wins; the fix carries no accuracy figure because
 * nothing downstream branches on one.
 */
class AndroidPhoneLocationSource(context: Context) : PhoneLocationSource {

    private val manager = context.getSystemService(LocationManager::class.java)
    private val availability = LocationAvailability(context)

    private val _fix = MutableStateFlow<StampedPosition?>(null)
    override val fix: StateFlow<StampedPosition?> = _fix.asStateFlow()

    private var listening = false

    /**
     * An explicit object, not a SAM conversion.
     *
     * `LocationListener` gained default implementations of its other three
     * methods in API 30. This app's `minSdk` is 26, where they are still
     * abstract - and a lambda compiled against `compileSdk` 37 would produce a
     * class with no implementation of them and throw `AbstractMethodError` on an
     * old phone the moment the provider changed state. The three overrides are
     * deprecated and empty on purpose.
     */
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            _fix.value = StampedPosition.fromDegrees(
                location.latitude,
                location.longitude,
                PositionOrigin.PHONE,
            )
        }

        @Deprecated("Required by LocationListener below API 30")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit
    }

    // The permission is checked immediately above every call, by availability
    // .granted(); lint cannot see through the helper.
    @SuppressLint("MissingPermission")
    override fun start() {
        if (listening) return
        val manager = manager ?: return
        if (!availability.granted()) return
        var requested = false
        for (provider in PROVIDERS) {
            // A phone with no GPS chip, or one whose provider the vendor has
            // removed, throws IllegalArgumentException from requestLocationUpdates
            // rather than returning. One missing provider must not cost the other.
            if (!manager.allProviders.contains(provider)) continue
            runCatching {
                manager.requestLocationUpdates(
                    provider,
                    MIN_TIME_MILLIS,
                    MIN_DISTANCE_METERS,
                    listener,
                    // The five-argument overload, so this can be called from the
                    // application scope's Dispatchers.Default rather than only from
                    // a thread that already has a Looper.
                    Looper.getMainLooper(),
                )
                requested = true
            }.onFailure { Log.w(TAG, "no location updates from $provider", it) }
        }
        listening = requested
    }

    override fun stop() {
        if (!listening) return
        manager?.removeUpdates(listener)
        listening = false
        // The last fix is deliberately kept. Turning the setting off does not
        // un-know where the phone was, and the engine's PositionMode.NODE is what
        // stops it being used - one rule, in one place, tested.
    }

    private companion object {
        const val TAG = "PhoneLocation"

        val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

        /**
         * Ten seconds between deliveries.
         *
         * `minTime` is the only battery lever of the two: it is what lets the
         * platform power the GNSS down between fixes, at its own discretion. Ten
         * seconds keeps it warm, which is the price of the resolution this tool
         * needs - the question being asked in the field is *where exactly was I
         * standing when this relay read -92 dBm*, asked while walking a ridge to
         * decide where a repeater goes.
         */
        const val MIN_TIME_MILLIS = 10_000L

        /**
         * Ten metres of movement between deliveries.
         *
         * Not a battery lever at all: it filters *after* the GNSS has already
         * computed a fix, so it suppresses a callback, not the radio. It is a
         * de-duplication rule. At walking pace, roughly 1.4 m/s, ten metres is
         * about seven seconds, so measurements taken a few paces apart get
         * distinct pins. A coarser 100 m would stamp a whole hillside with one
         * coordinate and drop one pin for all of them, answering a different and
         * less useful question.
         *
         * Both gates apply to every delivery, so a phone on a table produces no
         * updates at all - which is right; it has not moved.
         */
        const val MIN_DISTANCE_METERS = 10f
    }
}
```

- [ ] **Step 5: The manifest**

Replace the pre-Android-12 location line with two unrestricted ones:

```xml
    <!-- Before Android 12, scanning required a location permission -->
    <uses-permission android:name="android.permission.BLUETOOTH"
        android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN"
        android:maxSdkVersion="30" />

    <!-- Unrestricted, and superseding the maxSdkVersion="30" entry that used to
         stand here for pre-Android-12 BLE scanning: the Graph command stamps every
         measurement with where the observer was standing, on every API level. Both
         are declared so that a user choosing "Approximate" in the Android 12+
         permission dialog grants something rather than nothing. BLUETOOTH_SCAN
         above keeps neverForLocation - that flag asserts scans are not used to
         derive location, which is still true: this comes from the GNSS. -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

Nothing else in the manifest changes. In particular the service keeps
`android:foregroundServiceType="connectedDevice"` — see decision 8; Task 13 records the consequence.

- [ ] **Step 6: Ask for it at first connect**

In `MainActivity.kt`, the `requested` array (currently at `:186-192`):

```kotlin
    // Bluetooth, location and - from Android 13 - notifications, asked for in one
    // dialog sequence at first connect. Location is not part of BleReadiness: a
    // refusal is not an error, the setting stays on, no fix ever arrives, and every
    // measurement falls back to the node's position. Distinct, because below
    // Android 12 BluetoothAvailability already names ACCESS_FINE_LOCATION for
    // scanning and RequestMultiplePermissions should not be handed it twice.
    val requested = remember {
        val base = container.availability.requiredPermissions + LocationAvailability.REQUIRED_PERMISSIONS
        val all = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            base + Manifest.permission.POST_NOTIFICATIONS
        } else {
            base
        }
        all.distinct().toTypedArray()
    }
```

with `import com.cerocoder.meshrelay.location.LocationAvailability`.

Then, in the `permissionLauncher` callback, tell the container the answer may have changed — the
method arrives in Task 7, so for now leave the callback as it is and add this line there:

```kotlin
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { readiness = container.availability.check() }
```

- [ ] **Step 7: Run the test, green, and read the manifest diff**

`gradle :app:testDebugUnitTest --tests '*LocationAvailabilityTest*'`, then `git diff app/src/main/AndroidManifest.xml`
and confirm exactly two lines were added, one changed, and `neverForLocation` is untouched.

- [ ] **Step 8: Commit** — `feat(location): the phone's own position, and the permission for it`

---

### Task 6: The *Use phone location* setting

Spec §7.2. On by default.

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/settings/AppSettings.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/settings/SettingsRepository.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-es/strings.xml`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/settings/SettingsRepositoryTest.kt`

**Interfaces:**
- Produces: `AppSettings.usePhoneLocation: Boolean` (default `true`).

- [ ] **Step 1: Write the failing tests**

In `SettingsRepositoryTest`, add to `defaults match the terminal tool and the local mesh`:

```kotlin
        // On by default: the phone is what the surveyor is carrying, and the node's
        // position is the coarser answer. Off is the escape hatch, not the norm.
        assertEquals(true, settings.usePhoneLocation)
```

and a new test:

```kotlin
@Test
fun `use phone location persists both ways`() {
    // Both directions, because a default of true means "off" is the only value a
    // write can actually be seen to carry: a store that dropped the write would
    // still read back true and pass a one-directional test.
    val store = FakeStore()
    repo(store).update { it.copy(usePhoneLocation = false) }
    assertEquals(false, repo(store).settings.value.usePhoneLocation)

    repo(store).update { it.copy(usePhoneLocation = true) }
    assertEquals(true, repo(store).settings.value.usePhoneLocation)
}
```

- [ ] **Step 2: Run them and watch them fail**

`gradle :app:testDebugUnitTest --tests '*SettingsRepositoryTest*'` — expect "unresolved reference:
usePhoneLocation".

- [ ] **Step 3: The setting**

`AppSettings.kt`:

```kotlin
    val backgroundCollection: Boolean = true,
    /**
     * Stamp each measurement with the phone's own fix rather than with the local
     * node's position. On by default: the phone is what the surveyor is carrying,
     * and the node's position is the coarser answer. Off stops the location
     * updates rather than merely ignoring them.
     */
    val usePhoneLocation: Boolean = true,
```

`SettingsRepository.kt` — one key beside the others:

```kotlin
private const val KEY_USE_PHONE_LOCATION = "use_phone_location"
```

in `persist()`'s `bools` map:

```kotlin
                KEY_USE_PHONE_LOCATION to settingsSnapshot.usePhoneLocation,
```

and in `readSettings()`:

```kotlin
            usePhoneLocation = store.getBoolean(KEY_USE_PHONE_LOCATION, defaults.usePhoneLocation),
```

- [ ] **Step 4: Strings, both locales**

`values/strings.xml`, beside `settings_background_collection_summary`:

```xml
    <string name="settings_use_phone_location">Use phone location</string>
    <string name="settings_use_phone_location_summary">Stamp each measurement with the phone\'s own position. Turned off, the local node\'s position is used instead and the phone\'s GPS is not switched on.</string>
```

`values-es/strings.xml`, at the same position in the file:

```xml
    <string name="settings_use_phone_location">Usar la ubicación del teléfono</string>
    <string name="settings_use_phone_location_summary">Marca cada medición con la posición del propio teléfono. Si se desactiva, se usa la posición del nodo local y no se enciende el GPS del teléfono.</string>
```

- [ ] **Step 5: The row**

In `SettingsScreen`'s `LazyColumn`, straight after the `background-collection-summary` item, using
the `SwitchRow` and the summary shape already there:

```kotlin
            item(key = "use-phone-location") {
                SwitchRow(
                    label = stringResource(R.string.settings_use_phone_location),
                    checked = settings.usePhoneLocation,
                    onCheckedChange = { checked ->
                        onUpdate { current -> current.copy(usePhoneLocation = checked) }
                    },
                )
            }
            item(key = "use-phone-location-summary") {
                Text(
                    text = stringResource(R.string.settings_use_phone_location_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
```

- [ ] **Step 6: Run the tests, green**

`gradle :app:testDebugUnitTest --tests '*SettingsRepositoryTest*' --tests '*StringsParityTest*'`

- [ ] **Step 7: Commit** — `feat(settings): use the phone's location for measurements`

---

### Task 7: `AppContainer` wiring

The two new engine inputs, and the source's lifecycle.

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/AppContainer.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/MainActivity.kt` (the permission callback)

**Interfaces:**
- Consumes: `PhoneLocationSource`, `AndroidPhoneLocationSource`, `LocationAvailability` (Task 5);
  `PositionMode` (Task 1); `AppSettings.usePhoneLocation` (Task 6);
  `MeshStatsEngine(..., positionMode, phoneFix)` (Task 4).
- Produces: `AppContainer.locationAvailability: LocationAvailability`;
  `AppContainer.refreshLocationUpdates()`.

- [ ] **Step 1: Declare the source above the engine**

Order matters here for an ordinary Kotlin reason rather than the engine's exotic one: `engine` is a
`val` initialised in declaration order and now reads two flows that must already exist.

```kotlin
    /** Permission state for the phone's own position, for the activity's request. */
    val locationAvailability = LocationAvailability(context)

    private val phoneLocation: PhoneLocationSource = AndroidPhoneLocationSource(context)

    /**
     * The setting as the engine's own vocabulary. Derived rather than pushed, so
     * there is one source of truth (the repository) and no chance of the two
     * disagreeing after a settings write that failed to notify.
     *
     * `Eagerly`, not `WhileSubscribed`: the engine subscribes for the life of the
     * process and there is nothing to stop.
     */
    private val positionMode: StateFlow<PositionMode> = settings.settings
        .map { if (it.usePhoneLocation) PositionMode.PHONE else PositionMode.NODE }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            if (settings.settings.value.usePhoneLocation) PositionMode.PHONE else PositionMode.NODE,
        )

    val engine = MeshStatsEngine(
        scope = scope,
        skippedRelayNodes = settings.skippedRelayNodes,
        initialSortMode = settings.settings.value.defaultSortMode,
        positionMode = positionMode,
        phoneFix = phoneLocation.fix,
    )
```

with imports `com.cerocoder.meshrelay.location.*`, `com.cerocoder.meshrelay.stats.PositionMode`,
`kotlinx.coroutines.flow.map`, `kotlinx.coroutines.flow.stateIn`,
`kotlinx.coroutines.flow.SharingStarted`, `kotlinx.coroutines.flow.distinctUntilChanged`.

- [ ] **Step 2: Start and stop it with the setting**

In `AppContainer.init`, beside the other two collectors:

```kotlin
        // The switch is the escape hatch, so it stops the updates rather than
        // merely ignoring them: with it off, this app asks the platform for
        // nothing and costs no battery beyond the BLE link.
        scope.launch {
            settings.settings
                .map { it.usePhoneLocation }
                .distinctUntilChanged()
                .collect { on -> if (on) phoneLocation.start() else phoneLocation.stop() }
        }
```

and a method for the activity to call when a permission answer arrives:

```kotlin
    /**
     * Re-apply the location decision after a permission dialog.
     *
     * `start()` is a no-op while the permission is missing, and the first launch
     * asks for it *after* the container has already been built - so without this
     * the source would sit idle until the next process start even though the user
     * had just granted it.
     */
    fun refreshLocationUpdates() {
        if (settings.settings.value.usePhoneLocation) phoneLocation.start() else phoneLocation.stop()
    }
```

- [ ] **Step 3: Call it from the permission callback**

In `MainActivity.kt`:

```kotlin
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        readiness = container.availability.check()
        // A location grant does not change BleReadiness, so nothing else here
        // would notice it.
        container.refreshLocationUpdates()
    }
```

- [ ] **Step 4: Green CI run** — compilation is the gate; there is no new behaviour to unit-test
  that Task 4's engine tests do not already cover.

- [ ] **Step 5: Commit** — `feat: wire the phone's position into the engine`

---

### Task 8: `ChartGeometry`

Every number the canvas needs, pure, with no Compose types in any signature — the shape
`GaugeGeometry` already established. This is where all of the chart's arithmetic lives, because
`@Composable` functions in this project may not contain any.

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/graph/ChartGeometry.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/ui/graph/ChartGeometryTest.kt`

**Interfaces:**
- Consumes: `SignalScales`, `SignalStats`.
- Produces: `RowWindow(firstRow: Int, lastRow: Int)` with `val isEmpty: Boolean`;
  `ScaleRange(min: Float, max: Float)`;
  `ChartGeometry.visibleRows(scrollPx, viewportPx, size, pxPerSample): RowWindow`,
  `.yOf(row, scrollPx, pxPerSample): Float`, `.rowAt(y, scrollPx, pxPerSample): Int`,
  `.xOf(value, min, max): Float`, `.scaleRange(stats, autoScale, fixedMin, fixedMax): ScaleRange`,
  `.maxScrollPx(size, viewportPx, pxPerSample): Float`,
  `.anchorAfterAppend(scrollPx, appended, pxPerSample): Float`,
  `.indexOfRow(row, size): Int`, `const val OVERSCAN_ROWS`.

- [ ] **Step 1: Write the failing tests**

`app/src/test/kotlin/com/cerocoder/meshrelay/ui/graph/ChartGeometryTest.kt`:

```kotlin
package com.cerocoder.meshrelay.ui.graph

import com.cerocoder.meshrelay.stats.SignalScales
import com.cerocoder.meshrelay.stats.model.SignalStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Row 0 is the newest measurement and sits at the top when the chart is scrolled
 * to the start; the series itself is stored oldest first. Half of what is tested
 * here is that one inversion, done in one place.
 *
 * `pxPerSample` is 1 in every test but the two that name it, because 1 is what
 * the single caller passes (spec requirement 13: the parameter exists so a zoom
 * control can be added later without restructuring, and nothing sets it yet).
 */
class ChartGeometryTest {

    @Test
    fun `the visible window is the scrolled rows plus one of overscan each side`() {
        // The overscan is not cosmetic: a polyline segment joins two rows, so
        // without a row beyond each edge the top and bottom segments would be
        // missing and the line would appear to stop short of the viewport.
        val window = ChartGeometry.visibleRows(scrollPx = 100f, viewportPx = 50f, size = 1000, pxPerSample = 1f)
        assertEquals(99, window.firstRow)
        assertEquals(151, window.lastRow)
    }

    @Test
    fun `the window is clamped at both ends of the series`() {
        val top = ChartGeometry.visibleRows(scrollPx = 0f, viewportPx = 50f, size = 1000, pxPerSample = 1f)
        assertEquals(0, top.firstRow)

        val bottom = ChartGeometry.visibleRows(scrollPx = 980f, viewportPx = 50f, size = 1000, pxPerSample = 1f)
        assertEquals(999, bottom.lastRow)
    }

    @Test
    fun `a viewport taller than the series shows all of it and no more`() {
        val window = ChartGeometry.visibleRows(scrollPx = 0f, viewportPx = 800f, size = 3, pxPerSample = 1f)
        assertEquals(0, window.firstRow)
        assertEquals(2, window.lastRow)
    }

    @Test
    fun `an empty series has an empty window`() {
        // The canvas must draw nothing rather than index row 0 of nothing.
        assertTrue(ChartGeometry.visibleRows(0f, 800f, size = 0, pxPerSample = 1f).isEmpty)
    }

    @Test
    fun `y and row are inverses of each other`() {
        // This is what turns a touch into a measurement. If they drift, the
        // crosshair reports one row's numbers at another row's height.
        for (row in listOf(0, 1, 37, 999)) {
            val y = ChartGeometry.yOf(row, scrollPx = 120f, pxPerSample = 1f)
            assertEquals(row, ChartGeometry.rowAt(y, scrollPx = 120f, pxPerSample = 1f))
        }
    }

    @Test
    fun `the newest measurement is at the top when scrolled to the start`() {
        assertEquals(0f, ChartGeometry.yOf(0, scrollPx = 0f, pxPerSample = 1f), 0.0001f)
    }

    @Test
    fun `a row is the series index counted from the end`() {
        // Storage is oldest-first; the chart is newest-at-the-top. One function
        // owns the inversion.
        assertEquals(9, ChartGeometry.indexOfRow(row = 0, size = 10))
        assertEquals(0, ChartGeometry.indexOfRow(row = 9, size = 10))
    }

    @Test
    fun `x is a fraction of the track, the same fraction the bars use`() {
        assertEquals(
            SignalScales.fraction(-92f, SignalScales.RSSI_MIN, SignalScales.RSSI_MAX),
            ChartGeometry.xOf(-92f, SignalScales.RSSI_MIN, SignalScales.RSSI_MAX),
            0.0001f,
        )
        assertEquals(0f, ChartGeometry.xOf(-200f, SignalScales.RSSI_MIN, SignalScales.RSSI_MAX), 0.0001f)
        assertEquals(1f, ChartGeometry.xOf(0f, SignalScales.RSSI_MIN, SignalScales.RSSI_MAX), 0.0001f)
    }

    @Test
    fun `auto scale off is the fixed range every other screen uses`() {
        val stats = SignalStats.EMPTY.plus(-100f).plus(-80f)
        val range = ChartGeometry.scaleRange(stats, autoScale = false, SignalScales.RSSI_MIN, SignalScales.RSSI_MAX)
        assertEquals(SignalScales.RSSI_MIN, range.min, 0.0001f)
        assertEquals(SignalScales.RSSI_MAX, range.max, 0.0001f)
    }

    @Test
    fun `auto scale on is the observed span, the same figures the bars print`() {
        val stats = SignalStats.EMPTY.plus(-100f).plus(-80f).plus(-92f)
        val range = ChartGeometry.scaleRange(stats, autoScale = true, SignalScales.RSSI_MIN, SignalScales.RSSI_MAX)
        assertEquals(-100f, range.min, 0.0001f)
        assertEquals(-80f, range.max, 0.0001f)
    }

    @Test
    fun `a degenerate span falls back to the fixed range`() {
        // One sample, or a perfectly flat relay. Dividing by a zero span would put
        // every point hard against the left edge and read as a dead link.
        val flat = SignalStats.EMPTY.plus(-92f)
        val range = ChartGeometry.scaleRange(flat, autoScale = true, SignalScales.RSSI_MIN, SignalScales.RSSI_MAX)
        assertEquals(SignalScales.RSSI_MIN, range.min, 0.0001f)
        assertEquals(SignalScales.RSSI_MAX, range.max, 0.0001f)
    }

    @Test
    fun `no data at all falls back to the fixed range`() {
        val range = ChartGeometry.scaleRange(SignalStats.EMPTY, autoScale = true, SignalScales.SNR_MIN, SignalScales.SNR_MAX)
        assertEquals(SignalScales.SNR_MIN, range.min, 0.0001f)
        assertEquals(SignalScales.SNR_MAX, range.max, 0.0001f)
    }

    @Test
    fun `at the top, new measurements do not move the view`() {
        // A live chart scrolled to the newest stays at the newest.
        assertEquals(0f, ChartGeometry.anchorAfterAppend(0f, appended = 5, pxPerSample = 1f), 0.0001f)
    }

    @Test
    fun `scrolled down, the measurement under the reader's eye does not move`() {
        // Data arriving must never yank the view.
        assertEquals(305f, ChartGeometry.anchorAfterAppend(300f, appended = 5, pxPerSample = 1f), 0.0001f)
    }

    @Test
    fun `the scroll cannot go past the end of a saturated series`() {
        // Once the ring is full, size stops growing while measurements keep
        // arriving - so the anchor keeps advancing and something has to clamp it.
        val max = ChartGeometry.maxScrollPx(size = 5000, viewportPx = 1200f, pxPerSample = 1f)
        assertEquals(3800f, max, 0.0001f)
        assertEquals(0f, ChartGeometry.maxScrollPx(size = 3, viewportPx = 1200f, pxPerSample = 1f), 0.0001f)
    }

    @Test
    fun `a scale coefficient other than one moves every number with it`() {
        // Requirement 13: the parameter is present in the geometry and absent from
        // the interface. It must actually work when something eventually sets it.
        assertEquals(8f, ChartGeometry.yOf(row = 2, scrollPx = 0f, pxPerSample = 4f), 0.0001f)
        assertEquals(2, ChartGeometry.rowAt(y = 8f, scrollPx = 0f, pxPerSample = 4f))
        assertEquals(20f, ChartGeometry.anchorAfterAppend(10f, appended = 100, pxPerSample = 0.1f), 0.0001f)
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

`gradle :app:testDebugUnitTest --tests '*ChartGeometryTest*'`

- [ ] **Step 3: Implement**

`ui/graph/ChartGeometry.kt`:

```kotlin
package com.cerocoder.meshrelay.ui.graph

import com.cerocoder.meshrelay.stats.SignalScales
import com.cerocoder.meshrelay.stats.model.SignalStats
import kotlin.math.floor

/**
 * An inclusive range of rows to draw, or empty when there is nothing to draw.
 */
data class RowWindow(val firstRow: Int, val lastRow: Int) {
    val isEmpty: Boolean get() = lastRow < firstRow
}

/** One metric's horizontal scale, left edge to right edge. */
data class ScaleRange(val min: Float, val max: Float)

/**
 * Pure geometry for [SignalChart], in the shape [com.cerocoder.meshrelay.ui.common.GaugeGeometry]
 * established: no Compose types in any signature, every number the canvas needs,
 * and its own unit tests. This project's rule is that no `@Composable` contains
 * arithmetic; this object is where the chart's arithmetic went.
 *
 * **Rows run newest first.** Row 0 is the most recent measurement and sits at the
 * top of the viewport when `scrollPx` is 0, which is requirement 7. The series
 * itself is stored oldest first, its natural append order; [indexOfRow] is the
 * one place that inversion happens.
 *
 * **One measurement is one pixel row**, and only the rows inside the scrolled
 * window are drawn - 5000 rows in a single `Canvas` inside a `verticalScroll`
 * would be a layer far past the maximum texture size and fails on real hardware.
 * [pxPerSample] is a parameter throughout and is fixed at `1f` by its single
 * caller; requirement 13 asks for exactly that, so a zoom control (2x, 4x, or a
 * fraction such as 0.1) becomes a value to pass rather than a restructuring. It
 * may be fractional, which is why it is a `Float`.
 */
object ChartGeometry {

    /**
     * One row of overscan at each edge.
     *
     * A polyline segment joins two consecutive rows, so a window clipped exactly
     * to the viewport would be missing the segment that leaves the top edge and
     * the one that enters the bottom - the line would appear to stop short of both
     * edges and to jump when scrolled.
     */
    const val OVERSCAN_ROWS = 1

    /** The series index this row draws. Storage is oldest-first; rows are newest-first. */
    fun indexOfRow(row: Int, size: Int): Int = size - 1 - row

    fun contentHeightPx(size: Int, pxPerSample: Float): Float = size * pxPerSample

    /** How far the chart can be scrolled before the oldest measurement reaches the bottom. */
    fun maxScrollPx(size: Int, viewportPx: Float, pxPerSample: Float): Float =
        (contentHeightPx(size, pxPerSample) - viewportPx).coerceAtLeast(0f)

    /** The rows that need drawing at this offset, clamped to the series and overscanned. */
    fun visibleRows(scrollPx: Float, viewportPx: Float, size: Int, pxPerSample: Float): RowWindow {
        if (size <= 0 || pxPerSample <= 0f || viewportPx <= 0f) return RowWindow(0, -1)
        val first = floor(scrollPx / pxPerSample).toInt() - OVERSCAN_ROWS
        val last = floor((scrollPx + viewportPx) / pxPerSample).toInt() + OVERSCAN_ROWS
        return RowWindow(
            firstRow = first.coerceIn(0, size - 1),
            lastRow = last.coerceIn(0, size - 1),
        )
    }

    /** Where this row sits in the viewport, in pixels from its top edge. */
    fun yOf(row: Int, scrollPx: Float, pxPerSample: Float): Float = row * pxPerSample - scrollPx

    /**
     * Which row a touch at [y] landed on. Not clamped: the caller knows the size
     * and clamps, and then draws the crosshair at [yOf] of the clamped row - so
     * the line and the numbers beside it always describe the same measurement,
     * even when the touch was below the last one.
     */
    fun rowAt(y: Float, scrollPx: Float, pxPerSample: Float): Int =
        floor((y + scrollPx) / pxPerSample).toInt()

    /**
     * Where a value sits along the track, as a fraction in `0f..1f`.
     *
     * [SignalScales.fraction], the same function the gauges use, so the chart and
     * the bars above it cannot drift apart.
     */
    fun xOf(value: Float, min: Float, max: Float): Float = SignalScales.fraction(value, min, max)

    /**
     * The horizontal range for one metric.
     *
     * Auto scale off: the fixed range every other screen uses. On: the metric's
     * whole-session minimum and maximum - the same two figures the bars print
     * beside themselves, so bars and plot share one range and neither can
     * misrepresent the other. Every retained sample necessarily falls inside it,
     * the retained samples being a subset of the session those statistics cover.
     *
     * A degenerate span - one sample, or a perfectly flat relay - falls back to
     * the fixed range: [SignalScales.fraction] returns 0 for a zero span, which
     * would stack every point against the left edge and read as a dead link.
     */
    fun scaleRange(stats: SignalStats, autoScale: Boolean, fixedMin: Float, fixedMax: Float): ScaleRange {
        if (!autoScale || !stats.hasData) return ScaleRange(fixedMin, fixedMax)
        if (stats.maxVal - stats.minVal <= 0f) return ScaleRange(fixedMin, fixedMax)
        return ScaleRange(stats.minVal, stats.maxVal)
    }

    /**
     * Where the scroll offset goes when [appended] measurements arrive.
     *
     * At the top, it stays at the top - which is what a live chart should do.
     * Scrolled down, it advances by exactly the height the new rows added, so the
     * measurement under the reader's eye does not move: data arriving must never
     * yank the view.
     *
     * The result is not clamped. Once the ring buffer saturates, `size` stops
     * growing while measurements keep arriving, so the caller clamps to
     * [maxScrollPx] with the viewport height it alone knows.
     */
    fun anchorAfterAppend(scrollPx: Float, appended: Long, pxPerSample: Float): Float =
        if (scrollPx <= 0f) 0f else scrollPx + appended * pxPerSample
}
```

- [ ] **Step 4: Run the tests, green**

- [ ] **Step 5: Commit** — `feat(ui): the chart's geometry, pure and tested`

---

### Task 9: `Screen.Graph` and the back stack

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/nav/Screen.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/nav/BackStack.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/ui/nav/BackStackTest.kt`

**Interfaces:**
- Produces: `Screen.Graph(subject: DetailSubject)`.

- [ ] **Step 1: Write the failing tests**

In `BackStackTest`, beside the existing fixtures:

```kotlin
    private val relayGraph = Screen.Graph(DetailSubject.Relay(0x69))
    private val neighbourGraph = Screen.Graph(DetailSubject.Neighbour(0x9e75f1a4.toInt()))
```

and a new test:

```kotlin
@Test
fun `a graph screen survives being saved and restored, for both subjects`() {
    // The chart is reached from a detail screen, so a rotation on it must come
    // back to the same chart on the same subject - not to the device list.
    val stack = BackStack(Screen.Devices)
    stack.push(Screen.Main(MainTab.RELAYS))
    stack.push(relayDetail)
    stack.push(relayGraph)
    stack.push(neighbourGraph)

    val saved = with(SaverScope { true }) { backStackSaver.save(stack) }
    val restored = backStackSaver.restore(assertNotNull(saved)!!)

    assertEquals(
        listOf(Screen.Devices, Screen.Main(MainTab.RELAYS), relayDetail, relayGraph, neighbourGraph),
        assertNotNull(restored)!!.entries,
    )
}

@Test
fun `a graph screen's subject is not confused with a detail screen's`() {
    // The two subjects get their own tags, and so do the two destinations: a
    // relay's argument is a byte and a neighbour's is a whole node number, and
    // nothing about the numbers themselves tells the four cases apart.
    val stack = BackStack(Screen.Devices)
    stack.push(relayDetail)
    stack.push(relayGraph)

    val saved = with(SaverScope { true }) { backStackSaver.save(stack) }
    val restored = assertNotNull(backStackSaver.restore(assertNotNull(saved)!!))!!

    assertEquals(relayDetail, restored.entries[1])
    assertEquals(relayGraph, restored.entries[2])
}
```

Match whatever `assertNotNull` idiom the existing `a stack survives being saved and restored` test
uses rather than inventing a second one — read it first and copy its shape exactly.

The existing `saved data this version cannot read degrades to the device list` test still passes
unchanged: it uses a tag well outside the defined range, and adding 6 and 7 does not reach it. Read
it and confirm; if it happens to use tag 6 or 7, raise the sentinel it uses rather than weakening
the assertion.

- [ ] **Step 2: Run them and watch them fail**

`gradle :app:testDebugUnitTest --tests '*BackStackTest*'`

- [ ] **Step 3: Implement**

`Screen.kt`:

```kotlin
sealed interface Screen {
    data object Devices : Screen
    data class Main(val tab: MainTab) : Screen
    data object Settings : Screen
    data class Detail(val subject: DetailSubject) : Screen
    data class RemoteNode(val nodeNum: Int, val viaRelayByte: Int?) : Screen

    /**
     * RSSI and SNR against time, for one relay or one neighbour.
     *
     * A full-screen destination rather than a third tab inside [Detail]: the tab
     * would lose about 180 dp to the summary block and the tab row - the space the
     * plot needs - and could carry neither its own title nor its own two switches.
     */
    data class Graph(val subject: DetailSubject) : Screen
}
```

`BackStack.kt` — two tags, following the existing rule that each subject gets its own rather than a
shared tag plus a discriminator:

```kotlin
private const val TAG_REMOTE_NODE = 5
private const val TAG_GRAPH_RELAY = 6
private const val TAG_GRAPH_NEIGHBOUR = 7
```

in `encode`:

```kotlin
    is Screen.Graph -> when (val subject = screen.subject) {
        is DetailSubject.Relay -> listOf(TAG_GRAPH_RELAY, subject.relayByte, 0)
        is DetailSubject.Neighbour -> listOf(TAG_GRAPH_NEIGHBOUR, subject.nodeNum, 0)
    }
```

and in `decode`:

```kotlin
        TAG_GRAPH_RELAY -> Screen.Graph(DetailSubject.Relay(first))
        TAG_GRAPH_NEIGHBOUR -> Screen.Graph(DetailSubject.Neighbour(first))
```

The saver already drops entries it cannot decode, so an older build restoring a newer bundle
degrades to the root rather than crashing — that rule needs nothing new.

- [ ] **Step 4: Run the tests, green**

- [ ] **Step 5: Commit** — `feat(nav): a Graph destination for both detail subjects`

---

### Task 10: The overflow menu on `DetailScreen`

Spec §9. One parameter, one `IconButton`, one `DropdownMenu` — the same construction `StatsTopBar`
already uses.

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/detail/DetailScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-es/strings.xml`

**Interfaces:**
- Produces: `DetailMenuItem(labelRes: Int, onClick: () -> Unit)`;
  `DetailScreen(..., menuItems: List<DetailMenuItem> = emptyList())`.

- [ ] **Step 1: Strings, both locales**

`values/strings.xml`, beside the other `action_` keys:

```xml
    <string name="action_graph">Graph</string>
```

`values-es/strings.xml`:

```xml
    <string name="action_graph">Gráfica</string>
```

`action_more` already exists in both, and is reused as the button's content description.

- [ ] **Step 2: Remove the stale rule from the KDoc**

`DetailScreen`'s KDoc carries a paragraph beginning **"Tab content is a slot, not a call."** whose
argument is that no later task in the port may edit this file. That described the port's own task
sequencing, which has finished; this design edits the file deliberately. Delete the paragraph and
the one after it ("A consequence worth stating: …"), and replace them with the reason the slots
still exist, which is a real one:

```kotlin
 * **Tab content is a slot, not a call.** [matchingNodesTab] and [remoteNodesTab]
 * are supplied by the caller rather than built here from [MatchingNodesTab] and
 * [RemoteNodesTab] directly, because `MeshRelayNavHost` is the one place that
 * holds everything those two tabs need at once - the snapshot, the Meshview URL
 * and the container's skip commands - and closing over them there is cheaper than
 * threading four more parameters through this shell. Both default to an empty
 * composable, so this screen and its own previews render with the shell alone.
 * [onOpenRemoteNode], [onSkipNode], [onClearSkipped] and [meshviewUrl] are part of
 * this function's contract for the same reason and are deliberately not read in
 * this file's own body.
```

Leave the first paragraph of the KDoc (the "one shell, two subjects" argument) exactly as it is.

- [ ] **Step 3: The menu**

Add above `DetailScreen`:

```kotlin
/**
 * One entry in the detail screen's overflow menu.
 *
 * A list rather than a fixed set of parameters, so a second command is one more
 * entry rather than a change to this screen's signature. An empty list draws no
 * button at all - a menu with nothing in it is worse than no menu.
 */
data class DetailMenuItem(@StringRes val labelRes: Int, val onClick: () -> Unit)
```

with `import androidx.annotation.StringRes`.

Add the parameter, last, after the two tab slots:

```kotlin
    matchingNodesTab: @Composable () -> Unit = {},
    remoteNodesTab: @Composable () -> Unit = {},
    menuItems: List<DetailMenuItem> = emptyList(),
) {
```

Add local state beside `selectedTab`:

```kotlin
    var menuExpanded by remember { mutableStateOf(false) }
```

and the actions in the `TopAppBar`:

```kotlin
            TopAppBar(
                navigationIcon = { /* unchanged */ },
                title = { DetailTitle(primary = header.titlePrimary, secondary = header.titleSecondary) },
                actions = {
                    if (menuItems.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_action_more),
                                    contentDescription = stringResource(R.string.action_more),
                                )
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                menuItems.forEach { item ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(item.labelRes)) },
                                        onClick = {
                                            // Dismissed first: the click navigates
                                            // away, and a menu left expanded is
                                            // still expanded on the way back.
                                            menuExpanded = false
                                            item.onClick()
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
```

New imports: `androidx.compose.material3.DropdownMenu`, `androidx.compose.material3.DropdownMenuItem`,
`androidx.compose.ui.res.painterResource`, `androidx.compose.runtime.mutableStateOf`.
`R.drawable.ic_action_more` is the hand-authored vertical three-dot glyph already used as the
overflow on both list screens — requirement 12, and one glyph with one meaning application-wide.

- [ ] **Step 4: One preview with the menu**

Add beside the existing previews:

```kotlin
@Preview(showBackground = true, name = "Relay - with the overflow menu")
@Composable
private fun DetailScreenWithMenuPreview() {
    MeshRelayTheme {
        PreviewClock {
            DetailScreen(
                subject = DetailSubject.Relay(SampleData.RELAY_ONE_MATCH_BYTE),
                snapshot = SampleData.snapshot,
                gaugeMode = GaugeMode.COMPLEX,
                meshviewUrl = "https://meshview.meshtastic.es",
                onBack = {},
                onOpenRemoteNode = {},
                onSkipNode = {},
                onClearSkipped = {},
                menuItems = listOf(DetailMenuItem(R.string.action_graph) {}),
            )
        }
    }
}
```

- [ ] **Step 5: Green CI run, and `StringsParityTest`**

`gradle :app:testDebugUnitTest --tests '*StringsParityTest*'` plus a full build.

- [ ] **Step 6: Commit** — `feat(ui): an overflow menu on the detail screens`

---

### Task 11: `SignalGraphScreen` and `SignalChart`

The feature itself. The largest task here, and the only one written on Opus.

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/common/StatsFormat.kt` (+`graphTimestamp`)
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/detail/SignalBlock.kt` (+4 scale parameters)
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/graph/SignalChart.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/graph/SignalGraphScreen.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/preview/SampleData.kt` (+a series fixture)
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-es/strings.xml`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/ui/common/StatsFormatTest.kt`

**Interfaces:**
- Consumes: `SignalSeries` (Task 2), `ChartGeometry`/`RowWindow`/`ScaleRange` (Task 8),
  `SignalBlock`, `MapLinks.googleMaps`, `RssiTrack`/`SnrTrack`, `R.drawable.ic_field_coordinates`.
- Produces:
  `StatsFormat.graphTimestamp(atMillis: Long, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String`;
  `SignalChart(...)`;
  `SignalGraphScreen(title, subtitle, series, rssiStats, snrStats, gaugeMode, lastPacketAtMillis, onBack, modifier)`;
  `SampleData.graphSeries: SignalSeries`, `SampleData.graphSeriesSingle: SignalSeries`.

- [ ] **Step 1: Strings, both locales**

`values/strings.xml`:

```xml
    <string name="graph_title_relay">Relay %1$s graph</string>
    <string name="graph_title_neighbour">Neighbour %1$s graph</string>
    <string name="graph_freeze">Freeze</string>
    <string name="graph_auto_scale">Auto scale</string>
    <string name="graph_time">Time: %1$s</string>
    <string name="graph_open_map">Open this measurement on a map</string>
    <string name="graph_position_from_node">Open on a map — position from the node</string>
    <string name="graph_position_from_phone">Open on a map — position from the phone</string>
```

`values-es/strings.xml`:

```xml
    <string name="graph_title_relay">Gráfica del repetidor %1$s</string>
    <string name="graph_title_neighbour">Gráfica del vecino %1$s</string>
    <string name="graph_freeze">Congelar</string>
    <string name="graph_auto_scale">Escala automática</string>
    <string name="graph_time">Hora: %1$s</string>
    <string name="graph_open_map">Abrir esta medición en un mapa</string>
    <string name="graph_position_from_node">Abrir en un mapa: posición del nodo</string>
    <string name="graph_position_from_phone">Abrir en un mapa: posición del teléfono</string>
```

Spanish is the longer language and this screen has two labelled switches side by side with a plot —
`Escala automática` is 17 characters against `Auto scale`'s 10, which is the first place a layout
defect will show. That is item 6 of Task 13's hardware check.

- [ ] **Step 2: A timestamp formatter, with a test**

Add to `StatsFormat`:

```kotlin
    /**
     * A measurement's own timestamp: local time, then local date, as
     * `Pic1.pdf` shows it - `13:01:13 21.08.2026`.
     *
     * Both halves are locale-resolved rather than pattern-formatted, the same
     * argument [nodeDatabaseLastHeard] makes: a Spanish reader expects
     * day-before-month, and the platform is what knows that.
     * [FormatStyle.MEDIUM] is the shortest built-in time style that still
     * includes seconds - measurements on a busy relay arrive seconds apart, and a
     * chart whose two Time fields read the same to the minute would say nothing.
     * [FormatStyle.SHORT] is the only date style that stays numeric, which is what
     * the drawing shows and what fits under a chart on a phone.
     *
     * [zone] defaults to the device's own configured zone and is a parameter only
     * so a test can pin one.
     */
    fun graphTimestamp(atMillis: Long, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String {
        val localDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(atMillis), zone)
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withLocale(locale)
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)
        return "${time.format(localDateTime)} ${date.format(localDateTime)}"
    }
```

and to `StatsFormatTest`:

```kotlin
@Test
fun `a measurement's timestamp carries seconds and a numeric date`() {
    // Seconds because measurements on a busy relay arrive seconds apart; a
    // numeric date because the field is one line under a chart.
    val at = LocalDateTime.of(2026, 8, 21, 13, 1, 13)
        .atZone(ZoneId.of("Europe/Madrid")).toInstant().toEpochMilli()
    val text = StatsFormat.graphTimestamp(at, Locale.UK, ZoneId.of("Europe/Madrid"))

    assertTrue(text, text.contains("13:01:13"))
    assertTrue(text, text.contains("2026") || text.contains("26"))
}

@Test
fun `the timestamp follows the locale, not a hardcoded pattern`() {
    val at = LocalDateTime.of(2026, 8, 21, 13, 1, 13)
        .atZone(ZoneId.of("Europe/Madrid")).toInstant().toEpochMilli()
    val english = StatsFormat.graphTimestamp(at, Locale.US, ZoneId.of("Europe/Madrid"))
    val spanish = StatsFormat.graphTimestamp(at, Locale("es", "ES"), ZoneId.of("Europe/Madrid"))

    // A US reader gets a 12-hour clock and month-first; a Spanish one does not.
    // Asserting they differ rather than asserting either exact rendering, for the
    // reason ruling 26 records: the exact rendering is the platform's, not ours.
    assertNotEquals(english, spanish)
}
```

- [ ] **Step 3: `SignalBlock` takes its scales**

Requirement 5: Auto scale moves the bars' borders too. Add four parameters, defaulted to what the
function reads today, so its three existing call sites are unchanged:

```kotlin
@Composable
fun SignalBlock(
    snr: SignalStats,
    rssi: SignalStats,
    gaugeMode: GaugeMode,
    lastPacketAtMillis: Long,
    modifier: Modifier = Modifier,
    // Defaulted to the fixed ranges every other screen uses, so only the Graph
    // screen - the one place Auto scale exists - ever passes anything else.
    snrScaleMin: Float = SignalScales.SNR_MIN,
    snrScaleMax: Float = SignalScales.SNR_MAX,
    rssiScaleMin: Float = SignalScales.RSSI_MIN,
    rssiScaleMax: Float = SignalScales.RSSI_MAX,
) {
```

and use them in the two `MetricBlock` calls in place of the `SignalScales` constants. Nothing else
in the file changes.

- [ ] **Step 4: A preview fixture**

In `SampleData`, built through the real buffer so the fixture exercises the real code:

```kotlin
    /**
     * About twenty minutes of a relay drifting as the sun sets, at roughly one
     * packet every two seconds - the shape the Graph screen was designed to make
     * visible. Built through the real [SignalSeriesBuffer] rather than by
     * constructing a [SignalSeries] by hand, so a preview cannot show a series the
     * engine could never produce.
     */
    val graphSeries: SignalSeries = SignalSeriesBuffer().apply {
        val start = NOW - 20 * 60 * 1000L
        repeat(600) { i ->
            val drift = i * 0.03f
            append(
                atMillis = start + i * 2_000L,
                rssi = -78f - drift + (i % 7) * 0.8f,
                snr = 6.5f - drift * 0.12f + (i % 5) * 0.3f,
                position = StampedPosition.fromDegrees(
                    // A slow walk north-west, so consecutive crosshairs open
                    // different pins rather than the same one.
                    40.3057734 + i * 0.000012,
                    -3.7325611 - i * 0.000009,
                    if (i % 20 == 0) PositionOrigin.NODE else PositionOrigin.PHONE,
                ),
            )
        }
    }.snapshot()

    /** The thin state: one measurement, a point and no line. */
    val graphSeriesSingle: SignalSeries = SignalSeriesBuffer().apply {
        append(NOW - 30_000L, -92f, 4.5f, null)
    }.snapshot()
```

with imports for `SignalSeriesBuffer`, `SignalSeries`, `StampedPosition` and `PositionOrigin`.
`NOW` is `SampleData`'s existing fixture clock; use it, do not call `System.currentTimeMillis()`.

- [ ] **Step 5: The chart**

`ui/graph/SignalChart.kt`:

```kotlin
package com.cerocoder.meshrelay.ui.graph

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.cerocoder.meshrelay.stats.model.SignalSeries

/**
 * The plot itself: two polylines, virtualised, drawn newest-at-the-top.
 *
 * **Virtualised, and that is the design.** A `Canvas` holding all 5000 rows
 * inside a `verticalScroll` would be a layer far past the maximum texture size
 * and fails on real hardware; this one is exactly the size of the viewport and
 * draws only [ChartGeometry.visibleRows]. Owning the scroll offset rather than
 * delegating it to a scroll modifier is also what makes the custom scrollbar and
 * the crosshair possible at all.
 *
 * **No arithmetic lives here.** Every position comes from [ChartGeometry], which
 * has its own tests; this function multiplies fractions by a measured width and
 * does nothing else - the same division of labour
 * [com.cerocoder.meshrelay.ui.common.SignalGauge] and `GaugeGeometry` already
 * have.
 *
 * A touch anywhere on the plot places the crosshair, and a drag moves it: the
 * scrollbar beside it is what scrolls. That split is deliberate - a
 * `Modifier.scrollable` under the same touch drag would fight the crosshair, and
 * only one of the two can win a gesture.
 */
@Composable
fun SignalChart(
    series: SignalSeries,
    scrollPx: Float,
    pxPerSample: Float,
    rssiRange: ScaleRange,
    snrRange: ScaleRange,
    rssiColor: Color,
    snrColor: Color,
    strokeWidthPx: Float,
    onViewportHeight: (Float) -> Unit,
    onCrosshairAt: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { onViewportHeight(it.height.toFloat()) }
            .pointerInput(Unit) {
                // One gesture handler for both the touch and the drag that
                // follows it, so the two cannot disagree about which is in
                // charge. The drag is consumed, which is what stops the
                // scrollable under it from also acting on the same pointer.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onCrosshairAt(down.position.y)
                    drag(down.id) { change ->
                        onCrosshairAt(change.position.y)
                        change.consume()
                    }
                }
            },
    ) {
        val window = ChartGeometry.visibleRows(scrollPx, size.height, series.size, pxPerSample)
        if (window.isEmpty) return@Canvas

        drawMetric(series, window, scrollPx, pxPerSample, rssiRange, rssiColor, strokeWidthPx) { series.rssi(it) }
        drawMetric(series, window, scrollPx, pxPerSample, snrRange, snrColor, strokeWidthPx) { series.snr(it) }
    }
}

/**
 * One metric's polyline over [window].
 *
 * A single-row window draws a point rather than a path: a `Path` with one
 * `moveTo` and no `lineTo` strokes nothing at all, which is how "one measurement"
 * would otherwise render as an empty chart.
 */
private inline fun DrawScope.drawMetric(
    series: SignalSeries,
    window: RowWindow,
    scrollPx: Float,
    pxPerSample: Float,
    range: ScaleRange,
    color: Color,
    strokeWidthPx: Float,
    valueOf: (index: Int) -> Float,
) {
    fun xAt(row: Int): Float {
        val index = ChartGeometry.indexOfRow(row, series.size)
        return ChartGeometry.xOf(valueOf(index), range.min, range.max) * size.width
    }

    if (window.firstRow == window.lastRow) {
        drawCircle(
            color = color,
            radius = strokeWidthPx,
            center = Offset(xAt(window.firstRow), ChartGeometry.yOf(window.firstRow, scrollPx, pxPerSample)),
        )
        return
    }

    val path = Path()
    for (row in window.firstRow..window.lastRow) {
        val x = xAt(row)
        val y = ChartGeometry.yOf(row, scrollPx, pxPerSample)
        if (row == window.firstRow) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(path, color = color, style = Stroke(width = strokeWidthPx))
}
```

- [ ] **Step 6: The screen**

`ui/graph/SignalGraphScreen.kt`. The structure, in full:

```kotlin
package com.cerocoder.meshrelay.ui.graph

// imports as the compiler asks

private val CrosshairStroke = 1.dp
private val LineStroke = 1.5.dp
private val ScrollbarWidth = 12.dp
private val GlobeSize = 40.dp

/**
 * One measurement is one pixel row, and this is the "scale coefficient" spec
 * requirement 13 asks to exist without being exposed: `ChartGeometry` takes it
 * everywhere, this is its only caller, and a zoom control becomes a value to pass
 * rather than a restructuring. It is a `Float` because the requirement says the
 * coefficient may be fractional (0.1, say) so that scaling down is available too.
 */
private const val PX_PER_SAMPLE = 1f

/**
 * RSSI and SNR against time, for one relay or one neighbour.
 *
 * **The signature carries no `DetailSubject`, deliberately.** Spec requirement 2
 * says this is implemented once and shared by both subjects; a component that
 * cannot see which subject it is drawing cannot diverge per subject, so the rule
 * is enforced by the type signature rather than by discipline. Everything
 * subject-shaped - the title, the subtitle, the statistics - is resolved by
 * `MeshRelayNavHost` and arrives here already decided.
 *
 * [series] is null when nothing is being watched yet, and
 * [SignalSeries.EMPTY]-shaped when the subject has no measurements; both render
 * the empty state. A reset under an open chart arrives as the second of those,
 * which is how this screen survives one - the same way `DetailScreen` already
 * does.
 */
@Composable
fun SignalGraphScreen(
    title: String,
    subtitle: String,
    series: SignalSeries?,
    rssiStats: SignalStats,
    snrStats: SignalStats,
    gaugeMode: GaugeMode,
    lastPacketAtMillis: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Both survive a rotation. Freeze especially: a rotation must not silently
    // resume a chart the reader deliberately stopped.
    var freeze by rememberSaveable { mutableStateOf(false) }
    var autoScale by rememberSaveable { mutableStateOf(false) }

    var scrollPx by remember { mutableFloatStateOf(0f) }
    var viewportPx by remember { mutableFloatStateOf(0f) }
    var crosshairY by remember { mutableStateOf<Float?>(null) }
    var frozen by remember { mutableStateOf<SignalSeries?>(null) }
    var lastTotal by remember { mutableLongStateOf(0L) }

    // Freeze holds the drawing, not the collection: the engine keeps folding
    // packets throughout, and the graph is redrawn complete the moment it is
    // switched off.
    LaunchedEffect(freeze) { frozen = if (freeze) series else null }
    val shown = (if (freeze) frozen else series) ?: SignalSeries.EMPTY

    // Re-anchor as measurements arrive, so the row under the reader's eye does not
    // move. A decrease means the statistics were reset under this chart and the
    // session it was showing no longer exists, so the view goes back to the top.
    LaunchedEffect(shown) {
        val delta = shown.totalAppended - lastTotal
        scrollPx = when {
            delta < 0L -> 0f
            delta > 0L -> ChartGeometry.anchorAfterAppend(scrollPx, delta, PX_PER_SAMPLE)
            else -> scrollPx
        }
        lastTotal = shown.totalAppended
    }

    // One clamped value, used by every consumer, so no caller can forget to clamp.
    val effectiveScroll = scrollPx.coerceIn(
        0f,
        ChartGeometry.maxScrollPx(shown.size, viewportPx, PX_PER_SAMPLE),
    )

    val rssiRange = ChartGeometry.scaleRange(rssiStats, autoScale, SignalScales.RSSI_MIN, SignalScales.RSSI_MAX)
    val snrRange = ChartGeometry.scaleRange(snrStats, autoScale, SignalScales.SNR_MIN, SignalScales.SNR_MAX)

    val window = ChartGeometry.visibleRows(effectiveScroll, viewportPx, shown.size, PX_PER_SAMPLE)

    Scaffold(
        modifier = modifier,
        topBar = { /* back arrow + title/subtitle, the shape DetailScreen's own DetailTitle uses */ },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {

            // Requirement 20: label left, switch right, stacked and right-aligned
            // under the app bar. Disabled with no measurements - there is nothing
            // to freeze and nothing to scale.
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.End,
            ) {
                LabelledSwitch(stringResource(R.string.graph_freeze), freeze, shown.size > 0) { freeze = it }
                LabelledSwitch(stringResource(R.string.graph_auto_scale), autoScale, shown.size > 0) { autoScale = it }
            }

            SignalBlock(
                snr = snrStats,
                rssi = rssiStats,
                gaugeMode = gaugeMode,
                lastPacketAtMillis = lastPacketAtMillis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                snrScaleMin = snrRange.min,
                snrScaleMax = snrRange.max,
                rssiScaleMin = rssiRange.min,
                rssiScaleMax = rssiRange.max,
            )

            if (shown.size == 0) {
                Text(
                    text = stringResource(R.string.detail_no_signal_data),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center),
                )
                return@Column
            }

            val locale = displayLocale()
            TimeRow(shown.atMillis(ChartGeometry.indexOfRow(window.firstRow, shown.size)), locale)

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                SignalChart(
                    series = shown,
                    scrollPx = effectiveScroll,
                    pxPerSample = PX_PER_SAMPLE,
                    rssiRange = rssiRange,
                    snrRange = snrRange,
                    rssiColor = RssiTrack,
                    snrColor = SnrTrack,
                    strokeWidthPx = with(LocalDensity.current) { LineStroke.toPx() },
                    onViewportHeight = { viewportPx = it },
                    onCrosshairAt = { crosshairY = it },
                    modifier = Modifier.padding(end = ScrollbarWidth),
                )
                Crosshair(/* … see below … */)
                ChartScrollbar(
                    scrollPx = effectiveScroll,
                    contentPx = ChartGeometry.contentHeightPx(shown.size, PX_PER_SAMPLE),
                    viewportPx = viewportPx,
                    onScrollBy = { delta -> scrollPx = effectiveScroll + delta },
                    modifier = Modifier.align(Alignment.CenterEnd).width(ScrollbarWidth).fillMaxHeight(),
                )
            }

            TimeRow(shown.atMillis(ChartGeometry.indexOfRow(window.lastRow, shown.size)), locale)
        }
    }
}
```

The crosshair, its labels and its globe, as a private composable inside the same `Box`:

```kotlin
/**
 * The horizontal rule, the timestamp above it, the two values below it and the
 * globe at its right.
 *
 * The globe is a real [IconButton] offset into the box, **not** a shape painted
 * into the canvas: a painted glyph has no touch target, no ripple and no
 * accessible name. It is enabled only when that measurement stored a position,
 * and its content description names the origin, so "where did this pin come
 * from" is answerable rather than a mystery.
 *
 * The rule is drawn at [ChartGeometry.yOf] of the *resolved* row rather than at
 * the raw touch height, so the line and the numbers beside it always describe the
 * same measurement even when the touch landed past the last one.
 */
@Composable
private fun BoxScope.Crosshair(
    series: SignalSeries,
    touchY: Float,
    scrollPx: Float,
    locale: Locale,
    modifier: Modifier = Modifier,
) {
    val row = ChartGeometry.rowAt(touchY, scrollPx, PX_PER_SAMPLE).coerceIn(0, series.size - 1)
    val index = ChartGeometry.indexOfRow(row, series.size)
    val y = ChartGeometry.yOf(row, scrollPx, PX_PER_SAMPLE)
    val position = series.positionOf(index)
    val uriHandler = LocalUriHandler.current

    // The rule, painted into a canvas of its own so it sits over the plot.
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawLine(
            color = crosshairColor,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = crosshairStrokePx,
        )
    }

    // The timestamp above the rule and the two values below it, each value in its
    // metric's own colour - which is why this needs no legend.
    Column(modifier = Modifier.offset { IntOffset(0, (y - labelOffsetPx).roundToInt() ) }) {
        Text(StatsFormat.graphTimestamp(series.atMillis(index), locale), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(crosshairGap))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(R.string.format_rssi_dbm, StatsFormat.signalLast(/* … */)),
                color = RssiTrack,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = stringResource(R.string.format_snr_db, /* … */),
                color = SnrTrack,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }

    IconButton(
        onClick = {
            position?.let { uriHandler.openUri(MapLinks.googleMaps(it.latitude, it.longitude)) }
        },
        enabled = position != null,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .offset { IntOffset(0, (y - globeHalfPx).roundToInt()) }
            .size(GlobeSize),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_field_coordinates),
            contentDescription = stringResource(
                when (position?.origin) {
                    PositionOrigin.NODE -> R.string.graph_position_from_node
                    PositionOrigin.PHONE -> R.string.graph_position_from_phone
                    null -> R.string.graph_open_map
                },
            ),
        )
    }
}
```

Notes the implementer must honour:

- `MapLinks.googleMaps` takes degrees. Convert with `StampedPosition.latitude`/`.longitude`, which
  multiply in `Double`. `MapLinks` already formats under `Locale.ROOT`; a Spanish locale's decimal
  comma would otherwise break the query string, and that is why the conversion must not go through
  any display formatter.
- Format the two values with `StatsFormat`, wrapped in `R.string.format_rssi_dbm` and
  `R.string.format_snr_db` — the same unit strings `SignalBlock` uses. Add whatever small pure
  formatter is needed to `StatsFormat` (a spot reading is `%.0f` for RSSI and `%.1f` for SNR, both
  patterns it already holds) rather than calling `String.format` in the composable; this project's
  rule is that no `@Composable` formats.
- The crosshair's label block is placed with `Modifier.offset` and the values row below the rule,
  matching `Pic1.pdf`: timestamp above, values below, globe at the right.
- `ChartScrollbar` is a small private composable: a track, a thumb sized
  `viewportPx / contentPx` of the track and positioned `scrollPx / contentPx` down it, dragged with
  `Modifier.draggable(orientation = Orientation.Vertical)` converting a drag in track pixels to a
  scroll in content pixels by `contentPx / trackPx`. It draws nothing when `contentPx <= viewportPx`.
- `LabelledSwitch` is a private `Row` with the label on the left and a `Switch` on the right,
  right-aligned by the parent `Column`'s `horizontalAlignment = Alignment.End` — requirement 20.
- `displayLocale()` is the same private helper `SignalBlock`, `RelayCard` and `NeighbourCard` each
  already carry; copy it, as they copy each other.
- `crosshairColor` should be `MaterialTheme.colorScheme.onSurface`, so it reads in both themes
  without adding a colour to `Color.kt` — the two metric colours are the ones that carry meaning.

- [ ] **Step 7: Previews**

Six, the set every other screen in this application carries:

```kotlin
@Preview(showBackground = true, name = "Populated")
@Preview(showBackground = true, name = "One measurement")
@Preview(showBackground = true, name = "Empty")
@Preview(showBackground = true, name = "Auto scale on")   // seed autoScale via a preview-only wrapper
@Preview(showBackground = true, name = "Frozen")
@Preview(showBackground = true, name = "Dark theme", uiMode = 0x20)
```

`freeze` and `autoScale` are internal `rememberSaveable` state, so the "Auto scale on" and "Frozen"
previews cannot set them from the outside. Render them as they are and note in a comment that the
switch position is what the preview shows, not the state behind it — do **not** widen the screen's
signature with two parameters that exist only for previews.

- [ ] **Step 8: Green CI run**

`gradle :app:testDebugUnitTest` in full, plus `assembleDebug`. `StatsFormatTest` and
`StringsParityTest` are the two that can actually fail here.

- [ ] **Step 9: Commit** — `feat(ui): the Graph screen, its chart and its crosshair`

---

### Task 12: Navigation host wiring

The last piece of code: the menu item that opens the chart, the destination that draws it, and the
watch that starts and stops with it.

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/MeshRelayNavHost.kt`

**Interfaces:**
- Consumes: everything from Tasks 4 and 8-11.

- [ ] **Step 1: Collect the series beside the snapshot**

At the top of `MeshRelayNavHost`:

```kotlin
    val series by container.engine.series.collectAsState()
```

- [ ] **Step 2: The menu item on the detail destination**

In `DetailDestination`, add `backStack.push(Screen.Graph(subject))` as the one menu item:

```kotlin
        // Today exactly one item. A second command is one more entry in this list,
        // not a change to DetailScreen's signature.
        menuItems = listOf(DetailMenuItem(R.string.action_graph) { backStack.push(Screen.Graph(subject)) }),
```

- [ ] **Step 3: The destination**

A new branch in the `when (val screen = backStack.current)`, and the composable behind it:

```kotlin
        is Screen.Graph -> GraphDestination(
            subject = screen.subject,
            snapshot = snapshot,
            series = series,
            gaugeMode = settings.gaugeMode,
            container = container,
            onBack = { backStack.pop() },
            modifier = modifier,
        )
```

```kotlin
/**
 * [SignalGraphScreen] plus the one thing it deliberately cannot do for itself:
 * resolve a [DetailSubject].
 *
 * This is the only place that knows both vocabularies. `stats/` may not import
 * `ui/`, so `SeriesKey` mirrors `DetailSubject` without being it, and the mapping
 * lives here - one function, four lines, rather than a shared type that would
 * put a navigation concept inside the engine.
 *
 * The watch is a [DisposableEffect] keyed on the subject, so it starts when this
 * destination appears, switches when the subject does, and - this is the part
 * that matters - stops when the chart is closed. A watch left running would keep
 * copying 125 KB per batch for a screen nobody is looking at.
 */
@Composable
private fun GraphDestination(
    subject: DetailSubject,
    snapshot: StatsSnapshot,
    series: SignalSeries?,
    gaugeMode: GaugeMode,
    container: AppContainer,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val key = when (subject) {
        is DetailSubject.Relay -> SeriesKey.Relay(subject.relayByte)
        is DetailSubject.Neighbour -> SeriesKey.Neighbour(subject.nodeNum)
    }

    DisposableEffect(key) {
        container.engine.watchSeries(key)
        onDispose { container.engine.watchSeries(null) }
    }

    // A subject cleared by a reset while the chart is open falls back to an
    // all-zero record rather than crashing, exactly as DetailScreen's own header
    // resolution already allows for.
    when (subject) {
        is DetailSubject.Relay -> {
            val relay = snapshot.relays.find { it.relayByte == subject.relayByte }
                ?: RelayStats(relayByte = subject.relayByte)
            SignalGraphScreen(
                title = stringResource(R.string.graph_title_relay, relay.hexId),
                // A name beside an ambiguous byte would present a guess as a fact -
                // the same honesty rule DetailScreen's own title line enforces.
                subtitle = snapshot.directory.uniqueRelayName(relay.relayByte),
                series = series,
                rssiStats = relay.rssi,
                snrStats = relay.snr,
                gaugeMode = gaugeMode,
                lastPacketAtMillis = relay.lastPacketAtMillis,
                onBack = onBack,
                modifier = modifier,
            )
        }

        is DetailSubject.Neighbour -> {
            val neighbour = snapshot.neighbours.find { it.nodeNum == subject.nodeNum }
                ?: NeighbourStats(nodeNum = subject.nodeNum)
            SignalGraphScreen(
                title = stringResource(R.string.graph_title_neighbour, NodeId.format(subject.nodeNum)),
                subtitle = snapshot.directory.shortName(subject.nodeNum),
                series = series,
                rssiStats = neighbour.rssi,
                snrStats = neighbour.snr,
                gaugeMode = gaugeMode,
                lastPacketAtMillis = neighbour.lastPacketAtMillis,
                onBack = onBack,
                modifier = modifier,
            )
        }
    }
}
```

`neighbour.rssi` and `neighbour.snr` are plain `SignalStats` here because of Task 3 — that
narrowing is what lets both branches call the same function with the same types.

- [ ] **Step 4: Green CI run** — a full `assembleDebug` plus `testDebugUnitTest`.

- [ ] **Step 5: Commit** — `feat(ui): open the Graph from either detail screen`

---

### Task 13: Documents, and the run on the phone

CI has never seen a screen. This branch is not done until it has been read off the device.

**Files:**
- Modify: `mesh-relay-android/docs/decisions.md`
- Modify: `mesh-relay-android/docs/deferred-work.md`
- Modify: `mesh-relay-android/docs/acceptance-checklist.md`
- Modify: `mesh-relay-android/README.md` (the permission list, if it carries one)

- [ ] **Step 1: Record the rulings**

Append to `docs/decisions.md`, continuing its numbering:

```markdown
Ruling 29: 5000 measurements per relay and per neighbour, kept in six primitive arrays.
  125 KB per subject, about 7.5 MB for a typical session of sixty subjects and about 32 MB in the
  theoretical worst case - the largest allocation in the application, stated here rather than
  discovered in a heap dump. Cost if wrong: a low-memory phone in a long survey. SignalSeriesBuffer
  .MAX_SAMPLES is the one line to change.

Ruling 30: *Use phone location* ON falls back to the node; OFF never falls back to the phone.
  The asymmetry is deliberate. ON is a preference for precision, so a missing fix degrades rather
  than blanks, and the origin recorded with each sample keeps it honest. OFF is a request that the
  phone's GPS not be used, so honouring it means a sample with no position at all. Cost if wrong: a
  reader who assumes symmetry misreads the pins. Pinned by two engine tests.

Ruling 31: the application gains unrestricted ACCESS_FINE_LOCATION and ACCESS_COARSE_LOCATION.
  Visible on any store or F-Droid-style listing, and the maxSdkVersion="30" entry that existed for
  pre-Android-12 BLE scanning is superseded. BLUETOOTH_SCAN keeps neverForLocation, which stays
  true: the fix comes from the GNSS, not from a scan. Cost if wrong: a permission the user did not
  expect. The *Use phone location* switch is the escape hatch, and it stops the updates rather than
  ignoring them.

Ruling 32: the graph area's drag moves the crosshair; the scrollbar scrolls.
  Only one of the two can win a touch gesture. Requirement 9 asks for a crosshair on the plot and
  Pic1.pdf draws a scrollbar down the right edge, so the plot owns the crosshair and the bar owns
  scrolling. Cost if wrong: the biggest target on the screen does not scroll, which is unusual on a
  phone. This is the item to judge in the hand, not in a review.
```

- [ ] **Step 2: Record what was left out**

Append to `docs/deferred-work.md`:

```markdown
- **Background position stamping (2026-09-01).** From Android 10 an app receives location updates
  while backgrounded only if its foreground service declares
  `android:foregroundServiceType="location"`; this app's declares `connectedDevice`. So with the
  screen on every measurement is stamped, and with the screen off under background collection,
  samples on API 29+ fall back to the local node's position. Adding the type would also add
  `FOREGROUND_SERVICE_LOCATION` to the store-visible permission set - a second permission change the
  Graph design did not ask for. Awaiting the owner's decision.
- **The zoom control (2026-09-01).** 2x and 4x pixels per measurement, and fractions below 1 for
  scaling down. `ChartGeometry` takes `pxPerSample` in every signature and is tested at 0.1, 1 and
  4; `SignalGraphScreen` fixes it at 1. Deferred at the owner's request; adding a control is a value
  to pass, not a restructuring.
- **Persisting series across launches (2026-09-01).** Statistics remain a single session, per
  decision 8 of the stage-1 spec. Exporting a chart or its data is out of scope for the same reason.
- **A chart for a remote node (2026-09-01).** `Screen.RemoteNode` has no Graph command: the
  measurements there belong to the relay that carried them, which has its own chart.
```

- [ ] **Step 3: Add the hardware items**

Append to `docs/acceptance-checklist.md`, continuing its numbering:

```markdown
29. The `⋮` button appears in the top right of the **Relay** detail screen and opens a menu with
    **Graph** in it. Same on the **Neighbour** detail screen.
30. Graph opens a full screen with the subject in the title, both switches, both bars, a Time field
    above the plot and a Time field below it.
31. Two lines are drawn, one green (SNR) and one blue (RSSI), the same colours as the bars.
32. The newest measurement is at the top. New packets do not move a chart that has been scrolled
    down.
33. **Freeze** holds the drawing; packets keep arriving; switching it off redraws the chart complete,
    including everything collected while it was held.
34. **Auto scale** moves the left and right borders of *both bars* and of the plot to the observed
    minimum and maximum. Off by default.
35. Touching the plot draws a horizontal line with the timestamp above it, the RSSI and SNR values
    below it in their own colours, and a globe at its right. Dragging moves the line.
36. The globe opens Google Maps at that measurement's position, and its spoken description says
    whether the position came from the node or from the phone. It is greyed out on a measurement
    that stored no position.
37. **In Spanish**: `Escala automática` and `Congelar` fit beside their switches without wrapping or
    pushing the plot; the Time fields fit on one line; the crosshair labels do not overlap the globe.
38. Settings shows **Use phone location**, on by default, with its summary underneath. Turning it
    off and walking a few metres produces measurements whose globe description says "from the node".
39. On first connect, the permission dialog now asks for location alongside Bluetooth. **Refusing it
    is not an error**: the app connects, collects, and every measurement falls back to the node.
40. Rotate the phone on an open, frozen, scrolled-down chart. It comes back frozen, on the same
    subject. (Scroll position is not saved and is expected to return to the top.)
41. Reset the statistics with a chart open. It falls to the empty state rather than showing a
    session that no longer exists.
42. **Background stamping** (see `deferred-work.md`): with the screen off and background collection
    on, check whether new measurements still carry a phone position or fall back to the node.
    Whichever it is, record it - that answer is what decides the `foregroundServiceType` question.
```

- [ ] **Step 4: Run it on the phone**

Follow `docs/verifying.md`. **Read the view hierarchy, not a screenshot**: every layout defect in
this project was found in `bounds` and would have been argued about from pixels.

```bash
adb shell uiautomator dump /sdcard/w.xml && adb pull /sdcard/w.xml -
```

Two facts that keep repeating and will bite here specifically:

- The **demo transport's local node has no coordinates**. So in a demo scenario with
  *Use phone location* off, every measurement stores no position and every globe is disabled — that
  is correct behaviour, not a defect. Judge item 38 against a real node, or against the phone.
- **Spanish is the longer language**, and this screen puts two labelled switches in a right-aligned
  stack above a plot. Item 37 exists because that is where the first layout defect will be.

Leave the owner's install as you found it: put `shared_prefs` back if you edited it.

- [ ] **Step 5: Record what the phone said**

Fill in the checklist's "Issues found" section, and open a `docs/field-issues.md` entry for anything
that needs fixing, following the F-numbering already there.

- [ ] **Step 6: Commit** — `docs: rulings, deferred work and the acceptance items for the Graph`

---

## Self-review against the spec

Run after the plan is written, before the first task is dispatched.

**Spec coverage.** Requirements 1-20 and sections 1-12, each against a task:

| Spec | Task |
| :--- | :--- |
| Req 1 (`⋮` on both detail views) | 10, 12 |
| Req 2 (implemented once, shared) | 11 — enforced by the signature carrying no `DetailSubject` |
| Req 3 (the layout of `Pic1.pdf`) | 11 |
| Req 4 (Freeze holds drawing, not collection) | 11 |
| Req 5 (Auto scale moves the bars too) | 8, 11 — `SignalBlock` gains scale parameters |
| Req 6 (the application's own colours) | 11 — `RssiTrack`/`SnrTrack`, unchanged |
| Req 7 (plot fills the view, newest at top, scrolls) | 8, 11 |
| Req 8 (the two `Time` fields) | 11 |
| Req 9 (crosshair with timestamp, values, globe) | 11 |
| Req 10 (the globe opens Google Maps) | 11 |
| Req 11 (two lines, each in its metric's colour) | 11 |
| Req 12 (the vertical three-dot glyph) | 10 — `R.drawable.ic_action_more` |
| Req 13 (one row per measurement, `pxPerSample` unexposed, may be fractional) | 8, 11 |
| Req 14 (series for every subject, from the first packet) | 4 |
| Req 15 (5000 samples) | 2 |
| Req 16 (coordinates + a source flag per sample) | 1, 2, 4 |
| Req 17 (*Use phone location*, on by default) | 6 |
| Req 18 (the asymmetric fallback) | 4 |
| Req 19 (the node's position is what it already told us) | 4 — `localPositionOf` |
| Req 20 (label left, switch right, right-aligned stack) | 11 |
| §5.2 `SignalSeriesBuffer` | 2 |
| §5.3 memory | 2 (KDoc), 13 (ruling 29) |
| §5.4 `SignalSeries` | 2 — minus its statistics, decision 1 |
| §5.5 `StampedPosition`/`PositionOrigin` | 1 |
| §5.6 `NeighbourStats` narrows | 3 |
| §6 the engine | 4 |
| §7 location, §7.1 rate, §7.2 setting | 5, 6, 7 |
| §8.1 navigation | 9 |
| §8.2-8.8 the screen | 11 |
| §9 the overflow menu | 10 |
| §10 strings | 6, 10, 11 — all eleven keys, both locales |
| §11 testing | 1, 2, 4, 5, 6, 8, 9 |
| §12 out of scope | 13 — recorded in `deferred-work.md` |

**Two spec details corrected in passing, both harmless:** §5.1 calls the type
`PacketClassifier.Signal`; it is actually top-level `Signal` in package `stats`. §6.3 calls
`directory.localPosition()`, which existed only on `NodeDirectorySnapshot`; Task 4 adds it to
`NodeDirectory` and shares the rule.

**Placeholder scan.** Every code step carries real code. The two places that describe rather than
show — `ChartScrollbar` and the crosshair's label formatting in Task 11 — carry the exact
construction, the exact modifiers and the exact reasoning, and are marked as notes the implementer
must honour rather than as code to invent. Task 11 is the Opus task for exactly this reason.

**Type consistency.** `SeriesKey.Relay`/`.Neighbour` (Tasks 1, 4, 12); `SignalSeriesBuffer.append`
and `.snapshot()` (Tasks 2, 4, 11); `SignalSeries.totalAppended` (Tasks 2, 4, 11);
`StampedPosition.fromDegrees`/`.latitude`/`.longitude` (Tasks 1, 4, 5, 11);
`PositionOrigin.code`/`.NONE`/`.ofCode` (Tasks 1, 2, 11); `ChartGeometry.indexOfRow` (Tasks 8, 11);
`LocationAvailability.REQUIRED_PERMISSIONS` (Tasks 5, 7); `AppSettings.usePhoneLocation` (Tasks 6, 7);
`DetailMenuItem(labelRes, onClick)` (Tasks 10, 12); `Screen.Graph(subject)` (Tasks 9, 12).
`MeshStatsEngine`'s two new constructor parameters are defaulted in Task 4 and passed in Task 7, so
every intermediate commit compiles.
