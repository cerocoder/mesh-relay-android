# Design: Mesh Relay for Android — stage 1 port of `mesh_stats`

**Date:** 2026-08-26
**Status:** approved, ready for the implementation plan
**Ports:** `mesh_stats/mesh_stats.py` (2637 lines, GPL-3.0) and `mesh_stats/README.md` / `docs/USER_GUIDE.pdf`
**Skeleton:** `mesh-test-android` — architecture, BLE stack, build recipe, CI
**Target repository:** `mesh-relay-android/`

---

## 1. Goal

`mesh_stats` answers one question well: **which intermediate relays carry traffic
toward my node, how strong is each of them, and whose traffic does each carry.**
It answers it in a terminal, on a laptop, tethered to a node by USB.

The question is a field question. It is asked while walking a ridge, standing on a
roof, or deciding where a repeater should go — with a phone in hand and the node in
a pocket over Bluetooth. This stage moves the tool to where the question is asked.

The port is not a translation of a terminal into a phone. The data model and the
packet-handling loop carry over intact; the interface is designed again.

## 2. Requirements

As given:

1. Realise **all** features of the ported application, using its documentation.
2. Realise the architecture, internal data representation and packet-handling loop
   of `mesh_stats`.
3. Fit that data and those algorithms into the Android architecture presented by
   `mesh-test-android`, using it as the skeleton.
4. Screens: `Relays list`, `Relay detail` with sub-views `Matching nodes` and
   `Remote nodes`, and its sub-view `Remote node details`; plus `Settings`. Use
   graphics and icons from open libraries.
5. The application is internationalised, with **English** and **Spanish** at launch.

Settled during brainstorming:

6. Transports: **BLE plus the built-in demo emulator**. No USB-serial, no TCP.
7. **No record/replay** in this stage. Demo scenarios are hand-written.
8. Persistence: **settings and the skip-list only**. Statistics are a snapshot of one
   measurement session and reset on launch, exactly as in the terminal tool.
9. The **Neighbours** view — direct, unrelayed traffic, present in `mesh_stats` but
   absent from its documentation — becomes a bottom-navigation tab beside Relays.
10. The signal gauge reproduces **both** TUI modes, Simple and Complex.
11. Statistics keep being collected in the background, under a foreground service.
12. **No periodic redraw tick.** The interface reacts to packets and to user input.
13. Detail opened from Neighbours is a **Neighbour detail**, not a Relay detail —
    a different shape, not merely a different title.
14. Design in the seams for a later stage: node-DB save/load and packet
    save/load/replay. Seams only; no implementation.
15. Signal scales cover the ranges Meshtastic actually produces:
    **SNR −20…+15 dB, RSSI −130…−30 dBm**.

## 3. Decisions

| Decision | Choice | Reason |
| :--- | :--- | :--- |
| Core shape | Confined-state actor + immutable snapshot | One coroutine owns all mutable state. No locks exist to get wrong. Answers the open item in `devel-notes.md §5.5`: production runs on `Dispatchers.Default` while every test is single-threaded, so concurrency defects are invisible to the suite — confinement removes the class instead of testing it |
| Core purity | `stats/**` is pure Kotlin, no Android imports | Every core file is JVM-testable, and files stay small enough to hand to parallel agents |
| Reactivity | Conflated dirty-signal → snapshot, `WhileSubscribed(5_000)` | No timer. While no screen is subscribed, zero snapshots are built; ingestion continues |
| Relative-time text | 1 Hz ticker scoped to the resumed lifecycle, stepping to 30 s above a minute | The only thing that genuinely needs time to pass. Stops when backgrounded |
| Gradle layout | Single `:app` module | Matches the skeleton. Package boundaries are enough to parallelise; module boundaries would only add build complexity |
| New dependencies | **None** | `devel-notes.md §1`: the AGP 9 / Gradle 9.7 / compileSdk 37 / protobufs chain cost five CI runs and holds together only as a whole. Every addition re-opens it |
| Settings storage | `SharedPreferences` behind a repository | Consequence of the above. A handful of scalars and a node-id set; DataStore would buy nothing here |
| Navigation | Hand-rolled `List<Screen>` back stack | Consequence of the above. Six destinations in a strict stack, ~80 lines in one file |
| Language override | Locale-overridden `Context` at the composition root | Consequence of the above. `AppCompatDelegate.setApplicationLocales` would pull in `androidx.appcompat` |
| Protocol vocabulary | Stays English, untranslated | Precedent from `2026-08-24-frame-inspector-design.md`: a home-made label that diverges from the schema costs more than it saves. Port numbers, roles, hardware models and proto field names are looked up in the schema |
| Licence | GPL-3.0 | `mesh_stats` is GPL-3.0 and this is a derivative work |
| Node database | Built from handshake `node_info` frames, replaced wholesale at each committed want_config round; identity heard live over `NODEINFO_APP` is kept in a separate store, merged field by field (see `2026-09-03-node-storage-split-design.md`) | The Android equivalent of `interface.nodesByNum` |
| Signal presence | A packet carries signal information **iff `rx_rssi != 0`** | See §6.3. Wire cannot distinguish an absent proto3 scalar from its default; the Python dict layer can |
| Sample history | Capped at 500 samples per metric | Python keeps it unbounded; a multi-hour session on a phone would leak |

## 4. Global constraints

Exact values, inherited from `mesh-test-android` and not to be moved:

- AGP 9.3.1, Gradle 9.7.1, Kotlin 2.4.10 (carried by AGP 9), JDK 21.
- `compileSdk = 37`, `targetSdk = 36`, `minSdk = 26`. Compose BOM 2026.06.01.
- `org.meshtastic:protobufs:2.7.26`, `com.squareup.wire:wire-runtime:6.4.5`.
- Nordic `ble` / `ble-ktx` 2.11.0, `scanner` 1.6.0.
- `testOptions { unitTests.isReturnDefaultValues = true }` — without it every
  `android.util.Log` call in a JVM test throws "not mocked".
- CI caches `~/.android/debug.keystore`, otherwise successive builds cannot be
  installed over one another.

Project identity:

- `applicationId` / namespace: `com.cerocoder.meshrelay`
- Application label: `Mesh Relay`

**No Russian anywhere** — not in code, comments, documents, commit messages or
resources. Where the skeleton is copied, its comments are translated to English
with their reasoning preserved, not summarised away.

## 5. Architecture

