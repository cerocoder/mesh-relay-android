# Six field fixes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix six defects the owner found using the app on a phone: a false packet-arrival flash while
scrolling, "Never" shown for a packet that just arrived, two mislabelled database fields, our own node
polluting the Neighbour statistics, statistics surviving a reconnect to a *different* node, and no way
to leave the app and free what it holds.

**Architecture:** No new architecture. Five of the six are small, local corrections — two pure functions,
one string pair, one engine guard, one container comparison. The sixth (Exit) is the only one that adds
a path: a menu action that unwinds the connection, the foreground service and the process in that order.

**Tech Stack:** Unchanged. Kotlin 2.4.10, Compose BOM 2026.06.01, JUnit 4. No new dependencies.

**Spec:** none. This plan is the specification; it is an increment on
`docs/superpowers/specs/2026-08-26-mesh-relay-android-design.md`, which stands except where this says
otherwise. Root causes below were read out of the code, not guessed.

---

## Global Constraints

Every task's requirements implicitly include this section.

- **No new dependencies.**
- **`stats/**` must not import `android.*` or `ui/**`**, in main or test.
- **No user-facing literal strings in Kotlin.** Everything from `R.string`.
- **Both locales, always**, in the same commit. `StringsParityTest` fails the build otherwise and also
  checks `%1$s`-style placeholders match.
- **No `@Composable` may contain arithmetic or formatting.** Computation lives in a pure function with
  its own test.
- **No Russian anywhere** — code, comments, commits, resources, documents.
- Never call `System.currentTimeMillis()` outside `SystemTimeSource` (existing `@Preview` fixtures excepted).
- **No local build is possible**: no Android SDK, no Gradle wrapper, no `kotlinc`. Nothing can be
  compiled or run on the development machine. Write tests, reason about them, and say plainly in each
  report that nothing was executed. CI is the gate; the phone is the acceptance.
- The verification loop is CI, then the phone, per `docs/verifying.md`. `gh` is not installed and the
  unauthenticated GitHub API allows sixty requests an hour for the whole machine.

## Decisions taken before writing this plan

Four came from the owner, in answer to questions put before planning. Two of them were the options
carrying known downsides; they were chosen knowingly and are implemented as chosen, with the residual
gap recorded rather than hedged.

1. **Our own node's packets are dropped from `totalPackets` as well as from Direct** (owner's choice).
   The node becomes entirely invisible to the statistics. Consequence, stated because it is a real
   change of meaning: `totalPackets` stops being "traffic the radio delivered" and becomes "traffic from
   other nodes". A packet with no sender (`from == 0`) is still counted, as today — that rule is
   documented in the engine and is not in scope here.
2. **Our own node's payloads are still decoded.** This is not a deviation, it is a correctness
   requirement the owner's choice would otherwise break: our own `POSITION_APP` packets are how
   `directory.localPosition()` learns where *we* are, which is what stamps every measurement in the
   Graph when *Use phone location* is off. Excluding the packet from the statistics must therefore
   happen **after** `decodePayload` and **before** the counters.
