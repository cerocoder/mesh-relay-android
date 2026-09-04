# Design: comparing relay candidates on the Graph

**Date:** 2026-09-04
**Status:** approved, ready for the implementation plan
**Source:** the owner's instruction of 2026-09-04, with `Relay.jpg` and `Graph.png` (screenshots
of the live app), and the brainstorming session of the same day
**Builds on:** `docs/superpowers/specs/2026-09-01-signal-graph-design.md`

---

## 1. Goal

A relay identifies itself with **one byte** — the low byte of its node number. Several nodes on
this mesh answer to the same byte, so the Matching nodes tab lists them all and the owner must
decide which one is really relaying and skip the rest. Today that decision is unaided: the tab
shows each candidate's identity, and nothing connects it to the signal the relay is actually
delivering.

This adds the connection. On the Graph, the owner picks a candidate and sees a red line at the
signal level that candidate delivers when we hear it directly — against the cloud of points the
relay is delivering. If they disagree, the candidate is not the relay.

## 2. Why the comparison is valid

Not a heuristic. **A relayed packet reaches us over the air from the relay itself**, so `rx_rssi`
on a relayed packet measures the relay's own link to us — the same transmitter, the same antenna,
the same path as when we hear that node's own packets directly. If candidate A is the relay, its
direct RSSI and the relay byte's RSSI are two measurements of one thing and must agree.

Three confounders, all of which the design has to respect rather than hide:

1. **Time.** Conditions drift. A candidate heard this morning against a relay busy now is a
   weaker comparison than the numbers suggest.
2. **Sample count.** An average of two packets is a poor estimate of a link — but see §4, this
   must not become a gate.
3. **Movement.** A mobile node's direct RSSI is a distribution over positions, not a link.

## 3. What the app can and cannot know

| Signal | Available? | Worth |
| :--- | :--- | :--- |
| Candidate's direct RSSI/SNR (`NeighbourStats`) | yes, when heard directly this session | **the comparison itself** |
| Relay's RSSI/SNR (`RelayStats`) | yes | the other half |
| Role, from the node database or the air | yes | see below |
| `Last DB SNR`, `hops away` (`NodeRecord`) | yes, and **session-independent** | corroboration for a node silent this session |
| `rebroadcast_mode` | **no** | see below |
| Position → distance → expected path loss | yes when both positions known | out of scope, §10 |

**Role.** Verified against this workspace's firmware clone rather than recollection —
`FloodingRouter::isRebroadcaster()`, `firmware/src/mesh/FloodingRouter.cpp:129-133`:

```cpp
return config.device.role != meshtastic_Config_DeviceConfig_Role_CLIENT_MUTE &&
       config.device.rebroadcast_mode != meshtastic_Config_DeviceConfig_RebroadcastMode_NONE;
```

So **`CLIENT_MUTE` is the only role that cannot forward.** An earlier draft of this design also
named `CLIENT_HIDDEN`; that was wrong and the firmware says so.

**And the second half of that condition is invisible to us.** `rebroadcast_mode` is local device
config and never travels in `NodeInfo`, so a node configured `NONE` cannot forward yet looks like
any other candidate. That is a permanent limit of this feature, not a gap to be closed later, and
it is one reason the app ranks rather than decides.

## 4. Decisions

| Decision | Choice | Reason |
| :--- | :--- | :--- |
| How much the app decides | **Rank and colour; never a verdict, never an auto-skip** | The owner's ruling. A wrong auto-suggestion hides a genuine relay from a tool used to decide where a repeater goes |
| What the line shows | **A single line at the candidate's average direct RSSI** | The owner's ruling, taken against a ±1σ band. Keeps `SignalStats` unchanged |
| The judging metric | **The gap between averages alone. No minimum sample count** | The owner's correction, and it is decisive: *a router's job is forwarding, not talking*. A silent ROUTER may send its own NodeInfo once in three hours while relaying constantly, so a sample-count gate would leave the likeliest candidate permanently amber and reward chatty CLIENTs that relay nothing. Physics does not need the count: nothing but distance explains a 40 dB gap, even from one packet |
| Never heard directly | **UNKNOWN, ranked ahead of INCONSISTENT** | Same correction. An earlier draft called this a strong exclusion; it is not. A silent router in range may simply not have broadcast during a short session. No evidence is a better prospect than evidence against |
| Which subject | **Relay only** | The Graph is shared with Neighbour detail, and a neighbour has no relay byte. The control is absent there, not empty |
| Where the numbers live | **A pure function in `stats/`** | The whole rule is arithmetic over two averages; it belongs where it can be unit-tested, not inside a composable |

## 5. `RelayCandidate` — new, `stats/model/`

