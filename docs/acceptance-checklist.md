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
**Do:** With a relay that has heard a few hundred packets, read the screen against `Pic1.pdf`. Note:
`Pic1.pdf` predates the 2026-09-04 relay-candidate-comparison spec's section 7 layout change (decision
59) and still shows the two switches stacked — read it for everything **except** that row's layout.
**Pass looks like:** title, two switches side by side on one row and right-aligned under the app bar,
the RSSI and SNR bars, a `Time` field above the plot, the scrollable plot, a `Time` field below it.
Two lines, green for SNR and blue for RSSI — the same colours as the bars.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: Cleared for re-run by the final whole-branch review (finding I-1): this item was PASS on
  2026-09-02 for the two switches **stacked**, which N7 now correctly asserts is no longer the
  layout - re-running against the old PASS text would have been a spurious FAIL. Prior run, for the
  record: 2026-09-02, SM-S721B / Android 16 (API 36), build 436cb16 - title, both switches
  right-aligned, both bars, Time above and below; the plot itself: see F-7.

### H3. Newest at the top, and arriving data does not yank the view
**Do:** Watch a live relay. Scroll down into the history and wait for new packets to arrive.
**Pass looks like:** the newest measurement is at the top; while scrolled down, the measurement
under your eye does not move as new ones arrive.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### H4. Freeze holds the drawing; collection continues
**Do:** Switch **Freeze** on over a busy relay. Wait a minute. Switch it off. With a candidate
selected (final whole-branch review, finding I-6), do the same and additionally watch the red
comparison line and the selector row's own gap and verdict while frozen.
**Pass looks like:** nothing on screen moves while frozen — not the plot, not the bars, not the
scales, and, with a candidate selected, not the red line or the selector's gap/verdict either.
Switching off redraws complete, including everything collected while it was held.