```
        BLE node  ──┐
                    ├─▶ RadioTransport ─▶ RadioConnectionManager ─▶ Flow<TimestampedFrame>
   demo scenario ──┘        (bytes)          (handshake, FromRadio)          │
                                                                             ▼
                                                            ┌────────────────────────────┐
                                                            │      MeshStatsEngine       │
                                                            │  single coroutine owns:    │
                                                            │   relays, neighbours,      │
                                                            │   counters, NodeDirectory  │
                                                            └────────────┬───────────────┘
                                                              dirty (CONFLATED)
                                                                         ▼
                                                            StateFlow<StatsSnapshot>
                                                              WhileSubscribed(5s)
                                                                         ▼
                                                                  Compose screens
```

Layers, and what each may depend on:

| Layer | Package | May import |
| :--- | :--- | :--- |
| Transport | `ble/`, `transport/` | Android, Nordic BLE |
| Session | `connection/` | Wire protobuf, coroutines |
| Core | `stats/` | **Wire protobuf and coroutines only — no Android** |
| Persistence | `settings/` | Android `SharedPreferences` |
| Interface | `ui/` | Compose, `stats/` model types |
| Wiring | `AppContainer`, `MainActivity`, `service/` | everything |

`stats/**` importing anything from `android.*` is a defect, including in tests.

### 5.1 Threading

- The transport delivers frames on its own threads. `RadioConnectionManager`
  already funnels them into a `Channel` — that channel becomes the engine's input.
- `MeshStatsEngine` consumes on a **single** coroutine on `Dispatchers.Default`.
  Every mutable field it owns is touched only from that coroutine. No `Mutex`,
  no `@Volatile`, no `Atomic*` in `stats/`.
- User actions (pause, reset, sort, skip) are messages into the same coroutine,
  not direct field writes.
- Snapshot construction happens on that same coroutine, then the immutable result
  crosses to the UI through a `StateFlow`.

### 5.2 Reactivity, and why there is no tick

The engine marks itself dirty after any state change and offers a `Unit` to a
`Channel(CONFLATED)`. A builder coroutine receives from it, builds a snapshot, and
emits. Because the channel is conflated, a burst of thirty packets collapses into
one rebuild rather than thirty.

The published flow is created with
`stateIn(scope, SharingStarted.WhileSubscribed(5_000), StatsSnapshot.EMPTY)`. The
consequence that matters on a phone: with the screen off and the foreground service
running, **no snapshot is built at all**. Ingestion — the only thing that must not
stop — carries on.

The service notification reads `MeshStatsEngine.counters`, four plain integers,
at a 30-second cadence. It never touches a snapshot.

The RX flash is not polled: `SignalGauge` observes `lastPacketAtMillis` and runs a
Compose animation for `FLASH_MILLIS` when it changes.

Relative-time labels ("2s ago", `Src: DB:5m`) are the only genuinely time-driven
text. They read `LocalRelativeClock`, a `CompositionLocal` fed by a ticker that runs
only while a screen is resumed and steps from 1 s to 30 s once every visible age is
over a minute.

## 6. Core data model

All types below live in `stats/model/` unless stated otherwise, are `data class` or
`enum`, and are **immutable**: an update returns a new instance. Signatures here are
the contract between parallel implementers and must be matched exactly.

### 6.1 Signal statistics

```kotlin
data class SignalStats(
    val minVal: Float = Float.POSITIVE_INFINITY,
    val maxVal: Float = Float.NEGATIVE_INFINITY,
    val sumVal: Double = 0.0,
    val count: Int = 0,
    val lastVal: Float = 0f,
) {
    val avg: Float get() = if (count > 0) (sumVal / count).toFloat() else 0f
    val hasData: Boolean get() = count > 0
    fun plus(value: Float): SignalStats
    companion object { val EMPTY = SignalStats() }
}

data class Sample(val atMillis: Long, val value: Float)

data class SignalHistory(
    val stats: SignalStats = SignalStats.EMPTY,
    val samples: List<Sample> = emptyList(),
) {
    fun plus(atMillis: Long, value: Float): SignalHistory
    companion object { const val MAX_SAMPLES = 500 }
}
```

Ports `SignalStats` / `SignalHistoryStat`, `mesh_stats.py:223-266`. `sumVal` is a
`Double` where Python used a float — a long session accumulates tens of thousands of
samples and a `Float` sum would visibly drift the average. `SignalHistory.plus` drops
the oldest sample past `MAX_SAMPLES`; Python's list is unbounded, which is a leak on
a device that runs for hours.

### 6.2 Position

```kotlin
data class PositionReport(
    val atMillis: Long,
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Int?,
    val precisionBits: Int?,
) {
    val hasCoordinates: Boolean
    val hasAltitude: Boolean
    companion object {
        fun fromProto(position: Position, atMillis: Long): PositionReport
    }
}

data class PositionHistory(
    val nodeNum: Int,
    val reports: List<PositionReport> = emptyList(),
) {
    val last: PositionReport?
    val best: PositionReport?
    fun plus(report: PositionReport): PositionHistory
    companion object { const val MAX_REPORTS = 100 }
}
```

Ports `PositionMessage` / `NodePositionHistory`, `mesh_stats.py:270-343`.

- Coordinates come from `latitude_i` / `longitude_i` scaled by `1e-7`, computed in
  `Double`. Wire exposes them as `Int`; the multiplication must not go through
  `Float`.
- Altitude prefers `altitude_hae` (height above WGS84 ellipsoid) over `altitude`
  (mean sea level), matching `mesh_stats.py:288-295`.
- `best` reproduces a quirk of the original exactly: it returns the newest report
  carrying **both** coordinates and altitude, and `null` if no report has both — even
  when reports with coordinates alone exist. Callers fall back to the node database.
  Port the quirk; do not "fix" it, or the position source labelling diverges from the
  tool being ported.

### 6.3 Per-packet aggregates

```kotlin
data class RemoteNodeStats(
    val packetCount: Int = 0,
    val hopsMadeSum: Long = 0, val hopsMadeCount: Int = 0,
    val hopsLeftSum: Long = 0, val hopsLeftCount: Int = 0,
) {
    val avgHopsMade: Float?   // null when hopsMadeCount == 0
    val avgHopsLeft: Float?
    fun plus(hopStart: Int, hopLimit: Int, hopsKnown: Boolean): RemoteNodeStats
}

data class RelayStats(
    val relayByte: Int,
    val nodeName: String = "",
    val snr: SignalStats = SignalStats.EMPTY,
    val rssi: SignalStats = SignalStats.EMPTY,
    val packetCount: Int = 0,
    val firstPacketAtMillis: Long = 0,
    val lastPacketAtMillis: Long = 0,
    val fromNodeStats: Map<Int, RemoteNodeStats> = emptyMap(),
) {
    val hexId: String            // "0x69", lower case, always two digits
    val knownNodesCount: Int
    val packetsPerHour: Float    // 0f when packetCount < 2 or duration <= 0
}

data class NeighbourStats(
    val nodeNum: Int,
    val snr: SignalHistory = SignalHistory(),
    val rssi: SignalHistory = SignalHistory(),
    val packetCount: Int = 0,
    val lastPacketAtMillis: Long = 0,
)

data class TelemetryRecord(
    val lastUptimeSeconds: Int? = null,
    val observedRestartCount: Int = 0,
    val metrics: Map<String, SignalHistory> = emptyMap(),
)
```

