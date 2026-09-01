# Two sort modes and a "My node" screen — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two sort modes — *Known nodes* and *Latest packet* — and move everything about
this device onto a third main screen, "My node", leaving Relays and Neighbours to be lists.

**Architecture:** No new state and no engine redesign. Both sort keys already exist on the models
(`RelayStats.knownNodesCount`, `RelayStats.lastPacketAtMillis`, `NeighbourStats.lastPacketAtMillis`),
so the sort work is two `when` branches and one fallback rule. The new screen is a third
`MainTab` rendering the existing `NodeCard` against the local node — the same card the detail
screens draw — so it inherits long name, short name, position, altitude, Src and the map links
without a new component. The three status strips become one.

**Tech Stack:** Unchanged. Kotlin 2.4.10, Compose BOM 2026.06.01, JUnit 4. No new dependencies.

**Spec:** none. This plan is the whole specification: it is an increment on
`docs/superpowers/specs/2026-08-26-mesh-relay-android-design.md`, which stands unchanged except
where this plan says otherwise.

## Global Constraints

Every task's requirements implicitly include this section. These are the subset of the original
plan's constraints that this work can violate; the rest still apply.

- **No new dependencies.** Not `navigation-compose`, not `appcompat`, not `material-icons-extended`.
- **`stats/**` must not import `android.*`**, in main or test sources.
- **No user-facing literal strings in Kotlin.** Everything comes from `R.string`.
- **Both locales, always.** Every key added to `values/strings.xml` gets one in `values-es/strings.xml`
  in the same commit. `StringsParityTest` fails the build otherwise.
- **No `@Composable` may contain arithmetic or formatting.** Computation lives in a pure function
  with its own test.
- **No Russian anywhere** — code, comments, commits, resources, documents.
- Never call `System.currentTimeMillis()` outside `SystemTimeSource`.
- The verification loop is CI plus the phone. There is no local Android SDK, so *nothing here is
  verified by looking at it* — every task ends at a green CI run, and Tasks 3-5 are not done until
  they have been read off `uiautomator dump` on the device, in Spanish as well as English.
  **`docs/verifying.md` is how to reach both**: `gh` is not installed on this machine, GitHub's
  artifact endpoint returns 401 without a token, and neither is discoverable by trying. Read it
  before the first push, not after the first failure.

## Decisions taken before writing this plan

Recorded because each one closes a question a reader will otherwise re-open.

1. **"Known nodes" is hidden on Neighbours, not faked there.** The count is
   `RelayStats.knownNodesCount` — the distinct remote nodes whose traffic a relay carries. A
   neighbour is one node heard directly and has no such set. It is left out of the Neighbours sort
   menu entirely.
2. **The sort mode stays global, and the fallback is shown, not hidden.** One `sortMode` lives in
   the engine and both lists read it — as in the original, where it is one key press. So *Known
   nodes* can still arrive at Neighbours by being chosen on Relays or set as the default in
   Settings. When it does, neighbours sort by packet count **and the strip says "Packet count"**.
   One function, `SortMode.forNeighbours()`, is the only place that rule exists, and both the engine
   and the strip call it. The alternative — a per-screen sort mode — is a bigger change than these
   two features justify.
3. **"My node" is the last tab.** Relays stays the landing tab after a handshake, so the connect
   flow is untouched.
4. **"My node" draws the existing `NodeCard`.** It already renders long name, short name, role,
   hardware, position/altitude/Src/links (via `PositionLine`), DB last-heard, uptime, restarts,
   telemetry and public key. `index` and `onSkip` are `null` — there is nothing to number and
   nothing to rule out. Two fields read oddly for one's own node ("Last heard" in the database is
   the database's record of itself); they are left as they are rather than forked, and if they read
   badly on hardware that is a field issue, not a reason to copy the card.
5. **The local-node block leaves Relays and Neighbours entirely**, per the request. That deletes
   `ui/common/LocalNodeLine.kt`, added a day earlier for F-4. Recorded so the deletion does not look
   like a mistake: F-4 asked for the label and the name on one line, and the answer is now that the
   whole block lives on its own screen.

## Model selection

If this plan is executed with `superpowers:subagent-driven-development`, dispatch each task on the
model named here. If it is executed inline, ignore this section - everything runs on the session's
own model.

