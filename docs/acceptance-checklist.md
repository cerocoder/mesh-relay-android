# Hardware Acceptance Checklist

This checklist is worked through by hand, on two physical LoRa nodes, with a real phone in
hand. Nothing in it can be satisfied by CI. It is the last gate of this project, and it exists
because CI passing is not the same as the app working: on the sibling project
(`mesh-test-android`), six defects passed three code reviews and a fully green build, and were
found only by someone holding the hardware — two of those six were regressions introduced while
fixing the other four. Every green checkmark up to this point has earned this document, not
replaced it.

**Do not tick an item because it was discussed, reasoned about, or looked correct in a diff.**
Only a real run, on the device, counts. If an item was not actually run, leave it unticked and
say so in the Notes column.

## Before you start

**Where the APKs come from.** Both variants are built by CI on every push and published as
GitHub Actions artifacts on the workflow run for the commit under test:
- `app-debug` — includes the built-in demo device, used for most of this checklist.
- `app-release` — no demo devices at all; a small number of items below call this out
  explicitly and must be re-run against it.

**The two devices.** A LilyGO T-Echo and a Heltec Mesh Node T114. Both need to be paired fresh
at least once during this pass (items 3 and 4); after that either device can be used
interchangeably unless an item says otherwise.

**Pairing mechanics.** Bluetooth pairing requires reading a numeric code off the node's own
screen and entering it on the phone. The app's bonding timeout is **two minutes**, not the
Android default — thirty seconds was tried on the sibling project and was not enough time for a
human to read a small screen, look at the phone, and type the digits back. If you hit the
two-minute timeout anyway, that itself is worth recording — it means something upstream of the
timer is not the human transcription step.

**Language.** Several items must be run with the phone's language set to **Español**, not the
system default. Where that applies it is called out by name in the item, because the
corresponding bug was invisible under any other language.

**Order.** The items below are grouped so you touch permissions and pairing once each, then work
through live traffic and the detail/settings surfaces, then leave the long-running and
out-of-range checks for last (they take real clock time and are best started and left running
while you do something else).

---

## Group A — Permissions, Bluetooth state, and pairing

### A1. Fresh install, no permissions granted
**Do:** Install the app on a phone that has never granted it any permission. Open it cold.
**Pass looks like:** the permission flow completes (each prompt is understandable and, once
granted, the flow moves on) and the device list screen appears at the end without a restart.
**Silent-failure watch:** the app appearing to "hang" on a blank screen after the last permission
is granted — that would mean the permission-granted callback isn't reaching the list screen.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### A2. Bluetooth switched off from the notification shade
**Do:** With the app open on the device list (or connected), pull down the shade and turn
Bluetooth off. Then turn it back on from the shade, without touching the app.
**Pass looks like:** the app explains that Bluetooth is off and what to do next (not a bare
error code), and once Bluetooth is switched back on the app recovers on its own — no relaunch
required.
**Silent-failure watch:** the screen looks normal after Bluetooth is restored but the app never
actually reconnects — check that a scan or connection genuinely resumes, not just that the
warning banner went away.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### A3. Pair the Heltec Mesh Node T114 from cold
**Do:** With the T114 powered on and unpaired (forget it first if it was ever paired before),
start pairing from the app. Read the code from the T114's own screen and type it into the phone.
**Pass looks like:** the code is accepted and the bond completes comfortably inside the
two-minute window.
**Silent-failure watch:** none expected — a failure here is loud (timeout or rejected code). If
the two-minute window is hit while transcribing a normal-length code, record it; it suggests a
real regression, not a human-speed problem.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### A4. Pair the LilyGO T-Echo from cold
**Do:** Same as A3, on the T-Echo.
**Pass looks like:** same as A3.
**Silent-failure watch:** same as A3.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### A5. Relaunch from the launcher after a task swipe, while connected — *added*
*Why this is here:* `connectRequested` used to live on the Activity while the actual BLE
connection lived in the process. Swiping the app away in the recent-tasks list and relaunching
from the launcher icon left the GATT connection alive in the background while the newly created
Activity believed nothing was connected and started a fresh low-latency scan alongside it. The
old symptom was **not an error dialog** — it was a dropped link with nothing visibly wrong on
screen.
**Do:** Connect to a node normally. Swipe the app out of the recent-tasks list (do not use
"force stop", just the task swipe). Reopen the app from the launcher icon.
**Pass looks like:** the app reflects the still-live connection (or a clean single reconnect) and
the relay list keeps updating.
**Silent-failure watch:** the app looks like it reconnected (device list or status strip look
normal) but the relay list has actually **stalled** — no new relay entries or updated counters
for a full minute or more of known live traffic. Watch the list itself, not just the connection
banner.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### A6. Tap a different node while already connected — *added*
*Why this is here:* a review finding showed the app previously had no forward path out of an
already-connected state on a back press, and the fix that closed it makes a tap on a second node
tear down the first connection and handshake fresh with the new one. That teardown-and-rehandshake
path needs to actually be exercised on hardware, not just read in a diff.
**Do:** While connected to node A, go back to the device list and tap a different node B.
**Pass looks like:** the connection to A is cleanly torn down, a fresh handshake with B
completes, and B's own data (node database count, relay list) appears — not stale data from A.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