Ports `RemoteNodeStats`, `RelayNodeStats`, `NeighbourStat`, `NodeTelemetryRecord`,
`mesh_stats.py:346-479`.

**The proto3-default problem.** `mesh_stats` reads packets as dictionaries produced by
the `meshtastic` Python library, which omits fields left at their protobuf default —
so `rxSnr` absent and `rxSnr == 0.0` are two distinguishable states, and the original
code branches on `is None` throughout. Wire generates non-nullable `Float` / `Int`
defaulting to `0`, and the distinction is gone. Three rules restore the intent:

| Field | Rule | Why |
| :--- | :--- | :--- |
| `rx_snr`, `rx_rssi` | Signal information is present **iff `rx_rssi != 0`**; both values are then recorded together | 0 dBm is not physically observable, whereas exactly 0.0 dB SNR is ordinary. The firmware sets both fields together for a received packet, so RSSI is a sound presence witness for the pair |
| `hop_start`, `hop_limit` | Treated as-is; `hopsKnown = hopStart != 0` | `hopStart == 0` means the field was never set. When both are absent, `hopStart - hopLimit == 0`, which is the same conclusion the original reaches |
| `relay_node` | `0` means absent | Identical in both worlds; `mesh_stats.py:1041` already tests for it |

This deviation is deliberate and must survive review. Any implementer who "fixes"
`signalOf` to accept `rx_rssi == 0` reintroduces a stream of phantom 0/0 samples that
drag every average toward zero.

### 6.4 Snapshot

```kotlin
data class Counters(
    val totalPackets: Int = 0,
    val totalRelayedPackets: Int = 0,
    val totalDirectPackets: Int = 0,
    val relayCount: Int = 0,
)

data class StatsSnapshot(
    val relays: List<RelayStats>,          // already sorted per sortMode
    val neighbours: List<NeighbourStats>,  // already sorted per sortMode
    val counters: Counters,
    val paused: Boolean,
    val sortMode: SortMode,
    val lastPacketAtMillis: Long?,
    val lastRelayedPacketAtMillis: Long?,
    val directory: NodeDirectorySnapshot,
    val skippedRelayNodes: Set<Int>,
) {
    companion object { val EMPTY: StatsSnapshot }
}
```

Sorting happens in the engine, not the UI: the sort mode is engine state (the TUI's
`[S]` key), and a screen that re-sorted on every recomposition would do the work
repeatedly for nothing.

## 7. Node directory and geography

```kotlin
// stats/model/NodeRecord.kt — the app's own type; the UI never sees Wire messages
data class NodeRecord(
    val num: Int,
    val longName: String?,
    val shortName: String?,
    val hwModel: String?,
    val role: String?,             // absent role reads as "CLIENT", per mesh_stats.py:1816
    val dbPosition: PositionReport?,
    val dbSnr: Float?,
    val lastHeardEpochSeconds: Int?,
    val hopsAway: Int?,
    val hasPublicKey: Boolean,
) {
    companion object {
        fun fromProto(info: NodeInfo): NodeRecord
    }
}

data class LatLon(val lat: Double, val lon: Double)
enum class PositionSource { DB, CURRENT }
enum class Direction { N, NE, E, SE, S, SW, W, NW, UNKNOWN }

data class LocationInfo(
    val lat: Double?, val lon: Double?,
    val altitude: Int?,
    val distanceKm: Double?,
    val obfuscationRadiusMeters: Double?,
    val direction: Direction,
    val source: PositionSource?,
    val atMillis: Long?,
)
```

```kotlin
// stats/Geo.kt — pure functions, no state
object Geo {
    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double
    fun bearingDegrees(from: LatLon, to: LatLon): Double
    fun directionOf(bearingDeg: Double): Direction
    fun obfuscationRadiusMeters(precisionBits: Int?): Double?
    fun lastByteOfNodeNum(nodeNum: Int): Int
}

// stats/AgeBucket.kt
enum class AgeBucket { M1, M5, M30, H1, H12, D1, W1, Y1, UNKNOWN;
    companion object { fun of(elapsedMillis: Long): AgeBucket }
}
```

Ports `haversine_distance`, `obfuscation_radius_meters`, `bearing_to_direction`,
`get_last_byte_of_node_num` and the age table (`mesh_stats.py:184-222`, `:481-487`,
`:1775-1794`).

- `lastByteOfNodeNum` returns `nodeNum and 0xFF`, **or `0xFF` when that is `0`**.
  This mirrors a firmware convention, not an arithmetic identity; it is the single
  most load-bearing line in the whole port and gets its own test.
- `obfuscationRadiusMeters(bits) = 23905784.0 / 2^bits`, `null` for `null` or `<= 0`.
- `directionOf` maps 45° sectors with N centred on 0°: `((deg + 22.5) % 360) / 45`.
- Age buckets, in order: under 1 min → `M1`; under 5 → `M5`; under 30 → `M30`; under
  1 h → `H1`; under 12 h → `H12`; under 1 d → `D1`; under 1 w → `W1`; under 1 y →
  `Y1`; otherwise `UNKNOWN`.