- [ ] Ran on: __________________ Result: __________________________________________________
  Previously: 2026-09-02, SM-S721B / Android 16 (API 36), build 436cb16
  Notes: PASS for the plot/bars/scales, held before the candidate-comparison feature existed on this
  branch. Held at count 79 for 12 s while the engine collected; unfreeze jumped to 87, redrawn
  complete. Confirms the bars are held too (ruling 36). The candidate-line/selector clause above is
  new (finding I-6's fix) and not covered by this run - re-run with a candidate selected before
  relying on this item; C-1 means the zona-centro demo cannot exercise it either, so this needs
  hardware the same way N1-N4 do.

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
fringe around it; a blurred or rounded-looking mark is a fail. Since ruling 47 fixed the vertical
pitch to equal the mark size, consecutive marks of the same colour are expected to **touch edge to
edge with no background gap between them, at any sample count** — that is tiling working as
intended, not the merged-band failure a coarser floor could once produce; a visible gap between two
consecutive same-colour marks is what would now be wrong. A single tap places the crosshair
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

### H20. A young chart tiles without gaps, and grows downward as it fills
**Do:** Open a Graph on a subject with only a few dozen measurements — a fresh session, or a relay
heard rarely — and look closely at the space between consecutive marks, not just the overall fill.
Watch it for a few minutes while new packets arrive. Then find or wait for a subject with more than
275 measurements (where the plot fills on this phone's 1100 px plot at the fixed 4 px pitch) and
try the scrollbar on it. Ruling 47 replaced the fit-to-viewport scale this item was originally
written and passed against with a fixed pitch equal to the mark size — that earlier PASS described
behaviour the app no longer has and is superseded, so the result below has been cleared and this
item needs a fresh run.
**Pass looks like:** consecutive marks **tile with no vertical gap between them, at any sample
count** — that is the whole point of ruling 47, and is the property to look at closely rather than
the overall fill. A young chart is **partial, not full**: it grows downward from the top as
measurements arrive, row 0 pinned at the top, and is expected to cover well under half the plot for
the first few dozen measurements (39% at 107, by calculation) rather than filling it the way ruling
44's fit did — that is the accepted trade, not a regression to flag. The chart begins scrolling
once the series passes **275 measurements** (`viewportPx / POINT_SIZE_PX` on this phone's 1100 px
plot), at which point the scrollbar gains a thumb that moves. The crosshair reads the same
measurement its rule is drawn at throughout.
**Silent-failure watch:** any vertical gap between two consecutive marks, at any sample count.
Under a fixed pitch equal to the mark size, tiling is meant to be exact and unconditional — a gap
anywhere means the pitch and the mark size have drifted apart.

- [x] Ran on: 2026-09-02, SM-S721B / Android 16 (API 36), build 57046e0
  Notes: PASS on tiling, measured. Both metrics form ONE contiguous vertical run of 103 rows with no gap anywhere between marks - the pitch is the mark size, so consecutive marks meet edge to edge. Horizontally they stay discrete: every run is exactly 4 px wide, 412 full pixels per metric, and ZERO partially covered pixels, so the hard edges survive the change. The chart was young (about 50 measurements, roughly 200 px of a 1100 px plot), so it is partial and grows downward, which is the reopened F-7 trade the owner accepted. NOT yet seen: the changeover at 275 measurements where the content exceeds the plot and scrolling begins.

---


## Group I — The six field fixes of 2026-09-02

Build `d3277fb`. Item I4 **cannot be checked on a demo scenario**: the demo never emits packets from
its own local node, so our node's absence from Neighbours there proves nothing. Use a real node.

### I1. Scrolling does not flash the packet marker
**Do:** On **Relays**, and again on **Neighbours**, scroll and fling hard, both directions.
**Pass looks like:** the yellow "a packet just landed" marker appears on **one row at a time**, only
on rows whose packet count is visibly moving. The defect looked like **many rows lighting at once**
as they scrolled into view — that is the discriminator, not whether any yellow appears at all. The
marker must still work: sit still on a busy relay and confirm it does flash.

- [x] Ran on: 2026-09-02, SM-S721B / Android 16 (API 36), build d3277fb — by the owner
  Notes: PASS. Scrolling and flinging both lists no longer lights rows as they come into view; the marker still flashes on a row whose packets are landing. Instrumented check beforehand agreed: 10 of 25 at-rest frames showed a genuine flash, and scrolling produced none.

### I2. A packet that just arrived reads 0, not "Never"
**Do:** Watch the age on a busy relay's card for a minute.
**Pass looks like:** it reads `0sec ago` when a packet lands, never `Never`. A relay genuinely never
heard from must still read `Never` — that case has not been broken.

- [x] Ran on: 2026-09-02, SM-S721B / Android 16 (API 36), build d3277fb — by the owner
  Notes: PASS. A packet that has just landed reads 0, not "Never".

### I3. The database fields say so
**Do:** Open a node card with database values, in English and in Spanish.
**Pass looks like:** **Last DB SNR** and **Last DB heard** (Spanish: `Último SNR de la DB`,
`Última recepción de la DB` — "DB" is left untranslated, matching the existing `source_db`).

- [x] Ran on: 2026-09-02, SM-S721B / Android 16 (API 36), build d3277fb — by the owner
  Notes: PASS. Last DB SNR / Last DB heard, both locales.

### I4. Our own node is out of the statistics — REAL NODE ONLY
**Do:** Connect to a real node. Check **Neighbours**, the Direct count, and then open a Graph on any
relay with *Use phone location* **off**.
**Pass looks like:** your own node is absent from Neighbours; Direct counts only other nodes' traffic;
and **the crosshair globe still resolves**. That last check is the important one — it proves our own
position still reaches the node directory. If the globe went dead, the guard was placed one step too
early and every node-stamped measurement lost its pin, with nothing on screen to say why.

- [x] Ran on: 2026-09-02, SM-S721B / Android 16 (API 36), build d3277fb — by the owner
  Notes: PASS on a real node. Our own node is out of Neighbours and out of the Direct count, and the Graph globe still resolves with Use phone location off - so our own position still reaches the node directory, which is the check that would have caught the guard being placed one step too early.

### I5. A different node starts from nothing
**Do:** Connect to node A (or a demo), let statistics build, then connect to a **different** one.
Then reconnect to that same one again.
**Pass looks like:** switching wipes relays, neighbours, counters and the node database; reconnecting
to the same one loses nothing. Note there is **no confirmation yet** — that is agreed and pending in
`deferred-work.md`, so a mistap on the device list currently costs the session.

- [x] Ran on: 2026-09-02, SM-S721B / Android 16 (API 36), build d3277fb — by the owner
  Notes: PASS. Switching to a different node clears everything; reconnecting to the same one loses nothing.

### I6. Exit releases the process
**Do:** Overflow menu → **Exit** → confirm. Try it once from a healthy connection and once while
disconnected or retrying.
**Pass looks like:** a confirmation first; then the notification clears, the app leaves recents, and
`adb shell pidof com.cerocoder.meshrelay` returns nothing. **Then reconnect immediately** — a fast,
normal connect means the GATT link was torn down cleanly. A slow first connect, or a failure, is the
symptom of a half-open link and is the one failure this command must not cause.
**Watch for:** up to ~5 seconds between confirming and the app vanishing, with no spinner. If it feels
like a hang you will tap twice; the second tap is harmless but worth knowing.

- [x] Ran on: 2026-09-02, SM-S721B / Android 16 (API 36), build d3277fb — by the owner
  Notes: PASS. Confirmation, then the notification clears and the app leaves recents; reconnecting afterwards behaves normally, so the GATT link is torn down cleanly and the scan-during-teardown defect found in review is genuinely closed.

---

## Group J — A 12/24 hour clock and one map link (2026-09-03)

### J1. A 12 or 24 hour clock, everywhere the app prints one
**Do:** Open Settings and find the new **Time** group, after Map provider. Note its default. Open a
node's detail (**Last DB heard**) and a Graph (both **Time** fields, plus the crosshair on a subject
with position data) on the default setting, then return to Settings, switch **Time**, and check the
same three places again. While there, place the crosshair near midnight and near noon on a subject
whose data spans them, in **12 hour format**. Switch the phone to **Español** and repeat the Graph
check in both **Time** settings.
**Pass looks like:** the group shows **12 hour format** and **24 hour format**, with **24 hour
format** selected on a fresh install. Switching it changes all three absolute clocks at once — the
Graph's two `Time` fields, the Graph crosshair, and a node card's **Last DB heard** — while any
relative age (`5min ago`) on screen is unaffected, since it carries no clock at all. In 12 hour form,
a time around midnight reads `12:0x:xx AM` and around noon `12:0x:xx PM`, never `00`. In Spanish,
both forms still print the date day-before-month (`21/8/26`), and the labels fit.

**Check specifically: Last DB heard's own date.** This field's *date* is untouched by the Time
setting — only its clock changes — and it must keep the shape it always had: a full month name and a
**four-digit year**, date first and the clock after (e.g. `Aug 26, 2025, 16:45:12`), never a short
numeric date and never a two-digit year (`8/26/25`). This is the one field in the app where the year
matters — a database entry can genuinely be weeks old — and it is what this round of fixes repaired
after an earlier draft accidentally reshaped it. Check it in both Time settings and in Spanish
(`26 ago 2025, 16:45:12`).

- [x] Ran on: 2026-09-03, SM-S721B / Android 16 (API 36), build a5d8c24 — by the owner
  Notes: PASS. The Time setting changes the Graph's two Time fields, the crosshair and Last DB heard; relative ages are untouched. Midnight and noon read 12:0x AM / 12:0x PM in 12-hour form, not 00. Last DB heard reads its full date again (Aug 26, 2025, 16:45:12) after the round that repaired the date shape. Default is 24-hour.
---

### J2. One map link on a node panel, per the Map provider setting
**Do:** Open a node panel for a subject with a stamped position. Note the map button next to
**Meshview**. Go to Settings, switch **Map provider** to the other value, return to the same panel.
**Pass looks like:** the panel shows **one** map button, not two, named for the chosen provider
(**Google Maps** or **OpenStreetMap**), and tapping it opens that provider. Switching **Map
provider** changes both the label and the destination. The **Meshview** button is unaffected either
way — it is a different destination, not a map provider, and keeps its own condition.

- [x] Ran on: 2026-09-03, SM-S721B / Android 16 (API 36), build a5d8c24 — by the owner
  Notes: PASS. One map link on a node panel, named for the chosen provider and opening it; Meshview still beside it.
---

### J3. The Meshview link carries an unsigned node number
**Do:** On a node panel, open **Meshview** for a node whose number is above 2^31 — `!9e75f1a4` is one —
and then for one below it, such as `!5ead49bf`.
**Pass looks like:** both resolve. A node number is a uint32 carried in a Kotlin `Int`, so roughly half
of all real node numbers were rendering negative (`/node/-1636437596`); the second node is under the
boundary and always worked, so checking it confirms the fix disturbed nothing.

- [x] Ran on: 2026-09-03, SM-S721B / Android 16 (API 36), build a5d8c24 — by the owner
  Notes: PASS. Both node numbers resolve.

---

## Group K — The newest broadcast position, not a stale one (2026-09-03)

### K1. A node with no altitude reads Src: CUR, not DB
**Do:** Find a node broadcasting positions and open its panel.
**Pass looks like:** the card reads **Src: CUR** with a recent age. Before this change, any node
whose positions omit an altitude — a 2D fix, or a fixed node configured with only a latitude and a
longitude — read **DB** for ever, however many fresh positions arrived.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

---

### K2. The altitude row is absent, not stale
**Do:** On that same node, check the altitude row.
**Pass looks like:** if its positions carry no altitude, the row is simply absent — not a stale
figure. If the node does send an altitude, it is shown, and it is that report's own.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

---

### K3. Distance and bearing follow the live position
**Do:** Watch distance and bearing to that node as it or you move.
**Pass looks like:** both are computed from the live position and change accordingly, rather than
staying pinned to a database entry.

**Expect the bearing to be able to vanish, and count that a pass.** A live report carries a
precision, a database entry never did. If this node broadcasts at reduced precision - the Spain
public channels do - its obfuscation radius is now known and, where that radius exceeds the
distance, the direction letter is withheld and the distance reads `0.3 km ±2.9 km`. Before this
change the card drew a confident arrow from a stale position by pretending the precision was
unknown. A withheld direction here is the honest answer, not a regression.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

---

### K4. The Graph globe resolves for a node it previously could not
**Do:** With **Use phone location** off, open a Graph measurement on that node.
**Pass looks like:** the globe uses the node's own position, which now follows the same rule, so
it resolves for nodes it previously did not.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

---

## Group L — The node database and what's heard over the air, kept apart (2026-09-03)

### L1. The header counts both stores independently
**Do:** Leave the app running and connected for a while, watching the header line above the node
list.
**Pass looks like:** the header reads **DB(n) · Air(m)**, and `Air(m)` grows as nodes broadcast
NODEINFO_APP packets while `DB(n)` stays fixed at the radio's own node count. This is the original
complaint this whole change answers — an overnight run whose header never moved off `DB(80)` even
though the mesh kept talking — so a rising `Air(m)` beside a steady `DB(n)` is the proof it is
fixed.

- [x] Ran on: 2026-09-04, hardware   Result: PASS (owner)
  Notes: ________________________________________________________________________________

---

### L2. A recently-broadcast node's panel is headed NODE INFORMATION → Air Received
**Do:** Open the panel for a node that has broadcast recently.
**Pass looks like:** the card is divided into headed sections; **NODE INFORMATION** ends with
**Air Received** and a recent time, rather than an unlabelled stamp or none at all.

- [x] Ran on: 2026-09-04, hardware   Result: PASS (owner)
  Notes: ________________________________________________________________________________

---

### L3. A database-only node reads DB Received once, not twice
**Do:** Open the panel for a node the radio's database knows but which has not broadcast a
NODEINFO_APP packet this session.
**Pass looks like:** the identity block reads **DB Received**, and the **FROM THE NODE DATABASE**
section further down does **not** repeat that same stamp — the duplicate is suppressed because
both would be the same value off the same record.

- [x] Ran on: 2026-09-04, hardware   Result: PASS (owner)
  Notes: ________________________________________________________________________________

---

### L4. Reload node DB moves every DB Received stamp, and no Air Received stamp
**Do:** Press **Reload node DB**.
**Pass looks like:** every **DB Received** stamp on screen becomes the reload time. No **Air
Received** stamp changes — a reload replaces the radio's database, not what nodes have broadcast.

- [x] Ran on: 2026-09-04, hardware   Result: PASS (owner)
  Notes: ________________________________________________________________________________

---

### L5. Reset empties neither store
**Do:** Press **Reset**.
**Pass looks like:** node names survive, and the header's two counts — **DB(n)** and **Air(m)** —
do not drop. Reset clears the session's measurements, not the mesh's identity.

- [x] Ran on: 2026-09-04, hardware   Result: PASS (owner)
  Notes: ________________________________________________________________________________

---

### L6. Connecting to a different node drops both counts, cleanly
**Do:** Connect to a *different* node over Bluetooth.
**Pass looks like:** both **DB(n)** and **Air(m)** drop to reflect the new node's own view of the
mesh, and no name from the previous node survives anywhere on screen — a different radio describes
a different mesh, and carrying a stale name over would be worse than showing none.

- [x] Ran on: 2026-09-04, hardware   Result: PASS (owner)
  Notes: ________________________________________________________________________________

---

### L7. A relay named from a node the radio does not list
**Do:** Find a relay whose one-byte id matches a node known only from the air — never listed in the
radio's own database.
**Pass looks like:** its short name is still shown. Relay naming scans the union of both stores, so
a node heard only over the air is as valid a candidate as one the database lists.

- [x] Ran on: 2026-09-04, hardware   Result: PASS (owner)
  Notes: ________________________________________________________________________________

---

### L8. A neighbour known only from the air
**Do:** Open the neighbour tab for a node heard directly that the radio's database does not list.
**Pass looks like:** a full card with its name, position and telemetry — *not* "not in the database".
This is C1's acceptance item: before the fix, the tab gated on the database store alone and hid a
full card's worth of data for exactly the nodes most likely to have just broadcast.

- [x] Ran on: 2026-09-04, hardware   Result: PASS (owner)
  Notes: ________________________________________________________________________________

---

### L9. A node the radio knows by number only
**Do:** Open the panel for a node the radio's database lists (it appears in the header's `DB(n)`
count and in matching-node lists) but whose `NodeInfo` carried no `user` submessage at all — no
name, no role, nothing but the bare number.
**Pass looks like:** no **NODE INFORMATION** heading at all — not even a heading over a bare
"Public key: No" and a date. **FROM THE NODE DATABASE** still shows its **DB Received** stamp; the
record's real receipt time does not vanish along with the heading it used to sit under. This is the
P6 ruling, and `NodeCardNoPositionPreview` ("Candidate, known by number only (P6)") is its fixture
proof.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

