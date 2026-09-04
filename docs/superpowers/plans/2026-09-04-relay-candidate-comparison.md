# Comparing relay candidates on the Graph — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the owner pick one of a relay byte's candidate nodes, see the signal that node
delivers when heard directly drawn against the signal the relay is delivering, and skip it.

**Architecture:** A pure ranking function in `stats/` turns each candidate's direct-reception
statistics into a gap from the relay's average and a verdict. A pure geometry function maps that
average to an x position and reports whether it fell off-scale. The Graph screen gains a selector,
a red line and a Skip button; `MeshRelayNavHost` gathers the candidates from the snapshot.

**Tech Stack:** Unchanged. Kotlin 2.4.10, Compose BOM 2026.06.01, JUnit 4.13.2. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-04-relay-candidate-comparison-design.md`

## Global Constraints

- **No new dependencies.**
- **`stats/**` must not import `android.*` or `ui/**`.**
- **No user-facing literal strings in Kotlin** — every string a resource, in both `values/` and
  `values-es/`.
- **No Russian anywhere** — code, comments, commits, documents.
- Never call `System.currentTimeMillis()` outside `SystemTimeSource`.
- **No local build is possible**: no Android SDK, no Gradle wrapper, no `kotlinc`. There **is** a
  plain OpenJDK with `jshell`, which settles arithmetic but says nothing about Kotlin or Compose.
  CI is the gate; the phone is the acceptance.

---

## Two things that will catch an implementer

**1. `ChartGeometry.xOf` already clamps, silently.** It delegates to `SignalScales.fraction`
(`stats/SignalScales.kt:23-27`), which ends in `.coerceIn(0f, 1f)`. So calling `xOf` with an
off-scale candidate average returns the edge and is **indistinguishable from a genuine edge
value** — the line would be drawn with nothing saying it is off-scale. Spec §8 requires the
off-scale case to be marked, so the raw value must be compared against the range *before* mapping.
That is Task 2's whole reason to exist.

**2. This plan overrides a requirement of the earlier Graph spec.** `SignalGraphScreen.kt:316-318`
carries `// Requirement 20: label left, switch right, stacked and right-aligned`, from
`docs/superpowers/specs/2026-09-01-signal-graph-design.md`. The owner has since ruled that Freeze
and Auto scale share **one row** (spec §7), to buy the vertical space the selector needs. Task 3
implements the owner's ruling and must **update that comment** rather than leave it contradicting
the code — a comment citing a requirement the screen no longer follows is worse than none.

---

## File structure

| File | Responsibility | Task |
|---|---|---|
| `stats/model/RelayCandidate.kt` *(new)* | The candidate, the verdict, the source, and the ranking rule | 1 |
| `ui/graph/ChartGeometry.kt` | Where the candidate line sits, and whether it is off-scale | 2 |
| `ui/graph/SignalGraphScreen.kt` | One toggle row, the selector, the line | 3 |
| `ui/MeshRelayNavHost.kt` | Gathers candidates from the snapshot | 3 |
| `ui/graph/CandidateSelector.kt` *(new)* | The dropdown and its rows, kept out of the screen file | 3 |
| `res/values*/strings.xml` | Selector and Skip copy | 3, 4 |
| `docs/decisions.md`, `docs/acceptance-checklist.md` | Rulings and Group N | 4 |

---

## Model selection

| # | Task | Implementer | Reviewer |
|---|---|---|---|
| 1 | The ranking rule | Sonnet | Sonnet |
| 2 | The candidate line's geometry | Haiku | Sonnet |
| 3 | The Graph screen: one toggle row, selector, line | Sonnet | **Opus** |
| 4 | Skip, strings, docs, acceptance | Sonnet | Sonnet |

Task 3 gets an Opus reviewer: `SignalGraphScreen` carries a dense set of load-bearing decisions
about Freeze — `remember(freeze)` rather than an effect, the one-frame race it avoids, and which
switches disable when — and this task rearranges the block those comments describe.

---

### Task 1: The ranking rule

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/RelayCandidate.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/model/RelayCandidateTest.kt` (create)

**Interfaces:**
- Produces: `CandidateVerdict`, `CandidateSource`, `RelayCandidate`,
  `RelayCandidates.rank(relayRssiAvg: Float?, sources: List<CandidateSource>): List<RelayCandidate>`.

- [ ] **Step 1: Write the failing tests**