```kotlin
// stats/NodeDirectory.kt — mutable, engine-confined
class NodeDirectory(private val time: TimeSource) {
    fun applyNodeInfo(info: NodeInfo)
    fun applyUser(nodeNum: Int, user: User, atMillis: Long)
    fun applyPosition(nodeNum: Int, position: Position)
    fun applyTelemetry(nodeNum: Int, telemetry: Telemetry, atMillis: Long)
    fun setLocalNodeNum(num: Int)
    fun markLoaded(atMillis: Long)
    fun clearRuntimeData()                       // Reset: positions + telemetry; keeps the DB
    fun snapshot(skipped: Set<Int>): NodeDirectorySnapshot
}

// stats/model/NodeDirectorySnapshot.kt — immutable, UI-facing
class NodeDirectorySnapshot(
    val nodes: Map<Int, NodeRecord>,
    val loadedAtMillis: Long?,
    val localNodeNum: Int?,
    /* internal */ positions: Map<Int, PositionHistory>,
    /* internal */ telemetry: Map<Int, TelemetryRecord>,
    /* internal */ skipped: Set<Int>,
) {
    val count: Int
    fun node(nodeNum: Int): NodeRecord?
    fun shortName(nodeNum: Int): String                 // "" when unknown
    fun matchingNodeNums(relayByte: Int): List<Int>     // skip-list applied
    fun uniqueRelayName(relayByte: Int): String         // shortName iff exactly one match, else ""
    fun telemetry(nodeNum: Int): TelemetryRecord?
    fun localPosition(): LatLon?
    fun locationInfo(nodeNum: Int, from: LatLon?): LocationInfo
}

// stats/RelayIndex.kt — a fold over the relay list, not directory state
object RelayIndex {
    /** Relays carrying this node's traffic, most packets first. */
    fun relaysCarrying(nodeNum: Int, relays: List<RelayStats>): List<RelayStats>
}
```

`locationInfo` ports `get_node_location_info` (`mesh_stats.py:830-919`) and its
precedence is exact:

1. `PositionHistory.best` for that node → `source = CURRENT`, `atMillis` = report time,
   `precisionBits` from the report.
2. Otherwise `NodeRecord.dbPosition` → `source = DB`, `atMillis = lastHeard * 1000`,
   `precisionBits = null` (the database does not carry it).
3. With no coordinates, everything but `altitude` stays `null` and `direction` is
   `UNKNOWN`.
4. With coordinates and a local position: `distanceKm` by haversine; direction from
   the bearing — **unless** the obfuscation radius is greater than or equal to the
   distance in metres, in which case the direction is `UNKNOWN`, because the
   uncertainty exceeds the separation.

`RelayIndex.relaysCarrying` has no counterpart in the original. It inverts
`relays[*].fromNodeStats` to answer, for one remote node, which relays carry its
traffic. The README calls this knowledge essential — *"packets from some node can be
relayed through different relays… it is essential to study this info"* — but the TUI
can only ever show it one relay at a time. The inversion is a fold over at most 255
relays and is computed on demand, not stored.

**Telemetry** (`_process_telemetry_packet`, `mesh_stats.py:921-999`) records, per node:

- `DeviceMetrics` → `battery_level`, `voltage`, `channel_utilization`, `air_util_tx`,
  and `uptime_seconds`, which is not a metric but a restart detector: when the reported
  uptime is **lower** than the previous one, `observedRestartCount` increments.
- `EnvironmentMetrics` → `temperature`, `voltage`, `current`.
- `PowerMetrics` → `ch1_voltage` … `ch8_voltage`, `ch1_current` … `ch8_current`.
- `LocalStats` → kept only when the packet came from the local node.

Metric keys are the snake_case protobuf names, unchanged and untranslated; they are
displayed verbatim.

## 8. The packet-handling loop

```kotlin
// stats/TimestampedFrame.kt
data class TimestampedFrame(val rxMillis: Long, val frame: FromRadio)

// stats/PacketClassifier.kt
data class Signal(val snr: Float, val rssi: Float)

sealed interface Ingest {
    data class Relayed(
        val relayByte: Int, val fromNode: Int,
        val hopStart: Int, val hopLimit: Int, val signal: Signal?,
    ) : Ingest
    data class Direct(val fromNode: Int, val signal: Signal?) : Ingest
    data object Dropped : Ingest
}

object PacketClassifier {
    fun classify(packet: MeshPacket, skippedRelayNodes: Set<Int>): Ingest
    fun signalOf(packet: MeshPacket): Signal?
}
```

Ports `StatsCollector.on_receive`, `mesh_stats.py:1001-1091`. The decision, in order:

```
relayByte = packet.relay_node
hopsMade  = packet.hop_start - packet.hop_limit

if relayByte == 0 || (hopsMade == 0 && lastByteOfNodeNum(from) == relayByte)
        → Direct(from, signalOf(packet))

if from ∈ skippedRelayNodes
        → hopsMade == 1 ? Dropped : Relayed(...)      // one hop means we heard it first-hand

otherwise
        → Relayed(relayByte, from, hopStart, hopLimit, signalOf(packet))
```

`signalOf` returns `null` when `rx_rssi == 0`, otherwise
`Signal(packet.rx_snr, packet.rx_rssi.toFloat())` — see §6.3.

The engine applies the result:

- **`Direct`** — `totalDirectPackets++`; update or create the `NeighbourStats` for
  that node; record SNR and RSSI into its histories when a signal is present.
- **`Relayed`** — `totalRelayedPackets++`; update `lastRelayedPacketAtMillis`; update
  or create the `RelayStats` for the byte; fold the packet into
  `fromNodeStats[fromNode]`; record SNR and RSSI when present; refresh `nodeName`
  from `uniqueRelayName(relayByte)`.
- **`Dropped`** — counted in `totalPackets` only.

`totalPackets` increments for **every** packet, before classification. While paused,
nothing increments at all: a paused packet is dropped whole, exactly as
`mesh_stats.py:1013` does.

Frames that are not `MeshPacket` still matter and are handled before classification:

| Frame | Effect |
| :--- | :--- |
| `my_info` | `directory.setLocalNodeNum(my_node_num)` |
| `node_info` | `directory.applyNodeInfo(...)` |
| `config_complete_id` | `directory.markLoaded(now)` when it matches the node-DB nonce |
| `packet.decoded.portnum == POSITION_APP` | decode `Position`, `directory.applyPosition(...)` — **and** carry on to classification, since a position packet is also traffic |
| `packet.decoded.portnum == NODEINFO_APP` | decode `User`, `directory.applyUser(...)`, then classify |
| `packet.decoded.portnum == TELEMETRY_APP` | decode `Telemetry`, `directory.applyTelemetry(...)`, then classify |

An encrypted packet — `packet.encrypted != null`, `decoded == null` — still carries
`relay_node`, `hop_start`, `hop_limit` and signal strength, and is classified and
counted like any other. This is the point of the tool: relay topology is readable
without reading the traffic.

### 8.1 Engine surface