---

### L10. A public key across the split
**Do:** Find (or arrange) a node the radio's database already credits with a public key, then wait
for it to broadcast a NODEINFO_APP that carries no `public_key` of its own.
**Pass looks like:** the panel must not read **Public key: No** after that broadcast — the database
still says the node has a key, and I1's exception means `hasPublicKey` consults both stores rather
than the newest broadcast alone.

- [x] Ran on: 2026-09-04, hardware   Result: PASS (owner)
  Notes: ________________________________________________________________________________

---

### L11. An air-heard name survives a reload and a reset
**Do:** Note a relay's name, one that is unique only because of a node heard exclusively over the
air. Press **Reload node DB**, then **Reset**.
**Pass looks like:** the relay's *name* is unchanged after each action — not just L4/L5's counts.
Check the name specifically: I4 is a recorded way a name can silently disappear (a node in both
stores whose newest broadcast happened to omit `short_name`), so a count staying steady is not
proof the name held too.

- [x] Ran on: 2026-09-04, hardware   Result: PASS (owner)
  Notes: ________________________________________________________________________________

---

### L12. Scroll the relay list on the live mesh for thirty seconds
**Do:** With the relay list showing a large mesh, scroll it continuously for about thirty seconds,
watching for jank. Separately, compare battery use over an hour against the pre-branch build.
**Pass looks like:** no visible jank while scrolling, and no material battery regression. I3 replaced
a per-call union rebuild (`nodes.keys + airNodes.keys`, a fresh `LinkedHashSet` every call) with a
value computed once per snapshot — on a 1000-node mesh with an uncapped air store this was on the
order of 10^5 avoidable set insertions per snapshot before the fix.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

