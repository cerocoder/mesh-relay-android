# Field issues

Problems found by running the app on real hardware, rather than by a test or a review.
Collected here first and fixed in batches — a fix wave per batch, not a commit per finding.

This file exists because the branch's whole verification story was CI: 247 unit tests, 33 reviews,
seven green gates, and not one of them can see a screen. Everything below was invisible to all of
it.

Nothing here is fixed unless its entry says so.

**F-1 to F-5 were fixed as one wave on 2026-09-01**; each entry keeps its original finding
verbatim and ends with a note saying what was done. F-5 was found *by* that wave: fixing the F-2
crash was what first allowed the app to be run in Spanish at all, and the first Spanish screen
showed the next defect underneath it.

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
- **Status:** fixed — see the fix note below.

**Fixed:** the note below was followed rather than the symptom. The bar now carries three actions,
not six, and both list screens draw it from one shared composable,
`ui/common/StatsTopBar.kt`, instead of two near-identical copies. Sort and pause stay in the bar,
being the two used while watching traffic; gauge style, the database reload, reset and settings
moved into an overflow menu, which is also where a seventh action goes rather than back onto the
bar. Pause is now an icon: `material-icons-core` has `PlayArrow` and no `Pause`, so the running
half of the toggle is a hand-authored drawable (`res/drawable/ic_action_pause.xml`) rather than the
word it used to be — the word was half the width problem. The reset confirmation moved into the
shared bar too, so the screens hold no local state at all now.

**Verified on the phone, 2026-09-01**, in Spanish - the worse of the two cases, and the one the
original finding could not check. `Repetidores` renders on one line at `[45,234][369,313]`, 324 px
wide, and the first action starts at x=664: three icon buttons (`Ordenar`, `Pausar`, `Más`) against
the six controls that left the title a few characters of room.

**Note for whoever fixed it:** resist shrinking the font. The bar is overloaded, which is the
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
- **Status:** fixed - `LocalizedContext` in `ui/LocalizedApp.kt`, plus the persistence change below.

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

**What was done.** First the wrapper: `withLocale` returns `LocalizedContext`, a `ContextWrapper`
around the context it was built from, overriding only `getResources`, `getAssets` and `getTheme`, so
owner-walking terminates at the activity again. Then F-5 moved where it is applied - the composable
that provided it as `LocalContext` is gone, and the locale is applied in `MainActivity.attachBaseContext`
instead, which puts the crash out of reach entirely rather than merely surviving it. The wrapper
stays because a `ContextImpl` under an activity's base context would be a trap for the next reader
as much as it was for this one. `AppCompatDelegate.setApplicationLocales` was not taken: it needs
`appcompat`, a dependency this project has deliberately stayed off.

The persistence race is fixed too, and by deletion rather than by a lock: `SettingsRepository.persist`
no longer posts to `ioScope`, it calls the store on the caller's thread.
`SharedPreferences.Editor.apply()` is itself the platform's non-blocking write and is documented as
safe from the main thread, so the coroutine hop bought nothing and cost a window in which a setting
was on screen but not yet in the editor. `SettingsRepository` no longer takes a `CoroutineScope`.

**On the test.** Not added, and this is a gap, not an oversight. The failure needs a real `Activity`
in a composition; the project has no instrumented tests and no Robolectric, and CI has no emulator.
What guards it instead is a type: `withLocale` declares its return as `ContextWrapper`, so changing
it back to a bare `createConfigurationContext` fails to compile rather than compiling silently and
bricking the app again. That is narrower than a test - it cannot catch a *different* context being
provided somewhere else - and a first Robolectric test remains owed.

