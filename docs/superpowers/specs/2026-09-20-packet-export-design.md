# Design: export packet data to a file

**Date:** 2026-09-20
**Status:** draft, awaiting owner review
**Source:** brainstorming session of 2026-09-20
**Builds on:** `docs/superpowers/specs/2026-09-01-signal-graph-design.md` (this reuses
`SignalSeries`/`SignalSeriesBuffer` verbatim for the per-sample data)

---

## 1. Goal

The Graph screen (previous spec) already charts, for one relay or one neighbour,
every retained measurement: when it arrived, its RSSI and SNR, and where the
observer was standing at that instant. Nothing lets the owner take that data off
the phone. This adds a **file export** of the same measurements, reachable from
the same four screens' own overflow menus, so a session's readings can be opened
in a spreadsheet or fed into `meshlogparser` later.

This was explicitly out of scope at the Graph's own design time -
`docs/deferred-work.md`: *"Persisting series across launches... Exporting a chart
or its data is out of scope for the same reason"* (statistics remain a single
session, decision 8 of the stage-1 spec). That boundary is not being reconsidered
here - a session's statistics still vanish on relaunch - only the ability to save
a copy of the current session's readings before that happens.

## 2. Requirements

As given by the owner, refined during brainstorming:

1. Four export commands: relay detail, neighbour detail, relay list, neighbour
   list. Each lives in that view's own overflow (⋮) menu, top-right - the same
   menu the Graph command already occupies on the detail screens.
2. A detail view's export writes exactly the samples its own **Graph** command
   already charts for that subject: one row per retained measurement, carrying
   its timestamp, the remote node's RSSI and SNR, and the observer's position at
   that instant.
3. A list view's export writes the same per-sample data for **every** relay (or
   neighbour) currently tracked in that list - not a one-row-per-node summary -
   combined into a single file with a column naming which node each row belongs
   to.
4. **Observer altitude is recorded per sample too**, alongside latitude and
   longitude (added after the first design pass, at the owner's explicit
   request). Sourced from the phone's GPS fix when the phone is the position
   source, and from the local node's own newest position report (session heard,
   then database) when the node is - the same two sources `StampedPosition`
   already carries lat/lon from.
5. No altitude for the *remote* node - only RSSI, SNR, and the observer's own
   position/altitude were asked for.
6. CSV, one row per sample.
7. Tapping Export opens the system "Save As" document picker
   (`ActivityResultContracts.CreateDocument`); the user names the file and picks
   where it goes. No storage permission is requested - the first use of this API
   in the app.
8. Only currently-retained samples are exportable. The ring buffer caps at 5000
   samples per subject (the Graph screen already lives with this limit); export
   inherits it for the same reason - there is nothing older to write.

## 3. Decisions

| Decision | Choice | Reason |
| :--- | :--- | :--- |
| File format | CSV | Opens directly in a spreadsheet and in `meshlogparser`; the owner's own choice. |
| Save mechanism | SAF `CreateDocument`, not auto-save | No storage permission, standard scoped-storage pattern, and the owner's own choice over silently writing into Downloads. |
| List export granularity | Full per-sample history for every tracked node, one file | The owner's own reading of "RSSI and SNR of remote node ... or all nodes in the selected list" - a summary row would throw away exactly the history the Graph screen (and this export) exists to preserve. |
| Where observer altitude comes from | Reuse the existing altitude resolution, not a new one | `NodeDirectorySnapshot.locationInfo(num, from)` already resolves an altitude for any node from the same live-then-database precedence; `MyNodeScreen`'s own card and the header's `Alt(...)` reading (separate, already-shipped change) both call it for the local node. A third, independent resolution risks disagreeing with those two. |
| Altitude "no data" encoding | `Int.MIN_VALUE` sentinel in the ring buffer, not a second boolean array | A position can exist with no altitude (2D fix; a node with lat/lon only) - see `PositionHistory.newestWithCoordinates`'s own KDoc - so absence needs tracking independent of `source`. `PositionOrigin.NONE` already uses a reserved-value sentinel in the same class rather than a parallel array; `Int.MIN_VALUE` is a value no real altitude reaches. |
| CSV number/timestamp format | `Locale.ROOT`, ISO-8601 UTC timestamps | This is a data file, not prose - unlike `PositionLineText`, which deliberately uses the *display* locale for a human sentence, a CSV column must not grow a decimal comma depending on the phone's language, or every spreadsheet import breaks in Spanish. |
| Node label column | The same identifier the app already treats as primary | A relay's `hexId` ("0x1a"); a neighbour's `NodeId.format(nodeNum)`. Not the short name: a relay byte is not always uniquely named, and embedding a name risks CSV-unsafe characters and drags in i18n encoding this file doesn't need. |
| One CSV schema for both export kinds | Yes - the node column is always present | A detail export's rows all share one node value; keeping the same header either way means one writer, no schema branch, and a file opened without its context is still self-describing. |

