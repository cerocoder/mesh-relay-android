# Two node stores — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Store what the radio's node database says separately from what nodes broadcast
over the air, timestamp both, and make the interface say which one it is showing.

**Architecture:** `NodeDirectory` grows a second map, `nodesFromAir`, written only by
`NODEINFO_APP` packets and merged field-by-field. `nodes` becomes what the radio said and
nothing else: `node_info` frames accumulate in a buffer and replace it wholesale when the
`want_config` round completes. A new `NodeDirectorySnapshot.identity()` resolves the two
per record, and the node panel is divided into three sections by source.

**Tech Stack:** Unchanged. Kotlin 2.4.10, Compose BOM 2026.06.01, JUnit 4.13.2. No new
dependencies.

**Spec:** `docs/superpowers/specs/2026-09-03-node-storage-split-design.md`

## Global Constraints

- **No new dependencies.**
- **`stats/**` must not import `android.*` or `ui/**`.**
- **No user-facing literal strings in Kotlin** — every string is a resource, in both
  `values/` and `values-es/`.
- **No Russian anywhere** — code, comments, commits, documents.
- Never call `System.currentTimeMillis()` outside `SystemTimeSource`. A function that
  needs the time takes it as a parameter.
- **No local build is possible**: no Android SDK, no Gradle wrapper, no `kotlinc`. There
  **is** a plain OpenJDK with `jshell`, which settles `java.time` and arithmetic questions
  but says nothing about Kotlin, Compose or Android. CI is the gate; the phone is the
  acceptance.

---

## Where this plan overrides the spec

One place, argued:

**Spec §6.3 says a repeated node number within one refresh round means "the later frame
wins".** This plan keeps the existing `NodeDirectory.merge()` for that case instead. The
two rules differ only when the radio sends two `node_info` frames for the same node in one
round, which it does not do — but `merge()` already exists, is already tested, and carries
a long recorded justification for its `hasPublicKey` asymmetry. Following the spec
literally would leave `merge()` with no caller and invite its deletion, discarding that
reasoning for no behavioural gain. The staged-replace semantics the spec actually cares
about — that a node absent from the round disappears — are unaffected, because the buffer
starts empty each round.

---

## File structure

| File | Responsibility | Task |
|---|---|---|
| `stats/model/AirNodeRecord.kt` *(new)* | One node's identity as heard over the air, plus the merge rule and what "blank" means | 1 |
| `stats/model/NodeRecord.kt` | Gains `receivedAtMillis` | 1 |
| `stats/model/NodeIdentity.kt` *(new)* | The resolved identity handed to the interface, and its source | 3 |
| `stats/NodeDirectory.kt` | The two stores, the refresh buffer, the commit rule, lifecycle | 2 |
| `stats/MeshStatsEngine.kt` | Passes the receive time to `applyUser` | 2 |
| `stats/model/NodeDirectorySnapshot.kt` | `identity()`, `airCount`, union key sets | 3 |
| `ui/detail/NodeCard.kt` | Three sections divided by source | 4 |
| `ui/common/StatusStrip.kt` | `DB(n) · Air(m)` | 4 |
| `res/values*/strings.xml` | Six new strings, one changed | 4 |

---

## Model selection

| # | Task | Implementer | Reviewer |
|---|---|---|---|
| 1 | `AirNodeRecord`, the merge rule, `NodeRecord.receivedAtMillis` | Sonnet | Sonnet |
| 2 | The two stores in `NodeDirectory` | Sonnet | **Opus** |
| 3 | Reading: `identity()` and the union key sets | Sonnet | **Opus** |
| 4 | The panel, the header, the strings, the docs | Sonnet | Sonnet |

Tasks 2 and 3 get Opus reviewers for named reasons. Task 2 contains the commit rule, where
`nodes = pendingNodes` would alias one map under two names and the next line would empty
it — a defect that reads as the obvious implementation. Task 3 contains the union key
sets, where missing one call site makes the application name **fewer** relays than before
this work, silently and with every test still green.

---

### Task 1: `AirNodeRecord` and the merge rule

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/AirNodeRecord.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/NodeRecord.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/model/AirNodeRecordTest.kt` (create)

**Interfaces:**
- Produces: `AirNodeRecord(num, longName, shortName, hwModel, role, hasPublicKey, receivedAtMillis)`
  and `AirNodeRecord.folding(existing: AirNodeRecord?, num: Int, user: User, atMillis: Long): AirNodeRecord`.
- Produces: `NodeRecord.receivedAtMillis: Long`, defaulted to `0L` by `NodeRecord.fromProto`.

- [ ] **Step 1: Write the failing tests**

Create `AirNodeRecordTest.kt`. `User` is `org.meshtastic.proto.User`; `HardwareModel` and
`Role` are nested in it as `User.HardwareModel` / `User.Role` — check the import the
existing `NodeDirectoryTest.kt` uses and match it rather than guessing.

```kotlin
@Test
fun `a first packet creates the record and stamps it`() {
    val record = AirNodeRecord.folding(
        existing = null,
        num = 0x3f2a,
        user = User(long_name = "PQPL1 Getafe", short_name = "1ce5"),
        atMillis = 1_000L,
    )
    assertEquals(0x3f2a, record.num)
    assertEquals("PQPL1 Getafe", record.longName)
    assertEquals(1_000L, record.receivedAtMillis)
}