`SignalStats` is in `stats.model`; build one with `SignalStats.EMPTY` for "never heard" and by
folding values for the rest — read `SignalStatsTest` for the idiom this project already uses to
construct one rather than inventing a second.

```kotlin
private fun stats(vararg values: Float): SignalStats =
    values.fold(SignalStats.EMPTY) { acc, v -> acc.plus(v) }   // match the real fold name

private fun source(
    nodeNum: Int,
    shortName: String = "n",
    role: String? = "CLIENT",
    direct: SignalStats = SignalStats.EMPTY,
) = CandidateSource(nodeNum, shortName, role, direct, dbSnr = null, hopsAway = null)

@Test
fun `a gap inside six decibels is consistent`() {
    val ranked = RelayCandidates.rank(-71f, listOf(source(1, direct = stats(-69f))))
    assertEquals(CandidateVerdict.CONSISTENT, ranked.single().verdict)
    assertEquals(2f, ranked.single().gapDb!!, 1e-4f)
}

@Test
fun `exactly six decibels is still consistent`() {
    // The boundary is inclusive at the lower verdict. Pinned at the exact value
    // because a > / >= slip here is invisible in every other test.
    val ranked = RelayCandidates.rank(-71f, listOf(source(1, direct = stats(-65f))))
    assertEquals(CandidateVerdict.CONSISTENT, ranked.single().verdict)
}

@Test
fun `exactly fifteen decibels is uncertain, not inconsistent`() {
    val ranked = RelayCandidates.rank(-71f, listOf(source(1, direct = stats(-56f))))
    assertEquals(CandidateVerdict.UNCERTAIN, ranked.single().verdict)
}

@Test
fun `beyond fifteen decibels is inconsistent`() {
    val ranked = RelayCandidates.rank(-71f, listOf(source(1, direct = stats(-104f))))
    assertEquals(CandidateVerdict.INCONSISTENT, ranked.single().verdict)
}

@Test
fun `a single direct packet still convicts on a large gap`() {
    // The owner's correction, pinned: a router forwards rather than talks, so a
    // sample-count gate would leave the likeliest candidate permanently unjudged.
    // Nothing but distance explains 33 dB, from one packet or from two hundred.
    val ranked = RelayCandidates.rank(-71f, listOf(source(1, direct = stats(-104f))))
    assertEquals(CandidateVerdict.INCONSISTENT, ranked.single().verdict)
    assertEquals(1, ranked.single().directPacketCount)
}

@Test
fun `a node never heard directly is unknown, not excluded`() {
    val ranked = RelayCandidates.rank(-71f, listOf(source(1)))
    assertEquals(CandidateVerdict.UNKNOWN, ranked.single().verdict)
    assertNull(ranked.single().gapDb)
    assertNull(ranked.single().directRssiAvg)
}

@Test
fun `every candidate is unknown when the relay has no average yet`() {
    val ranked = RelayCandidates.rank(null, listOf(source(1, direct = stats(-69f))))
    assertEquals(CandidateVerdict.UNKNOWN, ranked.single().verdict)
    assertNull(ranked.single().gapDb)
}

@Test
fun `unknown outranks inconsistent`() {
    // Absence of evidence beats evidence of absence: a silent router is a better
    // prospect than a node measured 33 dB away.
    val ranked = RelayCandidates.rank(
        relayRssiAvg = -71f,
        sources = listOf(source(1, direct = stats(-104f)), source(2)),
    )
    assertEquals(listOf(2, 1), ranked.map { it.nodeNum })
}

@Test
fun `the full order is consistent, uncertain, unknown, inconsistent`() {
    val ranked = RelayCandidates.rank(
        relayRssiAvg = -71f,
        sources = listOf(
            source(4, direct = stats(-104f)),   // inconsistent
            source(3),                          // unknown
            source(2, direct = stats(-61f)),    // uncertain, gap 10
            source(1, direct = stats(-69f)),    // consistent, gap 2
        ),
    )
    assertEquals(listOf(1, 2, 3, 4), ranked.map { it.nodeNum })
}

@Test
fun `within a verdict the smaller gap comes first, then the node number`() {
    val ranked = RelayCandidates.rank(
        relayRssiAvg = -71f,
        sources = listOf(
            source(9, direct = stats(-66f)),   // gap 5
            source(2, direct = stats(-69f)),   // gap 2
            source(1, direct = stats(-69f)),   // gap 2, lower number
        ),
    )
    assertEquals(listOf(1, 2, 9), ranked.map { it.nodeNum })
}

@Test
fun `only CLIENT_MUTE is marked as unable to forward`() {
    // Cited from firmware/src/mesh/FloodingRouter.cpp:129-133. An earlier draft of
    // the design also named CLIENT_HIDDEN; the firmware does not.
    val ranked = RelayCandidates.rank(
        relayRssiAvg = -71f,
        sources = listOf(
            source(1, role = "CLIENT_MUTE", direct = stats(-69f)),
            source(2, role = "CLIENT_HIDDEN", direct = stats(-69f)),
            source(3, role = "ROUTER", direct = stats(-69f)),
            source(4, role = null, direct = stats(-69f)),
        ),
    )
    assertEquals(listOf(true, false, false, false), ranked.sortedBy { it.nodeNum }.map { it.cannotForward })
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `gradle :app:testDebugUnitTest --tests '*RelayCandidateTest*'`
Expected: FAIL, "unresolved reference: RelayCandidates". No local SDK; observed at Task 4's CI run.

- [ ] **Step 3: Write the model and the rule**

```kotlin
package com.cerocoder.meshrelay.stats.model

