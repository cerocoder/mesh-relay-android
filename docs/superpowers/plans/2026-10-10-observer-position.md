# One observer position for Alt and distance — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The status-line "Alt" and the distance/direction shown for remote nodes use the same
observer position the Graph already stamps on its samples: the phone fix when *Use phone location* is on,
the node's position otherwise.

**Architecture:** `MeshStatsEngine.positionForSample()` already resolves "where am I" (PHONE → phone fix,
falling back to the node; NODE → node only). It is exposed once, as `StatsSnapshot.observer`, and the
screens read that instead of `directory.localPosition()`. `NodeDirectorySnapshot.locationInfo(nodeNum, from)`
is unchanged. The *My node* tab is unchanged and keeps reading the node (`from = null`).

**Tech Stack:** Unchanged. No new dependencies.

**Spec:** none. This plan is the specification.

---

## Current state (verified in the source)

| Use | Reads | Where |
|-----|-------|-------|
| Status-line Alt | node altitude | `StatusStrip.myAltitudeText` → `locationInfo(local, from = null).altitude` |
| Distance / direction | node lat/lon | `directory.localPosition()` in `RelayListScreen`, `NeighbourListScreen`, `RemoteNodesTab`, `MatchingNodesTab`, `RemoteNodeScreen`, `MeshRelayNavHost` |
| Graph sample pin, CSV `observer*` | phone fix or node | `MeshStatsEngine.positionForSample()` |
| *My node* tab | node | `MyNodeScreen`, `from = null` — **stays** |

## Behaviour

| Setting | Fix | Alt and distance come from |
|---------|-----|----------------------------|
| ON | present | phone |
| ON | none yet | node (fallback, as the Graph) |
| OFF | — | node; phone GNSS not used |
| either | no position at all | Alt `n/a`, no distance or direction (as now) |

**Altitude is never mixed.** A phone fix without altitude yields `n/a`, not the node's altitude.

## Refresh rule for phone fixes

A phone fix triggers its own snapshot refresh only when **both** hold:

1. the fix is **more than 5 m** from the observer in the last published snapshot, and
2. **at least 10 s** have passed since the last *fix-triggered* refresh.

Details:

- Distance is `Geo.haversineKm(...) * 1000.0 > FIX_REFRESH_MIN_MOVE_M`. Altitude-only changes never trigger.
- A fix that moved more than 5 m but arrives inside the 10 s window arms **one** deferred refresh at the
  10 s mark (using the latest fix then). Further fixes in the window do not arm more.
- The very first fix (no reference observer) counts as moved; only the timer limits it.
- Every other refresh path (packet, list update, node-DB load, ...) is unchanged and publishes as today.
  The snapshot it builds carries the **latest stored fix**, not the last gated one.
- Every published snapshot, whatever its cause, becomes the new 5 m reference. It does **not** restart the
  10 s timer; only a fix-triggered refresh does.
- `SetPositionMode` (user toggled the setting) refreshes immediately, bypassing the gate.
- Constants, named, with a KDoc line each:
  - `FIX_REFRESH_MIN_INTERVAL_MS = 10_000` — owner's choice; bounds list redraws to 6 per minute.
  - `FIX_REFRESH_MIN_MOVE_M = 5.0` — owner's choice; roughly typical GNSS noise, so a stationary phone may still
    refresh about once per 10 s. Accepted.

## Tasks

### Task 1 — Observer in the snapshot

- [ ] Add `observer: StampedPosition?` to `StatsSnapshot` (default `null`).
- [ ] Add `StampedPosition.toLatLon(): LatLon` (integer coordinates back to degrees; reuse the existing scaling).
- [ ] Fill `observer` from `positionForSample()` wherever the engine builds a snapshot.
- [ ] Test: PHONE + fix → phone; PHONE, no fix → node; NODE + fix → node; nothing → `null`.

### Task 2 — Fix-triggered refresh with the gate

- [ ] Engine state: observer of the last published snapshot, time of the last fix-triggered refresh
      (via the injected `TimeSource`, already a constructor parameter), pending-deferred flag.
- [ ] `Command.SetPhoneFix`: store the fix, then apply the rule above.
- [ ] Deferred refresh is a command sent back through the engine's own channel, so the engine stays
      single-threaded. No new locks.
- [ ] `Command.SetPositionMode`: store the mode and refresh immediately.
- [ ] Tests, driven by a fake `TimeSource`:
  1. fix 4 m away after 30 s → no refresh;
  2. fix 6 m away after 3 s → no immediate refresh, exactly one at the 10 s mark;
  3. five fixes at 6 m steps inside 10 s → one deferred refresh;
  4. a packet refresh in between → carries the newest fix, moves the 5 m reference, does not restart the timer;
  5. mode switch → immediate refresh, gate bypassed;
  6. first fix after start → refreshes at once if the timer allows.

### Task 3 — Screens read the observer

- [ ] `StatusStrip.myAltitudeText`: use `snapshot.observer?.altitude`; keep `common_not_available` when null.
      Add a small origin marker (phone / node) using the Graph's existing wording.
- [ ] Replace `directory.localPosition()` with `snapshot.observer?.toLatLon()` as `from` in the six call sites listed above.
      Where a composable receives only the directory, pass the observer in as a parameter.
- [ ] `MyNodeScreen` and the `ui/preview` / `SampleData` previews: previews get an observer; `MyNodeScreen` untouched.
- [ ] Tests: a card's distance follows the observer; with no observer there is no distance.

### Task 4 — Words

- [ ] `strings.xml` (en and es): *Use phone location* label and description now say it also drives Alt and distance.
- [ ] `docs/decisions.md`: record the decision and the two constants.
- [ ] `docs/acceptance-checklist.md`: add a hardware check — walk 20 m with the setting ON and OFF; Alt and distances move only with ON;
      refresh no faster than every 10 s.

## Verification

- Unit tests above, plus the existing suite.
- On the phone (per project practice, green CI is not done): read the view hierarchy for the Alt text and a card's distance
  in English and Spanish, with the setting ON and OFF.

## Out of scope

- Timestamp / expiry of a phone fix (a dead GPS keeps its last value, as the Graph does today).
- Altitude fallback from node to phone fix or back.
