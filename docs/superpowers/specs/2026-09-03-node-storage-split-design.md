# Design: two node stores — the radio's database and NodesFromAir

**Date:** 2026-09-03
**Status:** approved, ready for the implementation plan
**Source:** the owner's instruction of 2026-09-03 and the brainstorming session of the same day
**Builds on:** `docs/superpowers/specs/2026-08-26-mesh-relay-android-design.md`

---

## 1. Goal

The owner ran the application overnight and the header still read `DB(80)`. The
investigation that followed found the cause and a deeper problem behind it.

**The cause.** Only a `NODEINFO_APP` packet can add a node. `POSITION_APP` writes to
`positions` and `TELEMETRY_APP` writes to `telemetryRecords`; neither creates a
`NodeRecord`. A node whose positions were heard all night therefore never appears in
the count, and `NodeInfo` is broadcast rarely — the firmware default
`node_info_broadcast_secs` is 10800 seconds, three hours, and many operators set it
higher.

**The deeper problem.** What the radio told us and what we heard over the air are
folded into one map, `nodes`, by two different write paths with two different merge
rules. Once folded, nothing records which path a field came from or when. The
interface cannot say *"this name is what your radio had stored in August"* as
distinct from *"this name is what the node broadcast twenty minutes ago"*, and there
is no timestamp on either.

This design separates the two, timestamps both, and states which one the interface
believes.

## 2. Requirements

As given by the owner:

1. The local node database is stored independently.
2. `NODEINFO_APP` packets are stored in a separate structure, **NodesFromAir**.
3. For a node already in NodesFromAir, an arriving packet updates its data; for one
   not present, the packet creates it. The node number is the key.
4. Every record in **both** stores carries a timestamp of when its data was stored or
   updated.
5. `Long name`, `Short name`, `hwModel`, `Role` and `Public key` are taken from
   NodesFromAir when a record exists there, and from the node database otherwise.
6. That timestamp is shown with a label: **Air Received** / **DB Received**.
7. `snr` and `last_heard` are shown from the node database only.
8. The node database can be refreshed; the refresh must be handled.
9. Both structures are cleared when connecting to a *different* local node.
10. The menu's **Reset** command must not affect either structure.

## 3. Decisions

| Decision | Choice | Reason |
| :--- | :--- | :--- |
| Precedence between stores | **Per record**, not per field | The owner's ruling, made against a stated alternative. If an air record exists, all five identity fields come from it, blanks included. Simpler to read and to explain: one label and one timestamp per node rather than five of each |
| Air record update | **Merge**, stamp on every packet | The owner's ruling. A field the packet carries overwrites; a blank one keeps what was stored. This is what makes per-record precedence safe: a thin broadcast can never blank a panel that a fat one filled. The stamp reads "when we last heard this node identify itself" |
| DB refresh | **Staged replace**, mirroring the radio | The owner's ruling. Entries are buffered during the `want_config` round and swapped in on completion, so a node the firmware has evicted disappears from our store too. `DB(n)` then always equals the radio's real count instead of drifting into a high-water mark |
| Detecting the node-DB round | "Did any `node_info` frame arrive?" | `config_complete_id` terminates *any* `want_config` round, and `stats/` may not import `transport/` to tell the nonces apart (`MeshStatsEngine.kt:258`). Committing unconditionally would replace the store with an empty map when a config-only round completes. A node-DB round always carries at least the local node's own entry; a config round carries none. No new import, no new command, no cross-coroutine race |
| `role` in the merge | Never treated as blank | `CLIENT` is the proto3 default and is indistinguishable from an omitted field. Treating it as blank would mean a node that genuinely changes ROUTER → CLIENT never updates. A real role change matters; a `User` that omits `role` is rare |
| NodesFromAir size | **Uncapped** | See §5.4. Evicting a node would un-name a relay, which is the exact failure this design exists to fix |
| Where the label is shown | Detail panel only, not cards | A source label on every row of a list is noise; the lists show names, the panel explains them |

## 4. What arrives, and by which path

The distinction the whole design rests on. `FromRadio` is a `oneof`: one frame
carries exactly one variant.

| | node_info frame | `NODEINFO_APP` |
| :--- | :--- | :--- |
| Where | `FromRadio.node_info` (field 4) | `FromRadio.packet` → `MeshPacket.decoded`, `portnum = NODEINFO_APP` |
| Carries | `NodeInfo` — 13 fields | `User` — 9 fields |
| Produced by | **Your own radio**, reading out its stored database | **Another node**, broadcasting |
| When | Only during a `want_config` round | Whenever a node announces itself |
| Crossed LoRa? | No | Yes |

`NodeInfo` fields, and what this application keeps:

| № | Type | Field | Kept | Destination |
| ---: | :--- | :--- | :---: | :--- |
| 1 | `uint32` | `num` | yes | `NodeRecord.num` |
| 2 | *optional* `User` | `user` | yes | the five identity fields |
| 3 | *optional* `Position` | `position` | yes | `dbPosition` |
| 4 | `float` | `snr` | yes | `dbSnr` |
| 5 | `fixed32` | `last_heard` | yes | `lastHeardEpochSeconds` |
| 6 | *optional* `DeviceMetrics` | `device_metrics` | no | — |
| 7 | `uint32` | `channel` | no | — |
| 8 | `bool` | `via_mqtt` | no | — |
| 9 | *optional* `uint32` | `hops_away` | yes | `hopsAway` |
| 10–13 | `bool` | `is_favorite`, `is_ignored`, `is_key_manually_verified`, `is_muted` | no | — |

`User` fields, all this application can ever learn from the air:

| № | Type | Field | Kept | Destination |
| ---: | :--- | :--- | :---: | :--- |
| 1 | `string` | `id` | no | duplicates `num` |
| 2 | `string` | `long_name` | yes | `longName` |
| 3 | `string` | `short_name` | yes | `shortName` |
| 4 | `bytes` | `macaddr` | no | — |
| 5 | `HardwareModel` | `hw_model` | yes | `hwModel` |
| 6 | `bool` | `is_licensed` | no | — |
| 7 | `Role` | `role` | yes | `role` |
| 8 | `bytes` | `public_key` | yes | `hasPublicKey` (presence only) |
| 9 | *optional* `bool` | `is_unmessagable` | no | — |

Field definitions verified against the locally installed Python `meshtastic`
**2.7.10**; the application pins `org.meshtastic:protobufs` **2.7.26**
(`gradle/libs.versions.toml:6`). Field numbers and names for these two messages are
unchanged across that range, but this was not verified against 2.7.26 directly.

## 5. Data

### 5.1 `AirNodeRecord` — new, `stats/model/`

```kotlin
data class AirNodeRecord(
    val num: Int,
    val longName: String?,
    val shortName: String?,
    val hwModel: String?,
    val role: String?,
    val hasPublicKey: Boolean,
    /** When the most recent NODEINFO_APP for this node was folded in. */
    val receivedAtMillis: Long,
)
```

Identity only. Nothing measured lives here: a position heard over the air already
has `PositionHistory`, and signal already has the relay and neighbour statistics.

### 5.2 `NodeRecord` gains one field

```kotlin
val receivedAtMillis: Long,
```

Set when the refresh that carried the record completed. Every record from one
refresh shares one value, because they do: the radio streams a whole database in a
burst of a few seconds and the phone cannot tell when the radio learned any of it.

### 5.3 What "blank" means

Load-bearing, because §3 makes the air store merge rather than replace. proto3 does
not distinguish an unset non-`optional` field from its default value, so every rule
here is about a default that must be read as absence.

| Field | Blank when | Note |
| :--- | :--- | :--- |
| `long_name`, `short_name` | `null` or `""` | Unset and empty are the same three bytes on the wire |
| `hw_model` | `null` or `UNSET` | `UNSET` is itself the schema's "not known" value |
| `public_key` | `null` or empty | Presence is remembered once observed and never unlearned, matching the existing `NodeDirectory.merge()` rule and its recorded reasoning |
| `role` | **never** | See §3. `CLIENT` is the default and cannot be told from absence; the transition is worth more than the omission |

Written to be correct whether Wire generates these as `String?` or as non-null
`String` defaulting to `""`, because it treats the two identically. That question is
still open — the artifact is not available locally to inspect, and
`allWarningsAsErrors` is not set, so a green build does not settle it. §9 pins it
with a test instead of assuming.

### 5.4 Memory

`hwModel` and `role` are `Enum.name`, which returns an interned instance shared by
every record, so they cost a reference each and no characters. The two names are the
only per-record allocation: about 130 bytes for a 20-character long name and a
4-character short name, plus roughly 70 bytes of object and fields. Call it **200
bytes per node**.

The Zona Centro mesh runs past 1000 nodes (`CLAUDE.md`), so 2000 is a generous
ceiling for what one phone can hear: **about 400 KB**. For comparison the signal ring
buffer already budgets 125 KB for a single watched node. No cap is imposed: dropping
a node from NodesFromAir would un-name a relay, which is the failure this design
exists to prevent.

## 6. `NodeDirectory`

### 6.1 New state

```kotlin
private val nodesFromAir = mutableMapOf<Int, AirNodeRecord>()
private val pendingNodes = mutableMapOf<Int, NodeRecord>()
private var pendingReceivedAny = false
```

`pendingNodes` accumulates a refresh in progress. `pendingReceivedAny` is the guard
of §3 — a separate flag rather than `pendingNodes.isNotEmpty()`, so the rule reads as
"a node_info frame arrived" rather than "the buffer happens to be non-empty", and so
a future change cannot make an empty-but-real round wipe the store.

### 6.2 `applyUser` writes to the air store

Takes the receive time as a parameter, in keeping with the standing rule that only
`SystemTimeSource` reads the clock. Merges per §5.3, creates when absent, stamps
`receivedAtMillis` on every call.

It no longer touches `nodes`. That map becomes what the radio said, and nothing else.

### 6.3 `applyNodeInfo` writes to the buffer