---

## Group B — Handshake and live traffic

### B1. Handshake completes; node database count matches
**Do:** Complete the handshake with a node. Compare the node database count shown in the app's
status strip against that same node's own count (e.g. via `meshtastic --info` on the same
node).
**Pass looks like:** the numbers match exactly.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### B2. Relay list populates from live traffic
**Do:** Let traffic flow and watch the relay list fill in. Cross-check relay byte values and
match counts against `meshtastic --info` on the same node.
**Pass looks like:** relay bytes and counts correspond to what the node itself reports.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### B3. A relay with several matching nodes shows `[n]`, never a name — *includes the brief's item 7, expanded*
*Why this is here, beyond the brief:* a relay's forwarded byte is only the last byte of a node
number, so several nodes in the database can share one relay byte. The app must show an
ambiguity count (`[n]`) and must **never** silently pick one of the candidates' names and show it
as if it were certain — that would misattribute traffic to a specific node that may not be the
real source.
**Do:** Find (or arrange, if the node database has one) a relay byte that matches more than one
node. Confirm the relay list shows `[n]` for it and no name.
**Pass looks like:** `[n]` shown, count matches the real number of candidates, no name.
**Silent-failure watch:** the row quietly shows one plausible-looking name instead of `[n]` — this
would look completely correct to anyone not cross-checking the node database by hand.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### B4. Skip-list round-trip on an ambiguous relay — *added, extends brief's item 10*
*Why this is here:* the brief's item 10 exercises skip on a single relay; this extends it to
confirm the skip mechanism behaves correctly against an *ambiguous* relay's candidate list, and
that the round trip (skip → clear) actually restores state rather than just changing what's
displayed.
**Do:** On the ambiguous relay from B3, skip one of its candidate nodes. Confirm the `[n]` count
drops by one (and, if only one candidate remains, that the row now shows that node's name
instead of `[n]`). Then clear the skip and confirm the count returns to its original value.
**Pass looks like:** the count falls on skip and returns to the original value on clear — both
directions verified, not just skip.
**Silent-failure watch:** the count falls correctly on skip but does not fully return after
clear (an off-by-one or a stuck entry) — this would look fine at a glance because the UI updated
at all.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### B5. Skip a candidate on a non-ambiguous relay, then clear (brief's item 10, as written)
**Do:** Skip a candidate on an ordinary relay. Confirm the relay's name and match count change
to reflect the skip. Clear the skip.
**Pass looks like:** name/count change on skip, and revert on clear.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### B6. Signal gauges and flash
**Do:** Watch the signal gauges (SNR / rxRssi) while traffic arrives; confirm the flash fires on
packet arrival. Check both light and dark appearance modes.
**Pass looks like:** gauges move in response to real packets, the flash fires on arrival, and
both modes render legibly and correctly.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### B7. The never-heard relay reads "Never" — *added*
*Why this is here:* a relay that has never actually been heard has a zero timestamp
(`lastPacketAtMillis == 0`). The age display previously computed `now - 0` directly, which is a
huge positive number of milliseconds and rendered as something like "492777h 46m" instead of a
sentinel — a wrong-but-plausible-looking number rather than an obvious error, exactly the kind of
defect that survives a walkthrough because nothing about it "looks broken" unless you know to
check.
**Do:** Find a relay entry that has genuinely never sent a packet this session (or check the
directory-only entries alongside the ones with real traffic).
**Pass looks like:** the age field reads **"Never"**, not a large hour count.
**Silent-failure watch:** a very large but not obviously absurd number (tens or hundreds of
thousands of hours) — easy to skim past as "some old node," so read the actual field, don't just
glance at the shape of it.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### B8. Detail screen: attributes and outbound links, run in Español — *includes and expands the brief's item 9*
*Why the language requirement is explicit here:* the map and Meshview links previously crashed
after switching the phone's language, because `LocalizedApp` supplies a non-Activity `Context`
for the localized locale and the detail screen's `PositionLine` called `context.startActivity(...)`
directly on it, which throws on modern Android without `FLAG_ACTIVITY_NEW_TASK`. Under the
default SYSTEM language the bug cannot reproduce at all — a tester following only the brief's
original wording, in whatever language the phone happened to be in, could sign this item off
clean while the defect sat there untouched. **This item must be run with the phone's language
set to Español, or it proves nothing.**
**Do:** Set the phone's language to Español. Open a matching node's detail screen. Confirm it
carries position, role and hardware. Tap the map link and the Meshview link.
**Pass looks like:** all three attributes are present and correct, and both links open the
correct destination without a crash, in Español.
**Silent-failure watch:** none here — this is specifically a "does it crash" check, and a crash
is loud. The silent-failure risk is skipping the Español requirement and unintentionally passing
a broken build.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### B9. Meshview URL entered with no scheme — *added*
*Why this is here:* this is a **known pre-existing issue**, not a regression from this build —
`ACTION_VIEW` on a schemeless `Uri` (e.g. `meshview.meshtastic.es` typed into Settings instead of
`https://meshview.meshtastic.es`) matches no installed activity, so the Meshview button does
nothing useful. It is being tracked here rather than fixed in this project because the fix
belongs on the Settings input side (normalise or validate the URL there), not in the button
itself. Record what actually happens; do not expect a pass.
**Do:** In Settings, enter a Meshview URL with no scheme. Return to a node's detail screen and
tap the Meshview button.
**Record:** what happens (nothing / silent failure / crash / toast). This is a known-issue
record, not a pass/fail — flag it as open work for the Settings screen regardless of the outcome.

