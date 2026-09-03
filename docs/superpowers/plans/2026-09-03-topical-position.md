# A topical position, not a stale one — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the newest position a node has actually broadcast, instead of falling back to a
possibly days-old node-database entry whenever that broadcast happened to omit an altitude.

**Architecture:** One rule changes, in one property. `PositionHistory.best` currently requires a report
to carry **both** coordinates and altitude; it becomes "the newest report carrying coordinates".
Everything downstream — the `CUR`/`DB` source label, distance, bearing, obfuscation radius, and our own
node's position for stamping Graph measurements — already flows from that one property and needs no
change of its own.

**Tech Stack:** Unchanged. Kotlin 2.4.10, JUnit 4. No new dependencies.

**Spec:** none. This plan is the specification.

---

## The defect, stated precisely

`PositionHistory.best` is:

```kotlin
get() = reports.lastOrNull { it.hasCoordinates && it.hasAltitude }
```

A Meshtastic `Position` message carries `latitude_i`, `longitude_i`, `altitude` and `altitude_hae` as
**independently optional** fields — confirmed from this project's own decoder, where all four are
nullable while `precision_bits` is not. A node with a 2D fix, or a fixed-position node configured with
latitude and longitude only, therefore broadcasts coordinates with no altitude.

For such a node **no report ever qualifies**, so `locationInfo` falls through to `nodes[num].dbPosition`
and labels it `DB` — permanently, however many fresh positions arrive. A fixed repeater configured
without an altitude sits there showing a stale database position indefinitely. That is the opposite of
what this application is for.

The current rule is not an accident: it reproduces the Python original's behaviour and is documented as
a deliberate quirk in `PositionHistory` and in `NodeDirectorySnapshot.locationInfo`. **This plan
overrides that on the owner's instruction**, and the documentation saying it is deliberate must be
corrected rather than left contradicting the code.

## Decisions taken before writing this plan

1. **"Good enough" means coordinates.** A report with a latitude and a longitude is usable; altitude is
   not part of the test. Altitude *within* a report keeps its existing preference, `altitude_hae` before
   `altitude` — already implemented in `PositionReport.fromProto` and unchanged by this work.