```kotlin
// stats/MeshStatsEngine.kt
class MeshStatsEngine(
    private val scope: CoroutineScope,
    // Plain flows, not SettingsRepository: that class reads SharedPreferences, and
    // stats/ must stay free of android.* so every file in it is JVM-testable.
    private val skippedRelayNodes: StateFlow<Set<Int>>,
    private val initialSortMode: SortMode,
    private val time: TimeSource = SystemTimeSource,
) {
    val snapshot: StateFlow<StatsSnapshot>
    val counters: StateFlow<Counters>

    fun attach(frames: Flow<TimestampedFrame>): Job
    fun setPaused(paused: Boolean)
    fun setSortMode(mode: SortMode)
    fun reset()
    fun skipRelayNode(nodeNum: Int)
    fun clearSkippedForRelay(relayByte: Int)
    fun onNodeDbReloaded()
}
```

- `reset()` clears relay stats, neighbour stats, counters, position history and
  telemetry. It does **not** clear the node database or the skip-list —
  `mesh_stats.py:1100-1113`.
- The skip-list is owned by `SettingsRepository` and reaches the engine as a plain
  `StateFlow<Set<Int>>` supplied by `AppContainer`. `skipRelayNode` and
  `clearSkippedForRelay` are requests the container forwards to the repository; the
  engine reacts to the resulting emission by recomputing the affected relay names.
  The engine never writes preferences.
- `attach` returns the collection `Job` so the caller can tear it down with the
  session.

## 9. Stage-2 seams

Node-DB save/load and packet save/load/replay are **not** built here. Four seams make
them additive later rather than a rewrite. They are cheap now and each is load-bearing:

| Seam | Shape in stage 1 | What stage 2 plugs in |
| :--- | :--- | :--- |
| Frame source | The engine consumes `Flow<TimestampedFrame>` — a source-agnostic `(rxMillis, FromRadio)` stream — never "whatever the connection manager emits" | A `FilePacketSource` emitting the same flow. The core does not change |
| Clock | `interface TimeSource { fun nowMillis(): Long }`, injected everywhere time is read. Only `SystemTimeSource` exists | `ReplayTimeSource`, driven by replayed packet timestamps with a speed factor — the original's `BaseTimeHolder` / `ReplayTime` split, `mesh_stats.py:41-104` |
| Node database | `NodeDirectory` is populated only through its `apply*` methods, never by reaching into the connection manager | A persisted snapshot seeds the same methods before, or instead of, the live handshake |
| Capture format | **Decided now:** length-delimited protobuf — each record a varint length followed by an encoded `FromRadio`, preceded by a header record carrying the node-DB snapshot, its load time and the local position | Nothing left to re-decide. Explicitly **not** Python's `pickle`, which no Android process can read |

No timestamp arithmetic anywhere may call `System.currentTimeMillis()` directly.
That is the seam that is easiest to lose and hardest to retrofit, because a single
direct call makes replay show the wrong ages on every screen.

## 10. Interface

### 10.1 Map of screens

```
Devices ──▶ Main ┬── Relays ────────┐
                 └── Neighbours ──┐ │
                                  │ │
                 Settings         ▼ ▼
                             Detail (Relay | Neighbour)
                              ├─ tab: Matching nodes / Node
                              └─ tab: Remote nodes ──▶ Remote node details
```

```kotlin
// ui/nav/Screen.kt
enum class MainTab { RELAYS, NEIGHBOURS }

sealed interface DetailSubject {
    data class Relay(val relayByte: Int) : DetailSubject
    data class Neighbour(val nodeNum: Int) : DetailSubject
}

sealed interface Screen {
    data object Devices : Screen
    data class Main(val tab: MainTab) : Screen
    data object Settings : Screen
    data class Detail(val subject: DetailSubject) : Screen
    data class RemoteNode(val nodeNum: Int, val viaRelayByte: Int?) : Screen
}
```

`ui/nav/BackStack.kt` holds a `List<Screen>` in `rememberSaveable` with a `listSaver`,
exposes `push` / `pop` / `replaceRoot`, and installs a `BackHandler`. Switching bottom
tabs replaces the root rather than pushing, so the back stack never fills with tab
changes.

### 10.2 Relays

The port of the TUI main view. Top app bar: the title, a connection indicator, and
actions for sort, gauge mode, pause/resume, reset and node-DB reload — the `[S]`,
`[M]`, `[P]`, `[R]`, `[D]` keys.

Below the bar, a **status strip** carrying what the TUI header line carries:
`DB(1043) · 14:22:07` (node count and load time), `Total 8421 · Relayed 3190`,
current sort, and a `PAUSED` badge when paused.

Below that, a **local-node line**: short name, coordinates, altitude, position source
and age, and a Meshview link when a base URL is configured.

Then the list. One card per relay:

```
┌──────────────────────────────────────────┐
│ 0x69 [1]  PQPL1                  2s ago  │
│ SNR   ░░░░████❘███✱█░░░░   -7.5 dB       │
│       -20        min/avg/max        +15  │
│ RSSI  ░░░░░░████❘██✱░░░░░   -94 dBm      │
│       -130       min/avg/max        -30  │
│ 12.4 km/NE · 812 m · 412 pkts 12.9% ·    │
│ 18 nodes                                 │
└──────────────────────────────────────────┘
```

- `0x69 [1]` — the relay byte and, in brackets, how many database nodes match it.
  The name appears only when exactly one node matches, per `get_node_name`.
- Distance, direction and altitude appear only when exactly one node matches, since
  otherwise there is no single position to speak of — as in the TUI.
- Empty state: an explanation that relayed packets have not been seen yet, and that
  directly-received traffic lives on the Neighbours tab.

### 10.3 Neighbours

The port of the `[N]` view. Same card shape, keyed by node instead of relay byte:
node id `!9e75f1a4` and short name, distance/altitude, direct packet count and its
percentage of all direct packets, SNR and RSSI gauges, last-heard age. Tapping a row
opens `Detail(DetailSubject.Neighbour)`.

### 10.4 Detail

One screen shell, two subjects. This is the point where the port stops being literal:
a relay is a one-byte guess, a neighbour is a known node, and pretending otherwise
would be a lie in the interface.

| | `DetailSubject.Relay` | `DetailSubject.Neighbour` |
| :--- | :--- | :--- |
| Title | `Relay 0x69` plus the name when unique | `Neighbour !9e75f1a4 · PQPL1` |
| Summary | Total packets relayed, packets/hour, explicitly skipped nodes | Direct packets, packets/hour, hops away from the database |
| Signal block | min / avg / max / last / count for SNR and RSSI, with both gauges | identical |
| Tab 1 | **Matching nodes** — every database node whose last byte matches, with skip and clear actions | **Node** — one card, identity known, no guessing and no skip action |
| Tab 2 | **Remote nodes** carried by this relay | **Remote nodes**, shown only when this node's last byte also appears as a relay byte; otherwise the tab is absent |