---

### L13. `DB(n)` during the handshake
**Do:** Connect to a real node and watch the header's `DB(n)` count through the handshake.
**Pass looks like:** `DB(n)` reaches the radio's true node count once the handshake finishes, and
does not sit at 0 or flicker partway there. Real firmware replays everything for every
`want_config_id`, so an early stage of the handshake carries node infos that a later stage discards —
the count no longer rises incrementally the way it might have before the split. Several seconds of
`DB(0)` followed by a jump straight to the true count is expected, not a bug.

- [x] Ran on: 2026-09-04, hardware   Result: PASS (owner)
  Notes: ________________________________________________________________________________

---

## Group N — Relay-candidate comparison and Skip (2026-09-04)

Nothing in this group has ever been run. CI proves `RelayCandidates.rank` and the off-scale
clamping compile and pass their unit tests; the selector, the line and the Skip flow have no
Compose test harness in this project and go here instead, per the design's own §11.

### N1. The selector lists ranked candidates with a colour, average RSSI and sample count — HARDWARE ONLY
**Do:** Open the Graph for a relay byte with several matching candidates. Tap the selector.
**Pass looks like:** every candidate is listed, grouped in ranked order (CONSISTENT, UNCERTAIN,
UNKNOWN, INCONSISTENT, each group ascending by gap), each row showing a coloured dot for its
verdict, its own average direct RSSI and its sample count.