```kotlin
enum class CandidateVerdict { CONSISTENT, UNCERTAIN, UNKNOWN, INCONSISTENT }

data class RelayCandidate(
    val nodeNum: Int,
    val shortName: String,
    /** The schema's own spelling, or null when neither store has said. */
    val role: String?,
    /** Null when this node has not been heard directly this session. */
    val directRssiAvg: Float?,
    val directPacketCount: Int,
    /** |directRssiAvg - relayRssiAvg|, null exactly when [directRssiAvg] is. */
    val gapDb: Float?,
    val verdict: CandidateVerdict,
    /** Session-independent corroboration, for a node silent this session. */
    val dbSnr: Float?,
    val hopsAway: Int?,
    /** True only for CLIENT_MUTE - see the firmware citation in section 3. */
    val cannotForward: Boolean,
)
```

## 6. The rule

`RelayCandidate` carries `gapDb` and `verdict`, which are **results**, so they cannot also be
inputs. The raw ingredients go in separately:

```kotlin
/** What the caller can gather about one candidate, before any judgement. */
data class CandidateSource(
    val nodeNum: Int,
    val shortName: String,
    val role: String?,
    /** This node's direct reception this session; EMPTY when never heard. */
    val directRssi: SignalStats,
    val dbSnr: Float?,
    val hopsAway: Int?,
)

fun rank(relayRssiAvg: Float?, sources: List<CandidateSource>): List<RelayCandidate>
```

`rank` computes `gapDb`, `verdict` and `cannotForward` for each source and returns them sorted.
Taking `SignalStats` rather than a bare average is deliberate: `hasData` is what distinguishes
"never heard directly" from "heard, and the average happens to be zero".

| Verdict | Condition | Why this number |
| :--- | :--- | :--- |
| `UNKNOWN` | no direct packets this session, or the relay has no RSSI average yet | nothing to compare. Not evidence against |
| `CONSISTENT` | gap **≤ 6.0 dB** | within ordinary packet-to-packet variation on one link |
| `UNCERTAIN` | 6.0 < gap **≤ 15.0 dB** | more than fading explains, less than a different node implies |
| `INCONSISTENT` | gap **> 15.0 dB** | about a fivefold difference in path distance; not the same transmitter |

Boundaries are inclusive at the lower verdict, so exactly 6.0 is `CONSISTENT` and exactly 15.0 is
`UNCERTAIN`. Both boundaries get a test at the exact value.

**Sort order: `CONSISTENT`, `UNCERTAIN`, `UNKNOWN`, `INCONSISTENT`**, each group by ascending gap,
then by node number so the order cannot change between recompositions. `UNKNOWN` sits ahead of
`INCONSISTENT` deliberately: absence of evidence outranks evidence of absence.

## 7. The selector

Below **Auto scale**, above the gauges. A dropdown whose first entry is **None** and whose
remaining entries are the ranked candidates, each showing short name, average direct RSSI, sample
count, gap, and a coloured dot. A candidate that cannot forward carries a reason line naming the
role. A candidate never heard directly shows its database SNR and hop count instead of a gap.

Absent entirely when the subject is a Neighbour, and when the relay byte has no candidates.

## 8. The line

A red vertical line at the selected candidate's `directRssiAvg`, drawn through **the same x
mapping the RSSI points use** so it stays correct under Auto scale, and **behind** the points so
it never hides data.

**Off-scale is clamped, not dropped.** When the value falls outside the current range the line is
drawn at the edge with an arrow and its value. Far off-scale is the most decisive answer this
screen can give, and omitting the line would show nothing exactly when the evidence is strongest.

A candidate with no direct RSSI draws no line; the selector already says why.

## 9. Skip

Below the selector, enabled unless **None** is selected. It reuses the confirmation dialog shape
`StatsTopBar` already uses for Reset and Exit rather than inventing a second, writes through the
existing `SettingsRepository.addSkippedRelayNode`, and resets the selection to None.

The dialog says the action is reversible, because it is: the Settings screen lists skipped nodes
and removes them through `onRemoveSkipped`.

## 10. Out of scope

- **Distance plausibility.** Both positions are usually known, and a node 40 km away cannot
  deliver −70 dBm — but turning that into a threshold needs a path-loss model this application
  has no business carrying.
- **Half-duplex exclusion.** If a direct packet from a candidate arrives at the same instant as a
  relayed packet, they are different radios. Real, and it needs airtime modelling.
- **Temporal correlation** between the candidate's activity and the relay's.
- **σ and a band instead of a line.** Explicitly rejected by the owner; it would need a
  sum-of-squares field on `SignalStats`.
- Auto-skip, or any verdict the app acts on by itself.

## 11. Testing

`rank` is pure and takes unit tests for: each verdict at its exact boundary (6.0, 15.0); the
unknown case with no direct packets; the unknown case with no relay average; the full sort order
including `UNKNOWN` ahead of `INCONSISTENT`; the node-number tie-break; and `cannotForward` set
for `CLIENT_MUTE` and clear for `CLIENT` and `ROUTER`.

Clamping is pure geometry and tested the same way, including a value exactly on each edge.

The selector, the line and the Skip flow have no test harness in this project and go to the
acceptance checklist as **Group N**.