3. **A different node is detected by BLE address** (owner's choice, over node number). Known gap,
   recorded in `deferred-work.md` rather than silently patched: the same node reached at a different
   address wipes unnecessarily (harmless), and two different nodes at the same address would not wipe
   (stale statistics). A `my_node_num` cross-check would close the second case and is a small addition
   if the field ever shows it.
4. **Exit disconnects, stops the service, removes the task, then kills the process** (owner's choice).
   Ordering is load-bearing: the GATT disconnect must complete before the process dies, or the radio is
   left with a half-open connection. `exitProcess` is therefore reached from a coroutine that has
   awaited the disconnect, not from the click handler.

Two more are mine, and small:

5. **Exit is confirmed before it acts.** It destroys a session's statistics, which are not persisted —
   the same reason Reset is confirmed. It reuses that dialog shape.
6. **Exit lives in the overflow menu of `StatsTopBar`**, beside Settings and Reset, so it is reachable
   from Relays, Neighbours and My node. Not on the device list: leaving from there is what the system
   back gesture already does.

---

## File Structure

```
app/src/main/kotlin/com/cerocoder/meshrelay/
├── stats/
│   ├── AgeText.kt                    MODIFY  a fresh packet is 0 seconds, not Never
│   ├── NodeDirectory.kt              MODIFY  +localNodeNum accessor, +clearAll()
│   └── MeshStatsEngine.kt            MODIFY  own-node guard; a reset that forgets the node
├── ui/
│   ├── common/
│   │   ├── SignalGauge.kt            MODIFY  flash on a change, not on composition
│   │   └── StatsTopBar.kt            MODIFY  +Exit item and its confirmation
│   ├── MeshRelayNavHost.kt           MODIFY  thread onExit through to the three screens
│   ├── relays/RelayListScreen.kt     MODIFY  +onExit
│   ├── neighbours/NeighbourListScreen.kt MODIFY  +onExit
│   └── mynode/MyNodeScreen.kt        MODIFY  +onExit
├── AppContainer.kt                   MODIFY  +shutdown(), wipe on a new address
└── MainActivity.kt                   MODIFY  perform the exit

app/src/main/res/values{,-es}/strings.xml   MODIFY  2 renamed values, +4 keys

app/src/test/kotlin/com/cerocoder/meshrelay/
├── stats/AgeFormatTest.kt            MODIFY  the fresh-packet case
└── stats/MeshStatsEngineTest.kt      MODIFY  +own-node tests, +new-node reset test
```

---

## Model selection

| # | Task | Implementer | Reviewer |
|---|---|---|---|
| 1 | The false flash on scroll | Sonnet | Sonnet |
| 2 | "Never" for a packet that just arrived | Sonnet | Sonnet |
| 3 | The two database labels | Sonnet | Sonnet |
| 4 | Our own node leaves the statistics | Sonnet | **Opus** |
| 5 | A different node wipes everything | Sonnet | **Opus** |
| 6 | Exit | Sonnet | **Opus** |
| — | Final whole-branch review | — | **Opus** |

Tasks 1-3 are small and independent and **should be batched into one dispatch with three commits**.
Tasks 4, 5 and 6 get Opus reviewers: 4 changes what a counter means, 5 destroys data, and 6 kills the
process — each is a place where being wrong is expensive and untestable by CI.

---

### Task 1: The flash marker fires on composition, not on a packet

**Root cause, read from the code.** `SignalGauge` drives its flash with
`produceState(false, lastPacketAtMillis)`. `produceState` runs its producer on **first composition** as
well as on a key change — and a `LazyColumn` row scrolled back into view *is* a first composition. So
every row that appears during a scroll lights its yellow "a packet just landed" marker, for packets that
may be minutes old.

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/common/SignalGauge.kt`

**Interfaces:** unchanged — this is internal to one composable.

- [ ] **Step 1: Replace the producer with a seeded change detector**

```kotlin
    // The flash means "a packet just landed", so it must fire on a *change* of
    // lastPacketAtMillis and never on composition. produceState could not do
    // that: it runs its producer on first composition too, and a LazyColumn row
    // scrolled back into view is a first composition - which lit every row's
    // marker on every scroll, for packets minutes old.
    //
    // `seen` is seeded with the value this row was composed with, so the effect
    // below returns immediately the first time and only a genuine later change
    // reaches the delay. A recycled row re-seeds and is silent again.
    var flashing by remember { mutableStateOf(false) }
    var seen by remember { mutableLongStateOf(lastPacketAtMillis) }
    LaunchedEffect(lastPacketAtMillis) {
        if (lastPacketAtMillis == seen || lastPacketAtMillis == 0L) return@LaunchedEffect
        seen = lastPacketAtMillis
        flashing = true
        delay(SignalScales.FLASH_MILLIS)
        flashing = false
    }
    val lastMarkerColor = if (flashing) FlashMarker else markerColor
```

Imports: `androidx.compose.runtime.LaunchedEffect`, `mutableStateOf`, `mutableLongStateOf`,
`getValue`, `setValue`, `remember`. Remove `produceState` if nothing else uses it.

Update the composable's KDoc paragraph that currently says the flash is "driven by `produceState`" —
it must say what drives it now and why `produceState` was wrong here.

- [ ] **Step 2: Green CI run.** There is no Compose test harness in this project, so compilation is the
      only automated gate; the phone is the real one.

- [ ] **Step 3: Commit** — `fix(ui): flash the marker on a packet, not on every scroll`

---

### Task 2: "Never" for a packet that arrived a moment ago

**Root cause, read from the code.** `AgeText.relative(elapsedMillis)` maps any negative elapsed time to
`RelativeAge.Never`. The screens' "now" comes from `LocalRelativeClock`, which `RelativeTimeTicker`
refreshes **once a second**. A packet that lands between two ticks has `atMillis` *ahead* of that
`now`, so `now - atMillis` is negative and the freshest packet on the screen reads "Never".

The genuine never-heard case is already handled one level up: `relativeTo` returns `Never` when
`atMillis == 0L`, the model's sentinel. The negative branch is defensive against a case that cannot
reach it, and it fires on a case it was not meant for.

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/AgeText.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/AgeFormatTest.kt`

**Interfaces:** `AgeText.relative` and `.relativeTo` keep their signatures; only the negative case changes.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `a packet newer than the clock tick reads as zero seconds, not never`() {
    // The screens' clock ticks once a second, so a packet that lands between two
    // ticks is ahead of "now". Before this fix that read "Never" - the worst
    // possible answer for the freshest thing on screen.
    assertEquals(RelativeAge.Seconds(0), AgeText.relative(-1))
    assertEquals(RelativeAge.Seconds(0), AgeText.relative(-999))
    assertEquals(RelativeAge.Seconds(0), AgeText.relativeTo(nowMillis = 1_000L, atMillis = 1_400L))
}

@Test
fun `a timestamp far in the future is still not a real age`() {
    // Beyond one tick's worth of skew this is not a clock artefact, it is a
    // nonsense timestamp, and clamping it to "0s ago" would present it as the
    // freshest packet on screen. One minute is well past any tick.
    assertEquals(RelativeAge.Never, AgeText.relative(-60_000))
}

@Test
fun `never heard is still never`() {
    // The sentinel case must be untouched: relativeTo, not relative, is what
    // knows the difference, and RelayStats/NeighbourStats default the field to 0.
    assertEquals(RelativeAge.Never, AgeText.relativeTo(nowMillis = 5_000L, atMillis = 0L))
}
```

- [ ] **Step 2: Run them and watch them fail.** `gradle :app:testDebugUnitTest --tests '*AgeFormatTest*'`
      — expect the first and second to fail. (No local SDK; this is observed at Step 4's CI run.)

- [ ] **Step 3: Implement**

```kotlin
    /**
     * How long ago, in a shape a screen can render.
     *
     * A small *negative* elapsed time is a clock artefact, not a future event:
     * the screens read "now" from `LocalRelativeClock`, which refreshes about
     * once a second, so a packet that lands between two ticks is momentarily
     * ahead of it. Those clamp to zero seconds - reading "Never" for the packet
     * that arrived a moment ago was the defect this rule replaced.
     *
     * Beyond [MAX_CLOCK_SKEW] the timestamp is not a tick artefact but a
     * nonsense value, and presenting it as the freshest thing on screen would be
     * worse than admitting it cannot be placed.
     *
     * The genuine never-heard case does not come through here at all:
     * [relativeTo] tests the zero sentinel before subtracting.
     */
    fun relative(elapsedMillis: Long): RelativeAge = when {
        elapsedMillis < -MAX_CLOCK_SKEW -> RelativeAge.Never
        elapsedMillis < 0 -> RelativeAge.Seconds(0)
        elapsedMillis < MINUTE -> RelativeAge.Seconds((elapsedMillis / SECOND).toInt())
        // ... unchanged ...
    }
```

with, beside the other constants:

```kotlin
    /**
     * How far ahead of the displayed clock a timestamp may be and still be read
     * as "just now".
     *
     * `RelativeTimeTicker` refreshes the displayed "now" every 1000 ms, so a
     * packet can legitimately be up to one whole tick ahead of it. Two ticks of
     * headroom absorbs that plus any scheduling delay, while staying far below
     * any age a reader would notice being rounded.
     */
    private const val MAX_CLOCK_SKEW = 2 * SECOND
```

- [ ] **Step 4: Run the tests, green**
- [ ] **Step 5: Commit** — `fix(stats): a packet newer than the clock tick is 0s, not Never`

---

### Task 3: Two database fields are not labelled as database fields

The node card shows a last-heard time and a last SNR that come from the **node database**, not from
traffic this session heard, and labels them exactly as the live figures are labelled. The owner wants
the source in the name.

**Files:**
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-es/strings.xml`

**Interfaces:** the keys `node_last_snr_db` and `node_last_heard_db` keep their names — only their
values change, so no Kotlin is touched.

- [ ] **Step 1: Change both values, in both locales**

`values/strings.xml`:

```xml
    <string name="node_last_snr_db">Last DB SNR</string>
    <string name="node_last_heard_db">Last DB heard</string>
```

`values-es/strings.xml` — read the current Spanish before editing and keep its register:

```xml
    <string name="node_last_snr_db">Último SNR de la BD</string>
    <string name="node_last_heard_db">Última recepción de la BD</string>
```

- [ ] **Step 2: Confirm nothing else reads these as live values.** `grep -rn 'node_last_snr_db\|node_last_heard_db' app/src`
      should show only the resource files and `NodeCard`. If any other screen uses them for a
      *live* figure, that is a second defect — report it rather than renaming around it.

- [ ] **Step 3: Green CI run** (`StringsParityTest` covers the locale pair)
- [ ] **Step 4: Commit** — `fix(ui): name the database fields as database fields`

---

### Task 4: Our own node leaves the statistics

**Root cause.** `MeshStatsEngine.foldDirect` folds any non-relayed packet into `neighbours` and
`totalDirectPackets`. Our own node's traffic is not relayed, so it becomes a neighbour — with no SNR
and no RSSI, because we did not receive it over the air — and inflates the divisor every other
neighbour's percentage is computed against.

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/NodeDirectory.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngine.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngineTest.kt`

**Interfaces:**
- Produces: `NodeDirectory.localNodeNum: Int?` (a read-only accessor for the existing private field).

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `our own node is not a neighbour and does not move any counter`() {
    // Decision 1: the owner chose for our own traffic to be invisible to the
    // statistics, not merely absent from the Direct tally. A row with no SNR and
    // no RSSI in a list about signal quality is noise, and counting it inflates
    // the divisor every other neighbour's percentage uses.
    val subject = engine(backgroundScope)
    val seen = collectSnapshots(subject)
    subject.attach(flowOf(myInfoFrame(SENDER), direct(from = SENDER), direct(from = SENDER)))
    runCurrent()

    assertTrue(seen.last().neighbours.isEmpty())
    assertEquals(0, seen.last().counters.totalDirectPackets)
    assertEquals(0, seen.last().counters.totalPackets)
}

@Test
fun `another node's direct traffic is unaffected`() {
    val other = 0x1111_2222
    val subject = engine(backgroundScope)
    val seen = collectSnapshots(subject)
    subject.attach(flowOf(myInfoFrame(SENDER), direct(from = SENDER), direct(from = other)))
    runCurrent()

    assertEquals(listOf(other), seen.last().neighbours.map { it.nodeNum })
    assertEquals(1, seen.last().counters.totalDirectPackets)
    assertEquals(1, seen.last().counters.totalPackets)
}

@Test
fun `our own position still reaches the directory`() {
    // Decision 2, and the reason the guard sits after decodePayload: our own
    // POSITION_APP packets are how localPosition() learns where we are, which is
    // what stamps every Graph measurement when Use phone location is off.
    // Dropping the packet before decoding would silently blank every globe.
    val subject = engine(backgroundScope)
    val seen = collectSnapshots(subject)
    subject.attach(flowOf(myInfoFrame(SENDER), positionFrame(SENDER, 398628316, -40273231)))
    runCurrent()

    assertNotNull(seen.last().directory.localPosition())
    assertEquals(39.8628316, seen.last().directory.localPosition()!!.lat, 1e-7)
    // and still counted nowhere
    assertEquals(0, seen.last().counters.totalPackets)
}
```

- [ ] **Step 2: Run them and watch them fail**

- [ ] **Step 3: Expose the local node number**

`NodeDirectory.kt:37` currently declares `private var localNodeNum: Int? = null`. Do **not** rename it
and add a second property — widen the existing one instead, which keeps every existing read working
and adds no indirection:

```kotlin
    /**
     * Which node is ours, or null before the handshake.
     *
     * Readable because the engine consults it per packet to recognise our own
     * traffic; writable only through [setLocalNodeNum], so the handshake stays the
     * one way it is set. A field read, never a snapshot - this is on the path of
     * every packet.
     */
    var localNodeNum: Int? = null
        private set
```

`setLocalNodeNum` keeps its body and its role; the existing reads at `:171` and `:181` are unaffected.

- [ ] **Step 4: Guard the *Direct* branch only — not every packet from us**

The placement is the whole subtlety, and getting it wrong destroys data. A packet whose `from` is our
own node is **not** automatically ours-and-uninteresting: if a relay rebroadcasts our transmission and
we hear it, `PacketClassifier` returns `Ingest.Relayed` (`PacketClassifier.kt:42-43` sends a packet
Direct only when the relay byte is absent or is the sender's own), and its `rx_snr`/`rx_rssi` describe
**the relay's link to us**. That is a real relay measurement and one of the more useful ones. Only the
**Direct** case — us appearing as our own neighbour — is what the owner asked to remove.

So the exclusion must come *after* classification, which means the counter increment has to move after
it too. Restructure the tail of `handleFrame`, preserving every documented behaviour around it:

```kotlin
        // Counted, and nothing else - see the comment above on why node 0 is not a
        // node. Unchanged behaviour, just hoisted into a helper now that the count
        // is no longer the first thing that happens.
        if (packet.from == 0) { countPacket(timestamped.rxMillis); return }

        decodePayload(packet.from, packet, timestamped.rxMillis)

        if (packet.relay_node !in 0..MAX_RELAY_BYTE) { countPacket(timestamped.rxMillis); return }

        val ingest = PacketClassifier.classify(packet, skipped)

        // Our own transmission, heard directly rather than through a relay: it is
        // us, not a neighbour. We never received it over the air, so it carries no
        // SNR and no RSSI, and folding it in would put a signal-less row in a list
        // about signal and inflate the divisor every other neighbour's percentage
        // is computed against. At the owner's decision it is counted nowhere at all,
        // so this returns before countPacket.
        //
        // Deliberately NOT every packet from us. One that a relay rebroadcast and we
        // heard back classifies as Relayed, and its signal measures that relay's link
        // to us - dropping those would throw away real relay data.
        //
        // Also deliberately after decodePayload: our own POSITION_APP packets are how
        // localPosition() learns where we are, which stamps every Graph measurement
        // when Use phone location is off.
        if (ingest is Ingest.Direct && ingest.fromNode == directory.localNodeNum) return

        countPacket(timestamped.rxMillis)

        when (ingest) {
            is Ingest.Relayed -> foldRelayed(ingest, timestamped.rxMillis)
            is Ingest.Direct -> foldDirect(ingest, timestamped.rxMillis)
            Ingest.Dropped -> Unit
        }
    }

    /** The two things every packet that counts as traffic moves, in one place so the
     *  four early returns above cannot disagree about them. */
    private fun countPacket(atMillis: Long) {
        counterState = counterState.copy(totalPackets = counterState.totalPackets + 1)
        lastPacketAtMillis = atMillis
    }