@Test
fun `a thin packet does not blank a field a fat one filled`() {
    // The defect this rule prevents: per-record precedence means the panel shows the
    // air record whole, so a field blanked here is a field the panel loses even
    // though the database still knows it.
    val fat = AirNodeRecord.folding(
        existing = null,
        num = 1,
        user = User(long_name = "PQPL1 Getafe", short_name = "1ce5"),
        atMillis = 1_000L,
    )
    val thin = AirNodeRecord.folding(
        existing = fat,
        num = 1,
        user = User(short_name = "1ce5"),
        atMillis = 2_000L,
    )
    assertEquals("PQPL1 Getafe", thin.longName)
    // The stamp still moves: it says when we last heard this node identify itself,
    // not when its identity last changed.
    assertEquals(2_000L, thin.receivedAtMillis)
}

@Test
fun `an empty string is treated as absence, exactly like a null`() {
    val fat = AirNodeRecord.folding(null, 1, User(long_name = "PQPL1 Getafe"), 1_000L)
    val blank = AirNodeRecord.folding(fat, 1, User(long_name = ""), 2_000L)
    assertEquals("PQPL1 Getafe", blank.longName)
}

@Test
fun `an UNSET hardware model does not overwrite a known one`() {
    val known = AirNodeRecord.folding(null, 1, User(hw_model = User.HardwareModel.HELTEC_MESH_NODE_T114), 1_000L)
    val unset = AirNodeRecord.folding(known, 1, User(hw_model = User.HardwareModel.UNSET), 2_000L)
    assertEquals("HELTEC_MESH_NODE_T114", unset.hwModel)
}

@Test
fun `role is taken even when it is CLIENT`() {
    // CLIENT is proto3's default and cannot be told from an omitted field. The
    // decision (spec section 3) is to let a real ROUTER -> CLIENT transition through
    // and accept that a User omitting role reports CLIENT.
    val router = AirNodeRecord.folding(null, 1, User(role = User.Role.ROUTER), 1_000L)
    val client = AirNodeRecord.folding(router, 1, User(role = User.Role.CLIENT), 2_000L)
    assertEquals("CLIENT", client.role)
}

@Test
fun `a public key once observed is never unlearned`() {
    val keyed = AirNodeRecord.folding(null, 1, User(public_key = "k".encodeUtf8()), 1_000L)
    val bare = AirNodeRecord.folding(keyed, 1, User(short_name = "1ce5"), 2_000L)
    assertTrue(bare.hasPublicKey)
}
```

`public_key` is `okio.ByteString`; use whatever construction `NodeDirectoryTest.kt`
already uses for its `PUBLIC_KEY` constant rather than introducing a second idiom.

- [ ] **Step 2: Run the tests and watch them fail**

Run: `gradle :app:testDebugUnitTest --tests '*AirNodeRecordTest*'`
Expected: FAIL, "unresolved reference: AirNodeRecord". There is no local Android SDK, so
this is observed at Task 4's CI run, not here.

- [ ] **Step 3: Write `AirNodeRecord`**

```kotlin
package com.cerocoder.meshrelay.stats.model

import org.meshtastic.proto.User

/**
 * One node's identity as it announced itself over the air, folded from every
 * NODEINFO_APP packet heard from it.
 *
 * Identity only. Nothing measured lives here: a position heard over the air is
 * already in [PositionHistory], and signal is already in the relay and neighbour
 * statistics. A NODEINFO_APP payload is a `User` message, which carries no
 * position, no SNR and no last-heard time - those four fields exist only in the
 * radio's own database and stay in [NodeRecord].
 */