## 4. Architecture

Three pieces, each already has a direct precedent in this codebase:

- **A one-shot engine command** that copies the requested subjects' `SignalSeries`
  once, distinct from the continuous single-subject `watchSeries`/`series`
  mechanism the Graph screen uses (§6).
- **A pure CSV writer**, `export/CsvWriter.kt`, Android-free like `PositionLineText`
  and `ChartGeometry` - takes rows, returns/writes lines, unit-testable on the
  JVM (§7).
- **A document-picker launcher in `MainActivity`**, alongside the existing
  permission-request launcher, that receives a `Uri` and writes through it (§8).

Nothing here introduces a new dependency; `ActivityResultContracts.CreateDocument`
and `ContentResolver.openOutputStream` are both platform APIs already available
at `minSdk 26`.

## 5. Data model changes

### 5.1 `StampedPosition` gains altitude

`stats/model/StampedPosition.kt`:

```kotlin
data class StampedPosition(val latI: Int, val lonI: Int, val origin: PositionOrigin, val altitude: Int? = null)
```

`fromDegrees` gains a matching `altitude: Int? = null` parameter. Both changes
are additive and default to the prior behaviour, so every existing call site
that does not know about altitude keeps compiling unchanged.

`NO_ALTITUDE = Int.MIN_VALUE` lives on `StampedPosition.Companion`, next to
`COORD_SCALE`, so `SignalSeriesBuffer` and `SignalSeries` share one sentinel
rather than each inventing their own.

### 5.2 `SignalSeriesBuffer` / `SignalSeries` gain a seventh array

`SignalSeriesBuffer` adds `altitudeI = IntArray(capacity)`, written
unconditionally on every `append` exactly like `latI`/`lonI`/`source` already
are (`position?.altitude ?: StampedPosition.NO_ALTITUDE`) - the same discipline
the class's own KDoc already argues for, so an evicted sample's altitude never
leaks into a reused slot.

This changes the class's own documented size budget, which is stated in
concrete numbers and needs updating along with the code (the existing figures
are decimal - "125 KB" is exactly 125,000 bytes, not a binary kibibyte - so the
replacements keep that convention): 29 bytes/sample (was 25), 145 KB per
subject at the 5000 cap (was 125 KB), ~8.7 MB for a typical 60-subject session
(was ~7.5 MB), ~37 MB worst case (was ~32 MB).

`SignalSeries` adds the matching `altitudeI: IntArray` constructor parameter and
`fun altitudeMeters(index: Int): Int? = altitudeI[index].takeIf { it != StampedPosition.NO_ALTITUDE }`.
`positionOf(index)` is extended to pass this through, so a `StampedPosition`
read back from a series carries the same altitude it was appended with.
`SignalSeries.EMPTY` gets an empty `IntArray(0)`.

### 5.3 Where each origin's altitude comes from

- **Phone** (`AndroidPhoneLocationSource.onLocationChanged`): `location.altitude`,
  guarded by `location.hasAltitude()` - GPS does not always report one.