- [ ] Ran on: __________________ Behaviour observed: _______________________________________
  Notes: ________________________________________________________________________________

---

## Group C — Remote nodes and neighbours

### C1. Remote nodes tab
**Do:** Open the remote nodes tab. Confirm it lists senders with hop counts. Open one remote
node and confirm the relays carrying its traffic are listed.
**Pass looks like:** senders and hop counts are populated and plausible; opening a node shows
the correct carrying relays.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### C2. Neighbours tab
**Do:** Open the neighbours tab.
**Pass looks like:** it shows only directly-heard nodes — nothing that arrived via a relay hop
should appear here.
**Silent-failure watch:** a relayed node sneaking into this list would look completely at home
next to genuine neighbours; cross-check hop count / relay origin for anything you don't recognize
as a direct neighbour.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### C3. The two new sort modes — *added with the sort-modes work*
**Do:** On Relays, choose **Known nodes**, then **Latest packet**. For each, read the order off a
`uiautomator dump` and check it against the per-card figures (the count of remote nodes a relay
carries; the packet age). Repeat **Latest packet** on Neighbours.
**Pass looks like:** the order matches the numbers on the cards, descending, on both screens; the
status strip names the mode that was chosen.
**Silent-failure watch:** a mis-keyed sort still produces *an* order and looks fine at a glance —
only the card figures prove it.

- [x] Ran on: **2026-09-01, Galaxy (1080x2340, density 450), node 49bf + demos**  Result: **PASS**
  Notes: Read off `uiautomator dump`, live node. Relays / Known nodes: `0x4b` (5 known nodes), `0x30` (1), `0x68` (1) - descending, matching the per-card figures. Relays / Latest packet: `0x4b` 28sec ago, `0x6f` 51sec ago. Both modes present in the menu, seven entries in all. Neighbours / Latest packet applied and named in the strip.

### C4. Known nodes on Neighbours falls back, and says so — *added with the sort-modes work*
**Do:** Choose **Known nodes** on Relays (or set it as the default sort in Settings), then switch
to the Neighbours tab.
**Pass looks like:** the sort menu on Neighbours does not offer Known nodes at all; the status
strip reads **Packet count** / **Número de paquetes**, and the list order matches that, not the
mode that was chosen.
**Silent-failure watch:** this is the one case where the strip and the list can disagree. A strip
saying "Known nodes" over a packet-count order is the defect, and it looks like a working screen.

- [x] Ran on: **2026-09-01, Galaxy (1080x2340, density 450), node 49bf + demos**  Result: **PASS**
  Notes: Known nodes chosen on Relays, then the Neighbours tab. Its sort menu lists six entries and **Known nodes is not among them**; the ticked entry is `Packet count`; the strip reads `Sort by  Packet count`. Strip, tick and order all agree on the mode that actually ran.

### C5. The My node tab, all three states — *added with the My node work*
**Do:** Open **My node** / **Mi nodo** on a live connection. Then reach the other two states: kill
and restart the app to catch the moment after the handshake but before the node's own NodeInfo
arrives, and read the pre-handshake state from the demo transport.
**Pass looks like:** connected and populated, the card shows this node's long name, short name,
position, altitude, Src and the Meshview link — with **no distance figure**, since there is no
other node to measure from. Before its NodeInfo arrives: the "has not sent its own details yet"
message. Before the handshake: "Local node unknown" / "Nodo local desconocido".
**Also confirm:** neither list screen shows the local-node block any more.