**Matching nodes** — one card per candidate, ported from `build_detail_lines`
(`mesh_stats.py:1838-1907`): index, `!xxxxxxxx`, long name, short name, role,
hardware, the position line, buttons for Google Maps, OpenStreetMap and Meshview,
last SNR in the database, last heard in the database, firmware when present, uptime
with observed restart count, and every telemetry metric with its latest value.

Two notes for the implementer:

- Role must read `CLIENT` when the field is absent — that is the protocol default and
  the original relies on it.
- `NodeInfo` has **no** `firmware_version` field. The original reads
  `node_info.get("firmwareVersion")`, which can never be populated for a remote node;
  keep the row, expect it to stay empty except for the local node, and do not invent
  a source for it.

Skipping is destructive enough to confirm: a dialog naming the node, matching the
TUI's `[y]/[n]` popup. `Clear skipped` confirms likewise.

**Remote nodes** — rows sorted by packet count descending: node id, short name,
packets, average hops made, average hops left, and the position line with distance
and direction. Tapping a row opens `RemoteNode(nodeNum, viaRelayByte)`.

The TUI's own guidance belongs on this tab as an explanatory line, because it is the
reason the tab exists: relays often carry traffic from one direction only, so the
spread of directions here reveals where a relay listens.

### 10.5 Remote node details

Everything known about one node: identity (id, long and short name, role, hardware,
whether a public key is present), the position line with map and Meshview links,
hops away, last heard, telemetry history, and — new in this port — **which relays
carry this node's traffic**, with per-relay packet counts, from
`RelayIndex.relaysCarrying`. When reached from a relay, that relay is marked in the list.

### 10.6 Devices and Settings

`Devices` is the skeleton's `DeviceListScreen` restyled: scan results, demo devices in
debug builds, connection state, and the permission and adapter states it already
handles. Its logic is sound and stays; only its appearance changes.

`Settings`: language (System / English / Español), gauge mode (Simple / Complex),
default sort mode, Meshview base URL, keep screen on, background collection, the
skip-list with a per-entry remove and a clear-all, and an About block with version and
the GPL-3.0 notice.

### 10.7 Signal gauge

`GaugeMode` lives in `settings/GaugeMode.kt`, not in `ui/`: `AppSettings` carries it,
and the persistence layer must not depend on the interface layer.

```kotlin
// settings/GaugeMode.kt
enum class GaugeMode { SIMPLE, COMPLEX }

// ui/common/GaugeGeometry.kt — pure, no Compose, so the geometry is testable on the JVM
data class GaugeMarks(
    val fillStart: Float, val fillEnd: Float,   // 0f..1f along the track
    val avg: Float?, val last: Float?,          // null when there is no data
)

object GaugeGeometry {
    fun marks(stats: SignalStats, scaleMin: Float, scaleMax: Float, mode: GaugeMode): GaugeMarks
}

// ui/common/SignalGauge.kt — draws GaugeMarks, holds no arithmetic
@Composable
fun SignalGauge(
    stats: SignalStats,
    scaleMin: Float,
    scaleMax: Float,
    mode: GaugeMode,
    lastPacketAtMillis: Long,
    modifier: Modifier = Modifier,
)
```

```kotlin
// stats/SignalScales.kt
object SignalScales {
    const val SNR_MIN = -20f
    const val SNR_MAX = 15f
    const val RSSI_MIN = -130f
    const val RSSI_MAX = -30f
    const val FLASH_MILLIS = 500L
    fun fraction(value: Float, min: Float, max: Float): Float   // clamped to 0f..1f
}
```

- **Simple** — a single filled bar from the scale minimum to the current value.
- **Complex** — the track filled between `minVal` and `maxVal`; a thin vertical rule
  at `avg`; a marker at `lastVal`. When `avg` and `lastVal` land on the same
  position, the last-value marker wins, matching `render_bar_complex`
  (`mesh_stats.py:1199-1263`).
- The last-value marker animates for `FLASH_MILLIS` whenever `lastPacketAtMillis`
  changes. Driven by `LaunchedEffect`, never by polling.
- A gauge with `count == 0` draws an empty track and no markers.

Scales were widened from the TUI's `−20…+10 dB` and `−120…−60 dBm` to cover the
ranges Meshtastic really produces. The consequence is honest and worth stating: a
100 dB RSSI span compresses the `−120…−60` region where nearly every real packet
sits, so the gauge reads less sensitively than the terminal's did. Every consumer
reads `SignalScales`, so making the range a settings item later is a change in one
file.

## 11. Internationalisation

- `res/values/strings.xml` — English, the default.
- `res/values-es/strings.xml` — Spanish, complete; no key may be missing.
- `res/xml/locales_config.xml` listing `en` and `es`, referenced from the manifest.

Both files are authored in wave 1 and are the **contract** for the screens built in
wave 3: no screen may introduce a key, and no screen may contain a literal.

Key naming: `<screen>_<element>`, lower snake case — `relays_title`,
`relays_empty_body`, `detail_tab_matching_nodes`, `settings_language_spanish`,
`common_unknown`, `action_pause`. Plurals use `<plurals>`; Spanish needs `one`/`other`.

Untranslated by decision: port numbers, device roles, hardware models, protobuf
field names, telemetry metric keys, node identifiers, and units (`dB`, `dBm`, `km`,
`m`). Compass points **are** translated, since `N`/`NE` read as `N`/`NE` in Spanish
but the accessibility descriptions differ.

Numbers, distances and timestamps are formatted through `java.text.NumberFormat` and
`DateTimeFormatter` with the active locale — Spanish uses a decimal comma, and a
hard-coded `"%.1f"` would print the wrong thing.

Language override without a new dependency: `AppSettings.language` is stored in
preferences; the composition root wraps content in a `Context` created with
`createConfigurationContext` for the chosen locale and provides it through
`LocalContext`, so every `stringResource` resolves against it. `SYSTEM` provides the
context unchanged.

## 12. Settings and persistence

```kotlin
// settings/AppSettings.kt
enum class LanguageOption { SYSTEM, EN, ES }

data class AppSettings(
    val language: LanguageOption = LanguageOption.SYSTEM,
    val gaugeMode: GaugeMode = GaugeMode.SIMPLE,
    val defaultSortMode: SortMode = SortMode.PACKETS,
    val meshviewUrl: String = "https://meshview.meshtastic.es",
    val keepScreenOn: Boolean = false,
    val backgroundCollection: Boolean = true,
)

// settings/SettingsRepository.kt
class SettingsRepository(context: Context, private val ioScope: CoroutineScope) {
    val settings: StateFlow<AppSettings>
    val skippedRelayNodes: StateFlow<Set<Int>>
    fun update(transform: (AppSettings) -> AppSettings)
    fun addSkippedRelayNode(nodeNum: Int)
    fun removeSkippedRelayNode(nodeNum: Int)
    fun clearSkippedForRelay(relayByte: Int)
}
```

