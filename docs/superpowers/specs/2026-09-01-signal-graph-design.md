# Design: the Graph command — RSSI and SNR against time

**Date:** 2026-09-01
**Status:** approved, ready for the implementation plan
**Source:** `Pic1.pdf` (owner's hand drawing) plus the brainstorming session of 2026-09-01
**Builds on:** `docs/superpowers/specs/2026-08-26-mesh-relay-android-design.md`

---

## 1. Goal

The Relay and Neighbour detail screens answer *how strong is this link* with four
numbers and a bar: min, max, avg, last. They do not answer *what has it been
doing* — whether a relay is steady, drifting, or dropping off as the sun sets.

This adds a second view of the same measurements, plotted against time, reachable
from an overflow menu on both detail screens. It also records, with every
measurement, **where the observer was standing when that measurement was taken**,
so a point on the chart can be opened on a map.

Nothing here is a port. `mesh_stats` has no chart; this is new work in the
application the port produced.

## 2. Requirements

As given by the owner:

1. A `⋮` overflow button in the top right of the **Relay** detail view and of the
   **Neighbour** detail view, showing a list of available commands.
2. One command, **Graph**, implemented **once** and shared by both subjects — not
   duplicated per subject.
3. The Graph view follows `Pic1.pdf`: title, two option switches top right, the
   RSSI and SNR bars, a `Time` field above the graph area, the scrollable graph
   area, a `Time` field below it.
4. **Freeze** holds the drawing. Data keeps being collected while frozen, and the
   graph is redrawn complete as soon as it is switched off.
5. **Auto scale** makes the left and right borders of the RSSI and SNR bars the
   observed min and max of each parameter. Switch off by default.
6. Bar and line colours are the same as everywhere else in the application.
7. The graph area fills most of the view. The oldest measurement is at the bottom,
   the newest at the top. It scrolls.
8. The `Time` field above the graph shows the timestamp of the topmost point
   displayed; the `Time` field below it shows the timestamp of the bottom point.
9. The graph area is interactive: touching it draws a horizontal line carrying the
   timestamp above it, the RSSI and SNR values below it, and a globe at its right.
10. The globe opens Google Maps at the observer's position for that line's time.
11. Two lines, one for RSSI and one for SNR, each in its metric's colour.
12. The `⋮` pictogram is the vertical three-dot glyph.

Settled during brainstorming:

13. **One measurement is one pixel row.** Only the rows inside the scrolled window
    are drawn. A scale coefficient (2 px, 4 px per measurement) is deliberately
    *not* exposed yet — it exists as a parameter so it can become a control later.
    The scale coefficient may be a fraction number (e.g 0.1) making scaling available.
14. Series are kept for **every** relay and neighbour, from the first packet,
    whether or not a Graph is open.
15. Sample retention: **5000 measurements** per relay and per neighbour.
16. Each stored measurement carries its own coordinates **and a flag naming the
    source**, `node` or `phone`. There is no search backwards for a past position.
17. A new setting, **Use phone location**, **on** by default. On, each measurement
    is stamped with the phone's latest fix. Off, with the local node's position.
18. Off never falls back to the phone; on falls back to the node when no fix is
    available. The source flag records what was actually used.
19. The node's position is whatever the node already tells us —
    `POSITION_APP` packets as they arrive, then the node-database entry. No timers,
    no generated mesh traffic.
20. Freeze and Auto scale are rendered as a label on the left with a switch on the
    right, stacked and right-aligned under the app bar.

## 3. Decisions

| Decision | Choice | Reason |
| :--- | :--- | :--- |
| Where the Graph lives | A full-screen destination, `Screen.Graph(subject)` | A third tab inside `DetailScreen` loses ~180 dp to the summary block and tab row — the space `Pic1.pdf` spends on the plot — and cannot carry its own title or its own two switches. A bottom sheet cannot host a full-height scroller |
| Sharing between subjects | The screen takes resolved data, never a `DetailSubject` | A component that cannot see which subject it is drawing cannot diverge per subject. Requirement 2 is enforced by the type signature rather than by discipline |
| Drawing | Virtualised canvas; the screen owns its scroll offset | 5000 rows in one `Canvas` inside `verticalScroll` is a layer far past the maximum texture size and fails on real hardware. Owning the offset is also what makes the custom scrollbar and the crosshair possible |
| Charting library | None | The build carries an explicit no-new-dependencies rule (`app/build.gradle.kts:56-65`), and no library draws a crosshair with a per-point map link anyway |
| Series storage | Primitive arrays in a ring buffer, engine-confined | An object per measurement costs ~280 KB per node at 5000; the primitive arrays, position included, cost ~125 KB. See §5.3 |
| Coordinate storage | Scaled `Int` at 1e-7 degrees | The protobuf's own representation. Lossless, four bytes, and it respects the standing rule against `Float` coordinates recorded at `PositionReport.kt:24` |
| Series delivery to the interface | A separate watch/publish channel, not `StatsSnapshot` | Widening `StatsSnapshot` would copy every node's series several times a second for the list screens that need none of it. The Graph watches one key and gets one copy |
| Position capture | At fold time, inside the engine | Requirement 16. Capturing later means searching, and searching is what storing the position per sample exists to avoid |
| Phone location API | Platform `LocationManager` | `play-services-location` is a new dependency and a proprietary blob; this application is otherwise F-Droid-clean |
| Overflow glyph | The existing `R.drawable.ic_action_more` | Already three vertically stacked dots, hand-authored for this project, already the overflow on both list screens. One glyph, one meaning, application-wide |

## 4. Architecture

```
      settings                    location/                    stats/
  usePhoneLocation ──┐      PhoneLocationSource ──┐        MeshStatsEngine
                     │        (latest fix only)   │      ┌──────────────────┐
                     └──── Command.SetPositionMode┼─────►│ relays           │
                                                  │      │ neighbours       │
                            Command.SetPhoneFix ──┘      │ directory        │
                                                         │ series ◄── NEW   │
                                                         └────────┬─────────┘
                                                    snapshot ◄────┤
                                                      series ◄────┘ (one watched key)
                                                         │
   ui/detail/DetailScreen ── ⋮ ──► Screen.Graph ──► ui/graph/SignalGraphScreen
                                                         │
                                          ChartGeometry (pure) ── SignalChart (canvas)
```

The engine keeps its existing shape: one coroutine owns all mutable state,
commands arrive through a channel, snapshots are built only when someone is
watching. The two new inputs (position mode, phone fix) arrive as commands, the
way `skippedRelayNodes` already does. The new output is published under the same
"nothing subscribed means nothing to build" rule the snapshot follows.

## 5. Data

### 5.1 What a measurement is

A measurement exists only when a packet carried decodable signal information.
`PacketClassifier.Signal` is `(snr: Float, rssi: Float)` — both are always present
together — so a measurement always has both values, and the crosshair can always
report both without a "not available" case.

### 5.2 `SignalSeriesBuffer` — new, `stats/`

A fixed-capacity ring buffer, mutable, confined to the engine's coroutine exactly
as `relays` and `neighbours` already are. Six parallel arrays:

```kotlin
class SignalSeriesBuffer(capacity: Int = MAX_SAMPLES) {
    private val times  = LongArray(capacity)
    private val rssi   = FloatArray(capacity)
    private val snr    = FloatArray(capacity)
    private val latI   = IntArray(capacity)    // 1e-7 degrees
    private val lonI   = IntArray(capacity)
    private val source = ByteArray(capacity)   // 0 none · 1 node · 2 phone

    fun append(atMillis: Long, rssi: Float, snr: Float, position: StampedPosition?)
    fun snapshot(): SignalSeries
    fun clear()

    companion object { const val MAX_SAMPLES = 5000 }
}
```

`source == 0` is the only "absent" marker; `latI`/`lonI` need no sentinel because
they are never read when the source is none.

Stored oldest-first, which is the natural append order. Turning that into
"newest at the top" is `ChartGeometry`'s job, not storage's.

### 5.3 Memory

25 bytes per measurement × 5000 = **125 KB per relay or neighbour**.

- A typical session (≈60 relays and neighbours between them): **≈7.5 MB**.
- The theoretical worst case (all 256 relay bytes seen, plus neighbours): **≈32 MB**.

This is the largest allocation in the application and it is stated here rather than
discovered later. `MAX_SAMPLES` is one constant in one file; if the field says 5000
is too much, that is the line to change. An object per measurement would cost more than twice as
much in the same worst case, which is why the arrays are primitive.

### 5.4 `SignalSeries` — new, `stats/model/`

The immutable value handed to the interface: copies of the six arrays trimmed to
`size`, plus the observed `SignalStats` for each metric so the screen has the auto
scale range without a second pass. Read-only accessors (`atMillis(i)`,
`rssi(i)`, `snr(i)`, `positionOf(i)`), never the raw arrays — the same rule
`NodeDirectorySnapshot` follows.

### 5.5 `StampedPosition` and `PositionOrigin` — new, `stats/model/`

```kotlin
enum class PositionOrigin { NODE, PHONE }
data class StampedPosition(val latI: Int, val lonI: Int, val origin: PositionOrigin)
```

`PositionOrigin` is deliberately not `PositionSource`, which already exists and
means something different (`CURRENT` vs `DB` — how fresh a *node's* position is).

### 5.6 `NeighbourStats` narrows

`NeighbourStats.snr`/`rssi` are `SignalHistory` today, whose `samples` list nothing
in `main/` reads. With the paired series in place those samples are duplicate
storage, so both fields become `SignalStats`. `TelemetryRecord` keeps
`SignalHistory` — telemetry genuinely needs per-metric sample lists — so the type
itself stays.

## 6. The engine

### 6.1 New state

```kotlin
private val series = LinkedHashMap<SeriesKey, SignalSeriesBuffer>()
private var positionMode = PositionMode.PHONE
private var phoneFix: StampedPosition? = null
private var watchedSeries: SeriesKey? = null
```

`SeriesKey` is a sealed interface in `stats/` — `Relay(relayByte)` and
`Neighbour(nodeNum)`. It mirrors `DetailSubject` but must not be it: `stats/` does
not import `ui/`. The navigation host maps one to the other.

**Declaration order matters.** `MeshStatsEngine`'s `init` block hands `this` to a
coroutine while the constructor is still running, and its own comment warns that
any field declared *below* that block is read at its default value by a loop that
has already started. Every field above is added above the `init` block.

### 6.2 New commands

`SetPositionMode`, `SetPhoneFix`, `WatchSeries(key: SeriesKey?)`. The first two
arrive from flows collected in `init`, exactly as `SetSkipped` already does. All
three go through the existing channel, so all three land on the owning coroutine.

### 6.3 Folding

`foldRelayed` and `foldDirect` each gain one call, guarded by the same
`signal != null` test that already guards the statistics:

```kotlin
series.getOrPut(key) { SignalSeriesBuffer() }
      .append(atMillis, signal.rssi, signal.snr, positionForSample())
```

`positionForSample()` resolves requirement 18:

| Mode | First choice | Fallback | Result if neither |
| :--- | :--- | :--- | :--- |
| `PHONE` | `phoneFix` (origin `PHONE`) | `directory.localPosition()` (origin `NODE`) | `null` — sample stored with no position |
| `NODE` | `directory.localPosition()` (origin `NODE`) | none | `null` |

`PHONE` falls back because a blank globe for the first minute of every session —
before the first fix lands, or after the permission was refused — is worse than a
slightly-less-precise pin, and the origin flag keeps it honest. `NODE` does not
fall back, because turning the setting off is a request that the phone's GPS not
be used, and quietly using it anyway would break that request.

`directory.localPosition()` already prefers a `POSITION_APP` report heard this
session over the node-database entry (`NodeDirectorySnapshot.locationInfo`,
precedence rules 1–2). That is requirement 19 in full; nothing new is needed.

### 6.4 Publishing the watched series

```kotlin
private val _series = MutableStateFlow<SignalSeries?>(null)
val series: StateFlow<SignalSeries?>
fun watchSeries(key: SeriesKey?)
```

In the loop's build step, after the snapshot: if `watchedSeries != null` and
`_series.subscriptionCount.value > 0`, publish `series[key]?.snapshot()`. One
node's 125 KB, copied only while a chart is open — never the 7.5 MB the snapshot
route would have cost.

`resetStatistics()` clears `series` alongside `relays` and `neighbours`. A reset is
a fresh measurement, and a chart outliving it would be showing a session that no
longer exists.

## 7. Location

New package `location/`, three files:

- **`PhoneLocationSource`** — interface, `val fix: StateFlow<StampedPosition?>`
  plus `fun start()` / `fun stop()`. Plain-JVM, so the engine wiring is testable
  without a device.
- **`AndroidPhoneLocationSource`** — platform `LocationManager`, `GPS_PROVIDER`
  and `NETWORK_PROVIDER`, requested at **10 s / 10 m** (see §7.1). Latest fix only;
  no history, because §5 stores the position with the sample instead.
- **`LocationAvailability`** — the permission array and the granted check, in the
  shape `BluetoothAvailability` already established.

Manifest gains unrestricted `ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`
(the existing `maxSdkVersion="30"` entry, which exists for pre-12 BLE scanning,
is superseded). `BLUETOOTH_SCAN` keeps `neverForLocation` — that flag asserts
scans are not used to derive location, which remains true.

**Two consequences, stated rather than buried.** This changes the application's
permission set, which is visible on any store or F-Droid-style listing. And
continuous location costs battery on a device already holding a BLE link — at the
rate chosen in §7.1 the GNSS stays warm, which is the price of the resolution this
tool needs. The *Use phone location* switch is the escape hatch, and turning it off
stops the updates rather than merely ignoring them.

Permissions are requested at first connect, alongside the Bluetooth ones. A
refusal is not an error: the setting stays on, no fix ever arrives, and every
sample falls back to the node under §6.3.

### 7.1 Update rate

`requestLocationUpdates` takes a minimum time and a minimum distance, and both
gate every delivery: a fix arrives no sooner than `minTime` after the last one,
**and** only once the phone has moved at least `minDistance`. A phone on a table
therefore produces no updates at all, which is right — it has not moved.

Only `minTime` is a battery lever. `minDistance` filters after the GNSS has already
computed a fix, so it suppresses a callback, not the radio; it is a de-duplication
rule, not a power saving. `minTime` is what lets the platform power the GNSS down
between fixes, at its own discretion and with wide variation between vendors.

**Default: `minTime` 10 s, `minDistance` 10 m**, as two named constants in
`AndroidPhoneLocationSource`.

The reason is the use this application is put to. The question being asked in the
field is *where exactly was I standing when this relay read −92 dBm*, asked while
walking a ridge to place a repeater. At walking pace, roughly 1.4 m/s, 10 m is
about seven seconds — so measurements taken a few paces apart get distinct pins.
A coarser 100 m would stamp a whole hillside of measurements with one coordinate
and drop one pin for all of them, which would answer a different and less useful
question. The cost is that the GNSS stays warm rather than duty-cycling.

Constants rather than a setting: they can become one later with no restructuring,
and a rate control is more surface to build, test and translate than a value most
users would set once. Should the field say otherwise, that is the change to make.

### 7.2 Setting

`AppSettings.usePhoneLocation: Boolean = true`, persisted by the existing
repository, edited by a switch in `SettingsScreen`. `AppContainer` starts and stops
the source as the setting changes and feeds `Command.SetPositionMode`.

## 8. The Graph screen

### 8.1 Navigation

`Screen.Graph(subject: DetailSubject)`, pushed from the detail screen's overflow.
`backStackSaver` gains `TAG_GRAPH_RELAY = 6` and `TAG_GRAPH_NEIGHBOUR = 7`,
following the existing rule that each subject gets its own tag rather than a shared
tag plus a discriminator. The saver already drops entries it cannot decode, so an
older build restoring a newer bundle degrades to the root rather than crashing.

### 8.2 Layout

```
┌──────────────────────────────────┐
│ ←  Relay 0xcd · PQPL1            │  app bar: back + resolved title
│               Freeze      [ ○]   │  label left, switch right,
│               Auto scale  [●]    │  right-aligned stack
│ RSSI  min max avg last           │  SignalBlock, unchanged component
│ ▓▓▓▓░░░░│░░▌░░░░░░               │
│ SNR   min max avg last           │
│ ░░░░░░░▌░░░░░░░░░░               │
│ Time: 13:01:13 21.08.2026        │  timestamp of the topmost row
│ ┌────────────────────────────┬─┐ │
│ │13:00:05                    │▲│ │
│ │─────────────────────🌐─────│ │ │  crosshair + globe
│ │-92 dBm  4.5 dB             │█│ │
│ │      ╱╲    ┊               │ │ │  RSSI and SNR polylines
│ │     ╱  ╲  ┊                │ │ │
│ │    ╱    ╲╱                 │▼│ │  custom scrollbar
│ └────────────────────────────┴─┘ │
│ Time  12:55:00 21.08.2026        │  timestamp of the bottom row
└──────────────────────────────────┘
```

The screen's signature carries no `DetailSubject`:

```kotlin
@Composable
fun SignalGraphScreen(
    title: String, subtitle: String,
    series: SignalSeries?,
    rssiStats: SignalStats, snrStats: SignalStats,
    onBack: () -> Unit,
)
```

### 8.3 `ChartGeometry` — pure, unit-tested

Every number the canvas needs, with no Compose types in the signatures, in the
shape `GaugeGeometry` already established:

- `visibleRows(scrollPx, viewportPx, size, pxPerSample)` → the index window, one
  row of overscan each side so the polyline segments join across the edges.
- `yOf(row, scrollPx, pxPerSample)` and `rowAt(y, scrollPx, pxPerSample)` — the
  second is what turns a touch into a measurement.
- `xOf(value, min, max)` — `SignalScales.fraction`, so the chart and the bars
  cannot drift apart.
- `scaleRange(stats, autoScale)` → fixed `SignalScales` bounds, or
  `stats.minVal..stats.maxVal`.
- `anchorAfterAppend(scrollPx, appended, pxPerSample)` — see §8.6.

`pxPerSample` is a parameter fixed at `1` by its single caller. Requirement 13:
present in the geometry, absent from the interface.

### 8.4 Auto scale

Off: `SignalScales.SNR_MIN..SNR_MAX` and `RSSI_MIN..RSSI_MAX`, the ranges every
other screen uses.

On: each metric's whole-session `minVal..maxVal` — the same two figures the bars
print beside themselves. Bars and plot share one range, so neither can misrepresent
the other, and every retained sample necessarily falls inside it (the retained
samples are a subset of the session the statistics cover). A degenerate range
(`min == max`, one sample or a perfectly flat relay) falls back to the fixed scale;
dividing by a zero span would put every point at the left edge.

### 8.5 Colours

`RssiTrack` and `SnrTrack` from `ui/theme/Color.kt`, unchanged — the same green
and blue the gauges on every list use. Colour, not dash pattern, is what
distinguishes the two lines (requirement 11).

### 8.6 Scrolling and freeze

The screen owns `scrollPx` and drives it through `Modifier.scrollable`. Row 0 is
the newest and sits at the top when `scrollPx == 0`.

- **New samples while scrolled to the top**: the view stays at the newest, which is
  what a live chart should do.
- **New samples while scrolled down**: `scrollPx` advances by
  `appended × pxPerSample`, so the measurement under the reader's eye does not move.
  Data arriving must never yank the view.
- **Freeze**: the screen captures the series and ignores flow updates. The engine
  keeps collecting — freeze is a drawing state, not a collection state.
- **Unfreeze**: the live series is adopted and `scrollPx` re-anchored by the same
  rule, so the reader keeps their place across the switch.

Freeze is `rememberSaveable`; a rotation must not silently resume a chart the
reader deliberately stopped.

### 8.7 The crosshair and the globe

A pointer gesture on the graph area sets `crosshairY`, and a drag moves it. From it,
`ChartGeometry.rowAt` gives the measurement, and therefore its timestamp, its two
values and its stored position.

Drawn: a horizontal rule at that y, the timestamp above it, the RSSI and SNR values
below it — each in its metric's colour, so no legend is needed.

The globe is a real `IconButton` offset into the same `Box`, **not** a shape painted
into the canvas: a painted glyph has no touch target, no ripple and no accessible
name. It is enabled only when that measurement stored a position, and opens
`MapLinks.googleMaps`, which already formats coordinates under `Locale.ROOT` — a
Spanish locale's decimal comma would otherwise break the query string. Its content
description names the origin, so "where this pin came from" is answerable rather
than a mystery.

The scaled `Int` coordinates are converted back to degrees for the link by
multiplying in `Double`, the conversion `PositionReport` already documents.

### 8.8 Empty and thin states

- **No measurements**: the existing `R.string.detail_no_signal_data`, centred. The
  two switches stay, disabled.
- **One measurement**: a single point, no line, and both `Time` fields showing that
  one timestamp.
- **Subject cleared by a reset while the chart is open**: the series goes null and
  the screen falls to the empty state, matching how `DetailScreen` already survives
  a reset under it.

## 9. The overflow menu

`DetailScreen` gains one parameter, `menuItems: List<DetailMenuItem>`, rendered as
an `IconButton` with `R.drawable.ic_action_more` and a `DropdownMenu` — the same
construction `StatsTopBar` uses, and the same `R.string.action_more` description.
An empty list draws no button.

Today the navigation host passes exactly one item, Graph. A second command is one
more entry in that list.

`DetailScreen`'s KDoc carries a rule from the original port that no later task may
edit the file. That rule described the port's own task sequencing, which has
finished; this design edits the file deliberately, and the stale paragraph is
removed rather than worked around.

## 10. Strings

New in `values/` and `values-es/`: `action_graph`, `graph_title_relay`,
`graph_title_neighbour`, `graph_freeze`, `graph_auto_scale`, `graph_time`,
`graph_open_map`, `graph_position_from_node`, `graph_position_from_phone`,
`settings_use_phone_location`, `settings_use_phone_location_summary`.
`StringsParityTest` already fails the build on a missing Spanish key.

## 11. Testing

| Test | Covers |
| :--- | :--- |
| `SignalSeriesBufferTest` | append; eviction at `MAX_SAMPLES`; the position and its origin round-tripping; a sample with no position; `clear` |
| `SignalSeriesTest` | the immutable snapshot's accessors and its trimming to `size` |
| `MeshStatsEngineTest` (additions) | series collected for relays and neighbours with no watcher present; `PHONE` mode falling back to the node; `NODE` mode **not** falling back to the phone; reset clearing series; watch publishing only while subscribed |
| `ChartGeometryTest` | the visible window at several offsets, including both ends; `yOf`/`rowAt` round-tripping; `xOf` against fixed and auto ranges; the degenerate `min == max` range; the two `Time` values; `anchorAfterAppend` |
| `LocationAvailabilityTest` | the permission array per API level, following `BluetoothAvailability`'s own shape (which has no test today; this adds one for the new type only) |
| `SettingsRepositoryTest` (addition) | `usePhoneLocation` persisting and defaulting to `true` |
| `BackStackTest` (addition) | `Screen.Graph` for both subjects surviving save/restore; an unknown tag still dropped |

Compose previews for the Graph screen: populated, one sample, empty, auto scale on,
frozen, and dark theme — the set every other screen in this application carries.

Verification on hardware follows `docs/verifying.md`: the chart is read on the
phone, in Spanish too, before this is called done.

## 12. Out of scope

- The zoom control (2×, 4× pixels per measurement). The geometry takes the
  parameter; nothing sets it. Deferred at the owner's request.
- Persisting series across launches. Statistics remain a single session, per
  decision 8 of the stage-1 spec.
- Exporting a chart or its data.
- A chart for a remote node (`Screen.RemoteNode`). The measurements there belong to
  the relay that carried them, which already has its own chart.