| # | Task | Implementer | Reviewer |
|---|---|---|---|
| 1 | Two sort keys in the engine | Sonnet | Sonnet |
| 2 | The modes on screen (`SortAction`) | Sonnet | Sonnet |
| 3 | Extract the status strip | Sonnet | Sonnet |
| 4 | My node screen + third tab | Sonnet | **Opus** |
| 5 | Strip the local node from the lists + docs | Sonnet | Sonnet |
| — | Final whole-branch review | — | **Opus** |

**No Haiku anywhere, deliberately.** Tasks 2 and 5 look like transcription and are not. Task 5 is a
deletion across two screens, and the same deletion done by hand on 2026-09-01 took
`NeighbourStatusStrip`, `LabelledCount` and `dbLoadTimeText` out with it, by matching a line range
instead of a function boundary - CI caught it as an unresolved reference. It also carries a real
judgment call: whether `meshviewUrl` is still used by each screen afterwards. Task 2 changes a
shared component's API and rewires three call sites; a cheap model takes two or three times the
turns on multi-file edits, which costs more wall-clock than the tier saves.

**Task 4 gets an Opus reviewer** because it is the only task touching navigation and the
`rememberSaveable` back stack, where a defect survives a rotation or a language change and surfaces
somewhere else entirely.

**Why none of the reviewers drop a tier.** Task 1 is the only task with executable tests. Tasks 2-5
are Compose, and this project has no Compose test harness, so their only gates are "it compiles" and
"it was read off a `uiautomator dump`". Their reviewers are doing more work than usual, not less.

## File Structure

```
app/src/main/kotlin/com/cerocoder/meshrelay/
├── stats/
│   ├── SortMode.kt                   MODIFY  +2 entries, +forNeighbours()
│   └── MeshStatsEngine.kt            MODIFY  +2 branches, route neighbours through forNeighbours()
├── ui/
│   ├── common/
│   │   ├── SortModeLabels.kt         MODIFY  +2 labels
│   │   ├── StatsTopBar.kt            MODIFY  sort becomes an optional SortAction
│   │   ├── StatusStrip.kt            CREATE  the one strip, three call sites
│   │   └── LocalNodeLine.kt          DELETE  its content moves to MyNodeScreen
│   ├── mynode/MyNodeScreen.kt        CREATE
│   ├── nav/{MainTab,Screen}.kt       MODIFY  +MY_NODE
│   ├── MeshRelayNavHost.kt           MODIFY  third NavigationBarItem, third branch
│   ├── relays/{RelayListScreen,StatusStrip}.kt      MODIFY / DELETE the local copy
│   └── neighbours/NeighbourListScreen.kt            MODIFY
└── app/src/main/res/values{,-es}/strings.xml        MODIFY  +4 keys each
```

---

### Task 1: The two sort keys, in the engine

Pure Kotlin, fully unit-testable, and the only task with tests. Do it first: Task 2 has nothing to
label until these exist.

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/SortMode.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngine.kt:352-379`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngineTest.kt`

**Interfaces:**
- Produces: `SortMode.KNOWN_NODES`, `SortMode.LATEST_PACKET`, `SortMode.forNeighbours(): SortMode`.

- [ ] **Step 1: Write the failing tests**

Add to `MeshStatsEngineTest`, following the fixtures the existing sort tests use:

```kotlin
@Test
fun `known nodes sorts relays by how many remote nodes they carry`() { /* 3, 1, 2 -> 3, 2, 1 */ }

@Test
fun `latest packet puts the most recently heard relay first`() { /* by lastPacketAtMillis desc */ }

@Test
fun `latest packet orders neighbours the same way`() { }

@Test
fun `known nodes falls back to packet count for neighbours`() {
    // A neighbour has no set of carried nodes. The mode can still reach this list by
    // being chosen on the relay screen or saved as the default, and when it does the
    // list must be ordered by something real - not left in map order.
    assertEquals(SortMode.PACKETS, SortMode.KNOWN_NODES.forNeighbours())
    // and the engine must agree with that function, not reimplement it
}

@Test
fun `every other mode is unchanged for neighbours`() {
    SortMode.entries.filter { it != SortMode.KNOWN_NODES }
        .forEach { assertEquals(it, it.forNeighbours()) }
}
```

- [ ] **Step 2: Run them and watch them fail**

`gradle :app:testDebugUnitTest --tests '*MeshStatsEngineTest*'` — expect "unresolved reference:
KNOWN_NODES". (There is no local Android SDK; this step runs in CI. Push a branch commit and read
the run, or accept that Step 4 is where the failure/pass edge is actually observed.)

- [ ] **Step 3: Implement**