```

Delete the original `counterState`/`lastPacketAtMillis` pair from the top of the method — `countPacket`
replaces it. Keep every existing comment in that method; they explain rules this change does not touch.

- [ ] **Step 5: Add the test that protects relay measurements**

This is the case the restructure exists to preserve, and nothing else in the suite covers it:

```kotlin
@Test
fun `our own transmission heard back through a relay is still a relay measurement`() {
    // `from` being our node does not make a packet uninteresting. A relay that
    // rebroadcast our transmission and let us hear it has told us about its link
    // to us, which is exactly what this app measures. Only the Direct case - us as
    // our own neighbour - is excluded.
    val subject = engine(backgroundScope)
    val seen = collectSnapshots(subject)
    subject.attach(flowOf(myInfoFrame(SENDER), relayed(from = SENDER)))
    runCurrent()

    assertEquals(1, seen.last().relays.size)
    assertEquals(1, seen.last().counters.totalRelayedPackets)
    assertEquals(1, seen.last().counters.totalPackets)
    assertTrue(seen.last().neighbours.isEmpty())
}
```

- [ ] **Step 6: Run the tests, green**
- [ ] **Step 7: Commit** — `fix(stats): our own node is not a neighbour and not a packet count`

---

### Task 5: Connecting to a different node wipes everything

**Root cause.** Nothing compares the node being connected to against the one before it.
`NodeDirectory.setLocalNodeNum` assigns, and `resetStatistics` is only ever reached from the user's
Reset command. So statistics, the node database and the signal series from node A are still on screen
after connecting to node B.

Per decision 3 the trigger is the **BLE address**, at the owner's choice.

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/NodeDirectory.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngine.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/AppContainer.kt`
- Modify: `mesh-relay-android/docs/deferred-work.md`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngineTest.kt`

**Interfaces:**
- Produces: `NodeDirectory.clearAll()`; `MeshStatsEngine.resetForNewNode()`.

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `a new node forgets the node database as well as the statistics`() {
    // Reset keeps the node database on purpose - reloading it costs a round trip
    // to the radio. A *different* node is the opposite case: that database
    // describes somebody else's mesh view and every name in it may be wrong.
    val subject = engine(backgroundScope)
    val seen = collectSnapshots(subject)
    subject.attach(flowOf(nodeInfoFrame(SENDER, "PQPL1"), relayed(), direct(from = 0x4242)))
    runCurrent()
    assertEquals(1, seen.last().relays.size)
    assertEquals(1, seen.last().directory.count)

    subject.resetForNewNode()
    runCurrent()

    assertTrue(seen.last().relays.isEmpty())
    assertTrue(seen.last().neighbours.isEmpty())
    assertEquals(0, seen.last().counters.totalPackets)
    assertEquals(0, seen.last().directory.count)
    assertNull(seen.last().directory.localNodeNum)
}

@Test
fun `an ordinary reset still keeps the node database`() {
    // The two must not converge: this is the distinction the new path exists for.
    val subject = engine(backgroundScope)
    val seen = collectSnapshots(subject)
    subject.attach(flowOf(nodeInfoFrame(SENDER, "PQPL1"), relayed()))
    runCurrent()

    subject.reset()
    runCurrent()

    assertTrue(seen.last().relays.isEmpty())
    assertEquals(1, seen.last().directory.count)
}
```