- **Node** (`MeshStatsEngine.nodePosition()`): today resolves only lat/lon via
  `NodeDirectory.localPosition()`. A sibling function, `localAltitudeOf`, is
  added to `stats/model/LocalPosition.kt` next to the existing `localPositionOf`,
  resolving from the identical winning report
  (`positions[num]?.newestWithCoordinates ?: nodes[num]?.dbPosition`) so the two
  functions can never disagree about which report they are reading from, even
  though they are called separately. `NodeDirectory` gains `fun localAltitude(): Int? =
  localAltitudeOf(localNodeNum, positions, nodes)` alongside `localPosition()`.

## 6. The engine: one-shot export

`watchSeries`/`series` intentionally never publish more than one subject at a
time - continuously republishing every subject's series was rejected as
expensive when the Graph screen was designed. Export is a single user action,
not a continuous subscription, so it gets its own path rather than reusing that
one:

```kotlin
// New command
data class RequestExport(val keys: List<SeriesKey>) : Command

// New published state, mirroring `series`'s shape
private val _exportResult = MutableStateFlow<Map<SeriesKey, SignalSeries>?>(null)
val exportResult: StateFlow<Map<SeriesKey, SignalSeries>?> = _exportResult.asStateFlow()

fun requestExport(keys: List<SeriesKey>) { commands.trySend(Command.RequestExport(keys)) }
fun exportConsumed() { _exportResult.value = null }

// In the command loop
is Command.RequestExport ->
    _exportResult.value = command.keys.associateWith { key -> seriesBuffers[key]?.snapshot() ?: SignalSeries.EMPTY }
```

A key with no buffer (nothing heard yet for that subject) resolves to
`SignalSeries.EMPTY`, the same fallback `publishWatchedSeries` already uses for
the equivalent case - an export button is never disabled while empty, it just
writes a header with no rows.

The caller collects the first non-null `exportResult`, calls `exportConsumed()`
immediately (clearing it, the same rule `watchSeries(null)` already applies to
`_series`, so a stale export never reappears on the next open), and only then
launches the document picker with the data already captured in memory - the
picker's own round trip (the user browsing folders) must not block on anything
still living inside the engine's coroutine confinement.

## 7. CSV format

One schema for both detail and list exports:

```
node,timestamp_utc,rssi_dbm,snr_db,observer_lat,observer_lon,observer_altitude_m,observer_source
0x1a,2026-09-20T18:32:04Z,-94,-7.5,40.330012,-3.750441,612,node
```

- `node` - `RelayStats.hexId` for a relay, `NodeId.format(nodeNum)` for a
  neighbour. Constant down a detail export's whole file; varies down a list
  export's.
- `timestamp_utc` - ISO-8601, UTC, seconds precision. Not epoch millis: a
  spreadsheet needs an extra step to make millis readable at all, and not local
  time: a file opened later, possibly on a different machine, must not depend on
  the time zone the phone happened to be in when it was written.