`SharedPreferences`, read once into memory at construction and written on
`Dispatchers.IO`. Skipped nodes are stored as a `StringSet` of `!xxxxxxxx` ids — the
same notation `--skip-relay` accepts, so a value can be moved between the terminal
tool and the phone by hand.

The default Meshview base URL is `https://meshview.meshtastic.es`, the Spanish
community instance used throughout this workspace.

Statistics deliberately do not persist. A measurement session is a snapshot, and a
relay table silently carrying yesterday's numbers into today's survey would be worse
than an empty one.

## 13. Icons and assets

Bundled `androidx.compose.material.icons.Icons.*` where a suitable icon exists.
Anything missing is checked in as a **Material Symbols** vector drawable
(Apache-2.0) under `res/drawable/`, named `ic_<meaning>.xml`, with its source and
licence recorded in `docs/third-party-assets.md`.

The plan must **verify** whether `androidx.compose.material:material-icons-extended`
still resolves under Compose BOM 2026.06.01 before anything relies on it; the
extended set has been deprecated in recent Compose releases. Checked-in drawables are
the fallback and require no verification.

`devel-notes.md §1` is the reason for the caution: a dependency's published metadata
gets read before it is depended upon, not after five CI runs.

## 14. Testing

JVM unit tests only, as in the skeleton; no instrumented tests. Every core file ships
with its test file, and a task is not done until its tests pass and
`compileDebugKotlin` is clean.

A `@Composable` cannot be unit-tested this way, so **no composable may contain
arithmetic or formatting**. Gauge positions come from `GaugeGeometry.marks`, the
position line from `PositionLineText.format`, relative ages from `AgeText.format` —
each a pure function with its own test, each rendered by a composable that only
draws. A composable that computes something is a defect here, not a style
preference.

What must be tested, because it is where a plausible-looking wrong answer survives:

- `lastByteOfNodeNum(0x…00) == 0xFF`, and the ordinary case.
- `PacketClassifier` across the full decision table: no relay byte; relay byte equal
  to the sender's last byte with zero hops made; a skipped sender at one hop and at
  two; an ordinary relayed packet.
- `signalOf` returning `null` at `rx_rssi == 0` and a value at `rx_rssi == -1`.
- `PositionHistory.best` returning `null` when reports carry coordinates but no
  altitude — the quirk, asserted deliberately.
- `locationInfo` precedence, and `Direction.UNKNOWN` when the obfuscation radius
  reaches the distance.
- Every age bucket boundary.
- Restart detection when reported uptime decreases.
- `MeshStatsEngine`: pause drops packets whole; reset spares the node database and
  the skip-list; a burst of frames yields one snapshot, not one per frame; no
  snapshot is built while nothing is subscribed.
- Both `strings.xml` files carry identical key sets.

`devel-notes.md §5.3` gives the standard each test is held to: **would it fail
against a plausible mutant?** An assertion about ordering on an empty list, or a
value the test itself wrote, is not a test.

Coroutine tests inherit two traps from the skeleton: work derived from
`backgroundScope` is not advanced by `advanceUntilIdle()`, and `advanceUntilIdle()`
over an endless loop hangs the run outright. The engine's collector loop is endless,
so its tests use `backgroundScope` with explicit `advanceTimeBy`.

## 15. Work breakdown

The spec exists to make parallel work safe. Every task below gets a brief carrying its
**exact file paths, the public signatures from §6–§12, the `mesh_stats.py` line range it
ports, the string-resource keys it may use, and its required test cases.** Where the
brief removes judgement, a cheaper model is the right tool; where semantics survive a
plausible-looking wrong answer, it is not.

Definition of done, every task: its test file passes, `compileDebugKotlin` is clean, no
Russian, no literal user-facing string, no `android.*` import inside `stats/`.

### Round 0 — foundation (sequential, Opus)

Everything downstream keys off these signatures, so an error here is systematic rather
than local.

`settings.gradle.kts` · `build.gradle.kts` · `gradle/libs.versions.toml` ·
`gradle.properties` · Gradle wrapper · `app/build.gradle.kts` · `AndroidManifest.xml` ·
`.github/workflows/build.yml` · `LICENSE` (GPL-3.0) · `README.md` ·
`ui/theme/{Theme,Color,Type}.kt` · and the small, complete contract types:
`stats/TimeSource.kt`, `stats/TimestampedFrame.kt`, `stats/SortMode.kt`,
`stats/SignalScales.kt`, `stats/model/LatLon.kt`, `stats/model/Direction.kt`,
`stats/model/PositionSource.kt`, `settings/GaugeMode.kt`, `ui/nav/Screen.kt`.

### Round 1 — nine tasks in parallel

| # | Files | Model | Ports | Test |
| :--- | :--- | :--- | :--- | :--- |
| A1 | `stats/Geo.kt`, `stats/AgeBucket.kt` | Sonnet | `:184-222`, `:481-487`, `:1775-1794` | `GeoTest`, `AgeBucketTest` |
| A2 | `stats/model/SignalStats.kt`, `SignalHistory.kt` | Sonnet | `:223-266` | `SignalStatsTest` |
| A3 | `stats/model/PositionReport.kt`, `PositionHistory.kt` | Sonnet | `:270-343` | `PositionTest` |
| A4 | `settings/AppSettings.kt`, `settings/SettingsRepository.kt` | Sonnet | — | `SettingsRepositoryTest` |
| A5 | `res/values/strings.xml`, `res/values-es/strings.xml`, `res/xml/locales_config.xml` | Sonnet | — | `StringsParityTest` |
| A6 | `ble/**` — copy from the skeleton, comments translated | Sonnet | — | skeleton tests, carried over |
| A7 | `transport/**` — copy, translated | Sonnet | — | skeleton tests, carried over |
| A8 | `connection/**` — copy, translated, **plus node-DB reload** | Opus | `:582-609` | `RadioConnectionManagerTest`, extended |
| A9 | `service/**` — copy, translated | Sonnet | — | — |

A8 is the exception among the copies: adding a mid-session node-DB reload touches
handshake state, which `devel-notes.md §8` records as the exact place where extending a
contract broke a consumer that had reasoned about its previous shape.

### Round 2 — six tasks in parallel