- [x] Ran on: **2026-09-01, Galaxy (1080x2340, density 450), node 49bf + demos**  Result: **PASS (two states observed, one unreachable by hand)**
  Notes: Live node, populated: `!5ead49bf`, `M - PQP78`, `49bf`, `CLIENT_BASE`, `HELTEC_MESH_NODE_T114`, `40.331281, -3.750738`, `648 m`, `Src: DB:5min`, Google Maps / OpenStreetMap / Meshview, `Last heard`, `Uptime 8days 12hrs 23min`, `Restarts 0`, four telemetry rows - and **no distance figure**, which is the `from = null` claim and is only provable against a node that has coordinates. No `Sort` in `content-desc`, only Pausar/Mas. No-node-info state seen in Spanish on the 200-node and Zona Centro demos: "Este nodo aun no ha enviado sus datos...". **The third state (`Local node unknown`) was not reachable by hand:** `localNodeNum` is set from the `my_info` handshake frame and never cleared, and `Screen.Main` is only pushed once the connection reports Connected - so on this build the branch is correct but unvisitable. It has a preview; leaving it in place is right, and it stays defensive rather than dead. Local-node block confirmed **gone** from both lists: on the Relays and Neighbours screens the first card now begins at y=556 where it began at y=938 before - about 135 dp of list returned on the screen that had the least.

### C6. The three-count status strip at a large font scale, in Spanish — *added with the My node work*
**Do:** Set the system font scale to its largest, the language to Español, and open **Mi nodo**
while paused, so the row holds Total, Repetidos, Directos *and* the paused badge. Read the strip's
bounds off a `uiautomator dump`.
**Pass looks like:** every label and number is present and inside the screen's width; the row wraps
onto a second line rather than clipping or pushing anything off the edge.
**Silent-failure watch:** this is the F-3 shape. A screenshot at the default font scale in English
will not show it.

- [x] Ran on: **2026-09-01, Galaxy (1080x2340, density 450), node 49bf + demos**  Result: **PASS**
  Notes: Espanol, font scale 1.3, paused, Zona Centro demo. Line 1 `Total 52  Repetidos 36  Directos 16`, ending at x=909 of 1080; line 2 `Pausado` - the badge **wrapped** instead of clipping or pushing a count off the edge. The Relays strip wrapped the same way, with `Ordenar por / Numero de paquetes` moving to a second line. This is the FlowRow doing what it was put there for; a `Row` would have shown the F-3 failure here.

---

## Group D — Controls: pause, reset, reload

### D1. Pause and resume
**Do:** Pause. Confirm counters stop entirely (not just slow down). Resume.
**Pass looks like:** nothing moves while paused; on resume, counting continues from where it
stopped rather than jumping or resetting.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### D2. Reset
**Do:** Reset. Confirm statistics clear.
**Pass looks like:** statistics are cleared, but the node database count and the skip-list
survive the reset unchanged.
**Silent-failure watch:** the skip list quietly clearing along with statistics — check the exact
skip entries you set earlier are still there, not just that the settings screen still has a
skip-list section.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### D3. Reload node database
**Do:** Trigger a node database reload.
**Pass looks like:** a spinner appears and clears, the node database count updates, and
statistics (relay counts, gauges, etc.) are untouched by the reload.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### D4. Demo mode: reload does not change the traffic rate — *added, requires debug APK*
*Why this is here:* a JUnit test asserting "no second traffic loop starts after a demo-mode
reload" was written during the build, found to pass for the wrong reason (a test-clock boundary
artifact, not because the code was actually correct), and was deliberately **deleted** rather
than left in place as a test that could never fail. That was the right call by this project's
own standard, but it leaves the underlying property completely unverified by anything except a
human. This item is that verification. Requires the **debug** build (the demo device does not
exist in `app-release`).
**Do:** Run the debug APK, connect to the built-in demo device, note the traffic rate, then press
reload.
**Pass looks like:** the traffic rate after reload is the same as before — specifically, no
second demo-traffic loop has started running alongside the first (which would show as roughly
double the packet rate, or visibly duplicated/accelerated relay updates).
**Silent-failure watch:** a doubled traffic rate can look like "the demo is just lively" rather
than an obvious bug — compare the rate before and after reload directly, don't just eyeball
whether it "looks busy."

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

---

## Group E — Rotation and localisation