data class AirNodeRecord(
    val num: Int,
    val longName: String?,
    val shortName: String?,
    val hwModel: String?,
    val role: String?,
    val hasPublicKey: Boolean,
    /** When the most recent NODEINFO_APP for this node was folded in. */
    val receivedAtMillis: Long,
) {
    companion object {
        /**
         * [existing] updated with what [user] carries, or a new record when there is
         * none. The stamp moves on every call: it records when this node was last
         * heard identifying itself, not when its identity last changed.
         *
         * **A field the packet does not carry keeps the value it had.** proto3 does
         * not distinguish an unset non-`optional` field from its default, so every
         * rule below is about reading a default as absence:
         *
         *  - `long_name`, `short_name`: null or `""`. Unset and empty are the same
         *    bytes on the wire.
         *  - `hw_model`: null or `UNSET`, which is the schema's own "not known".
         *  - `public_key`: null or empty. Presence is remembered once observed and
         *    never unlearned - the same rule, for the same reason, as
         *    `NodeDirectory.merge()`.
         *  - `role`: **never** treated as absent. `CLIENT` is proto3's default and
         *    cannot be told from an omitted field. Treating it as absent would mean a
         *    node that genuinely changes ROUTER to CLIENT never updates. A real role
         *    change is worth more than a `User` that omitted the field.
         *
         * Merging rather than replacing is what makes the per-record precedence rule
         * safe: the panel shows an air record whole, so a thin broadcast that blanked
         * a field would blank the panel even though the database still knew it.
         *
         * Written to be correct whether Wire generates these as `String?` or as
         * non-null `String` defaulting to `""` - it treats the two identically.
         */
        fun folding(existing: AirNodeRecord?, num: Int, user: User, atMillis: Long): AirNodeRecord =
            AirNodeRecord(
                num = num,
                longName = user.long_name.orBlankKeep(existing?.longName),
                shortName = user.short_name.orBlankKeep(existing?.shortName),
                hwModel = user.hw_model?.takeIf { it != User.HardwareModel.UNSET }?.name
                    ?: existing?.hwModel,
                role = user.role?.name ?: existing?.role,
                hasPublicKey = (user.public_key?.size ?: 0) > 0 || existing?.hasPublicKey == true,
                receivedAtMillis = atMillis,
            )

        /** This string when it carries anything, [previous] when it is null or empty. */
        private fun String?.orBlankKeep(previous: String?): String? =
            if (this.isNullOrEmpty()) previous else this
    }
}
```

If Wire generates `hw_model`, `role` and `public_key` as non-null, drop the `?.`/`?:` on
those three and keep the behaviour identical; the safe calls above are written to compile
either way. Do **not** change `orBlankKeep` — it is correct for both.

- [ ] **Step 4: Add `receivedAtMillis` to `NodeRecord`**

Add the field after `hasPublicKey`:

```kotlin
    /**
     * When the refresh that carried this record completed.
     *
     * Every record from one refresh shares one value, because they do: the radio
     * streams a whole database in a burst of a few seconds and the phone cannot tell
     * when the radio learned any of it.
     *
     * Set by [NodeDirectory.markLoaded] at commit, not here: [fromProto] has no clock
     * and must not acquire one. It constructs the record with `0L`, which is never
     * observable, because nothing outside the refresh buffer can see a record before
     * it is committed.
     */
    val receivedAtMillis: Long,