import kotlin.math.abs

/**
 * How well a candidate's own signal matches the signal its supposed relay byte
 * delivers. Declared in the order candidates are listed, but the order is taken
 * from [sortRank] rather than from `ordinal` - reordering this enum for any other
 * reason must not silently reorder the interface.
 */
enum class CandidateVerdict(val sortRank: Int) {
    CONSISTENT(0),
    UNCERTAIN(1),

    /**
     * Nothing to compare - this node has not been heard directly this session, or
     * the relay has no average yet.
     *
     * Ranked **ahead of** [INCONSISTENT] on purpose: absence of evidence outranks
     * evidence of absence. A router forwards rather than talks, so the likeliest
     * candidate of all may be one we have never heard speak for itself.
     */
    UNKNOWN(2),
    INCONSISTENT(3),
}

/** What the caller can gather about one candidate, before any judgement. */
data class CandidateSource(
    val nodeNum: Int,
    val shortName: String,
    /** The schema's own spelling, or null when neither store has said. */
    val role: String?,
    /** Direct reception this session; [SignalStats.EMPTY] when never heard. */
    val directRssi: SignalStats,
    val dbSnr: Float?,
    val hopsAway: Int?,
)

/** One candidate, judged. */
data class RelayCandidate(
    val nodeNum: Int,
    val shortName: String,
    val role: String?,
    val directRssiAvg: Float?,
    val directPacketCount: Int,
    val gapDb: Float?,
    val verdict: CandidateVerdict,
    val dbSnr: Float?,
    val hopsAway: Int?,
    val cannotForward: Boolean,
)

object RelayCandidates {
    /**
     * Within ordinary packet-to-packet variation on one link, so a gap this small
     * says the two measurements could be of the same transmitter.
     */
    const val CONSISTENT_MAX_GAP_DB = 6.0f

    /**
     * Beyond this, roughly a fivefold difference in path distance - more than
     * fading explains, so the two measurements are of different transmitters.
     */
    const val UNCERTAIN_MAX_GAP_DB = 15.0f

    /** The only role that cannot forward: `FloodingRouter::isRebroadcaster()`. */
    private const val NON_FORWARDING_ROLE = "CLIENT_MUTE"

    fun rank(relayRssiAvg: Float?, sources: List<CandidateSource>): List<RelayCandidate> =
        sources.map { evaluate(relayRssiAvg, it) }.sortedWith(ORDER)

    private fun evaluate(relayRssiAvg: Float?, source: CandidateSource): RelayCandidate {
        val directAvg = source.directRssi.avg.takeIf { source.directRssi.hasData }
        val gap = if (directAvg != null && relayRssiAvg != null) abs(directAvg - relayRssiAvg) else null
        return RelayCandidate(
            nodeNum = source.nodeNum,
            shortName = source.shortName,
            role = source.role,
            directRssiAvg = directAvg,
            directPacketCount = source.directRssi.count,
            gapDb = gap,
            verdict = verdictFor(gap),
            dbSnr = source.dbSnr,
            hopsAway = source.hopsAway,
            cannotForward = source.role == NON_FORWARDING_ROLE,
        )
    }

