# Field issues

Problems found by running the app on real hardware, rather than by a test or a review.
Collected here first and fixed in batches — a fix wave per batch, not a commit per finding.

This file exists because the branch's whole verification story was CI: 247 unit tests, 33 reviews,
seven green gates, and not one of them can see a screen. Everything below was invisible to all of
it.

Nothing here is fixed unless its entry says so.

**Entry template**

```
## F-N — one-line summary

- **Found:** date, device, build (commit + CI run)
- **Where:** file:line
- **What:** what you see
- **Why it happens:** mechanism, if known
- **Severity:** cosmetic | degraded | broken | data loss
- **Status:** open | fixed in <sha> | won't fix (reason)
```

---

## F-1 — the Relays title wraps to two lines

- **Found:** 2026-09-01, Samsung SM-S721B (Galaxy S24 FE, 1080×2340, Android 16), build
  `50adb13` from CI run `33477285981`, `app-debug`, demo scenario `zona-centro`.
- **Where:** `app/src/main/kotlin/com/cerocoder/meshrelay/ui/relays/RelayListScreen.kt:103-106`
- **What:** the top app bar renders the title broken across two lines — `Rela` / `ys` — on the
  first screen reached after connecting. Cosmetic, but it is the first thing anyone sees.
- **Why it happens:** Material3's `TopAppBar` lays out `actions` at their measured width and
  gives the title whatever is left. This bar carries six: a sort dropdown, a gauge-mode text
  button, a pause text button, and clear / refresh / settings icons. Two of those are `TextButton`s
  showing words (`Simple`, `Pause`), not icons, so they are far wider than the icon buttons the
  component is sized for. At 1080 px the remainder is a few characters wide and `Text` wraps
  inside it.
- **Worse in Spanish, and untested there:** `relays_title` is `Repetidores` against `Relays`, and
  `action_pause` is `Pausar` against `Pause` — a longer title competing for space with a wider
  action row. This was observed under English only. Check `es` before calling any fix done.
- **Severity:** cosmetic
- **Status:** open — deliberately not fixed; collecting issues first

**Note for whoever fixes it:** resist shrinking the font. The bar is overloaded, which is the
actual defect: six actions is past what a `TopAppBar` title can survive. Moving the two text
buttons into the sort menu's overflow, or onto the content area as a filter row, fixes the cause.
Constraining the title fixes the symptom and leaves the next added action to break it again.

---

## Not yet exercised on hardware

Recorded so absence of a finding is not mistaken for a passing result.

- **A live radio.** Every observation above came from the demo transport, which feeds synthetic
  packets in-process. BLE scanning, the two-stage handshake, reconnect and back-off, and any real
  SNR or RSSI value remain untested. This is the bulk of `docs/acceptance-checklist.md`.
- **The other four demo scenarios.** Only `zona-centro` was opened. `200nodes` is the one that
  would show scroll and snapshot cost; `empty` and `handshake` are the empty-state and
  partial-connection paths.
- **Spanish, and any runtime language change.** Never displayed on a device.
- **Light theme.** The phone was in dark mode throughout.
- **Rotation, split screen, and large display sizes / font scales.** Given F-1, layout under any
  of these is unknown.