- [ ] **Step 2: Run them and watch them fail**

- [ ] **Step 3: A directory that can forget everything**

In `NodeDirectory`, beside `clearRuntimeData`:

```kotlin
    /**
     * Forgets the mesh entirely - the node database included, and the identity of
     * the local node with it.
     *
     * [clearRuntimeData] deliberately keeps the node database because reloading it
     * costs a round trip to the radio, and a Reset is a fresh measurement of the
     * same mesh. This is the other case: a *different* local node, whose database
     * describes a different view. Every name, position and hop count in the old one
     * may be wrong, and a wrong name on a relay is worse than no name.
     */
    fun clearAll() {
        nodes.clear()
        positions.clear()
        telemetryRecords.clear()
        localNodeNum = null
        loadedAtMillis = null
    }
```

- [ ] **Step 4: An engine command for it**

A `Command.ResetForNewNode`, a `fun resetForNewNode() { commands.trySend(Command.ResetForNewNode) }`
beside `reset()`, and in `apply`:

```kotlin
            Command.ResetForNewNode -> {
                resetStatistics()
                directory.clearAll()
            }
```

`resetStatistics()` already clears `relays`, `neighbours`, `seriesBuffers`, the counters, both
last-packet timestamps and bumps `resetEpoch` — reuse it rather than duplicating the list, so the two
paths cannot drift.