**Verified on the phone, 2026-09-01.** `language` set to `ES` in `shared_prefs` and the app
launched: alive, no crash, and every screen in Spanish including the decimal commas
(`40,330942`, `12,5%`) that come from `LocalConfiguration` rather than from a string resource. That
is the exact state that used to kill the process on every launch.

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
- **The consequence is a LOST ACTION, not just bad layout** (owner's observation, 2026-09-01):
  "there are some nodes with 'Open in Meshview', but the last wide node doesn't have this action,
  but they should". Measured on one screen, two cards:

  ```
  node WITHOUT a position (!a1991854)  - only the Meshview link is in the row:
    TextView x=  91- 417 w=326  "Open in Meshview"
    Button   x=  57- 451 w=394                       <- normal, label rendered

  node WITH a position                 - three links in the row:
    View     x= 957-1057 w=100 y=1211-1644 clickable=true   <- NO TextView child at all
  ```

  The Meshview control is still present and still clickable, but it renders **no label glyph
  whatsoever** - a 100 px invisible strip at the card's right edge. So the action vanishes exactly
  on the nodes where it is most wanted (the ones with a position, i.e. the ones worth looking up),
  and an invisible-but-tappable strip is worse than an absent one: a stray thumb opens a browser
  with no warning. Any fix must be judged on whether the label is *readable* on a positioned node,
  not merely on whether the row stops overflowing.
- **Severity:** broken - the app's primary screen shows about one relay at a time
- **Status:** fixed - `FlowRow` in `ui/common/PositionLine.kt`, plus shorter labels.

**What was done.** Both halves of the note below. The links row is a `FlowRow`, so a third button
that does not fit moves to a second line instead of being squeezed to a character's width - that is
the fix, and it holds at any font scale, in any language, on any width. The labels were shortened
as well, from "Open in Google Maps" / "Open in OpenStreetMap" / "Open in Meshview" to the service
names alone, in both locales: not as the fix but so that the common case does not wrap at all. Under
a button, "Google Maps" already reads as "open in Google Maps". This also settles the clipping
measured on the second call site, where OpenStreetMap lost 66 px of its label with only two links
in the row.

All four callers get it, since all four go through `PositionLine`.

**Verified on the phone, 2026-09-01**, connected to the real node, in both languages. The three
links sit on one line with every label rendered - in Spanish
`Google Maps [79,831][314,888]`, `OpenStreetMap [405,831][684,888]`, `Meshview [775,831][954,888]`,
875 px of the 1080 - and the relay list runs from y≈900 to the bottom of the screen showing three
full relay cards, against the 128 px that held two thirds of one.

**Notes for whoever fixed it.** The row needs to stop assuming three labelled buttons fit on one
line. `FlowRow` (`androidx.compose.foundation.layout`, already available) is the smallest change
and wraps to a second line on narrow screens. Shortening the labels only moves the threshold - a
larger font scale or a narrower device brings it straight back, and Spanish labels are longer
again ("Abrir en Google Maps"). Whatever is chosen, the fix should be checked at a large font
scale, not just at default.

Worth pairing with F-1: both are the same mistake in different places - a horizontal row given
more content than it can hold, with no wrap and no measurement. A sweep for unbounded `Row`s
carrying text would likely find the third instance before a user does.

---

## F-4 — "Local node" and the node's name should share one line

- **Requested:** 2026-09-01 by the owner, after seeing the header on hardware:
  "the 'Local node' text on the main screen should [be] in the same line as local node name
  (49bf in my case)".
- **Where:** `app/src/main/kotlin/com/cerocoder/meshrelay/ui/relays/RelayListScreen.kt:268`
  (`LocalNodeLine`) **and** `app/src/main/kotlin/com/cerocoder/meshrelay/ui/neighbours/NeighbourListScreen.kt:342`
  (a second, independent copy of the same composable).
- **What it does now:** the label and the name stack, costing a line for a two-word caption:

  ```
  Local node
  49bf
  (globe) 40.330942, -3.750708
  ```

  Wanted:

  ```
  Local node  49bf
  (globe) 40.330942, -3.750708
  ```

- **Why it is worth doing:** the Relays screen has less vertical room than anything else in the
  app - see F-3, where the relay list is down to a 128 px slit - and this is a whole line spent on
  a caption. It also reads better: the label names the value beside it rather than hovering above
  it, matching the `LabelValueRow` pattern the node card already uses everywhere.
- **Both copies must change.** `LocalNodeLine` exists twice, verbatim, on two screens. Changing
  one leaves the Relays and Neighbours headers looking different from each other. This is one of
  the duplicated helpers already recorded in `deferred-work.md`; a fix here is the natural moment
  to extract it into `ui/common/` instead of editing the same code twice.
- **Two states to keep working.** `shortName` is `""` - not null - when the database knows this
  node's number but has never heard its own User message, and the current code hides the name with
  `isNotEmpty()`. On one line that must degrade to the label alone, not to a label with a dangling
  separator. The separate `relays_local_node_unknown` branch, for no local node at all, is
  untouched by this.
- **Severity:** cosmetic / layout economy
- **Status:** fixed - `ui/common/LocalNodeLine.kt`.

**What was done.** Extracted rather than edited twice, as the note above asked: one
`LocalNodeLine` in `ui/common`, called by both screens, with the label and the name as siblings in
a spaced `Row`. Siblings rather than one concatenated string is what makes the empty-`shortName`
case degrade correctly - the label stands alone, with no separator left dangling after it. The
`relays_local_node_unknown` branch is untouched.

`SortModeLabels` moved from `ui/relays` to `ui/common` in the same pass, since the shared app bar
builds the sort menu for both screens and a common component should not import from `ui/relays`.

**Verified on the phone, 2026-09-01:** `Local node [45,516][221,561]` and `49bf [244,510][327,567]`
- one line, label then name, on both screens.

**Superseded, 2026-09-01 (same day).** The owner then asked for everything about this device to
move off the two lists and onto a screen of its own, so `ui/common/LocalNodeLine.kt` is deleted and
the block it fixed now lives on the **My node** tab, drawn by the shared `NodeCard`. The finding
above stands and so does what was done for it - a stacked label and value was the defect, and the
answer that superseded it is the same answer taken further: the caption is now the screen's title,
and the list screens spend no vertical room on this at all. `relays_local_node` ("Local node") has
no caller left and is deleted with the composable; `relays_local_node_unknown` survives as the My
node screen's first state.

---

## F-5 - the app runs in the chosen language but every menu and dialog stays in the system's

- **Found:** 2026-09-01, Samsung SM-S721B, build `ee6b01f` from CI run `33494212291`, `app-debug`,
  connected to the real node, `language` forced to `ES`. Found while verifying the F-1 to F-4 fix
  wave - and only findable then, because until F-2 was fixed the app could not be run in a chosen
  language at all.
- **Where:** the old `ui/LocalizedApp.kt` - the `LocalContext` override, again, and this time not
  for what it was but for how far it reached.
- **What:** with Spanish chosen, the screen behind is fully Spanish and every popup is English.
  Measured from the view hierarchy on one screen:

  ```
  app bar / cards (in the activity's window)   overflow + sort menus (in popup windows)
    'Repetidores'                                'Gauges' / 'Complex'
    'Nodo local'  'Ordenar por'                  'Reload node database'
    'Número de paquetes'                         'Reset'  'Settings'
    'Fuente: DB:1min'                            'Packet count'  'Percentage'  'Avg SNR'
  ```

  `Número de paquetes` in the status strip and `Packet count` in the sort menu are the *same string
  resource*, rendered two ways on one screen.
- **Why it happens:** `Popup` and `Dialog` each host their content in their own
  `AbstractComposeView`, and every `AbstractComposeView` runs `ProvideAndroidCompositionLocals`,
  which provides `LocalContext` from its own window's context. That shadows any `LocalContext` an
  ancestor provided, however far up. So a composition-local override reaches the activity's window
  and nothing else - and every dropdown menu, sort menu and confirmation dialog in this app is in a
  window of its own.
- **This is the same root cause a fourth time.** Map links (fixed at `31892ad`), the permission
  launcher (F-2), and now every popup. Each time the treatment was the call site. The mechanism was
  wrong: a composition local cannot carry a language, because composition is not what resolves
  resources - a `Context` is, and windows get their own.
- **Severity:** broken - a bilingual UI is arguably worse than an untranslated one, and it makes
  the Spanish translation look unfinished when it is complete
- **Status:** fixed - `MainActivity.attachBaseContext`.

**What was done.** The locale is applied to the activity's base context, underneath every window
the app opens, so there is nothing left above it to shadow it. `LocalizedApp` is deleted; what
survives is `AppLocale.kt` with `localeFor`, `withLocale` and `LocalizedContext`. A language chosen
in Settings now recreates the activity, because rebuilding its resources is what applying the
locale means - the navigation stack is `rememberSaveable` and everything longer-lived is in
`AppContainer`, which is what rotation already relies on. `MeshForegroundService` got the same
override, so the notification's title and channel name follow the setting too (its body text
already did, through `AppContainer.notificationContext`).