### E1. Rotate on every screen
**Do:** Rotate the phone on every screen in the app, including with a detail screen open and
with a list scrolled partway down.
**Pass looks like:** nothing is lost across rotation — the open detail screen stays open (same
node), and list scroll position is preserved.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### E2. Full Español pass
**Do:** With the phone set to Español, walk every screen, every dialog, every notification, and
every empty state.
**Pass looks like:** everything is translated, and no string shows a raw format placeholder
(e.g. `%1$s`, `{0}`) instead of a substituted value.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

---

## Group F — Release build

### F1. Release build has no demo devices, and a live node still connects
**Do:** Install the **release** APK (`app-release`, not `app-debug`). Confirm no demo device is
offered anywhere in the device list. Connect to a real, live node.
**Pass looks like:** no demo device appears; the live node connects and behaves as in the debug
build.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

---

## Group G — Long-running and out-of-range (start these last, let them run)

### G1. Screen locked for thirty minutes
**Do:** Lock the screen with the app connected and receiving traffic. Leave it locked for thirty
minutes. Unlock and return to the app.
**Pass looks like:** the ongoing notification is still present throughout, counters have kept
rising the whole time (not just resumed on unlock), and the relay list is current — not stale
from the moment of locking — when you return.
**Silent-failure watch:** the notification is present but counters actually stopped incrementing
partway through — check a counter value against elapsed time, don't just confirm the
notification survived.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### G2. Walk out of range and back
**Do:** Physically walk away from the node until the link drops, then walk back into range.
**Pass looks like:** the reconnect loop recovers the connection with no tap from you, and the
foreground service survives the gaps between reconnect attempts (it doesn't die and get killed
by the OS while waiting to retry).
**Silent-failure watch:** the app appears to reconnect but the service was actually restarted by
the OS in between — check that session-scoped state (e.g. statistics accumulated before the
drop) is still present, not silently reset by an invisible service restart.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### G3. Kill Bluetooth mid-session
**Do:** With the app connected and receiving traffic, turn Bluetooth off from the shade mid-session
(distinct from A2's cold-start check — this is specifically about the message shown while a
session with real traffic history is active).
**Pass looks like:** the message names the actual cause (Bluetooth) and the next step to take —
not a raw status/error code.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### G4. Battery over a two-hour session, screen mostly off
**Do:** Run a two-hour session with the screen off for most of it (as it would be in real use).
Compare the app's own reported drain (if any) against the phone's own battery-usage reporting for
the app.
**Record:** both figures, and whether they are consistent with a background service doing BLE +
LoRa relay work for two hours (i.e. not wildly out of line with similar always-on BLE apps on the
same phone).

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

---

## Group H — The Graph command (added 2026-09-02)

Nothing in this group has ever been run. CI proves the branch compiles and its unit tests pass;
no reviewer has seen a pixel of it, and this project has no Compose test harness.

### H1. The overflow menu reaches the Graph from both subjects
**Do:** Open a **relay** detail screen. Confirm a `⋮` button in the top right, tap it, tap
**Graph**. Go back, open a **neighbour** detail screen, and do the same.
**Pass looks like:** the button appears on both; both open a chart titled for that subject.

- [x] Ran on: 2026-09-02, SM-S721B / Android 16 (API 36), build 436cb16
  Notes: PASS (relay side). `More` at [968,148][1036,216]; menu item "Graph" / "Gráfica". Neighbour side NOT yet run.

### H2. The chart matches the drawing
**Do:** With a relay that has heard a few hundred packets, read the screen against `Pic1.pdf`.
**Pass looks like:** title, two switches stacked and right-aligned under the app bar, the RSSI and
SNR bars, a `Time` field above the plot, the scrollable plot, a `Time` field below it. Two lines,
green for SNR and blue for RSSI — the same colours as the bars.

- [x] Ran on: 2026-09-02, SM-S721B / Android 16 (API 36), build 436cb16
  Notes: PASS. Title, both switches right-aligned, both bars, Time above and below. The plot itself: see F-7.

### H3. Newest at the top, and arriving data does not yank the view
**Do:** Watch a live relay. Scroll down into the history and wait for new packets to arrive.
**Pass looks like:** the newest measurement is at the top; while scrolled down, the measurement
under your eye does not move as new ones arrive.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### H4. Freeze holds the drawing; collection continues
**Do:** Switch **Freeze** on over a busy relay. Wait a minute. Switch it off.
**Pass looks like:** nothing on screen moves while frozen — not the plot, not the bars, not the
scales. Switching off redraws complete, including everything collected while it was held.

- [x] Ran on: 2026-09-02, SM-S721B / Android 16 (API 36), build 436cb16
  Notes: PASS. Held at count 79 for 12 s while the engine collected; unfreeze jumped to 87, redrawn complete. Confirms the bars are held too (ruling 36).