```kotlin
/**
 * Ports SORT_MODES, mesh_stats.py:167-174 - the first five. [KNOWN_NODES] and
 * [LATEST_PACKET] are additions, not ports: the terminal tool has neither, and
 * a reader comparing the two should not go looking for them.
 *
 * Labels live in the ui layer.
 */
enum class SortMode {
    PACKETS, PERCENT, AVG_SNR, AVG_RSSI, NAME, KNOWN_NODES, LATEST_PACKET;

    /**
     * This mode as the neighbour list can honour it.
     *
     * [KNOWN_NODES] counts the distinct remote nodes a relay forwards for. A
     * neighbour is a single node heard directly and has no such set, so it is not
     * offered in the neighbour sort menu - but the sort mode is one engine-wide
     * value, so it can still arrive here from the relay screen or from the saved
     * default. It degrades to [PACKETS].
     *
     * The neighbour status strip calls this too, so the screen names the order it
     * actually applied rather than the one that was asked for. Both callers must
     * keep using this function: an inlined `if` in either place is how the label
     * and the list start disagreeing.
     */
    fun forNeighbours(): SortMode = if (this == KNOWN_NODES) PACKETS else this
}
```

In `MeshStatsEngine.sortedRelays`, add two branches — note both are `sortedByDescending`, and both
keys already exist on `RelayStats`:

```kotlin
SortMode.KNOWN_NODES -> values.sortedByDescending { it.knownNodesCount }
SortMode.LATEST_PACKET -> values.sortedByDescending { it.lastPacketAtMillis }
```

In `sortedNeighbours`, route the whole `when` through the fallback and add one branch:

```kotlin
return when (sortMode.forNeighbours()) {
    ...
    SortMode.LATEST_PACKET -> values.sortedByDescending { it.lastPacketAtMillis }
    // forNeighbours() has already mapped this away; the branch exists because the
    // `when` is exhaustive over the enum and a silent `else` would swallow the next
    // mode someone adds.
    SortMode.KNOWN_NODES -> values.sortedByDescending { it.packetCount }
}
```

- [ ] **Step 4: Run the tests, green**
- [ ] **Step 5: Commit** — `feat(stats): sort by known nodes and by latest packet`

---

### Task 2: The two modes on screen

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/common/SortModeLabels.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/common/StatsTopBar.kt`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/neighbours/NeighbourListScreen.kt`

**Interfaces:**
- Consumes: `SortMode.KNOWN_NODES`, `SortMode.LATEST_PACKET`, `SortMode.forNeighbours()` (Task 1).
- Produces: `SortAction(mode, available, onSet)`; `StatsTopBar(sort: SortAction?, …)`.

- [ ] **Step 1: Strings, both locales**

```xml
<!-- values -->
<string name="sort_known_nodes">Known nodes</string>
<string name="sort_latest_packet">Latest packet</string>
<!-- values-es -->
<string name="sort_known_nodes">Nodos conocidos</string>
<string name="sort_latest_packet">Último paquete</string>
```

- [ ] **Step 2: Labels**

Add both to `SortModeLabels.labelOf`. The `when` is exhaustive over the enum with no `else`, so
forgetting one is a compile error, not a runtime `?`.

- [ ] **Step 3: Make the sort action optional and per-screen**

`StatsTopBar` currently takes `sortMode` and `onSetSortMode` as required parameters. "My node" has
no sort at all and Neighbours has a shorter menu, so both become one nullable value, mirroring the
`ReloadAction` already in this file:

```kotlin
/**
 * The sort control, or `null` on a screen that sorts nothing (My node).
 *
 * [available] is per-screen rather than always `SortMode.entries`: the neighbour
 * list leaves out [SortMode.KNOWN_NODES], which counts something a neighbour does
 * not have. [mode] is what the menu ticks, and on the neighbour list it is the
 * mode *after* `forNeighbours()` - so when an unofferable mode arrives from the
 * relay screen, the tick and the strip agree with the order actually applied.
 */
data class SortAction(
    val mode: SortMode,
    val available: List<SortMode>,
    val onSet: (SortMode) -> Unit,
)
```

Wrap the sort `Box` in `if (sort != null) { … }` and iterate `sort.available` instead of
`SortMode.entries`.

- [ ] **Step 4: Wire the two list screens**

```kotlin
// RelayListScreen
sort = SortAction(snapshot.sortMode, SortMode.entries, onSetSortMode)

// NeighbourListScreen
sort = SortAction(
    mode = snapshot.sortMode.forNeighbours(),
    available = SortMode.entries - SortMode.KNOWN_NODES,
    onSet = onSetSortMode,
)
```