**Verified on the phone, 2026-09-01.** The same two menus that showed the defect:
`Indicadores`/`Complejo`, `Recargar base de nodos`, `Reiniciar`, `Ajustes` in the overflow, and
`Número de paquetes`, `Porcentaje`, `SNR medio`, `RSSI medio`, `Nombre del nodo` in the sort menu.
Then the path that used to be fatal, driven through the UI rather than the preference file:
Settings -> English -> the whole app switches with no crash and the same process id, Settings ->
System default -> switches back. The install was left on `SYSTEM`, as it was found.

---

## F-6 - relay and neighbour cards jump between positions, and the code claims an animation it does not have

- **Found:** 2026-09-01, Samsung SM-S721B, build `23ecdf3` from CI run `33495104837`, connected to
  the real node. Raised by the owner after the F-1 to F-5 wave: "look like an ordering now is
  performed on the main screen".
- **Where:** `app/src/main/kotlin/com/cerocoder/meshrelay/ui/relays/RelayListScreen.kt:101-103` and
  `app/src/main/kotlin/com/cerocoder/meshrelay/ui/neighbours/NeighbourListScreen.kt:113-115`.
- **The ordering itself is correct, and is not new.** Checked in both modes on hardware:

  ```
  Sort by Packet count   4 / 4 / 2 packets, descending
  Sort by Node name      03bb, 0x32, 0x4b, 0xa5, 0xab, 0xf5, RPST, TG80
  ```

  The second is exactly the ascending byte-wise order of `nodeName.ifEmpty { hexId }`, unnamed
  relays included - `'0'` (0x30) sorts before `'R'`, so every `0x..` fallback lands ahead of every
  real name. That is not a defect: `MeshStatsEngine.sortedRelays` is a faithful port of
  `get_sorted_nodes` (mesh_stats.py:1120-1140), whose name branch is the same raw string sort with
  the same hex-id fallback. Ties are stable in both - `LinkedHashMap` plus a stable `sortedBy` here,
  an insertion-ordered `dict` plus a stable `list.sort` there - so equal rows keep first-seen order
  and do not swap between snapshots.

  What changed is only that it can be *seen*. Until F-3 was fixed the relay list was a 128 px slit
  showing two thirds of one card, and a sort is invisible with one row.