**Hardware only (final whole-branch review, ruling C-1):** in `zona-centro`, the only demo scenario
that emits traffic, `directNodeInfoSenders` never includes any relay byte's candidate, so
`directRssiAvg` stays null for every candidate all session - this item cannot be exercised off
hardware. The fixture is deliberately not changed to fix this: all twelve of its traffic slots are
already taken by other scenario coverage that giving a candidate a direct slot would displace,
re-opening acceptance groups that have already passed on this branch.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

---

### N2. The red line follows Auto scale with the points, not off them — HARDWARE ONLY
**Do:** Select a candidate whose average direct RSSI falls inside the plotted range. Toggle **Auto
scale** on, then off.
**Pass looks like:** a red vertical line appears behind the blue points at that candidate's own
average. Toggling Auto scale moves the line together with the point cloud it is being compared
against - it never drifts to a position the points themselves have moved away from.

**Hardware only, for the same reason as N1 (ruling C-1):** no candidate ever carries a direct RSSI
average in the `zona-centro` demo, so `CandidateLineOverlay` always early-returns and no line is
ever drawn to move.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

---

### N3. Off-scale is clamped with a marker and its value, never silently absent — HARDWARE ONLY
**Do:** With **Auto scale ON**, select a candidate whose average direct RSSI falls outside the
current plotted range (the `CandidateLineLowOffScalePreview` / `CandidateLineHighOffScalePreview`
previews show the shape if none is available live). With Auto scale **off** the fixed range is
−130…−30 dBm, 100 dB wide, and essentially no real candidate's average falls outside it, so this
item is unreachable in that mode.
**Pass looks like:** the line still appears, clamped to the near edge, with a small triangle marker
and the candidate's actual value printed beside it - not simply missing. **This is the item that
proves Task 2 was worth its own task:** without the clamp-and-label, a far off-scale candidate would
sit unmarked on the edge and look like an ordinary in-range value, showing nothing exactly when the
evidence is strongest.