    private fun verdictFor(gap: Float?): CandidateVerdict = when {
        gap == null -> CandidateVerdict.UNKNOWN
        gap <= CONSISTENT_MAX_GAP_DB -> CandidateVerdict.CONSISTENT
        gap <= UNCERTAIN_MAX_GAP_DB -> CandidateVerdict.UNCERTAIN
        else -> CandidateVerdict.INCONSISTENT
    }

    // Node number last so the order cannot change between recompositions. An
    // unknown candidate has no gap; Float.MAX_VALUE keeps it inside its own group
    // rather than letting a null sort arbitrarily.
    private val ORDER = compareBy<RelayCandidate>(
        { it.verdict.sortRank },
        { it.gapDb ?: Float.MAX_VALUE },
        { it.nodeNum },
    )
}
```

Check `SignalStats`'s real fold method name and its `count`/`avg`/`hasData` members before
writing; match them rather than assuming the names above.

- [ ] **Step 4: Run the tests, green**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/RelayCandidate.kt \
        app/src/test/kotlin/com/cerocoder/meshrelay/stats/model/RelayCandidateTest.kt
git commit -m "feat(stats): rank a relay byte's candidates by how well their signal matches"
```

---

### Task 2: Where the candidate line sits

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/graph/ChartGeometry.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/ui/graph/ChartGeometryTest.kt`

**Interfaces:**
- Produces: `ChartGeometry.OffScale` (NONE / LOW / HIGH) and
  `ChartGeometry.candidateLine(value: Float, min: Float, max: Float): CandidateLine`
  where `CandidateLine` carries `fraction: Float` and `offScale: OffScale`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `a value inside the range is not off-scale`() {
    val line = ChartGeometry.candidateLine(value = -71f, min = -120f, max = -40f)
    assertEquals(ChartGeometry.OffScale.NONE, line.offScale)
    assertEquals(0.6125f, line.fraction, 1e-4f)
}

@Test
fun `a value below the range is reported low and pinned to the left edge`() {
    // xOf alone cannot express this: SignalScales.fraction ends in coerceIn(0,1),
    // so an off-scale value returns the same 0f a genuine minimum does. Drawing
    // that with nothing to mark it would hide the most decisive answer this
    // screen can give.
    val line = ChartGeometry.candidateLine(value = -140f, min = -120f, max = -40f)
    assertEquals(ChartGeometry.OffScale.LOW, line.offScale)
    assertEquals(0f, line.fraction, 1e-6f)
}

@Test
fun `a value above the range is reported high and pinned to the right edge`() {
    val line = ChartGeometry.candidateLine(value = -20f, min = -120f, max = -40f)
    assertEquals(ChartGeometry.OffScale.HIGH, line.offScale)
    assertEquals(1f, line.fraction, 1e-6f)
}

@Test
fun `a value exactly on an edge is in range, not off-scale`() {
    assertEquals(ChartGeometry.OffScale.NONE, ChartGeometry.candidateLine(-120f, -120f, -40f).offScale)
    assertEquals(ChartGeometry.OffScale.NONE, ChartGeometry.candidateLine(-40f, -120f, -40f).offScale)
}

@Test
fun `a degenerate range is not off-scale in either direction`() {
    // SignalScales.fraction returns 0f when the span is not positive; the line
    // must not then claim the value was off-scale.
    val line = ChartGeometry.candidateLine(value = -71f, min = -71f, max = -71f)
    assertEquals(ChartGeometry.OffScale.NONE, line.offScale)
}
```

- [ ] **Step 2: Run them and watch them fail**

Expected: FAIL, "unresolved reference: candidateLine".

- [ ] **Step 3: Implement**

