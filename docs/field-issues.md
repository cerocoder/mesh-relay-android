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

---

## F-2 — choosing any language in Settings crashes the app, and can make it unlaunchable

- **Found:** 2026-09-01, Samsung SM-S721B (Galaxy S24 FE, Android 16), build `50adb13` from CI
  run `33477285981`, `app-debug`. Reported by the owner after selecting Spanish in Settings.
- **Where:** `app/src/main/kotlin/com/cerocoder/meshrelay/ui/LocalizedApp.kt:52` (the
  `LocalContext` override) crashing `app/src/main/kotlin/com/cerocoder/meshrelay/MainActivity.kt:145`
  (`rememberLauncherForActivityResult`).
- **What:**

  ```
  java.lang.IllegalStateException: No ActivityResultRegistryOwner was provided
      via LocalActivityResultRegistryOwner
    at androidx.activity.compose.ActivityResultRegistryKt.rememberLauncherForActivityResult
    at com.cerocoder.meshrelay.MainActivityKt.MeshRelayContent(MainActivity.kt:145)
    ...
    at com.cerocoder.meshrelay.ui.LocalizedAppKt.LocalizedApp(LocalizedApp.kt:52)
  ```

  The process dies immediately. Observed twice in the owner's own session (PIDs 24940 and 30608),
  then reproduced deliberately.
- **NOT Spanish-specific.** Verified by setting `language` to `EN` directly in
  `shared_prefs/mesh_relay.xml` and launching: identical crash. `localeFor` returns `null` only
  for `SYSTEM`; every other value takes the wrapping path. So **English and Spanish both crash,
  and only the `SYSTEM` default works.** Any report framing this as a Spanish bug is wrong.
- **Why it happens:** `LocalizedApp` overrides `LocalContext` with `Context.withLocale(...)`,
  which returns `createConfigurationContext(configuration)`. That is a **`ContextImpl`** - not a
  `ContextWrapper` whose `baseContext` chain leads back to the Activity.
  `rememberLauncherForActivityResult` resolves `LocalActivityResultRegistryOwner`, whose default
  walks the `LocalContext` unwrapping `ContextWrapper`s in search of the Activity. It reaches a
  `ContextImpl` on the first step, finds no owner, and throws.
- **This is the second bug from the same root cause.** The first was found in the final wiring
  pass: `PositionLine.openUrl` called `startActivity` on the same non-Activity context, so every
  map and Meshview link died once a language was chosen. That was fixed at `31892ad` by switching
  to `LocalUriHandler` - which fixed *that call site* and left the `LocalContext` override in
  place. Anything else needing the Activity through `LocalContext` was still broken; the permission
  launcher is the next one to surface. **Fix the override, not the third symptom.**
- **How bad it really is:** worse than the owner's session showed. `SettingsRepository:125`
  persists inside `ioScope.launch { ... apply() }`, so the write is asynchronous. In the observed
  crash the process died before it landed and the file still read `SYSTEM`, so the app restarted
  clean. That is a race, not a safeguard. When the write *does* land first, `MainActivity` crashes
  on every launch and the app cannot be opened at all - confirmed by writing `EN` to the prefs
  file and launching: dead every time, recoverable only by clearing app data. The owner's install
  was restored to `SYSTEM` over adb after this test.
- **Severity:** broken - crash on use, with a latent unrecoverable state
- **Status:** open - deliberately not fixed; collecting issues first

**Notes for whoever fixes it.** The i18n mechanism itself is sound and `LocalConfiguration` still
needs providing - the per-card `displayLocale()` helpers read it for number formatting. What must
change is overriding `LocalContext` with a context that has no path back to the Activity. Two
directions worth weighing: wrap the Activity in a `ContextWrapper` carrying the localized
resources, so owner-walking still terminates at the Activity; or drop the override and switch the
app to `AppCompatDelegate.setApplicationLocales` / `android:localeConfig`, the platform's own
per-app language support, which recreates the Activity properly and also puts the language in
Android's system Settings.

Whatever the fix, it needs a test that would have caught this - none of the 247 existing tests
could, since the failure needs a real Activity in the composition. That points at an instrumented
test, which this project does not currently have; adding the first one is part of the work.

Also fix the persistence race while here: a setting whose application can crash the process must
not be written asynchronously, or a crash mid-write leaves an install that cannot start.

---

## F-3 — the Meshview link overflows its row, collapsing the relay list to a 128 px slit