### H5. Auto scale moves both the bars and the plot
**Do:** Switch **Auto scale** on. It is off by default.
**Pass looks like:** the left and right borders of *both bars* and of the plot become the observed
minimum and maximum. Turn Freeze on as well and confirm the scale then holds.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### H6. The crosshair and the globe
**Do:** Touch the plot. Drag the line up and down. Tap the globe.
**Pass looks like:** a horizontal rule with the timestamp above it, RSSI and SNR below it in their
own colours, and a globe at its right. Google Maps opens at that measurement's position. On a
measurement with no stored position the globe is greyed out. With TalkBack on, the globe says
whether the position came from the node or from the phone.

- [x] Ran on: 2026-09-02, SM-S721B / Android 16 (API 36), build 436cb16
  Notes: PASS. Crosshair gave timestamp, RSSI and SNR; the globe is a real 48 dp target (135 px at density 450) whose description names the origin.

### H7. The gesture split — the most likely thing to feel wrong
**Do:** On the plot, drag slowly, then flick fast. Then drag the scrollbar on the right edge.
**Pass looks like:** a drag on the plot moves the crosshair and does **not** also scroll; the
scrollbar is what scrolls. Also confirm the 12 dp scrollbar is actually grabbable — it is below
the 48 dp interactive minimum, and the whole bar rather than just the thumb is draggable.

- [x] Ran on: 2026-09-02, SM-S721B / Android 16 (API 36), build 436cb16
  Notes: NOT TESTABLE YET. See F-7: the trace is shorter than the viewport, so maxScrollPx is 0 and there is nothing to scroll.

### H8. The `Time` fields name the rows actually on screen
**Do:** Scroll to a position that is neither end. Compare both `Time` fields against the topmost
and bottom points you can see, using the crosshair to read each one.
**Pass looks like:** they match exactly. Off by one measurement means the label window regressed
to the drawing window.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### H9. Spanish
**Do:** Switch the app to Spanish and repeat H2 and H6. Then raise the system font scale.
**Pass looks like:** `Escala automática` fits beside its switch without wrapping badly or
clipping; the `Hora:` fields fit on one line; the crosshair's three labels ellipsise rather than
cut mid-glyph and do not collide with the globe. **Read this off `uiautomator dump`, not off a
screenshot** — every layout defect in this project was found in `bounds`.

- [x] Ran on: 2026-09-02, SM-S721B / Android 16 (API 36), build 436cb16
  Notes: PASS, read off uiautomator dump. `Escala automatica` ends at x=866 with the switch beyond it, no wrap or clip; crosshair labels end at x=317, globe starts at x=911, no collision; decimal commas and d/M/yy dates correct.

### H10. Use phone location, and refusing it
**Do:** Confirm **Use phone location** is on by default in Settings with its summary underneath.
Walk a few metres and check the globes. Switch it off, walk again, check the globes' spoken
descriptions. Separately, on a fresh install, deny the location permission at first connect.
**Pass looks like:** on, pins follow the phone and say "from the phone"; off, they say "from the
node" and the phone's GPS is not used. A refusal is **not** an error: the app connects, collects,
and every measurement falls back to the node. Then, with **Collect in background** on and the
location permission granted, lock the screen for several minutes while walking, and check the
measurements taken during it: they must still carry a phone position, which is what the service's
second foreground-service type (`location`, ruling 39) buys. A run of samples that all fall back
to the node while the phone was moving is this item failing.
**If it does fail on a device that otherwise looks correctly configured, check this first:** from
Android 11 a foreground service *started while the app is in the background* receives no
while-in-use permissions at all, location included, whatever type it declares. Here the start is
made from the foreground (a tap on a device, or the background-collection switch — see
`AppContainer.startForegroundService`), so it should pass; but that rule is invisible in the
manifest and in the logs, and looking anywhere else first costs a field session.
**Warning:** the demo transport's local node has no coordinates, so with *Use phone location*
off every globe is correctly disabled there regardless of anything else under test. Judge this
item against a real node or the phone's own position, not a demo scenario, or a field issue gets
logged against behaviour that is actually correct.

- [x] Ran on: 2026-09-02, SM-S721B / Android 16 (API 36), build 436cb16
  Notes: PARTIAL PASS. Switch present with its summary, on by default. Granting location from system Settings and then resuming DID start updates - dumpsys location shows both providers at `@+10s0ms, minUpdateDistance=10.0`, the spec 7.1 constants reaching the platform. Globe description became "posicion del telefono", so the whole stamping chain works end to end. NOT yet run: screen-off stamping, and the demo-node caveat above (judged against the phone, not a real node).

