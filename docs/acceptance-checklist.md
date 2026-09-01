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

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### C4. Known nodes on Neighbours falls back, and says so — *added with the sort-modes work*
**Do:** Choose **Known nodes** on Relays (or set it as the default sort in Settings), then switch
to the Neighbours tab.
**Pass looks like:** the sort menu on Neighbours does not offer Known nodes at all; the status
strip reads **Packet count** / **Número de paquetes**, and the list order matches that, not the
mode that was chosen.
**Silent-failure watch:** this is the one case where the strip and the list can disagree. A strip
saying "Known nodes" over a packet-count order is the defect, and it looks like a working screen.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### C5. The My node tab, all three states — *added with the My node work*
**Do:** Open **My node** / **Mi nodo** on a live connection. Then reach the other two states: kill
and restart the app to catch the moment after the handshake but before the node's own NodeInfo
arrives, and read the pre-handshake state from the demo transport.
**Pass looks like:** connected and populated, the card shows this node's long name, short name,
position, altitude, Src and the Meshview link — with **no distance figure**, since there is no
other node to measure from. Before its NodeInfo arrives: the "has not sent its own details yet"
message. Before the handshake: "Local node unknown" / "Nodo local desconocido".
**Also confirm:** neither list screen shows the local-node block any more.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

### C6. The three-count status strip at a large font scale, in Spanish — *added with the My node work*
**Do:** Set the system font scale to its largest, the language to Español, and open **Mi nodo**
while paused, so the row holds Total, Repetidos, Directos *and* the paused badge. Read the strip's
bounds off a `uiautomator dump`.
**Pass looks like:** every label and number is present and inside the screen's width; the row wraps
onto a second line rather than clipping or pushing anything off the edge.
**Silent-failure watch:** this is the F-3 shape. A screenshot at the default font scale in English
will not show it.

- [ ] Ran on: __________________ Result: __________________________________________________
  Notes: ________________________________________________________________________________

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

## Overall verdict

Fill in only after every item above has actually been run (or explicitly recorded as not run,
with a reason).

- **Total items run:** _____ / 32
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