| # | Files | Model | Needs | Test |
| :--- | :--- | :--- | :--- | :--- |
| B1 | `stats/model/{RemoteNodeStats,RelayStats,NeighbourStats,TelemetryRecord,Counters}.kt` | Sonnet | A2 | `AggregatesTest` |
| B2 | `stats/PacketClassifier.kt` | **Opus** | A1 | `PacketClassifierTest` |
| B3 | `stats/model/NodeRecord.kt`, `LocationInfo.kt` | Sonnet | A3 | `NodeRecordTest` — covers `fromProto` |
| B4 | `ui/common/{NodeIdText,AgeText,RelativeTimeTicker}.kt` | Sonnet | A1, A5 | `AgeFormatTest` |
| B5 | `ui/common/GaugeGeometry.kt`, `ui/common/SignalGauge.kt` | Sonnet | A2, round 0 | `GaugeGeometryTest` |
| B6 | `emulator/{MeshScenario,Scenarios}.kt` — scenarios carrying relay traffic | Sonnet | A7 | `ScenariosTest` |

B2 is Opus work: the `rx_rssi != 0` presence rule and the skipped-sender hop test are
both places where a wrong implementation compiles, passes a naive test, and quietly
corrupts every average in the application.

B6 must produce packets that actually exercise the classifier — several relay bytes,
at least one byte matching more than one database node, encrypted as well as decoded
payloads, position and telemetry traffic, and a spread of SNR and RSSI wide enough to
show both gauge modes doing something.

### Round 3 — three tasks in parallel

| # | Files | Model | Needs | Test |
| :--- | :--- | :--- | :--- | :--- |
| C1 | `stats/NodeDirectory.kt`, `stats/model/NodeDirectorySnapshot.kt`, `stats/model/StatsSnapshot.kt`, `stats/RelayIndex.kt` | **Opus** | A1, A3, B1, B3 | `NodeDirectoryTest`, `RelayIndexTest` |
| C2 | `ui/common/PositionLineText.kt`, `ui/common/PositionLine.kt`, `ui/common/MapLinks.kt` | Sonnet | B3, A5 | `PositionLineTextTest` |
| C3 | `ui/preview/SampleData.kt` | Sonnet | B1, C1 | — |

C1 is Opus work: the `CURRENT` over `DB` precedence, the deliberately preserved `best`
quirk, and the obfuscation-radius rule are three separate places where a reasonable
reading of the code produces a different answer from the tool being ported.

### Round 4 — nine tasks in parallel

| # | Files | Model | Needs |
| :--- | :--- | :--- | :--- |
| D1 | `stats/MeshStatsEngine.kt` | **Opus** | all of `stats/` |
| D2 | `ui/relays/RelayListScreen.kt` | Sonnet | C1, C3, B4, B5, C2 |
| D3 | `ui/neighbours/NeighbourListScreen.kt` | Sonnet | same |
| D4 | `ui/detail/DetailScreen.kt` | Sonnet | same |
| D5 | `ui/detail/MatchingNodesTab.kt`, `ui/detail/NodeCard.kt` | Sonnet | same |
| D6 | `ui/detail/RemoteNodesTab.kt` | Sonnet | same |
| D7 | `ui/detail/RemoteNodeScreen.kt` | Sonnet | same |
| D8 | `ui/settings/SettingsScreen.kt` | Sonnet | A4, A5 |
| D9 | `ui/devices/DeviceListScreen.kt` — restyled | Sonnet | A6, A7 |

D1 is Opus work and the reason is `devel-notes.md §5.5`: state confinement, conflation
and subscription-scoped sharing are exactly the properties a single-threaded test suite
cannot observe failing.

**Every screen is a stateless composable** taking a `StatsSnapshot` (or the slice it
needs) plus callbacks. No screen reads `AppContainer`, holds a ViewModel, or starts a
coroutine of its own. This is what makes nine screens buildable at once by nine agents
who never see each other's work, and what makes each one previewable from
`SampleData`.

### Round 5 — integration (sequential, Opus)

`ui/nav/BackStack.kt`, `ui/MeshRelayNavHost.kt`, `ui/LocalizedApp.kt`, `AppContainer.kt`,
`MeshRelayApp.kt`, `MainActivity.kt`, service wiring, green CI, and manual acceptance
against the T-Echo and the Heltec T114.

`devel-notes.md §6` is the reason this round is not optional and not cheap: six defects
on the previous project passed three reviews and a fully green CI, and were found only
by a person holding the hardware. Two of them were regressions introduced while fixing
the others.

### Briefing rule for the cheap tasks

A Sonnet brief that says "port `SignalStats`" will produce something plausible and
subtly wrong. A brief that carries the signature, the source lines, the deviation notes
from §6.3, and the list of assertions its test must contain will produce the right file.
The cost of the difference is paid once, here, in the plan — not once per agent.

## 16. Out of scope

Named explicitly, so that "port all features" is not read as an argument for adding
them back mid-stream:

- USB-serial and TCP transports.
- Packet recording, replay and replay speed control — the seams exist (§9), the
  features do not.
- Node-database persistence.
- Statistics persistence across launches.
- Sending messages, changing node configuration, or anything that writes to the mesh
  beyond the handshake and heartbeat the skeleton already performs.
- Maps drawn in-app. Positions link out to Google Maps, OpenStreetMap and Meshview.
- `cluster_analysis.py`, which is a separate offline tool and not part of the TUI.

## 17. Risks and open items

| Risk | Handling |
| :--- | :--- |
| `material-icons-extended` may not resolve under Compose BOM 2026.06.01 | The plan verifies before use; checked-in Material Symbols drawables are the fallback (§13) |
| Mid-session node-DB reload re-runs part of the handshake and floods the frame stream | A8 confirms the real firmware's response to a repeated `want_config_id` on hardware before the behaviour is relied upon |
| Nordic BLE read and write are not cancellable (`devel-notes.md §2.2`) | Inherited unchanged from the skeleton, including its own timeouts. Not re-litigated here |
| The drain-loop exception classification is a known open item in the skeleton | Carried over as-is and left open. Resolving it needs logs from hardware, not a guess; it is listed so it is not mistaken for a defect introduced by this port |
| A long session grows telemetry and position history without bound | Capped at 500 samples and 100 position reports per node (§6.1, §6.2) |
| Widened RSSI scale reads less sensitively than the TUI's | Accepted and stated (§10.7). Single-sourced in `SignalScales` so it can become a setting |
| The relay byte is one byte: several nodes can match, and the tool can only guess | Inherent to the protocol and to the original. The interface always shows the match count and never presents a guess as a fact |