**Hardware only, for the same reason as N1 (ruling C-1):** no candidate ever carries a direct RSSI
average in the `zona-centro` demo, so there is no average to fall off-scale at all - the two
`@Preview`s remain the only check possible off hardware.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

---

### N4. A candidate never heard directly draws no line and says so — HARDWARE ONLY
**Do:** Select a candidate the selector already shows as not heard directly this session (its row
names that and shows database SNR / hops away instead of a gap).
**Pass looks like:** no line is drawn at all. The selector row is the explanation on its own -
"not heard directly", plus whatever database SNR and hop count it has - rather than a line
fabricated from nothing.

**Hardware only, in spirit the same reason as N1 (ruling C-1):** every candidate happens to satisfy
this item's precondition in the `zona-centro` demo (none is ever heard directly), but the run still
needs a real node database entry with `Last DB SNR`/`hops away` behind that candidate for the
"says so" half of this item to mean anything - the demo scenario is not built out for that either,
so this is grouped with N1-N3 rather than treated as accidentally free.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

---

### N5. Skip is disabled on None, and reversible from Settings once confirmed
**Do:** With the selector reading **None**, note the Skip button. Select a candidate and press
Skip; read the confirmation dialog, then confirm it. Open Settings' skipped-nodes list afterward.
**Pass looks like:** Skip is disabled while the selection is **None**, enabled the moment a
candidate is chosen. The dialog names the candidate and says the action is reversible. Confirming
removes that candidate from the selector's list immediately and the selection returns to **None**;
Settings then lists the node among skipped nodes with a way to remove it, which restores it as a
candidate again.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