- **What is actually wrong:** both `LazyColumn`s carry a comment saying

  > Keyed on relayByte, not list index, so re-sorting animates items into their new position
  > instead of rebuilding every row.

  and neither animates anything. A key preserves an item's *identity* across recompositions, which
  is what stops the row being rebuilt; placement animation is a separate opt-in,
  `Modifier.animateItem()` on the item content, and it appears nowhere in this project (`grep
  animateItem` returns nothing). So a card that changes rank teleports.
- **Why it matters more than it reads.** Under `PACKETS`, the default and the mode this app starts
  in, the sort key changes on *every relayed packet* - 128 of them in the 26 minutes this screen was
  observed. Each one can flip two adjacent cards. On the terminal tool that is invisible: it redraws
  a whole table into a curses window and movement between frames is what a table does. On a touch
  screen a card can move between the moment it is read and the moment it is tapped, and the tap then
  opens a different relay's detail screen. The list is now tall enough to show three or more cards,
  so there is real distance for a card to travel.
- **Severity:** degraded - no data is wrong, but the primary screen moves under the finger, and a
  comment documents behaviour the code does not implement
- **Status:** open - filed, not implemented

**Notes for whoever fixes it.** Two separate things, and the second is the interesting one:

1. `Modifier.animateItem()` on the item content in both lists, and correct the two comments either
   way. A comment claiming an animation that is not there is worse than no comment - it is what
   would stop the next reader from looking.