2. **No altitude fallback** (the owner's choice, asked before planning). When the winning report has no
   altitude, the card shows none. One report is one moment: coordinates and altitude always come from
   the same packet, so nothing on a node card is silently from a different instant. The alternative —
   borrowing an older altitude — would put a reading from minutes ago beside coordinates from now with
   nothing saying so, which on a node being carried along a ridge is actively misleading.
3. **`best` is renamed.** Under the new rule "best" means only "newest with coordinates", and a name
   that vague is what let the altitude condition hide inside it. It becomes `newestWithCoordinates`.
4. **`PositionHistory.last` is deleted.** It has no caller in `app/src/main` and, with `best` renamed to
   say what it does, an unused near-synonym beside it is exactly the sort of thing that gets picked up
   by mistake later.

---

## Global Constraints

- **No new dependencies.**
- **`stats/**` must not import `android.*` or `ui/**`.**
- **No user-facing literal strings in Kotlin.** This change adds no strings at all.
- **No Russian anywhere** — code, comments, commits, documents.
- Never call `System.currentTimeMillis()` outside `SystemTimeSource`.
- **No local build is possible**: no Android SDK, no Gradle wrapper, no `kotlinc`. There **is** a plain
  OpenJDK with `jshell`, which settles `java.time` and arithmetic questions but says nothing about
  Kotlin, Compose or Android. CI is the gate; the phone is the acceptance.

---

## Model selection

| # | Task | Implementer | Reviewer |
|---|---|---|---|
| 1 | The rule, and everything that documents it | Sonnet | **Opus** |

One task — the change is a single property, and splitting it from the documentation and tests that
assert the old behaviour would leave the branch self-contradictory in between. It gets an Opus reviewer
because it moves what every node card, every distance and every bearing is computed from.

---

### Task 1: The newest report with coordinates wins

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/PositionHistory.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/NodeDirectorySnapshot.kt` (its
  `locationInfo` KDoc documents the quirk as deliberate)
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/LocalPosition.kt` (call site + KDoc)
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/preview/SampleData.kt` (two fixture comments
  describe the old rule)
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/model/PositionTest.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/NodeDirectoryTest.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngineTest.kt` (a fixture comment)
- Modify: `docs/decisions.md`

**Interfaces:**
- Produces: `PositionHistory.newestWithCoordinates: PositionReport?` (replacing `best`).
- Removes: `PositionHistory.last`.

- [ ] **Step 1: Rewrite the three tests that pin the old rule**

`PositionTest` has three tests on `best`. Two of them assert the altitude requirement directly and must
now assert the opposite; read them before editing so the fixtures are reused rather than reinvented.

```kotlin
@Test
fun `the newest report with coordinates wins, with or without an altitude`() {
    // The defect this replaced: a node that broadcasts coordinates without an
    // altitude - a 2D fix, or a fixed node configured with latitude and longitude
    // only - had NO qualifying report, so its card fell back to the node database
    // and read "DB" for ever, however many fresh positions arrived.
    val history = PositionHistory(nodeNum = 1)
        .plus(PositionReport(1_000L, 40.0, -3.0, altitude = 600, precisionBits = null))
        .plus(PositionReport(2_000L, 41.0, -4.0, altitude = null, precisionBits = null))

    val chosen = history.newestWithCoordinates
    assertEquals(2_000L, chosen!!.atMillis)
    assertEquals(41.0, chosen.latitude!!, 1e-9)
    // Decision 2: one report is one moment. The altitude is this report's, which
    // is none - it is NOT borrowed from the older report that had one.
    assertNull(chosen.altitude)
}

@Test
fun `a report with no coordinates is skipped even when it carries an altitude`() {
    // Altitude alone is not a position. The older complete report still wins.
    val history = PositionHistory(nodeNum = 1)
        .plus(PositionReport(1_000L, 40.0, -3.0, altitude = 600, precisionBits = null))
        .plus(PositionReport(2_000L, null, null, altitude = 700, precisionBits = null))

    assertEquals(1_000L, history.newestWithCoordinates!!.atMillis)
}

@Test
fun `a history with no coordinates anywhere has no position`() {
    val history = PositionHistory(nodeNum = 1)
        .plus(PositionReport(1_000L, null, null, altitude = 600, precisionBits = null))
    assertNull(history.newestWithCoordinates)
}
```

Check `PositionReport`'s actual constructor parameter order before writing these — match it rather than
assuming the order above.

- [ ] **Step 2: Fix the test that pins the consequence**

`NodeDirectoryTest:177` has a test whose comment begins "Consequence of PositionHistory.best returning
null unless a report has…" — it asserts that a live coordinates-only report is reported as `DB`. Under
the new rule it must assert `CURRENT`, with the live coordinates. Rewrite it and its comment; do not
delete it, it is now the test that proves the fix.

- [ ] **Step 3: Run them and watch them fail**

`gradle :app:testDebugUnitTest --tests '*PositionTest*' --tests '*NodeDirectoryTest*'` — expect
"unresolved reference: newestWithCoordinates". No local SDK; observed at Step 7's CI run.

- [ ] **Step 4: The rule**

```kotlin
    /**
     * The newest report that carries coordinates.
     *
     * Altitude is deliberately **not** part of the test. A `Position` message
     * carries its coordinates and its altitude as independently optional fields,
     * so a node with a 2D fix - or a fixed node configured with a latitude and a
     * longitude and nothing else - broadcasts positions with no altitude at all.
     * Requiring both, as this property used to, meant no report from such a node
     * ever qualified and its card fell back to the node database's entry for ever,
     * however many fresh positions arrived. A stale position presented as the
     * node's position is the one failure this application cannot afford.
     *
     * Whatever altitude the winning report carries is the altitude shown, and that
     * may be none. It is never borrowed from an older report: one report is one
     * moment, and coordinates from now beside an altitude from ten minutes ago
     * would be a reading that never existed.
     */
    val newestWithCoordinates: PositionReport?
        get() = reports.lastOrNull { it.hasCoordinates }
```

Delete `last` — it has no caller.

- [ ] **Step 5: The two call sites**

`LocalPosition.kt:22` and `NodeDirectorySnapshot.kt:89` change name only; neither needs new logic.
`locationInfo`'s existing `lat == null || lon == null` branch stays — it now handles only the
database-entry case, which can still be coordinate-less, and that is worth a word in its comment.

- [ ] **Step 6: Correct everything that documents the old rule as deliberate**

This is the part most likely to be skimped, and a comment asserting a rule the code no longer has is
worse than no comment. At minimum:
- `NodeDirectorySnapshot.locationInfo`'s KDoc — its numbered precedence list ends with a paragraph
  explaining that `best` returns the newest report carrying **both**, that a coordinates-only report is
  therefore labelled `DB`, and that "that quirk is the original's and is preserved". All of that is now
  false. Replace it with the new rule and say the quirk was removed deliberately.
- `LocalPosition.kt`'s KDoc if it repeats the rule.
- `SampleData.kt:183` and `:310` — two fixture comments describing what `best` would do.
- `MeshStatsEngineTest.kt:60` — a fixture comment saying "PositionHistory.best returns only reports
  that have both". **Check whether that fixture still exercises what its test needs**: if a test relied
  on a coordinates-only report being ignored, it may now behave differently.

Grep for `\bbest\b` and `hasAltitude` across `app/src` when you think you are done, and account for
every remaining hit.

- [ ] **Step 7: Record the override**

Append the next ruling to `docs/decisions.md` (read the end of the file for the number), in that file's
voice: the position shown is the newest report with coordinates, altitude no longer required; this
deliberately **overrides** the preserved Python-original quirk, at the owner's instruction, because a
node that never sends an altitude otherwise shows a database position for ever; altitude is taken from
the winning report only and never borrowed; and the cost if wrong — a node card can now show
coordinates with no altitude beside them, where before it showed both from an older source.

- [ ] **Step 8: Run the tests, green**
- [ ] **Step 9: Commit** — `fix(stats): show the newest broadcast position, not a stale database one`

---

## Acceptance, on the phone

Add to `docs/acceptance-checklist.md` as **Group K**, in the format its neighbours use:

1. Find a node broadcasting positions and confirm its card reads **`Src: CUR`** with a recent age,
   rather than `DB`. Before this change, any node whose positions omit an altitude read `DB` for ever.
2. On such a node, the altitude row is simply absent — not a stale figure. If the node does send
   altitude, it is shown and it is that report's.
3. Distance and bearing to that node are computed from the live position: they should change as it or
   you move, rather than being pinned to a database entry.
4. With *Use phone location* **off**, a Graph measurement's globe uses the node's own position — which
   now follows the same rule, so it should resolve for nodes it previously did not.