---

### N6. A neighbour's Graph has no selector and no Skip
**Do:** Open the Graph for a neighbour, not a relay.
**Pass looks like:** neither the candidate selector nor the Skip button appears anywhere on screen -
a neighbour has no relay byte, so there is nothing for either control to act on.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

---

### N7. Freeze and Auto scale share one row and still work, including together disabled
**Do:** On a relay showing live data, confirm Freeze and Auto scale sit side by side on one row and
toggle each independently. Then find (or arrange) a relay with no statistics and no measurements
retained at all.
**Pass looks like:** both switches sit on a single right-aligned row, each still doing its own job
(Freeze holds the whole drawing; Auto scale moves both the bars and the plot together). In the
fully empty state, both switches go disabled together - there is nothing to freeze and nothing to
scale.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

---

### N8. The line is legible at arm's length inside the point cloud
**Do:** On a busy relay with several candidates, select one whose verdict is **CONSISTENT**.
**Pass looks like:** the red line is findable at arm's length among the blue RSSI points, not a
hairline lost inside them. This is the final whole-branch review's C-2 item: the line is now drawn
`POINT_SIZE_PX` wide (4 physical pixels, matching the point squares), not the 1 px hairline it used
to be.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

---

### N9. Spanish, portrait and landscape: no clipped label, no two-line selector
**Do:** Switch the app to Spanish. Open the Graph for a relay with candidates, in both portrait and
landscape.
**Pass looks like:** `Escala automática` reads in full beside its switch in both orientations,
neither clipped nor wrapped (final whole-branch review, finding I-5); the Skip label does not push
the candidate selector into a two-line field.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

---

### N10. Four or more candidates: the menu scrolls, and the order holds still
**Do:** Open the selector for a relay byte with four or more candidates. Scroll the open menu.
Watch it for a while as live packets keep arriving.
**Pass looks like:** the menu scrolls, every row stays readable, and the ranked order does not churn
between recompositions while packets arrive - only an actual change in a candidate's own gap moves
it.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

---

### N11. A never-heard candidate, menu closed: the screen still explains the absent line
**Do:** Select a candidate never heard directly this session (its dropdown row shows database SNR /
hops away instead of a gap). Close the menu.
**Pass looks like:** with the menu closed, a one-line caption under the selector row still says the
candidate was not heard directly this session, and still shows whatever database SNR / hop count it
has (final whole-branch review, finding I-4) - the explanation for the missing line does not
disappear along with the dropdown.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

---

### N12. Cross-screen agreement: ranking, and the Skip dialog's name for a node
**Do:** Compare the Matching nodes tab's `[1] [2] [3]` order against the Graph selector's order for
the same relay byte. Then start a Skip from the Matching nodes tab, and separately from the Graph,
for the same candidate.
**Pass looks like:** the two orders agree. Both Skip confirmation dialogs name the candidate the
same way (final whole-branch review, finding I-7) - short name and node id together, e.g.
`"TOL1 (!a1b2c3d4)"`, not one screen's short name against the other's bare id.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

---

### N13. Two plausibly-live relays sharing one byte
**Do:** Find or arrange a relay byte where two candidates both look like they could be actively
relaying right now (both recently heard directly, both otherwise plausible).
**Pass looks like:** record whether every candidate reads UNCERTAIN or INCONSISTENT rather than one
reading CONSISTENT - the blended-average confounder decision 55's cost and spec §3 now name (final
whole-branch review, finding I-8). This is the item that tells the owner when to distrust the
ranking rather than act on it.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

---

### N14. Skip a selected candidate from the Matching nodes tab while its Graph is open
**Do:** Open the Graph for a relay byte and select one of its candidates. Without closing the
Graph, go to the Matching nodes tab and Skip that same candidate from there.
**Pass looks like:** the Graph's selector drops the skipped candidate from its list and falls back
to **None** cleanly - no stale selection, no crash, no line left drawn for a candidate that is no
longer offered.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

---

## Overall verdict

Fill in only after every item above has actually been run (or explicitly recorded as not run,
with a reason).

- **Total items run:** _____ / 92
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