2. Whether a live re-sort is right for this screen at all. Animating a card that moves every second
   makes the movement prettier, not less disruptive. Worth weighing: re-sort only when the sort mode
   changes or the user pulls to refresh, keeping the live figures updating in place; or keep the
   live sort but suppress reordering while the list is scrolled away from the top. The original's
   behaviour is not automatically the right answer here - it was designed for a redrawn terminal
   table with no touch targets in it.

---

## F-7 - the plot is a sliver: one pixel per measurement leaves 92% of the graph area empty

**Found:** 2026-09-02, Galaxy SM-S721B, Android 16 (API 36), 1080x2340 at density 450, debug build
of `feat/signal-graph` at `436cb16`, Zona Centro demo scenario.

**What was seen.** With 69 measurements collected, the drawn trace occupied **87 of the 1100
pixels** of the plot area - about 8% - as a thin band at the top, with the rest of the screen empty.
Read off a pixel scan of `screencap`, not by eye: rows carrying non-background pixels ran y=1060 to
y=1146 inside a plot spanning y=1060 to y=2160.

**Why it happens, and why it is not a bug.** Requirement 13 of the design says one measurement is
one pixel row, and `SignalGraphScreen` fixes `PX_PER_SAMPLE = 1f`. The code is doing exactly what it
was told. But a *physical* pixel at density 450 is 0.36 dp, so filling this plot needs **1100
measurements**. In the demo, running at roughly one packet a second, that is 18 minutes. On a real
relay heard every ten seconds it is **just over three hours**. For the first hours of any survey -
which is most of the time anyone will open this screen - the chart is a sliver above a large empty
area, and it reads as broken rather than as sparse.

**A second consequence, worth stating separately.** While the content is shorter than the viewport
`maxScrollPx` is zero, so nothing scrolls, the custom scrollbar has nothing to move, and the
crosshair clamps every touch below the trace to the last row. Acceptance items **H7** (the gesture
split) and **H14** (a saturated series) are therefore *not testable at all* until a subject has
collected more measurements than the plot is tall. That is not a defect in those items; it means the
first hours of a session cannot exercise the scrolling half of this screen.

**Severity:** degraded - every number on the screen is correct, the crosshair reads the right
measurement, and nothing crashes. What is wrong is that the default scale makes a working feature
look empty.

**Status:** fixed - `ChartGeometry.fitPxPerSample`, plus the fix note below.

**Notes for whoever fixes it.** The machinery is already there and tested: `ChartGeometry` takes
`pxPerSample` in every signature and `ChartGeometryTest` exercises it at 0.1, 1 and 4, including the
fractional round trip that `ROW_EPSILON` exists for (ruling 35). The zoom control is recorded in
`deferred-work.md` as deferred at the owner's request, and this is the field evidence for taking it
off that list. Three options, in the order I would weigh them:

1. **Fit-to-viewport as the default**, with the fixed 1 px scale as an option: choose
   `pxPerSample` so the retained series fills the plot, clamped to some sane maximum so two
   measurements do not become a 500-pixel staircase. Costs nothing structurally - it is a value to
   pass - but it makes the vertical axis non-uniform between subjects, so two charts are no longer
   directly comparable by eye.
2. **The zoom control the design already anticipated** (2x, 4x, and fractions below 1). Honest and
   explicit, but it puts the work on the reader every time they open a young chart.
3. **A dp-based rather than pixel-based row height.** One measurement per dp would make the trace
   2.8x taller here and identical across densities, which the current pixel rule is not - the same
   series is nearly three times shorter on this phone than on a 160 dpi device. Worth noting that
   the present behaviour is already density-dependent in a way requirement 13's wording does not
   acknowledge.

**What was done.** The owner chose option 1. `PX_PER_SAMPLE` is gone; the scale is derived once per
composition by `ChartGeometry.fitPxPerSample(size, viewportPx, minPxPerSample)`, which is
`viewportPx / size` clamped up to a floor - **fit while it fits, then scroll**. The floor is
`MIN_PX_PER_SAMPLE = 2f`, the value ruling 41 established by measurement: two pixels of vertical
room per measurement at a `1.dp` point radius, below which dots overlap so heavily that the trace
becomes the solid band ruling 40 exists to avoid.

**The changeover is at `viewportPx / 2` measurements - 550 on this phone's 1100 px plot.** Below it
the whole retained series is on screen, `maxScrollPx` is 0, and the scrollbar correctly has no
travel: nothing is hidden, so there is nothing to reveal. Above it the floor takes over and the
chart scrolls exactly as it did before this change - a saturated 5000-sample buffer is still
10000 px of content. The 69 measurements this issue was measured at now fill the plot exactly,
against the 87 pixels of 1100 recorded above.

**The consequence option 1 names is accepted, not avoided:** the vertical axis is no longer uniform
between subjects, so two charts of different lengths are not directly comparable by eye. Recorded
as ruling 44. A second, smaller consequence is new: while the chart is fitting, the scale changes
slightly with every measurement, so the plot compresses gently as it fills. Row 0 is the newest
measurement at y=0, so the top edge stays put and the compression happens below it. Acceptance item
**H20** is what judges whether that reads as distracting on the phone.

**One thing had to be fixed alongside it.** While fitting, the chart's content height is the round
trip `size * (viewportPx / size)`, which in IEEE-754 misses `viewportPx` by about a ten-thousandth
of a pixel in whichever direction the rounding goes, differently for each series length. Against
`ChartScrollbar`'s old `contentPx <= viewportPx` guard that would have made the whole 12 dp bar
appear and vanish at random as measurements arrived. The guard now asks for one whole pixel of
travel (`MIN_SCROLLABLE_PX`): a fraction of a pixel is not a distance anything can be scrolled to,
and past the changeover the travel is a whole row, so nothing genuinely scrollable is hidden.

**One knock-on worth knowing about.** A fitted scale is an arbitrary `Float` such as 15.94, where
`yOf`'s subtract-then-add round trip is not exact - at the old power-of-two `2f` it was. That makes
`ChartGeometry`'s `ROW_EPSILON` (ruling 35) load-bearing for the first time: it is now the only
thing keeping the crosshair's numbers on the row its rule is drawn at. Both KDocs say so, so that a
later reader does not simplify it away as dead defence.

**The second consequence recorded above is unchanged in kind, only in threshold.** Acceptance items
**H7** (the gesture split) and **H14** (a saturated series) still need a series longer than the
changeover before there is anything to scroll - 550 measurements rather than 1100. They are
untestable on a young chart by design now rather than by accident: a chart with nothing hidden has
no scrolling to exercise.

**Not verified on the phone at the time of this commit.** Nothing was compiled or run locally - CI
is the gate on this branch - and the hardware run is H20.