### H11. Rotation, reset and subject switching
**Do:** Rotate the phone with the Graph open, frozen and scrolled down — **on a deliberately quiet
relay**, one that has not heard a packet in a while. Then reset the statistics from Settings with a
chart open. Then go Graph → back → a *different* subject's Graph, quickly.
**Pass looks like:** it comes back frozen, on the same subject, with the trace still there (scroll
position is not saved and returning to the top is expected). The reset falls to the empty state
rather than crashing. No frame of the previous subject's trace appears. The quiet-relay rotation is
the one path whose correctness was argued from framework source rather than observed.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### H12. Backgrounded with a chart open
**Do:** Open the Graph on a busy relay, press Home, return after several minutes.
**Pass looks like:** the chart is live and complete. Note any battery or CPU attributable to the
watch staying armed while backgrounded — that is a known, recorded consequence, and this item is
how its size gets measured.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### H13. The two empty states
**Do:** Open a Graph on a subject with no signal data at all. Then open one on a subject whose
bars have data but whose series is empty.
**Pass looks like:** the first shows one centred message with both switches present and disabled
and **no second copy** from the signal block; the second shows the bars plus one message below
them. Nothing else on the checklist looks at these, and a ruling exists specifically so
`detail_no_signal_data` cannot render twice.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### H14. A saturated series
**Do:** On a long-running busy relay, scroll to the oldest measurement.
**Pass looks like:** the scrollbar thumb sits flush at the foot of its track with the oldest row
at the bottom, and the chart does not scroll into empty space. Nothing checks past 5 000 samples,
which is where the ring buffer wraps, where `size` stops growing while `totalAppended` keeps
moving, and where the scroll clamp becomes load-bearing.

- [x] Ran on: 2026-09-02, SM-S721B / Android 16 (API 36), build 436cb16
  Notes: NOT TESTABLE YET. See F-7; needs a subject past roughly 1100 measurements.

### H15. The crosshair holds its measurement as packets arrive
**Do:** Touch the plot to place the crosshair on a measurement, then watch a live relay for
several minutes without touching the screen again.
**Pass looks like:** while the buffer is unsaturated, the crosshair still names the same
measurement afterwards. Note that past 5 000 samples it cannot, because the row and the index
stop advancing together. H3 checks that the view does not yank; nothing else checks that a
crosshair left in place still names what it named.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### H16. Degenerate Auto scale
**Do:** Switch Auto scale on for a relay with a single measurement, and separately for one whose
history is perfectly flat.
**Pass looks like:** the fixed scale is used, not a zero-width range — every point stacked
against the left edge would read as a dead link. This is unit-tested; this item is the visual
confirmation.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### H17. Declining location, then connecting — the crash this must not do
**Do:** On a fresh install (or after clearing the app's data and revoking location in system
settings), grant Bluetooth but **deny** location, then connect to a node and let the foreground
service start — the notification appearing in the shade is the moment under test. Leave it
connected for a minute, then background the app and come back.
**Pass looks like:** no crash, at all. The app connects, collects, the notification stands, and
every measurement falls back to the node's position. Since ruling 39 the service declares
`location` as a second foreground-service type, and from Android 14 starting it with that type
while the permission is missing throws `SecurityException` — so the type is computed at runtime
and left off when the grant is absent. This item is the only one on the checklist that would
catch that check being removed, and it is a hard crash if it ever is. Run it on the newest
Android available (14 or later); on older releases the failure mode does not exist.

- [x] Ran on: 2026-09-02, SM-S721B / Android 16 (API 36), build 436cb16
  Notes: PASS, with evidence. Location denied, connected: service types=0x00000010 (CONNECTED_DEVICE only, no LOCATION bit), no SecurityException, app alive. After granting, types=0x00000018 within one 30 s refresh.

---

### H18. Points, the doubled scale, and dismissing the crosshair
**Do:** Open a Graph with a good spread of measurements and switch **Auto scale** on. Then tap
once on the plot, drag the crosshair, and double tap it.
**Pass looks like:** the trace is discrete marks, not joined lines, and where the RSSI and SNR
clouds cross **both colours stay visible** — that is the whole reason for the change (ruling 40).
Each mark is a hard-edged 4x4 pixel square — ruling 46, at the owner's instruction after seeing
the 2x2 square from ruling 45 measured pixel-by-pixel on the phone — with no soft or antialiased
fringe around it; a blurred or rounded-looking mark is a fail. A single tap places the crosshair
with no perceptible delay, a drag still moves it and still does **not** scroll, and a double tap
makes it and its globe disappear. A brief flicker of the crosshair during the double tap is
expected and is documented in ruling 42.