Into `pendingNodes`, setting `pendingReceivedAny = true`. It does not merge onto the
previous refresh: a staged replace means each round stands alone. Within one round,
should the same node number arrive twice, the later frame wins.

### 6.4 `markLoaded` commits

```
if (!pendingReceivedAny) return          // a config-only round: change nothing
nodes = pendingNodes                      // wholesale replace
every record stamped receivedAtMillis = atMillis
loadedAtMillis = atMillis
pendingNodes.clear(); pendingReceivedAny = false
```

A round that completes having carried no node_info leaves both the store and
`loadedAtMillis` untouched. Today `loadedAtMillis` is updated by any completed round;
that becomes wrong once it dates the store's contents, and this corrects it.

### 6.5 Lifecycle

| Event | `nodes` | `nodesFromAir` | `pendingNodes` |
| :--- | :--- | :--- | :--- |
| **Reset** (menu) | untouched | untouched | untouched |
| **Reload node DB** | replaced on completion | untouched | consumed |
| Reconnect, same node | replaced on completion | untouched | consumed |
| **Connect to a different node** | cleared | **cleared** | cleared |

`clearRuntimeData()` keeps its current body and gains nothing: requirement 10 is
satisfied by it continuing to touch only `positions` and `telemetryRecords`.
`clearAll()` gains `nodesFromAir.clear()` and the buffer reset.

## 7. Reading

### 7.1 `NodeIdentity` — new, `stats/model/`

```kotlin
enum class IdentitySource { AIR, DB, NONE }

data class NodeIdentity(
    val source: IdentitySource,
    val longName: String?,
    val shortName: String?,
    val hwModel: String?,
    val role: String?,
    val hasPublicKey: Boolean,
    /** Null when source is NONE. */
    val receivedAtMillis: Long?,
) { companion object { val NONE: NodeIdentity } }
```

### 7.2 `NodeDirectorySnapshot.identity(nodeNum)`

Per record: an air record if one exists, otherwise the database record, otherwise
`NONE`. No field-level mixing, by the owner's ruling.

### 7.3 Both key sets, everywhere a node is looked for

`matchingNodeNums` and `uniqueRelayName` scan `nodes.keys` today. After the split
they must scan the **union** of `nodes.keys` and `nodesFromAir.keys`, and
`shortName(nodeNum)` must resolve through `identity()`.

This is not a refinement. A relay is named by matching the low byte of its
`relay_node` field against known node numbers; a node known only from the air would
otherwise be invisible to that match, and the feature would name *fewer* relays after
this change than before it.

### 7.4 What stays database-only

`snr`, `last_heard`, `dbPosition` and `hopsAway`. Requirement 7 for the first two,
and the air path carries none of the four in any case — `User` has no such fields.

A node evicted from the radio's database but present in NodesFromAir therefore keeps
its name and loses its stored SNR and last-heard. That follows from requirements 5
and 7 together and is intended, but it is a visible change and belongs in the
acceptance checklist.

## 8. Interface

### 8.1 The label

On the node detail panel the five identity fields carry one label and one timestamp
for the record as a whole:

```
Air Received  3 Sep 2026, 09:12:41
```

or `DB Received`, formatted through the existing 12/24-hour Time option, by the same
`StatsFormat` path every other absolute time already uses. When `source` is `NONE`,
no label and no timestamp.

Spelled "Received". The owner's instruction wrote "Recieved"; this is taken as a
typing slip rather than an intended spelling, and is flagged here so it can be
overruled.

### 8.2 The header counter

`format_db_header` becomes a two-count line: `DB(n) · Air(m)`. `DB(n)` will now sit
at the radio's true count permanently, and `Air(m)` is the number that answers the
observation in §1 by growing overnight.

### 8.3 Strings

New: `label_air_received`, `label_db_received`. Changed: `format_db_header` gains the
second count. Both locales, as every string in this application already is.

## 9. Testing

Unit tests, `stats/`, no Android dependency:

1. A thin `NODEINFO_APP` does not blank a field a fat one filled.
2. `role` is taken even when it is `CLIENT`.
3. An empty `User` does not erase a stored name — this also settles the open Wire
   nullability question in §5.3 by observation rather than assumption.
4. The staged swap drops a node the refresh no longer reports.
5. A completed round that carried **no** node_info leaves the store and
   `loadedAtMillis` untouched.
6. Per-record precedence: an air record with a blank long name wins over a database
   record that has one.
7. `uniqueRelayName` finds a node present only in NodesFromAir.
8. **Reset** preserves both stores; connecting to a different node clears both.

## 10. Out of scope

- Deriving `hopsAway` for air-only nodes from `hop_start − hop_limit`. The value is
  already computed in `PacketClassifier` and folded into `RemoteNodeStats`; feeding
  it back into a node record is a separate change with its own precedence question.
- Persisting either store across process death.
- The confirmation dialog before switching nodes, already recorded in
  `docs/deferred-work.md`.
- The Relays mirror of the own-node fix, still pending the owner's decision.