- **Found:** 2026-09-01, Samsung SM-S721B (1080×2340, density 450), build `50adb13` from CI run
  `33477285981`, `app-debug`, **connected to a real node** (local `49bf`, position
  40.331006, -3.750717). Reported by the owner: "pay attention to the space in the center area,
  the router shown in the bottom of the app screen".
- **Where:** `app/src/main/kotlin/com/cerocoder/meshrelay/ui/common/PositionLine.kt:66` (the link
  `Row`), with the damage landing at
  `app/src/main/kotlin/com/cerocoder/meshrelay/ui/relays/RelayListScreen.kt:201`
  (`LazyColumn(modifier = Modifier.weight(1f) …)`).
- **What:** on the Relays screen, most of the display is empty and a single relay card sits near
  the bottom, apparently floating. Measured from the view hierarchy (`uiautomator dump`), not
  from pixels:

  ```
  View  y= 748-1706  h= 958  w= 111   <- the Meshview TextButton
  View  y=1717-1845  h= 128  w=1080   <- the relay LazyColumn, scrollable
  ```

- **Why it happens:** `PositionLine` puts its links in a plain
  `Row(horizontalArrangement = Arrangement.spacedBy(8.dp))` with no wrapping and no horizontal
  scroll. When the node has a position **and** a Meshview URL is configured, three `TextButton`s
  compete for 1080 px: "Open in Google Maps" takes 450, "Open in OpenStreetMap" 451, and the
  Meshview button is left ~111 px. Its label cannot fit, so it wraps to roughly one character per
  line and the button grows to **958 px tall**. The relay list is the `weight(1f)` sibling, so it
  receives what is left - 128 px, about two thirds of one card.
- **The data is fine; only the space is gone.** Swiping inside the 128 px window does scroll, and
  a second relay (`0xa5[1]`) appears. Every relay is present and correct - the screen simply has
  no room to show them. This is a layout defect, not an engine one.
- **Why no test and no demo run caught it:** the demo scenarios' local node carries **no
  coordinates**, so only the Meshview button renders and it fits comfortably. Three links in that
  row is a state that only a real node with a real position produces. Every screenshot taken
  before connecting to hardware showed at most two.
- **Not limited to this screen.** `PositionLine` has four callers, and all four pass a
  `meshviewUrl`: `RelayListScreen.kt:296` (local node), `NeighbourListScreen.kt:370`,
  `NodeCard.kt:132`, `RemoteNodesTab.kt:263`. Any of them showing a node that has a position will
  overflow the same way; the relay list is merely where a `weight(1f)` sibling turns the overflow
  into an unusable screen. Check all four when fixing.
- **Second call site confirmed on hardware (2026-09-01, same build).** On Relay `0x4b` ->
  *Remote nodes*, node `!9932b1c0` / `MIK6`, `RemoteNodesTab.kt:263` overflows identically:

  ```
  View x=  57- 507  w=450  y=1560-1695   <- Open in Google Maps
  View x= 530- 957  w=427  y=1560-1695   <- Open in OpenStreetMap
  View x= 957-1057  w=100  y=1560-2205   <- Meshview: 100 px wide, 645 px tall
  ```

  The card itself is 1034 px of 1080 - a normal width. It is the 645 px-tall Meshview button
  inside it that makes one remote node fill the whole screen. Note also that the OpenStreetMap
  `Button` measures `x=530-1023` (493 px) inside a container ending at `x=957`: **66 px of its
  label is already clipped before Meshview is laid out at all**, so the row is over-full even
  with two links. That matters for the fix - shortening the Meshview label alone would leave
  OpenStreetMap still truncated.
- **Severity:** broken - the app's primary screen shows about one relay at a time
- **Status:** open - deliberately not fixed; collecting issues first

**Notes for whoever fixes it.** The row needs to stop assuming three labelled buttons fit on one
line. `FlowRow` (`androidx.compose.foundation.layout`, already available) is the smallest change
and wraps to a second line on narrow screens. Shortening the labels only moves the threshold - a
larger font scale or a narrower device brings it straight back, and Spanish labels are longer
again ("Abrir en Google Maps"). Whatever is chosen, the fix should be checked at a large font
scale, not just at default.

Worth pairing with F-1: both are the same mistake in different places - a horizontal row given
more content than it can hold, with no wrap and no measurement. A sweep for unbounded `Row`s
carrying text would likely find the third instance before a user does.