- [x] Ran on: 2026-09-02, SM-S721B / Android 16 (API 36), builds 11cab7a, a4d3df1, 3d59f6e
  Notes: PASS, measured three times. 11cab7a (round marks, Auto scale on): 1017 RSSI-blue pixels against 1274 SNR-green - both metrics present in comparable quantity, where a polyline left one all but absent. Single tap placed the crosshair with no perceptible delay; double tap removed it and its globe. a4d3df1 (hard 2x2 squares): every mark exactly 2x2, ZERO partially covered pixels. 3d59f6e (4x4 at the owner's request): every mark exactly 4x4, 240 full pixels per metric over 15 marks, still ZERO partial pixels - the integral-coordinate approach holds at the larger size, so no antialias flag and no per-draw Paint. NOT yet seen: the banding ruling 46 predicts from about 275 measurements, where 4 px marks 4 px apart begin to touch.

---

### H19. Map provider setting reaches the crosshair globe
**Do:** Open Settings and find the new Map provider group, under Gauge mode. Leave it on its
default, open a Graph on a subject with a stamped position, place the crosshair and tap its
globe. Then go back to Settings, switch the value to OpenStreetMap, return to the same Graph,
place the crosshair again and tap the globe a second time.
**Pass looks like:** the group shows two options, Google Maps and OpenStreetMap, with Google
Maps selected on a fresh install. The first tap opens Google Maps; after switching the setting
the second tap opens OpenStreetMap instead — the globe's target actually follows the setting
rather than the fixed Google link it always opened before (ruling 43). `PositionLine`'s own
three buttons elsewhere in the app are unaffected either way.

- [x] Ran on: 2026-09-02, SM-S721B / Android 16 (API 36), build 11cab7a
  Notes: PASS. "Map provider" group present after Gauge mode with Google Maps and OpenStreetMap. Default is Google (no stored key). Choosing OpenStreetMap persisted OPEN_STREET_MAP and the crosshair globe then launched https://www.openstreetmap.org/... ; choosing Google Maps persisted GOOGLE. Coordinate formatting is pinned by MapLinksTest rather than read here.

---

### H20. A young chart fills the plot instead of banding at the top
**Do:** Open a Graph on a subject with only a few dozen measurements — a fresh session, or a relay
heard rarely — and look at the whole plot area. Watch it for a few minutes while new packets
arrive. Then find or wait for a subject with more than 550 measurements (the changeover on this
phone's 1100 px plot) and try the scrollbar on it.
**Pass looks like:** the trace uses the **full height** of the plot rather than sitting as a thin
band at the top of a mostly empty area — that is F-7, and fitting is the fix (ruling 44). The dots
are spread apart and individually resolvable rather than merged. As measurements arrive the plot
compresses gently, the top edge staying put with row 0 at the top; that is inherent to fitting and
the question here is only whether it is distracting enough to matter. On the young chart the
scrollbar has no travel and the plot does not scroll — correct, because nothing is hidden. Past
550 measurements the chart scrolls again and the scrollbar gains a thumb that moves, exactly as it
did before this change. The crosshair reads the same measurement its rule is drawn at throughout,
at the fitted scale as well as at the floor.
**Silent-failure watch:** the 12 dp scrollbar down the right edge **blinking on and off** as
measurements arrive on the young chart. It should be absent the whole time, not flickering — if it
flickers, `MIN_SCROLLABLE_PX` is not doing its job against the fitted scale's float round trip.

- [x] Ran on: 2026-09-02, SM-S721B / Android 16 (API 36), build 51249bf
  Notes: PASS on the fitting half. A chart barely a minute old filled 91% of the plot (span 1003 px of 1100), against the 8% F-7 measured at a fixed scale with ten times as many measurements. The shortfall from 100% is inherent and correct: the last row sits at (size-1)/size of the viewport, so a young chart stops just short of the bottom and approaches it as it fills. The scrollbar column is entirely background while fitting, so the MIN_SCROLLABLE_PX guard is doing its job and no zero-travel bar is drawn. NOT yet run: the changeover past 550 measurements, where the floor takes over and scrolling resumes - that needs a subject roughly 90 minutes old.

---


## Overall verdict

Fill in only after every item above has actually been run (or explicitly recorded as not run,
with a reason).

- **Total items run:** _____ / 52
- **Items passed:** _____
- **Items failed / found an issue:** _____ (list below)
- **Overall verdict (circle one):** ACCEPT / ACCEPT WITH KNOWN ISSUES / REJECT

**Issues found (one line each, with item number):**
-
-
-

**Fixes made in response, and which items were re-run after each fix:**
-
-
-

**Signed off by:** _______________________  **Date:** _______________________