```kotlin
    /** Which side of the plotted range a value fell outside, if any. */
    enum class OffScale { NONE, LOW, HIGH }

    /** Where a candidate's line is drawn, and whether the value fits on screen. */
    data class CandidateLine(val fraction: Float, val offScale: OffScale)

    /**
     * The candidate line's horizontal position.
     *
     * [xOf] cannot answer this on its own. It delegates to
     * [com.cerocoder.meshrelay.stats.SignalScales.fraction], which ends in
     * `coerceIn(0f, 1f)`, so a value far below the range returns exactly the `0f`
     * a genuine minimum returns. Drawn that way the line would sit on the edge
     * with nothing saying it does not belong there - and "far off-scale" is the
     * most decisive verdict this screen can offer, so hiding it is the one outcome
     * worth designing against.
     *
     * A range with no span reports [OffScale.NONE]: `fraction` gives up and
     * returns `0f` there, and claiming the value was off-scale would be inventing
     * information the empty range does not carry.
     */
    fun candidateLine(value: Float, min: Float, max: Float): CandidateLine {
        val fraction = xOf(value, min, max)
        val offScale = when {
            max - min <= 0f -> OffScale.NONE
            value < min -> OffScale.LOW
            value > max -> OffScale.HIGH
            else -> OffScale.NONE
        }
        return CandidateLine(fraction = fraction, offScale = offScale)
    }
```

- [ ] **Step 4: Run the tests, green**
- [ ] **Step 5: Commit** — `feat(graph): report when a candidate's line falls off-scale`

---

### Task 3: The Graph screen

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/graph/SignalGraphScreen.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/graph/CandidateSelector.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/MeshRelayNavHost.kt`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-es/strings.xml`

**Interfaces:**
- Consumes: `RelayCandidates.rank(...)`, `RelayCandidate`, `CandidateVerdict`,
  `ChartGeometry.candidateLine(...)`, `ChartGeometry.OffScale`.
- Produces: `SignalGraphScreen(..., candidates: List<RelayCandidate>, onSkipCandidate: (Int) -> Unit, ...)`.

- [ ] **Step 1: Merge the two toggles into one row**

`SignalGraphScreen.kt:316-337` is a `Column(horizontalAlignment = Alignment.End)` holding two
`LabelledSwitch`es. It becomes a single `Row` holding both, spaced apart, still right-aligned.

**Update the comment above it.** It currently reads `// Requirement 20: label left, switch right,
stacked and right-aligned under the app bar.` That requirement came from the Graph spec and the
owner has since overridden it to buy vertical space for the selector. Say that, and say why -
a comment citing a requirement the screen no longer follows misleads the next reader.

Leave `hasSomethingToAct` and both `enabled` bindings exactly as they are. The Freeze machinery
around them - `remember(freeze)` rather than an effect, and the one-frame race that avoids - is
load-bearing and documented at `:158-213`; this step moves two composables and changes nothing
about when they are enabled.

- [ ] **Step 2: Gather the candidates**

In `MeshRelayNavHost.kt`, at the Relay branch that builds `SignalGraphScreen` (around `:451`):

```kotlin
val candidates = remember(snapshot, relay.relayByte, relay.rssi) {
    RelayCandidates.rank(
        relayRssiAvg = relay.rssi.avg.takeIf { relay.rssi.hasData },
        sources = snapshot.directory.matchingNodeNums(relay.relayByte).map { nodeNum ->
            val identity = snapshot.directory.identity(nodeNum)
            CandidateSource(
                nodeNum = nodeNum,
                shortName = identity.shortName ?: "",
                role = identity.role,
                directRssi = snapshot.neighbours.firstOrNull { it.nodeNum == nodeNum }?.rssi
                    ?: SignalStats.EMPTY,
                dbSnr = snapshot.directory.node(nodeNum)?.dbSnr,
                hopsAway = snapshot.directory.node(nodeNum)?.hopsAway,
            )
        },
    )
}
```

Check `StatsSnapshot`'s real neighbour accessor before writing this — it may be a map rather than
a list, in which case use the lookup rather than a linear scan.

The **Neighbour** branch (around `:469`) passes `candidates = emptyList()`. Spec §7: the control is
absent for a neighbour, not empty.

- [ ] **Step 3: The selector**

New file `CandidateSelector.kt`, so the screen file does not grow a third responsibility. An
`ExposedDropdownMenuBox` whose field shows the selection and whose menu lists **None** first, then
each candidate as: coloured dot, short name, average direct RSSI, sample count, gap. A candidate
with `cannotForward` gets a second line naming the role; one with a null `gapDb` shows its `dbSnr`
and `hopsAway` instead of a gap.

Colours come from the theme, not from literals: consistent uses the same green the SNR gauge uses,
inconsistent the same red as the flash, uncertain the amber already defined for the gauges. Find
them in the theme rather than introducing a fourth palette.

Selection lives in the screen as `var selected by rememberSaveable { mutableStateOf<Int?>(null) }`
— it must survive a rotation, for the same reason Freeze does.

