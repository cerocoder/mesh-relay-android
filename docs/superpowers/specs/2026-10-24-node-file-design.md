# Design: saving and loading the node database

**Date:** 2026-10-24
**Status:** draft, awaiting the owner's approval
**Source:** the owner's instruction of 2026-10-24 and the brainstorming session of the same day
**Builds on:** `docs/superpowers/specs/2026-09-03-node-storage-split-design.md`,
`docs/superpowers/specs/2026-09-04-relay-candidate-comparison-design.md`

Items marked **(my call)** were not decided by the owner; they are the author's choices and
should be checked while reading.

---

## 1. Why

Learning the neighbourhood from the air takes a day or two of listening. The owner needs that
knowledge, and the conclusions drawn from it (which node is really the router behind a relay
byte), to survive past one session and to be reloadable, so the days of waiting are not repeated.

## 2. Goals, non-goals, anti-goals

**Goals**
- "Save nodes" writes the node database and the owner's annotations to a file.
- "Load nodes" reads such a file back and merges it into the running app.
- Loading can only add knowledge; it never makes the app know less than it already does.

**Non-goals**
- Signal statistics: counters, series, relay and neighbour tables. They are neither saved nor
  touched by a load. The sole exception is the two averages in section 4.
- Watch points: the field exists and is always empty. Bookmarks on the Relay graph, and jumping
  between them, are a later feature.
- Using `avg_snr`: it is saved now, but the SNR line on the Graph is a later feature.
- Any use of `known_as_relay` beyond storing it and the skipped-relay display in section 6.
  "How to account for it" is a later decision.

**Anti-goals** (failures even if it technically works)
- A load that overwrites something the radio or the air told this session.
- A load that silently discards a skip the owner made.
- Loaded numbers appearing as if they were measured this session.

## 3. Menu

"Save nodes" and "Load nodes" are commands in the menu of the **My node** view. Both use the
system file picker, the way CSV export already does **(my call)**. Strings in English and
Spanish (`StringsParityTest` enforces parity).

## 4. File content

One JSON file **(my call)**, with a format version. The loader ignores fields it does not
recognise and rejects a file with a version it does not know.

Top level: `version`, `nodes` (array), `skipped_nodes` (array of node numbers).

Each node record:

| Group | Field | Meaning |
| :--- | :--- | :--- |
| Standard | the identity and database fields the app holds today | `num`, long and short name, hardware model, role, public-key presence, database position, `last_heard`, `hops_away`, database SNR (exact set fixed by the plan, from `NodeRecord` and `AirNodeRecord`), telemetry  |
| Additional | `learnt_source` | enum `node` (from the local radio's database) or `air` (from a NODEINFO packet) |
| Additional | `known_as_relay` | boolean, section 6 |
| Additional | `notes` | free text typed by the owner |
| Additional | `watch_points` | array, always empty for now |
| Additional | `avg_rssi` | average direct RSSI of this node, absent when unknown |
| Additional | `avg_snr` | average direct SNR of this node, absent when unknown |

`learnt_source` says where the information held for the node came from. When both stores hold
the node, the source follows the store the app currently believes for identity (the air record
when one exists, per the storage-split design).

## 5. Save and load rules

**Save**
- The averages come from this session's direct measurements of the node when there are any.
  Otherwise the file carries forward whatever average was loaded earlier, so a second save does
  not lose it.

**Load**, per node in the file:
- Not in memory: a new node is created from the file.
- Already in memory:
  - **Additional attributes** (`learnt_source`, `known_as_relay`, `notes`, `watch_points`, `avg_rssi`, `avg_anr`) are
    overwritten completely by the file.
  - **Standard attributes** the app has not filled yet are added from the file. Standard
    attributes already read from the local node or heard on the air stay unchanged.
  - Later, any value that arrives from the air or the local node overwrites a loaded one.
- **Skipped nodes**: the file's list is appended (union) to the skip list the app holds at that
  moment. The skip list stays in application settings, where it lives today
  (`SettingsRepository.skippedRelayNodes`); the file only carries a copy.
- **Statistics** are not affected.

**The averages on load** are held as a fallback only. They never enter the counters, the
series, or the Graph's point cloud. The Graph's red line uses a loaded `avg_rssi` only while the
app has **zero** direct packets from that node this session. The first direct packet makes the
live value take over. The owner confirmed that zero is the whole definition: there is no
minimum sample count, consistent with the relay-candidate design, which deliberately has none.

## 6. `known_as_relay` and skipped relays

A relay byte can match several nodes. The owner skips the ones known not to be relays; the app
selects the match that remains and the Relay view shows its address and name. `known_as_relay`
is true for that node **(derivation is my call: the sole candidate left for its byte)**.

When the flag and a skip meet on the same node (typically: the file says it is the relay, the
running app has it skipped):
- Nothing is discarded; the node keeps both facts.
- The Matching nodes tab shows "1 skipped relay" instead of "N skipped nodes". The count is one
  whatever other nodes are skipped on that byte.
- The command **Return matching relay** replaces "Clear skipped nodes" in that state. It removes
  only that node from the skip list, and the node is again the assigned relay.
- The other skipped nodes stay skipped, and "Clear skipped nodes" remains available as a
  separate item **(my call)**.

## 7. Constraints

- `stats/` may not import `ui/` or `transport/`; file reading and writing belongs outside
  `stats/`, with merging in `NodeDirectory`.
- No Russian text or comments anywhere.
- Persistent app settings stay in `SettingsRepository`; this feature adds no new persistent
  store, the file is the store.
- JSON is read and written with `org.json`, which ships with Android (the owner's decision), so
  no new runtime dependency is added. It is only a stub in JVM unit tests, so the test source
  set needs `testImplementation("org.json:json")`. All reading and writing sits in one class
  behind a plain interface, so a different library could replace it without touching callers.

## 8. Left to the builder

- The exact standard-field set in the file, from `NodeRecord` and `AirNodeRecord`.
- The shape of unknown-version and corrupt-file errors, shown to the
  user in both languages.
- How merging is expressed in `NodeDirectory` without breaking its single-writer discipline.
- Tests for each rule in section 5, including the zero-packet fallback and the union of skips.

## 9. To confirm when reading

- **(my call)** The directory is cleared when the app connects to a *different* local node
  (storage-split design, requirement 9). Loaded nodes are cleared with it; the owner reloads the
  file. Say so if loaded nodes should survive that.
- **(my call)** Loading while disconnected is allowed, so a file can be loaded before connecting.