- [ ] **Step 5: Trigger it on a new address**

In `AppContainer`, a field **above** the `engine` declaration is not required (this one is not read by
the engine's loop), but keep it beside `requestedAddress` for readability:

```kotlin
    /**
     * The address whose statistics are currently on screen.
     *
     * Connecting to a *different* node must not leave the previous node's relays,
     * neighbours and database on screen: they describe a different vantage point,
     * and a relay byte means a different node from a different receiver.
     * Reconnecting to the *same* address keeps everything, which is what makes a
     * dropped link recover without losing an afternoon's survey.
     *
     * Identity is the BLE address at the owner's decision. The gap that leaves is
     * recorded in `docs/deferred-work.md`: two different nodes reached at the same
     * address would not trigger this.
     */
    private var statisticsAddress: String? = null
```

and in `requestConnect`, before anything else:

```kotlin
        if (statisticsAddress != null && statisticsAddress != address) engine.resetForNewNode()
        statisticsAddress = address
```

- [ ] **Step 6: Record the known gap**

Add to `docs/deferred-work.md`: node identity is the BLE address, so the same node at a new address
wipes unnecessarily (harmless) and two nodes at one address would not wipe (stale statistics); a
`my_node_num` cross-check on the handshake would close the second and is small.

- [ ] **Step 7: Run the tests, green**
- [ ] **Step 8: Commit** — `feat: a different node starts from nothing`

---

### Task 6: Exit

There is no way to leave the app and release what it holds. The foreground service keeps the process
alive by design, so the back gesture leaves a live GATT link and a notification behind.

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/common/StatsTopBar.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/relays/RelayListScreen.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/neighbours/NeighbourListScreen.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/mynode/MyNodeScreen.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/MeshRelayNavHost.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/AppContainer.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`, `values-es/strings.xml`

**Interfaces:**
- Produces: `AppContainer.shutdown()` (suspend); `StatsTopBar(..., onExit: () -> Unit)`;
  `onExit: () -> Unit` on all three main screens and on `MeshRelayNavHost`.

- [ ] **Step 1: Strings, both locales**

```xml
<!-- values -->
<string name="action_exit">Exit</string>
<string name="action_exit_confirm_title">Exit the app?</string>
<string name="action_exit_confirm_body">Disconnects from the node, stops collecting and closes the app. Statistics collected in this session are not saved.</string>
<!-- values-es -->
<string name="action_exit">Salir</string>
<string name="action_exit_confirm_title">¿Salir de la aplicación?</string>
<string name="action_exit_confirm_body">Se desconecta del nodo, deja de recopilar y cierra la aplicación. Las estadísticas de esta sesión no se guardan.</string>
```

- [ ] **Step 2: A container that can unwind itself**

```kotlin
    /**
     * Release everything this process holds, in an order that matters.
     *
     * The GATT disconnect is awaited before the service is stopped and before the
     * caller kills the process: tearing the process down with the link still open
     * leaves the radio holding a half-open connection, and the next connection
     * attempt then meets a node that thinks it is already connected.
     */
    suspend fun shutdown() {
        _connectRequested.value = false
        _requestedAddress.value = null
        connectionManager.disconnect()
        stopForegroundService()
    }
```

- [ ] **Step 3: The menu item and its confirmation**

`StatsTopBar` gains `onExit: () -> Unit` and, in the overflow after Settings, a `DropdownMenuItem` for
`R.string.action_exit` that raises a confirmation dialog in the same shape as the existing reset
confirmation — dismiss changes nothing, confirm calls `onExit`.

- [ ] **Step 4: Thread it through**

Each of the three main screens gains `onExit: () -> Unit` and passes it to `StatsTopBar`;
`MeshRelayNavHost` gains one too and passes it to all three. `MainActivity` supplies it.

- [ ] **Step 5: Perform the exit**

In `MainActivity`, where the nav host is built:

```kotlin
        onExit = {
            // Ordered, and the order is the point: await the GATT disconnect, stop
            // the service that is holding this process up, drop the task so the app
            // does not sit in recents looking alive, and only then end the process.
            // exitProcess before the disconnect completes would leave the radio with
            // a half-open link.
            lifecycleScope.launch {
                container.shutdown()
                finishAndRemoveTask()
                exitProcess(0)
            }
        },
```

with `androidx.lifecycle.lifecycleScope`, `kotlinx.coroutines.launch` and `kotlin.system.exitProcess`.

- [ ] **Step 6: Green CI run**
- [ ] **Step 7: Commit** — `feat: an Exit command that actually releases the process`

---

## Acceptance, on the phone

Add to `docs/acceptance-checklist.md` as a new group, in the format its neighbours use:

1. Scroll the Relays list hard, then the Neighbours list. **No yellow marker appears on a row merely
   because it scrolled into view**; it appears only on a row whose packet count is visibly changing.
2. Watch a busy relay's card. When a packet lands the age reads `0sec ago`, never `Never`. A relay
   that has genuinely never been heard still reads `Never`.
3. On a node card, the two database fields read **Last DB SNR** and **Last DB heard**, in both languages.
4. Our own node does **not** appear in Neighbours. The Direct count and every neighbour percentage are
   computed without it. Open the Graph on a relay with *Use phone location* off and confirm the globe
   still resolves — that proves our own position still reaches the directory.
5. Connect to node A, collect, then connect to node B: relays, neighbours, counters and the node
   database are all empty. Reconnect to B again and nothing is lost.
6. Exit from the overflow menu: confirmation appears, and on confirming the notification disappears,
   the app leaves recents, and `adb shell pidof com.cerocoder.meshrelay` returns nothing.

---

## Self-review against the request

**Coverage.** All six items map to a task: (1)→1, (2)→2, (3)→3, (4)→4, (5)→5, (6)→6.

**Placeholders.** Every code step carries the actual code. Task 6's Step 3 describes the dialog rather
than quoting it, because it is a copy of the reset confirmation already in that file — the implementer
is told to match it, which is more accurate than a paraphrase.

**Type consistency.** `NodeDirectory.localNodeNum` (Task 4) is read by Task 4 only.
`resetForNewNode()`/`clearAll()` (Task 5) are used by Task 5 only. `onExit` threads through five files
in Task 6 with one signature. `AppContainer.shutdown()` is suspend and is only called from a coroutine.

**One risk worth naming, and checked rather than asserted.** Task 4 changes what `totalPackets` means.
I grepped every use before writing this: it is read by the status strip, by the foreground
notification (`AppContainer.kt:206`) and nowhere else. `DetailSummary`'s parameter of the same name is
a *relay's own* packet count, not the global counter. Every percentage on every card divides by
`totalRelayedPackets` or `totalDirectPackets`, never by `totalPackets`. So the change is display-only.
The reviewer should still confirm it rather than take it from this plan.