```

and in `fromProto`, as the last argument: `receivedAtMillis = 0L,`.

- [ ] **Step 5: Fix every `NodeRecord(` construction the new field breaks**

`NodeRecord` has no default for the new field, so every direct construction fails to
compile. Find them all with `grep -rn 'NodeRecord(' app/src --include='*.kt'` and read the
**complete** output — do not pipe it through `head`. Add `receivedAtMillis = 0L` to each,
except where a test wants a real stamp.

- [ ] **Step 6: Run the tests, green**

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/AirNodeRecord.kt \
        app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/NodeRecord.kt \
        app/src/test/kotlin/com/cerocoder/meshrelay/stats/model/AirNodeRecordTest.kt
git commit -m "feat(stats): add AirNodeRecord and the field-by-field merge rule"
```

---

### Task 2: The two stores in `NodeDirectory`

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/NodeDirectory.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngine.kt:365`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/NodeDirectoryTest.kt`

**Interfaces:**
- Consumes: `AirNodeRecord.folding(existing, num, user, atMillis)`, `NodeRecord.receivedAtMillis`.
- Produces: `NodeDirectory.applyUser(nodeNum: Int, user: User, atMillis: Long)` — **signature
  changed**, third parameter added. `NodeDirectory.airNodes: Map<Int, AirNodeRecord>` for the
  snapshot to copy.

- [ ] **Step 1: Write the failing tests**

Append to `NodeDirectoryTest.kt`. `markLoaded(atMillis)` is what commits a round.

```kotlin
@Test
fun `a refresh replaces the store, dropping a node the radio no longer reports`() {
    val directory = NodeDirectory(FixedTimeSource(1_000L))
    directory.applyNodeInfo(nodeInfo(num = GETAFE_ROUTER))
    directory.applyNodeInfo(nodeInfo(num = PINTO))
    directory.markLoaded(1_000L)
    assertEquals(setOf(GETAFE_ROUTER, PINTO), directory.snapshot(emptySet()).nodes.keys)

    // Second round: the radio has evicted PINTO.
    directory.applyNodeInfo(nodeInfo(num = GETAFE_ROUTER))
    directory.markLoaded(2_000L)
    assertEquals(setOf(GETAFE_ROUTER), directory.snapshot(emptySet()).nodes.keys)
}

@Test
fun `a completed round that carried no node info leaves the store alone`() {
    // config_complete_id terminates ANY want_config round, and stats may not import
    // transport to tell the nonces apart. Committing unconditionally would replace an
    // 80-entry database with an empty map every time a config round finished.
    val directory = NodeDirectory(FixedTimeSource(1_000L))
    directory.applyNodeInfo(nodeInfo(num = GETAFE_ROUTER))
    directory.markLoaded(1_000L)

    directory.markLoaded(2_000L)   // a config-only round

    val snapshot = directory.snapshot(emptySet())
    assertEquals(setOf(GETAFE_ROUTER), snapshot.nodes.keys)
    assertEquals(1_000L, snapshot.loadedAtMillis)
    assertEquals(1_000L, snapshot.nodes[GETAFE_ROUTER]!!.receivedAtMillis)
}

@Test
fun `every record of a refresh carries the completion stamp`() {
    val directory = NodeDirectory(FixedTimeSource(1_000L))
    directory.applyNodeInfo(nodeInfo(num = GETAFE_ROUTER))
    directory.applyNodeInfo(nodeInfo(num = PINTO))
    directory.markLoaded(7_000L)
    val nodes = directory.snapshot(emptySet()).nodes
    assertEquals(7_000L, nodes[GETAFE_ROUTER]!!.receivedAtMillis)
    assertEquals(7_000L, nodes[PINTO]!!.receivedAtMillis)
}

@Test
fun `a node info frame is not visible before its round completes`() {
    val directory = NodeDirectory(FixedTimeSource(1_000L))
    directory.applyNodeInfo(nodeInfo(num = GETAFE_ROUTER))
    assertTrue(directory.snapshot(emptySet()).nodes.isEmpty())
}

@Test
fun `NODEINFO_APP writes the air store and never the database store`() {
    val directory = NodeDirectory(FixedTimeSource(1_000L))
    directory.applyUser(PINTO, User(long_name = "Pinto Norte", short_name = "pnt1"), 5_000L)
    val snapshot = directory.snapshot(emptySet())
    assertTrue(snapshot.nodes.isEmpty())
    assertEquals("Pinto Norte", snapshot.airNodes[PINTO]!!.longName)
    assertEquals(5_000L, snapshot.airNodes[PINTO]!!.receivedAtMillis)
}

@Test
fun `a refresh does not disturb the air store`() {
    val directory = NodeDirectory(FixedTimeSource(1_000L))
    directory.applyUser(PINTO, User(long_name = "Pinto Norte"), 5_000L)
    directory.applyNodeInfo(nodeInfo(num = GETAFE_ROUTER))
    directory.markLoaded(6_000L)
    assertEquals("Pinto Norte", directory.snapshot(emptySet()).airNodes[PINTO]!!.longName)
}

@Test
fun `Reset preserves both stores`() {
    val directory = NodeDirectory(FixedTimeSource(1_000L))
    directory.applyNodeInfo(nodeInfo(num = GETAFE_ROUTER))
    directory.markLoaded(1_000L)
    directory.applyUser(PINTO, User(long_name = "Pinto Norte"), 5_000L)

    directory.clearRuntimeData()

    val snapshot = directory.snapshot(emptySet())
    assertEquals(setOf(GETAFE_ROUTER), snapshot.nodes.keys)
    assertEquals(setOf(PINTO), snapshot.airNodes.keys)
}

@Test
fun `a different local node clears both stores and the pending round`() {
    val directory = NodeDirectory(FixedTimeSource(1_000L))
    directory.applyNodeInfo(nodeInfo(num = GETAFE_ROUTER))
    directory.markLoaded(1_000L)
    directory.applyUser(PINTO, User(long_name = "Pinto Norte"), 5_000L)
    directory.applyNodeInfo(nodeInfo(num = PINTO))   // a round left in flight

    directory.clearAll()

    val snapshot = directory.snapshot(emptySet())
    assertTrue(snapshot.nodes.isEmpty())
    assertTrue(snapshot.airNodes.isEmpty())
    // The abandoned round must not be committed onto the new node's database.
    directory.markLoaded(9_000L)
    assertTrue(directory.snapshot(emptySet()).nodes.isEmpty())
}
```

`nodeInfo(num = ...)` and the `GETAFE_ROUTER` / `PINTO` / `FixedTimeSource` helpers already
exist in `NodeDirectoryTest.kt` — read the file and reuse them; do not define new ones.

- [ ] **Step 2: Run them and watch them fail**

Run: `gradle :app:testDebugUnitTest --tests '*NodeDirectoryTest*'`
Expected: FAIL, "unresolved reference: airNodes" and an arity error on `applyUser`.
Observed at Task 4's CI run.

- [ ] **Step 3: Add the state**

In `NodeDirectory`, beside `nodes`:

```kotlin
    private val nodesFromAir = HashMap<Int, AirNodeRecord>()

    /**
     * A node-database refresh in progress. The radio streams one `node_info` frame per
     * entry and terminates the round with `config_complete_id`; entries land here and
     * replace [nodes] wholesale at [markLoaded], so a node the firmware has evicted
     * disappears from this application too.
     */
    private val pendingNodes = HashMap<Int, NodeRecord>()

    /**
     * Whether the round in progress has carried a `node_info` frame.
     *
     * `config_complete_id` terminates **any** want_config round, and this package may
     * not import `transport/` to tell the nonces apart. Without this flag a completed
     * *config* round would replace an eighty-entry database with an empty map. A
     * node-database round always carries at least the local node's own entry.
     *
     * A flag rather than `pendingNodes.isNotEmpty()`, so the rule reads as "a frame
     * arrived" and a future change cannot make an empty-but-real round wipe the store.
     */
    private var pendingReceivedAny = false
```

- [ ] **Step 4: `applyNodeInfo` fills the buffer**

```kotlin
    fun applyNodeInfo(info: NodeInfo) {
        val incoming = NodeRecord.fromProto(info)
        val existing = pendingNodes[info.num]
        pendingNodes[info.num] = if (existing == null) incoming else merge(existing, incoming)
        pendingReceivedAny = true
    }
```

Note it merges against `pendingNodes`, not `nodes`: each round stands alone, and the merge
only ever applies within one round. Update its KDoc to say so — the existing text describes
merging onto the previous connect's data, which is no longer what happens.

- [ ] **Step 5: `markLoaded` commits**

```kotlin
    /**
     * A want_config round has completed. Commit the refresh it carried, if it carried
     * one.
     *
     * **The contents are copied; the buffer is never assigned.** `nodes = pendingNodes`
     * would alias one map under two names and the `clear()` below would then empty the
     * store it had just filled.
     */
    fun markLoaded(atMillis: Long) {
        if (!pendingReceivedAny) return
        nodes.clear()
        pendingNodes.forEach { (num, record) -> nodes[num] = record.copy(receivedAtMillis = atMillis) }
        loadedAtMillis = atMillis
        pendingNodes.clear()
        pendingReceivedAny = false
    }
```

- [ ] **Step 6: `applyUser` writes the air store**

```kotlin
    /**
     * Folds one NODEINFO_APP payload into the air store, creating the record when this
     * node has not identified itself before.
     *
     * It does not touch [nodes]. That map is what the radio's own database says and
     * nothing else; what a node broadcasts is a separate account of the same node, and
     * keeping them apart is what lets the interface say which one it is showing.
     *
     * [atMillis] is a parameter because this class may not read the clock outside
     * [TimeSource].
     */
    fun applyUser(nodeNum: Int, user: User, atMillis: Long) {
        nodesFromAir[nodeNum] = AirNodeRecord.folding(nodesFromAir[nodeNum], nodeNum, user, atMillis)
    }
```

- [ ] **Step 7: Lifecycle and snapshot**

In `clearAll()`, after `nodes.clear()`:

```kotlin
        nodesFromAir.clear()
        pendingNodes.clear()
        pendingReceivedAny = false
```

Leave `clearRuntimeData()` exactly as it is — requirement 10 is satisfied by it continuing
to touch only `positions` and `telemetryRecords`. Add a sentence to its KDoc saying both
node stores are deliberately untouched, so a later reader does not "fix" the omission.

In `snapshot()`, add `airNodes = HashMap(nodesFromAir),` — copied like every other map, for
the reason its KDoc already gives.

- [ ] **Step 8: Update the one `applyUser` call site**

`MeshStatsEngine.kt:365` becomes:

```kotlin
                PortNum.NODEINFO_APP ->
                    directory.applyUser(fromNode, User.ADAPTER.decode(decoded.payload), atMillis)
```

`atMillis` is already `decodePayload`'s third parameter and is the frame's receive time.

- [ ] **Step 9: Run the tests, green**

- [ ] **Step 10: Commit**

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/stats/NodeDirectory.kt \
        app/src/main/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngine.kt \
        app/src/test/kotlin/com/cerocoder/meshrelay/stats/NodeDirectoryTest.kt
git commit -m "feat(stats): store air-heard identity apart from the radio's database"
```

---

### Task 3: Reading — `identity()` and the union key sets

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/NodeIdentity.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/NodeDirectorySnapshot.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/NodeDirectoryTest.kt`

**Interfaces:**
- Consumes: `NodeDirectorySnapshot.airNodes: Map<Int, AirNodeRecord>` from Task 2.
- Produces: `NodeIdentity`, `IdentitySource`, `NodeDirectorySnapshot.identity(nodeNum): NodeIdentity`,
  `NodeDirectorySnapshot.airCount: Int`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `an air record wins whole, blanks included`() {
    // The owner's ruling, made against a per-field alternative: if an air record
    // exists, all five identity fields come from it. A node that broadcast only a
    // short name shows only a short name, and the label says the data is from the air.
    val directory = NodeDirectory(FixedTimeSource(1_000L))
    directory.applyNodeInfo(nodeInfo(num = PINTO, longName = "Pinto Norte", shortName = "pnt1"))
    directory.markLoaded(1_000L)
    directory.applyUser(PINTO, User(short_name = "pnt2"), 5_000L)

    val identity = directory.snapshot(emptySet()).identity(PINTO)
    assertEquals(IdentitySource.AIR, identity.source)
    assertEquals("pnt2", identity.shortName)
    assertNull(identity.longName)
    assertEquals(5_000L, identity.receivedAtMillis)
}

@Test
fun `the database record is used when the air store has nothing`() {
    val directory = NodeDirectory(FixedTimeSource(1_000L))
    directory.applyNodeInfo(nodeInfo(num = PINTO, longName = "Pinto Norte"))
    directory.markLoaded(1_000L)

    val identity = directory.snapshot(emptySet()).identity(PINTO)
    assertEquals(IdentitySource.DB, identity.source)
    assertEquals("Pinto Norte", identity.longName)
    assertEquals(1_000L, identity.receivedAtMillis)
}

@Test
fun `a node in neither store has no identity`() {
    val snapshot = NodeDirectory(FixedTimeSource(1_000L)).snapshot(emptySet())
    assertEquals(IdentitySource.NONE, snapshot.identity(PINTO).source)
    assertNull(snapshot.identity(PINTO).receivedAtMillis)
}

@Test
fun `a relay is named from a node known only over the air`() {
    // Without the union, this feature would name FEWER relays after this change than
    // before it: a node the radio has never listed would be invisible to the byte
    // match, however often it announced itself.
    val directory = NodeDirectory(FixedTimeSource(1_000L))
    directory.applyUser(PINTO, User(short_name = "pnt1"), 5_000L)

    val snapshot = directory.snapshot(emptySet())
    val relayByte = Geo.lastByteOfNodeNum(PINTO)
    assertEquals(listOf(PINTO), snapshot.matchingNodeNums(relayByte))
    assertEquals("pnt1", snapshot.uniqueRelayName(relayByte))
}

@Test
fun `a node in both stores is one candidate, not two`() {
    val directory = NodeDirectory(FixedTimeSource(1_000L))
    directory.applyNodeInfo(nodeInfo(num = PINTO, shortName = "pnt1"))
    directory.markLoaded(1_000L)
    directory.applyUser(PINTO, User(short_name = "pnt1"), 5_000L)

    val snapshot = directory.snapshot(emptySet())
    assertEquals(listOf(PINTO), snapshot.matchingNodeNums(Geo.lastByteOfNodeNum(PINTO)))
}

@Test
fun `the skip list still applies to an air-only node`() {
    val directory = NodeDirectory(FixedTimeSource(1_000L))
    directory.applyUser(PINTO, User(short_name = "pnt1"), 5_000L)
    assertTrue(directory.snapshot(setOf(PINTO)).matchingNodeNums(Geo.lastByteOfNodeNum(PINTO)).isEmpty())
}

@Test
fun `the two counts are independent`() {
    val directory = NodeDirectory(FixedTimeSource(1_000L))
    directory.applyNodeInfo(nodeInfo(num = GETAFE_ROUTER))
    directory.markLoaded(1_000L)
    directory.applyUser(PINTO, User(short_name = "pnt1"), 5_000L)

    val snapshot = directory.snapshot(emptySet())
    assertEquals(1, snapshot.count)
    assertEquals(1, snapshot.airCount)
}
```

Check `nodeInfo(...)`'s real parameter list in `NodeDirectoryTest.kt` before writing these —
match it rather than assuming it takes `longName` and `shortName`.

- [ ] **Step 2: Run them and watch them fail**

Expected: FAIL, "unresolved reference: identity".

- [ ] **Step 3: Write `NodeIdentity`**

```kotlin
package com.cerocoder.meshrelay.stats.model

/** Which store an identity was resolved from. */
enum class IdentitySource { AIR, DB, NONE }

/**
 * A node's identity as the interface should show it, and where it came from.
 *
 * Resolved **per record**, not per field: when the air store holds this node, all five
 * fields come from it, blanks included. That is the owner's ruling, taken against a
 * per-field alternative, and it is what lets one label and one timestamp describe the
 * whole block instead of five of each. [AirNodeRecord.folding]'s merge rule is what
 * keeps it safe - an air record accumulates, so a thin broadcast cannot empty one.
 */
data class NodeIdentity(
    val source: IdentitySource,
    val longName: String?,
    val shortName: String?,
    val hwModel: String?,
    val role: String?,
    val hasPublicKey: Boolean,
    /** Null only when [source] is [IdentitySource.NONE]. */
    val receivedAtMillis: Long?,
) {
    companion object {
        val NONE = NodeIdentity(IdentitySource.NONE, null, null, null, null, false, null)
    }
}
```

- [ ] **Step 4: Resolve it on the snapshot**

Add `val airNodes: Map<Int, AirNodeRecord>,` to the constructor, then:

```kotlin
    val airCount: Int get() = airNodes.size

    fun identity(nodeNum: Int): NodeIdentity {
        airNodes[nodeNum]?.let { air ->
            return NodeIdentity(
                source = IdentitySource.AIR,
                longName = air.longName,
                shortName = air.shortName,
                hwModel = air.hwModel,
                role = air.role,
                hasPublicKey = air.hasPublicKey,
                receivedAtMillis = air.receivedAtMillis,
            )
        }
        val db = nodes[nodeNum] ?: return NodeIdentity.NONE
        return NodeIdentity(
            source = IdentitySource.DB,
            longName = db.longName,
            shortName = db.shortName,
            hwModel = db.hwModel,
            role = db.role,
            hasPublicKey = db.hasPublicKey,
            receivedAtMillis = db.receivedAtMillis,
        )
    }
```

- [ ] **Step 5: Both key sets, everywhere a node is looked for**

```kotlin
    /** The node's short name, or `""` when neither store has named it. */
    fun shortName(nodeNum: Int): String = identity(nodeNum).shortName ?: ""
```

and in `matchingNodeNums`, replace `nodes.keys` with the union:

```kotlin
    fun matchingNodeNums(relayByte: Int): List<Int> = (nodes.keys + airNodes.keys)
        .filter { Geo.lastByteOfNodeNum(it) == relayByte && it !in skippedNodes }
        .sorted()
```

`Set + Set` is a union, so a node in both stores appears once. Add a sentence to the KDoc
saying the union is deliberate and why: a node known only from the air is a relay candidate
like any other, and scanning only the database would name fewer relays than before this
work.

`count` stays `nodes.size` — it is the radio's database count and the header labels it `DB`.

- [ ] **Step 6: Run the tests, green**

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/NodeIdentity.kt \
        app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/NodeDirectorySnapshot.kt \
        app/src/test/kotlin/com/cerocoder/meshrelay/stats/NodeDirectoryTest.kt
git commit -m "feat(stats): resolve node identity across both stores"
```

---

### Task 4: The panel, the header, the strings and the record

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/detail/NodeCard.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/common/StatusStrip.kt`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-es/strings.xml`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/preview/SampleData.kt`
- Modify: `docs/decisions.md`, `docs/acceptance-checklist.md`

**Interfaces:**
- Consumes: `NodeDirectorySnapshot.identity(nodeNum)`, `NodeIdentity`, `IdentitySource`,
  `NodeDirectorySnapshot.airCount`, `NodeRecord.receivedAtMillis`.

- [ ] **Step 1: Add the strings**

`values/strings.xml`:

```xml
    <string name="section_node_information">NODE INFORMATION</string>
    <string name="section_heard_over_air">HEARD OVER THE AIR</string>
    <string name="section_from_node_database">FROM THE NODE DATABASE</string>
    <string name="label_air_received">Air Received</string>
    <string name="label_db_received">DB Received</string>
    <string name="format_db_header">DB(%1$d) · Air(%2$d) · %3$s</string>
```

`values-es/strings.xml` — the three headings and two labels translated, and the same
`format_db_header`. Read the neighbouring Spanish strings for register and capitalisation
and match them; do not translate `DB` or `Air` in the format string, they are the same
tokens in both locales.

`format_db_header` gains a parameter and its argument order changes. Fix its one caller in
`StatusStrip.kt` in the same step, passing `snapshot.directory.count`,
`snapshot.directory.airCount`, then the load-time text.

- [ ] **Step 2: Take `NodeCard` through `identity()`**

`NodeCard` receives `record: NodeRecord` today. Add a parameter rather than replacing it —
the card still needs `record` for `dbSnr`, `lastHeardEpochSeconds` and `num`:

```kotlin
fun NodeCard(
    index: Int?,
    record: NodeRecord,
    identity: NodeIdentity,
    location: LocationInfo,
    ...
```

Update its two callers (`MatchingNodesTab` and the neighbour detail tab — find them with
`grep -rn 'NodeCard(' app/src`, reading the whole output) to pass
`snapshot.directory.identity(record.num)`.

- [ ] **Step 3: Rebuild the card as three sections**

Replace the run of identity rows, and move the position, telemetry and public-key rows, so
the card reads in this order. Each section is preceded by its heading, and **a section with
no rows is omitted entirely, heading included** — the reasoning already recorded in this
file for `PositionLine`: a heading with nothing under it reads as a missing row rather than
an absent one.

```kotlin
// NODE INFORMATION - the five identity fields, one label, one stamp.
val identityRows = listOfNotNull(identity.longName, identity.shortName, identity.role, identity.hwModel)
if (identityRows.isNotEmpty() || identity.source != IdentitySource.NONE) {
    Text(stringResource(R.string.section_node_information), style = MaterialTheme.typography.labelSmall)
    identity.longName?.let { LabelValueRow(stringResource(R.string.node_long_name), it) }
    identity.shortName?.let { LabelValueRow(stringResource(R.string.node_short_name), it) }
    identity.role?.let { LabelValueRow(stringResource(R.string.node_role), it) }
    identity.hwModel?.let { LabelValueRow(stringResource(R.string.node_hardware), it) }
    LabelValueRow(
        label = stringResource(R.string.node_public_key_present),
        icon = R.drawable.ic_field_public_key,
        value = stringResource(if (identity.hasPublicKey) R.string.common_yes else R.string.common_no),
    )
    identity.receivedAtMillis?.let { stamp ->
        LabelValueRow(
            label = stringResource(
                if (identity.source == IdentitySource.AIR) R.string.label_air_received
                else R.string.label_db_received
            ),
            value = StatsFormat.nodeDatabaseLastHeard(
                epochSeconds = (stamp / 1000L).toInt(),
                locale = locale,
                timeFormat = LocalTimeFormat.current,
            ),
        )
    }
}
```

`StatsFormat.nodeDatabaseLastHeard` takes epoch **seconds** and already honours the 12/24
hour setting — check its exact signature before calling it and match it; if it does not
take a `timeFormat` parameter, pass whatever the existing `Last DB heard` call site passes.

Then the air section — Position, Uptime, Restarts, Telemetry, moved up from below:

```kotlin
// HEARD OVER THE AIR. None of these were ever in the node database: telemetry,
// uptime and restarts are folded from TELEMETRY_APP packets and the position from
// POSITION_APP. Position keeps its own Src: CUR|DB marker, which is finer-grained
// than this heading and stays authoritative for that row.
```

and last the database section — `Last DB SNR`, `Last DB heard`, and the record's own
`DB Received` stamp, **suppressed when the identity block already showed a DB stamp**:

```kotlin
if (identity.source != IdentitySource.DB) {
    // When identity resolved to DB, this is the same value from the same record and
    // two identical lines on one panel read as a defect.
    LabelValueRow(stringResource(R.string.label_db_received), /* record.receivedAtMillis rendered */)
}
```

Leave the `Firmware` row where it is relative to its neighbours — its KDoc explains it is
unconditionally null and kept in its ported position for a future task.

- [ ] **Step 4: Update `SampleData` so the previews still build**

`SampleData.kt` builds `NodeRecord`s for `@Preview`s. Add whatever `NodeIdentity` the new
`NodeCard` parameter needs. Give at least one preview an `IdentitySource.AIR` identity with
a blank `longName`, so the blank-field case is visible in the preview pane rather than only
on hardware.

- [ ] **Step 5: Record the rulings**

Append to `docs/decisions.md`, in that file's voice, reading the end of the file for the
next number. Four rulings:

1. The two stores, and why `nodes` is now only what the radio said.
2. Per-record precedence, naming the per-field alternative it was chosen over, and the cost
   if wrong: a node that broadcasts a thin `User` shows less than the database knows.
3. The commit guard — why `config_complete_id` alone cannot be trusted, and the cost if
   wrong: an eighty-entry database replaced by an empty map on every config round.
4. The union key sets, and the cost if wrong: fewer relays named than before this work,
   silently.

- [ ] **Step 6: Add Group L to `docs/acceptance-checklist.md`**

In the format its neighbours use, with blank result lines, and update the "Total items run"
denominator (count the `^### [A-Z][0-9]` headings after adding).

1. The header reads `DB(n) · Air(m)`. Leave the app running and confirm `Air(m)` grows while
   `DB(n)` stays at the radio's count — this is the original complaint, and this item is the
   proof it is answered.
2. Open a node that has broadcast recently: the panel shows three headed sections, and
   **NODE INFORMATION** ends with `Air Received` and a recent time.
3. Open a node the radio knows but which has not broadcast: the identity block reads
   `DB Received`, and the database section does **not** repeat that stamp.
4. Press **Reload node DB**. Every `DB Received` stamp becomes the reload time; no `Air
   Received` stamp changes.
5. Press **Reset**. Neither store is emptied — node names survive, and the header's two
   counts do not drop.
6. Connect to a *different* node over Bluetooth. Both counts drop to reflect the new node,
   and no name from the previous node survives.
7. Find a relay named from a node the radio does not list. Its short name is still shown.

- [ ] **Step 7: Commit and push, then read CI**

```bash
git add -A
git commit -m "feat(ui): divide the node panel by source, and count both stores"
git push
```

CI is the first execution any of this gets. Read the run and fix what it reports before
declaring the task done.

---

## Acceptance, on the phone

Group L above. Item 1 is the one that answers the observation this whole design started
from — an overnight run whose header never moved off `DB(80)`.