Render nothing at all when `candidates.isEmpty()`.

- [ ] **Step 4: Draw the line**

In the chart `Canvas`, **before** the point loop so the line sits behind the data:

```kotlin
val candidate = candidates.firstOrNull { it.nodeNum == selected }
candidate?.directRssiAvg?.let { avg ->
    val line = ChartGeometry.candidateLine(avg, rssiRange.min, rssiRange.max)
    val x = floor(line.fraction * widthPx)
    drawLine(
        color = candidateLineColour,
        start = Offset(x, 0f),
        end = Offset(x, size.height),
        strokeWidth = 1f,
    )
}
```

Use **the RSSI range**, the same `rssiRange` the blue points use, so the line stays correct under
Auto scale. When `line.offScale` is not `NONE`, draw a small triangular marker at that edge and
the value beside it — the line alone at the edge is exactly what Task 2 exists to prevent.

- [ ] **Step 5: Strings, both locales**

`graph_candidate_label`, `graph_candidate_none`, `graph_candidate_gap`, `graph_candidate_samples`,
`graph_candidate_cannot_forward`, `graph_candidate_not_heard`, `graph_candidate_off_scale`.

- [ ] **Step 6: Commit** — `feat(graph): compare a relay's signal against a candidate's own`

---

### Task 4: Skip, the rulings and the checklist

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/graph/SignalGraphScreen.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/MeshRelayNavHost.kt`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-es/strings.xml`
- Modify: `docs/decisions.md`, `docs/acceptance-checklist.md`

- [ ] **Step 1: The Skip button**

To the **right of the selector**, on the same row (spec §9, the owner's correction). Enabled unless
the selection is None. On press it opens a confirmation dialog of the same shape `StatsTopBar`
already uses for Reset and Exit — find it and reuse it rather than writing a second.

The dialog names the node and **says the action is reversible**, because it is: the Settings screen
lists skipped nodes and removes them through `onRemoveSkipped`. Confirming calls
`onSkipCandidate(nodeNum)`, wired in `MeshRelayNavHost` to
`container.settings.addSkippedRelayNode(nodeNum)`, and resets the selection to null.

- [ ] **Step 2: Record the rulings**

Append to `docs/decisions.md`, reading the end of the file for the next number, in that file's
voice. Four:

1. The comparison is valid because a relayed packet reaches us over the air from the relay itself,
   so both measurements are of one link.
2. The metric judges on the gap alone with **no sample-count gate** — the owner's correction —
   because a router forwards rather than talks, so a gate would leave the likeliest candidate
   unjudged. Cost if wrong: a verdict formed from one packet.
3. `UNKNOWN` ranks ahead of `INCONSISTENT`. Cost if wrong: a node we have never heard is offered
   before one measured far away.
4. `CLIENT_MUTE` is the only role marked as unable to forward, cited from
   `firmware/src/mesh/FloodingRouter.cpp:129-133` — and `rebroadcast_mode` is invisible to us, so
   a node configured never to rebroadcast still appears as a candidate. Cost if wrong: nothing is
   acted on automatically, so a wrong mark misleads rather than hides.

- [ ] **Step 3: Group N in the acceptance checklist**

Same format as its neighbours, blank result lines, and update the "Total items run" denominator by
counting `^### [A-Z][0-9]+\.` headings yourself.

1. On a relay with several candidates, the selector lists them ranked, each with a colour, its
   average direct RSSI and its sample count.
2. Selecting a candidate draws a red vertical line; it moves when Auto scale is toggled, staying
   with the blue points rather than drifting off them.
3. A candidate whose signal is far from the relay's shows the line clamped to an edge **with a
   marker and its value** — not silently absent.
4. A candidate never heard directly draws no line and says so, showing its database SNR instead.
5. Skip is disabled on **None** and enabled otherwise; confirming removes the candidate from the
   list, and Settings can restore it.
6. Open the Graph for a **neighbour**: no selector, no Skip.
7. Freeze and Auto scale sit on one row and still work — including that both go disabled in the
   fully empty state.

- [ ] **Step 4: Commit, push, read CI**

CI is the first execution any of this gets. Fix what it reports before declaring the task done.

---

## Acceptance, on the phone

Group N above. Item 3 is the one that proves Task 2 was worth its own task: without it the line
would sit on the edge looking like an ordinary in-range value.