- `rssi_dbm`, `snr_db` - the raw stored floats, `Locale.ROOT` formatted (`.`
  decimal separator, regardless of the app's display language - see §3).
- `observer_lat`, `observer_lon` - decimal degrees, `Locale.ROOT`, blank when the
  sample carried no position at all.
- `observer_altitude_m` - blank when the position had no altitude, per §5.
- `observer_source` - `node`, `phone`, or blank when the sample carried no
  position at all (mirrors `PositionOrigin`).

`export/CsvWriter.kt` is the one place that owns this: a pure function from a
`List<ExportRow>` (a new, small, Android-free data class holding exactly these
eight fields) to text, so it is unit-testable without touching `SignalSeries` or
Android at all. Building the `List<ExportRow>` from a `Map<SeriesKey, SignalSeries>`
and the directory (for the node label) is a second, separate, equally pure
function - keeping "what a row looks like" apart from "how to fill 5000 of them
from a ring buffer's arrays" is what makes both independently testable.

## 8. UI wiring

### 8.1 Detail screens

`DetailScreen`'s existing `menuItems: List<DetailMenuItem>` is the same list
`action_graph` already occupies (`MeshRelayNavHost.kt`). A second
`DetailMenuItem(R.string.action_export) { onExportSeries(subject) }` is added
next to it, where `onExportSeries` resolves `subject` to the one `SeriesKey`
already used to open the Graph screen for it.

### 8.2 List screens

`StatsTopBar`'s overflow menu (`RelayListScreen`, `NeighbourListScreen`) gains
one more `DropdownMenuItem`, wired to `onExportRelays()` /
`onExportNeighbours()`, which build the key list from every subject currently in
`snapshot.relays` / `snapshot.neighbours`.

### 8.3 The Save As flow

`MainActivity` gains a `rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv"))`
launcher alongside the existing permission one. The tap handler:

1. Calls `engine.requestExport(keys)`.
2. Collects the first non-null `exportResult`, calls `exportConsumed()`, and
   builds the row list (§7).
3. Launches the picker with a suggested filename (§8.4), holding the row list in
   memory until the picker returns.
4. On a non-null result `Uri`, opens it via `contentResolver.openOutputStream(uri)`
   and writes through `CsvWriter`. A `null` result (the user cancelled) is a
   no-op.

### 8.4 Suggested filenames

`meshrelay_relay_0x1a_20260920_183204.csv` (detail, relay),
`meshrelay_neighbour_a1b2c3d4_20260920_183204.csv` (detail, neighbour - `NodeId.format`'s
leading `!` stripped for the filename only; the CSV's own `node` column keeps it),
`meshrelay_relays_20260920_183204.csv` / `meshrelay_neighbours_20260920_183204.csv`
(list). The picker lets the user rename before saving; this is only the default.

## 9. Strings

One label, reused across all four menu entries, exactly as `action_graph` already
is: `action_export` = "Export data" (en) / "Exportar datos" (es).

## 10. Testing

- `CsvWriter` - pure JVM: header line, one row, multiple rows, blank fields for
  null lat/lon/altitude/source, `Locale.ROOT` formatting proven independent of
  the default locale (the same class of bug `PositionLineText`'s tests already
  guard against, in the opposite direction).
- The row-building function - pure JVM: a detail export's rows all share one
  node label; a list export's vary; a subject with no buffered samples produces
  a header with zero rows.
- `MeshStatsEngine` - extend `MeshStatsEngineTest.kt`: `requestExport` returns
  the buffered series for exactly the requested keys; a key nothing has been
  heard for resolves to `SignalSeries.EMPTY`, mirroring the existing watched-series
  coverage; requesting does not disturb an unrelated in-progress `watchSeries`.
- `SignalSeriesBufferTest.kt` / `SignalSeriesTest.kt` - extend for the new
  altitude array: append with and without altitude, sentinel round-trips to
  `null`, an evicted slot's old altitude never survives a wraparound (mirrors
  the existing position-leak test if one exists for `latI`/`lonI`).
- `NodeDirectoryTest.kt` - extend alongside the existing `localPosition()`
  coverage (`the local position is resolved through the local node number`,
  `with no local node number there is no local position`, etc.) with the
  matching cases for `localAltitude()`.
- No UI test for the document-picker flow itself, consistent with this
  project's own stated boundary (`docs/verifying.md`: *"CI proves it compiles and
  the pure logic holds. It has never once seen a screen."*) - verified on the
  phone instead: tap Export on each of the four views, confirm the picker opens,
  confirm the written file's contents.

## 11. Out of scope

- **Persisting series across app launches.** Unchanged from the Graph's own
  spec - export saves a copy of the current session; it does not make the
  session itself durable.
- **Any format besides CSV**, and **any destination besides the system picker**
  (no auto-save, no share-sheet) - both were explicit either/or choices in §3.
- **Altitude, or any other field, for the remote node.** Only the observer's own
  position gained a new field; the remote node's row is RSSI/SNR/timestamp,
  unchanged from the Graph screen's own data.
- **Exporting from the Graph screen itself.** The four menus named in the
  requirements are the only entry points; the Graph screen's own overflow (if it
  gains one later) is a separate decision.