- [ ] **Step 5: The neighbour strip names the effective mode**

In `NeighbourStatusStrip`, `SortModeLabels.labelOf(snapshot.sortMode.forNeighbours())`. Without
this the strip claims a sort the list did not perform.

- [ ] **Step 6: Settings needs no change** — its picker iterates `SortMode.entries` and gains both
  automatically. Confirm by reading, and note it in the commit rather than editing the file.

- [ ] **Step 7: Commit and push. CI green before Task 3.**

---

### Task 3: One status strip instead of three

Do this **before** the new screen, not after. There are two copies today; the new screen would make
three, and F-1 was exactly this mistake left to grow.

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/common/StatusStrip.kt`
- Delete: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/relays/StatusStrip.kt`
- Modify: `NeighbourListScreen.kt` (drop its private `NeighbourStatusStrip`, `LabelledCount`,
  `dbLoadTimeText`), `RelayListScreen.kt`

**Interfaces:**
- Produces: `StatusStrip(snapshot, counts: List<StatusCount>, sortMode: SortMode?, modifier)`, where
  `StatusCount(@StringRes val label: Int, val value: Int)`.

The three call sites:

| Screen | Counts | Sort shown |
|---|---|---|
| Relays | Total, Relayed | yes, `snapshot.sortMode` |
| Neighbours | Direct | yes, `snapshot.sortMode.forNeighbours()` |
| My node | Total, Relayed, Direct | **no** |

- [ ] **Step 1: Move, do not rewrite.** Take `ui/relays/StatusStrip.kt` as the base — the DB header
  line, `LabelledCount` and `dbLoadTimeText` are identical in both copies. The only differences are
  which counts are drawn and whether the sort pair is drawn.
- [ ] **Step 2: Parameterise the counts.** A `List<StatusCount>` rather than three booleans, so the
  My node row is a list of three and nothing needs a fourth flag later.
- [ ] **Step 3: `sortMode: SortMode?`** — `null` draws no sort pair. Same shape as `SortAction`
  above, for the same reason.
- [ ] **Step 4: Keep the paused badge on all three.** Pausing is engine-wide; a screen that hides
  the badge would let a user forget the whole app is frozen. The existing comment about
  `relays_status_paused` being deliberately shared survives the move.
- [ ] **Step 5: With three counts the row may not fit.** `Total 157 Relayed 128 Direct 29` plus the
  paused badge, in Spanish (`Repetidos`, `Directos`), is the F-3 shape again: a `Row` with more
  content than width. Use `FlowRow`, as `PositionLine` now does. Do not discover this on the phone.
- [ ] **Step 6: Commit. CI green.**

---

### Task 4: The "My node" screen and its tab

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/mynode/MyNodeScreen.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/nav/Screen.kt` (`MainTab`)
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/MeshRelayNavHost.kt`
- Modify: `app/src/main/res/values{,-es}/strings.xml`

**Interfaces:**
- Consumes: `StatsTopBar(sort = null, …)` (Task 2), `StatusStrip` (Task 3), `NodeCard` (unchanged).

- [ ] **Step 1: `MainTab` gains `MY_NODE`, last.**

`enum class MainTab { RELAYS, NEIGHBOURS, MY_NODE }`. Ordinal order is tab order. Check
`BackStack.selectTab` and `backStackSaver` — `selectTab` is tab-agnostic, but confirm the saver
round-trips the new entry (`BackStackTest`).

- [ ] **Step 2: Strings**

```xml
<string name="nav_my_node">My node</string>          <!-- es: Mi nodo -->
<string name="my_node_no_info">This node has not sent its own details yet. They arrive with its next node info packet, or on the next database reload.</string>
<!-- es: Este nodo aún no ha enviado sus datos. Llegarán con su próximo paquete de información de nodo, o en la próxima recarga de la base de datos. -->
```

Reuse `relays_local_node_unknown` for "no local node number yet" rather than adding a key.
(`deferred-work.md` already carries a note to rename the `relays_local_node*` keys to neutral ones;
this is the moment that rename earns its keep, but it is a separate change — do not fold it in.)

- [ ] **Step 3: The screen**

Three states, in this order:

```kotlin
@Composable
fun MyNodeScreen(
    snapshot: StatsSnapshot,
    meshviewUrl: String?,
    onTogglePause: () -> Unit,
    onSetGaugeMode: (GaugeMode) -> Unit,
    gaugeMode: GaugeMode,
    onReset: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            StatsTopBar(
                title = stringResource(R.string.nav_my_node),
                sort = null,          // nothing on this screen is a list
                gaugeMode = gaugeMode,
                onSetGaugeMode = onSetGaugeMode,
                paused = snapshot.paused,
                onTogglePause = onTogglePause,
                onReset = onReset,
                onOpenSettings = onOpenSettings,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            StatusStrip(
                snapshot = snapshot,
                counts = listOf(
                    StatusCount(R.string.relays_status_total, snapshot.counters.totalPackets),
                    StatusCount(R.string.relays_status_relayed, snapshot.counters.totalRelayedPackets),
                    StatusCount(R.string.neighbours_status_direct, snapshot.counters.totalDirectPackets),
                ),
                sortMode = null,
            )
            val localNodeNum = snapshot.directory.localNodeNum
            val record = localNodeNum?.let { snapshot.directory.node(it) }
            when {
                localNodeNum == null -> Message(R.string.relays_local_node_unknown)
                record == null -> Message(R.string.my_node_no_info)
                else -> NodeCard(
                    index = null,
                    record = record,
                    // from = null: there is no "self" to measure a distance from, so
                    // PositionLine draws coordinates, altitude and Src with no distance.
                    location = snapshot.directory.locationInfo(localNodeNum, from = null),
                    telemetry = snapshot.directory.telemetry(localNodeNum),
                    meshviewUrl = meshviewUrl,
                    onSkip = null,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
    }
}
```

`verticalScroll`, not a `LazyColumn`: this is one card, and the card is taller than the screen once
telemetry arrives.

The middle state is real and reachable — the directory learns the local node's *number* from the
`my_node_info` handshake before any `NodeInfo` for it has been heard. It is the state that made
`shortName` return `""` in F-4.

- [ ] **Step 4: Tab and branch in `MeshRelayNavHost`**

A third `NavigationBarItem` using `Icons.Filled.Person` (core set; the other two tabs use
`Icons.Filled.Share` and `Icons.AutoMirrored.Filled.List` from the same set). If the compile
rejects it, hand-author `res/drawable/ic_nav_my_node.xml` rather than adding
`material-icons-extended` — `ic_action_pause.xml` is the precedent.

A `MainTab.MY_NODE ->` branch calling `MyNodeScreen`. The connect hand-over keeps pushing
`Screen.Main(MainTab.RELAYS)` — do not change it.

- [ ] **Step 5: Previews.** Three: populated, no-info, no-local-node. `SampleData` already has a
  local node with `longName = "Mi Nodo Local"`.

- [ ] **Step 6: Commit. CI green.**

---

### Task 5: Take the local node off the two list screens

Last, so the two lists are never without it *and* without a My node tab in the same commit.

**Files:**
- Delete: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/common/LocalNodeLine.kt`
- Modify: `RelayListScreen.kt`, `NeighbourListScreen.kt`
- Modify: `docs/field-issues.md`, `docs/acceptance-checklist.md`

- [ ] **Step 1:** Remove the `LocalNodeLine(...)` call and its import from both screens, delete the
  file, and drop `meshviewUrl` from both screens' parameters — check first whether either still uses
  it (the relay *card* may; the neighbour card may). Remove it only where it becomes unused, and
  update `MeshRelayNavHost`'s call sites to match.
- [ ] **Step 2:** Note in `docs/field-issues.md` under F-4 that the line it fixed now lives on the
  My node screen. Leave F-4's finding and its "what was done" text intact — it was true.
- [ ] **Step 3:** Add to `docs/acceptance-checklist.md`: the two sort modes on both lists, the
  Known-nodes fallback (choose it on Relays, switch to Neighbours, confirm the strip reads "Packet
  count" and the order matches it), the three My node states, and the three-count row at a large
  font scale in Spanish.
- [ ] **Step 4:** Commit, push, CI green, then **install on the phone and read every claim above off
  a `uiautomator dump`**, in both languages. This is the only verification that has ever caught a
  layout defect in this project.

---

## What this plan deliberately does not do

- **F-6 is not fixed here.** Cards still teleport when the sort key changes, and adding two more
  sort modes does not make that worse — *Latest packet* reorders on every packet exactly as *Packet
  count* does. It stays a separate decision.
- **The sort mode stays global.** Per-screen sort modes would remove the `forNeighbours()` fallback
  entirely, and are the right answer if a third list screen ever appears. Not for two features.
- **`relays_local_node*` are not renamed**, though this work makes the names wronger. It is a
  string-key rename touching both locales and is already recorded in `deferred-work.md`.
- **The release-signing question is untouched** and still open.
