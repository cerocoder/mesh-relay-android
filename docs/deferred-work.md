# Deferred work

Follow-up items found during the Stage 1 port and deliberately **not** fixed, so that review
findings would not keep widening the branch. Each was seen by a reviewer, judged non-blocking,
and recorded at the moment it was found. None is a known crash or a known wrong result.

Provenance: extracted from the SDD ledger for
`docs/superpowers/plans/2026-08-26-mesh-relay-android.md` before that scratch workspace was
deleted. Wording is the reviewer's or mine at the time of the finding, kept verbatim rather than
re-summarised — the reasoning for deferring is part of the record, and a later reader deserves to
judge it rather than take it.

Items marked **[CLOSED]** were fixed later, by a subsequent task or by the final fix wave. They
are kept rather than removed so this list is not silently rewritten.

---

- **Task 2** — Type.kt:11-13 allocates a throwaway Typography() to read its default
  bodySmall; Typography().copy(bodySmall = ...) would do it in one. No functional difference.
- **Task 3** — GeoTest.kt:221 uses Kotlin `assert()`, a no-op without -ea. Gradle
  enables assertions by default so it runs, and the assertEquals on the next line is the
  load-bearing check regardless. Plan-mandated (my brief text), not an implementer choice.
- **Task 6** — AndroidSettingsStore does not runCatching around SharedPreferences
  getString/getStringSet, which throw ClassCastException on a type-mismatched hand-edited key.
  Beyond the brief's stated degrade-not-crash scope (unparseable ids, renamed enums), both handled.
- **Task 6** — persist() rewrites all setting keys on every mutation, incl. skip-list
  changes. Harmless, just more I/O per call than needed.
- **Task 6** — per-mutation ioScope.launch gives no ordering guarantee on a
  multi-threaded dispatcher. Speculative — no consumer wiring exists yet to judge against.
- **Task 7** — values-es action_skip_confirm_body uses the infinitive where its sibling
  action_reset_confirm_body uses the tu imperative; the rest of the file is imperative throughout.
- **Task 7** — "count" renders as "Numero de paquetes" in sort_packets but "Recuento"
  in detail_stat_count. Different contexts, slight canon gap.
- **Task 11** — the report's prose mischaracterises a createChannel comment that does
  not exist in either the original or the port. Report narrative only, shipped code unaffected.
- **Task 13** — an in-code comment cites mesh_stats.py:1043 where the block actually
  begins around 1039-1041. Approximate line reference in a comment, not a defect.
- **Task 13** — reviewer's #4, from == 0 handling — carried to Task 21, see note above.
- **Task 12** — hexId does not clamp relayByte to 0..255, so an unmasked value would
  print more than two hex digits. No current caller can produce one — carry to Task 21.
- **Task 17** — the report's narrative misstates a few figures (omits Yuncos as a direct
  sender; RELAY_WEAK min is -115 not -118; RELAY_STEADY SNR max is 8.3 not 9). Code unaffected —
  noted so nobody treats the report as authoritative.
- **Task 17** — every packet in the fixture has non-zero rx_rssi, so the no-signal
  (rx_rssi == 0) path is never exercised in the demo data. A later screen author debugging
  null-signal rendering will find no case here.
- **Task 17** — Illescas Router carries the ROUTER role AND the intermittent profile;
  worth a comment distinguishing role from link reliability.
- **Task 8** — **[CLOSED by 7aa39bd]** two user-facing literals remain in BleRadioTransport.kt:87,119 —
  deliberate, to keep that class Context-free and plain-JVM-testable; both are carry-overs, not new.
- **Task 8** — BleScannerImpl.kt:101 "unknown node" literal — the brief's own
  BleScanner interface takes no Context, so extracting it needs a constructor change.
- **Task 8** — BleFailureText.kt:48 catch-all surfaces e::class.simpleName, which names
  neither a cause nor a next step. Unchanged from source; worth a follow-up generic message.
- **Task 8** — the task-8 report overstates translation #6 (see above).
- **Task 10** — the reload flag survives a silence-detector teardown, so
  nodeDbReloading can read true over a disconnected session. Exactly as the brief prescribed.
  CARRY TO TASK 22: derive the spinner from `nodeDbReloading && state == Connected`.
- **Task 10: minor (deferred) — REAL FORWARD GAP: FakeRadioTransport never answers
  NODE_INFO_RELOAD_NONCE, so a reload in demo mode falls to `else -> ignored` and spins the full
  30s until the watchdog clears it. Out of scope for Task 10's three files. CARRY TO TASK 31 (or a
- **Task 10** — T3's discrimination depends on the strict > at RadioConnectionManager
  :400 — relaxing it to >= would keep T3 passing while silently losing its mutant.
- **Task 10** — two "nothing arrived" assertions are vacuous if drain() breaks; the
  suite catches it elsewhere. Also MeshProtocol.kt's KDoc writes [D] as a doc link to a
  nonexistent symbol.
- **Task 15** — my brief's Interfaces line names AgeBucket as consumed, but nothing in
  the five files references it — brief boilerplate, not an unmet requirement.
- **Task 15** — AgeText's citation points a few lines early of the actual since_str
  block; inherited from my brief's citation.
- **Task 16** — SignalGauge KDoc says the draw scope "does no other arithmetic" but does
  one subtraction (fillEndPx - fillStartPx) for the Canvas Size API. Pixel bookkeeping, not signal
  math; the comment slightly overstates.
- **Task 16** — tests 4 and 5 share the fixture stats(-5f); both justified but a reader
  must check docstrings to see they are not duplicates.
- **Task 18: minor (deferred) — PLAN DEFECT 10 (mine): my brief names a test "clearing runtime data
  keeps the node database and the skip list", but the skip list is a snapshot() parameter, not
  NodeDirectory state, so clearRuntimeData structurally cannot clear it. That half of the test
- **Task 18** — spec section 7 still lists a fourth telemetry branch (LocalStats) that
  the brief deliberately drops as dead code. Stale; strike at final review so it does not read as
  an omission.
- **Task 18** — redundant `if` guard at NodeDirectorySnapshot.kt:96; precisionBits
  stripped twice (NodeRecord.fromProto already does it) so the local guard is defence in depth and
  the comment does not say which line is which; RelayIndexTest's size assertion is subsumed by the
- **Task 19** — several mesh_stats.py:<line> citations in the new comments are wrong
  against the current file (actual: 1758, 1770/1768, 1767, 1798-1799). One of the wrong ones came
  from MY brief. Undermines the file:line traceability every reviewer in this project relies on.
- **Task 19** — FOLLOW-UP TICKET: MapLinks renders coordinates via raw Double.toString,
  so a magnitude below 1e-3 emits scientific notation ("1.0E-7") and a node within ~1 cm of the
  equator or prime meridian gets a malformed maps URL. Upstream has the identical behaviour.
- **Task 20** — the report miscounts the long name as 99 chars (it is 95); the two
  neighbour entries lack a comment explaining why those nodes were chosen.
- **Task 21** — report says "nine discarding collectors" where there are now ten; a
  comment says Internal.countNonNull where Kotlin Wire emits a top-level countNonNull; the optional
  one-line assertThrows hardening against schema renumbering.
- **Task 23** — **[CLOSED by the shared StatusStrip]** a KDoc claims ui/relays/StatusStrip is
  "private to its own package" — it is not, it is public; the real reason duplication was right is
  that StatusStrip hardcodes the Total/Relayed pair. The duplication is correct, the stated
  justification is false. *Closed because the duplication itself is gone: StatusStrip moved to
  ui/common and takes its counters as a parameter, so the false justification and the thing it
  justified are both deleted.*
- **Task 23** — rename relays_status_paused / relays_local_node_unknown to neutral keys.
  (`relays_local_node` itself is gone: the My node screen's title replaced it.)
- **Task 23** — add never-heard and no-signal neighbour fixtures to SampleData.
- **Task 24** — **[half CLOSED by the shared StatusStrip]** two doc comments misattribute the
  LabelledCount precedent to RelayListScreen (it is in StatusStrip.kt:85) — *closed: one of the two
  went with the merged strip and the survivor now sits in the same file as LabelledCount*;
  SignalBlock's fourth parameter (lastPacketAtMillis, required by SignalGauge's flash animation)
  is unremarked in the diff — **still open**.
- **Task 25** — it correctly REFUSED to fix an out-of-scope KDoc in MatchingNodesTab.kt
  (that file was not in the fix round's allowed list), leaving a slightly misattributed comment
  about why PreviewClock is needed. Right discipline, small residue — carrying to the final review
- **Task 26** — displayLocale() is now a FOURTH copy of the same LocalConfiguration
  locale resolution (also in PositionLine.kt, RelayCard.kt, NeighbourCard.kt). Consistent with the
  codebase rather than a new invention, but it is now a real extraction candidate — carrying to the
- **Task 26** — remote_direction_hint renders above an empty list, explaining a
  direction pattern before there is data to show one.
- **Task 27** — the empty state could earn a dedicated string; and the PREVIEW NAME
  "Carried by no relay - heard directly" bakes the very overclaim the UI text avoids into
  developer-facing naming. Good catch — rename at the final review.
- **Task 28** — skippedRelayNodes.sorted() runs in the composable body rather than
  behind remember(); presentation-only ordering, matching RemoteNodesTab's precedent.

---

## Not deferred — the two open items

These are not follow-ups. They are the state of the branch.

- **Release signing.** `app/build.gradle.kts` signs the release variant with the debug key, and
  the workflow publishes that APK to GitHub Releases on a tag. Fine for a build you install
  yourself; not fine for one anybody else installs, and unfixable after the fact, since Android
  refuses a signature change on update. Awaiting the owner's decision.
- **Hardware acceptance.** `docs/acceptance-checklist.md` — 28 items, none run. CI proves the code
  compiles and the tests pass; nothing in this branch has ever talked to a radio. The BLE
  handshake, the demo-to-live transition, and every gauge against real SNR are unverified.

- **Sort modes / My node (2026-09-01)** — `MyNodeScreen`'s `Local node unknown` branch is correct
  but not reachable by hand. `localNodeNum` is set from the `my_info` handshake frame and never
  cleared, and `Screen.Main` is only pushed once the connection reports Connected, so by the time
  the tab exists the number is always known. Left in place with its preview rather than deleted:
  it is the honest reading of a nullable field, and a transport that completes a handshake without
  `my_info` would land on it. If it is ever to be *tested*, a demo transport that omits `my_info`
  is what would do it.

## The Graph command (2026-09-02)

- **A user who grants Bluetooth but denies location has no in-app route back to the permission
  dialog, only system settings.** Location is deliberately not part of `BleReadiness` - a
  refusal is not an error, and every measurement falls back to the node's position - and the
  permission launcher only fires on `PERMISSIONS_MISSING` or the explicit retry action, neither
  of which a location-only refusal triggers. Recorded as a consequence of ruling 31
  (`docs/decisions.md`), not a defect.
- **[CLOSED at the owner's decision]** **Background position stamping.** From Android 10 an app
  receives location updates while backgrounded only if its foreground service declares
  `android:foregroundServiceType="location"`; this app's declares `connectedDevice`. So with the
  screen on every measurement is stamped, and with the screen off under background collection,
  samples on API 29+ fall back to the local node's position. Adding the type would also add
  `FOREGROUND_SERVICE_LOCATION` to the store-visible permission set - a second permission change
  the Graph design did not ask for. **Awaiting the owner's decision**; acceptance item H10 is what
  finds out what the phone actually does. *Closed: the owner chose to spend the permission. The
  service now declares `connectedDevice|location` and the manifest carries
  `FOREGROUND_SERVICE_LOCATION`, so measurements stay phone-stamped with the screen off (ruling 39,
  `docs/decisions.md`). The type is computed at each start and the `location` half added only when
  the location permission is granted - a `location`-typed foreground service without it is a
  `SecurityException` on Android 14+, and location is optional in this app. H10 now verifies the
  new behaviour rather than deciding the question, and new item H17 covers the crash the runtime
  check prevents.*
- **The series watch stays armed while the app is backgrounded.** `DisposableEffect` fires when
  the destination leaves the composition, and pressing Home does not - the window is not detached,
  so the composition outlives `onStop`. The engine keeps re-snapshotting the watched subject for a
  chart nobody is looking at. Bounded rather than a leak: snapshot building continues while
  backgrounded anyway, because `collectAsState` keeps the snapshot subscribed, so this is extra
  array copying and not a new wake-up path. `repeatOnLifecycle(STARTED)` around the watch would
  close it. **Awaiting the owner's decision** - changing when a watch arms is a design choice.
- **A NaN `rx_snr` propagates into the scales.** `SignalStats.plus` uses `minOf`/`maxOf`, which
  propagate NaN, so one malformed packet makes a node's statistics NaN and `scaleRange` returns
  `ScaleRange(NaN, NaN)` under Auto scale. **Pre-existing and wider than the Graph** - the same
  statistics already feed every gauge in the application. Not introduced by this branch; recorded
  because the Graph is where it was noticed.
- **The zoom control** (2x, 4x pixels per measurement, and fractions below 1 for scaling down).
  `ChartGeometry` takes `pxPerSample` in every signature and is tested at 0.1, 1 and 4;
  `SignalGraphScreen` fixes it at 1. Deferred at the owner's request; adding a control is a value
  to pass, not a restructuring.
- **Persisting series across launches.** Statistics remain a single session, per decision 8 of the
  stage-1 spec. Exporting a chart or its data is out of scope for the same reason.
- **A chart for a remote node.** `Screen.RemoteNode` has no Graph command: the measurements there
  belong to the relay that carried them, which has its own chart.
- **SNR prints to whole units in the bars and to a tenth in the crosshair**, on the same screen -
  the same reading can appear as `4` above the plot and `4,5` below the rule. `Pic1.pdf` shows
  `4.5 dB` in the crosshair, so the crosshair is right and the bars' precision is the older
  choice. **Awaiting the owner.**
- **The crosshair does not appear until the plot is first touched.** Spec section 8.7's wording
  supports this; `Pic1.pdf`'s sketch shows a crosshair on an unopened screen. Two lines to seed
  row 0 if the owner wants it. **Awaiting the owner.**
- **[CLOSED at the owner's decision]** **Freeze and Auto scale are both disabled when the bars
  have data but the series is empty.** `SignalGraphScreen.kt` has `enabled = shown.size > 0` on
  both switches, not just one. Auto scale could still usefully move the bars' borders in that
  state; of the two, disabling Freeze over what is still a live bar readout is arguably the more
  defensible choice. *Closed: the owner ruled both switches usable in that state. Both now read
  `frame.rssiStats.hasData || frame.snrStats.hasData || shown.size > 0` — anything to act on at
  all. Freeze holds the drawing and a live bar readout is a drawing; Auto scale moves the bars'
  borders. Spec section 8.8 is untouched: with neither metric holding a figure and no measurement
  retained, the pair is still present and still disabled over the centred message.*
- **`SignalSeries`'s constructor is public** while both its KDoc and `SignalSeriesBuffer`'s say the
  value is built only by `snapshot()`. `internal` would make the documented rule enforced rather
  than advisory.
- **`SignalSeriesBuffer.clear()` is unreferenced.** `resetStatistics()` clears the map and discards
  the buffers whole. The method is spec-mandated (section 5.2) and tested, so it is not dead by
  accident - but a reader will ask.
- **`LocationAvailability.granted()`'s any-vs-all semantics have no test.** It is the
  behaviourally interesting half of the class (a user granting only "Approximate" must count as
  granted) and needs a `Context`, so covering it means Robolectric, which this project does not
  use.
- **[CLOSED by this fix wave]** `NodeDirectoryTest`'s first agreement assertion (`the directory
  and its snapshot agree on where we are`) passed vacuously if both sides returned `null` - if
  `applyNodeInfo` ever stopped recording `dbPosition`, the database-fallback branch would go
  silently untested. Closed: an `assertNotNull` plus a value assertion on the database-derived
  latitude now pin that branch directly.
- **Two `MAX_SAMPLES` constants live a package apart.** `SignalHistory`'s is 500;
  `SignalSeriesBuffer`'s is 5000. Both are deliberate (ruling 29 sizes the latter), but a reader
  scanning for "the" sample cap will find two answers.
- **`publishedEpoch` is not reset in the `WatchSeries` command handler.** Opening a watch resets
  `publishedKey` to `null` and `publishedTotal` to `-1`, which is what actually forces the next
  republish through the `(key, resetEpoch, totalAppended)` guard (ruling 33) - `publishedEpoch`
  itself is left stale. Correct today only because the `key` term of the guard already fails on
  its own; a change to how `publishedKey` is reset on watch would make the stale epoch load-
  bearing and wrong.
- **`positionForSample()` allocates on the node-fallback path**, via `PositionHistory.newestWithCoordinates`.
  Every other path through it is allocation-free. Not measured as a problem at the sampling rates
  this app sees; noted for whoever next profiles the engine.
- **The chart-geometry task's implementation report garbles its own inversion argument** (around
  the report's line 53) - the conclusion and the supporting table are correct, only the prose
  connecting them is tangled. Report narrative only; the shipped `ChartGeometry` code is
  unaffected.
- **`ChartGeometry.overlayTopPx` is scope rather than compulsion.** Five of the six additions
  `ChartGeometry` gained for the crosshair overlay were forced by the project's no-arithmetic-
  in-a-composable rule; this sixth one was a judgement call to keep the clamping logic in one
  place rather than a strict requirement of that rule.
- **`GraphFrame` is reallocated every recomposition, including while frozen.** Freeze holds what
  the frame contains (ruling 36) but not the frame object itself, so a frozen screen still
  discards and rebuilds an unchanged `GraphFrame` on every recomposition. Allocation churn only -
  nothing on screen is wrong.

### Test-quality notes, none blocking

Small, individually trivial gaps found while reviewing the Graph's test suite. None of them lets
a wrong answer through in a way a later reviewer wouldn't also catch; each is a test that could
pin its claim a little more tightly than it does.

- `StampedPosition`'s KDoc claims `roundToInt` saturates on an out-of-range value; no test feeds
  an out-of-range `Double` through `fromDegrees`, so the guarantee is documentation-only.
- `SignalSeriesTest`'s "the snapshot does not change when the buffer does" asserts only sizes,
  never reads back a value from the taken snapshot to show it is unaffected.
- `SignalSeriesBufferTest`'s `clear` test comment claims a head-reset guarantee the test cannot
  actually check (a ring buffer is correct starting from any head position).
- `SignalSeries.EMPTY`'s "answers every question" test checks two of its six accessors.
- `require(capacity > 0)` is the one untested branch in `SignalSeriesBuffer`.
- `NodeDirectoryTest`'s "node mode never falls back to the phone" pins the no-fallback half of
  the rule but not that NODE mode does use the node's own position.
- `LocationAvailabilityTest`'s second test asserts `size == 2`, which its first test already
  implies - redundant rather than a second guard.
- `the scroll cannot go past the end of a saturated series` tests `maxScrollPx`, which itself
  clamps nothing (the clamping happens where its result is used) - the test name overstates what
  it pins.
- Three of the Graph's Compose previews cannot show what they are named for.

## The six field fixes (2026-09-02)

- **Task 5 — node identity is the BLE address, not `my_node_num`.** At the owner's explicit
  choice, `AppContainer.requestConnect` decides whether a connection is "a different node" by
  comparing BLE addresses, not by anything from the handshake. That leaves two gaps, both
  accepted rather than fixed:
  - The *same* physical node reached at a new BLE address (a re-paired device, a randomized
    address) reads as a different node and wipes statistics that did not need wiping. Harmless -
    the mesh is unchanged, only the display resets.
  - *Two different* physical nodes that happen to present the same BLE address would not trigger
    a wipe, leaving one node's statistics on screen while actually connected to another. Stale
    data, not a crash, and not observed - but not detectable by this check either.

  A `my_node_num` cross-check on the handshake (comparing `NodeDirectory.localNodeNum` before and
  after) would close the second gap and is small if the field ever shows it: deliberately not
  added now, since the owner chose BLE-address identity knowing this trade.

## Field fixes of 2026-09-02 — found by review, not yet built

Both came out of reviews during `docs/superpowers/plans/2026-09-02-six-field-fixes.md`. Neither is a
defect in what that plan shipped; both are work it deliberately did not widen into.

- **[AGREED — implement next]** **Switching nodes must ask for confirmation.** The wipe added by
  task 5 commits on *intent*, not on a successful handshake: `AppContainer.requestConnect` compares
  the new BLE address against `statisticsAddress` and calls `engine.resetForNewNode()` before the
  radio is contacted at all. So backing out to the device list and mistapping the adjacent row
  destroys the session's relays, neighbours, series and node database before the other node has even
  answered — with no confirmation and no undo, and statistics are not persisted. Mitigating, and the
  reason this was not treated as a defect: that same mistap tears down the current link regardless,
  so the connection was lost either way; only the statistics are the extra loss.

  Notes for whoever builds it, so the shape is not rediscovered:
  1. The confirmation has to sit **in front of** `requestConnect`, in `MainActivity`'s
     `onSelectDevice` or in `DeviceListScreen` — `requestConnect` both wipes and connects, so a
     dialog inside it would be asking after the fact.
  2. It must appear **only when a wipe would actually happen**. First connect (`statisticsAddress`
     null) and a repeat tap on the node already connected must stay silent — the repeat tap is the
     designed way back into the statistics from the device list, and a dialog there would be noise
     on the commonest action. That means exposing something like
     `AppContainer.wouldDiscardStatistics(address: String): Boolean` rather than duplicating the
     comparison in the interface.
  3. `DeviceListScreen` has no confirmation dialog today. Match the shape `StatsTopBar` already uses
     for Reset and Exit rather than inventing a third.
  4. The text should name what is lost — statistics, node database, signal series — and that it is
     not saved. Both locales, as ever.

- **[PENDING — the owner's decision]** **The mirror of the own-node fix, in the Relays list.**
  Task 4 removed our own node from Neighbours because it appears there with no SNR and no RSSI and
  inflates the divisor every other neighbour's percentage uses. The same shape exists one list over
  and is untouched: a packet from *another* node whose `relay_node` byte equals our own node's last
  byte means **we** were the relay. Those echoes carry `rx_rssi == 0`, so `PacketClassifier.signalOf`
  returns null and no SNR or RSSI is folded — but `foldRelayed` still increments that row's
  `packetCount` and `totalRelayedPackets`, and `sortedRelays`' `share()` divides by that total. So a
  signal-less row sits in a list about signal quality and skews every real relay's percentage.

  **Why this is not simply "do what task 4 did".** Task 4's exclusion is exact: it compares a whole
  node number, `ingest.fromNode == directory.localNodeNum`. Here the only thing available is a single
  byte, and `Geo.lastByteOfNodeNum` maps `0x00` to `0xff`, so several real nodes can answer to our
  byte. Excluding on the byte alone would suppress a genuine third-party relay that happens to share
  it — trading a skewed percentage for a missing relay, which is the worse failure for a tool used to
  decide where a repeater goes. Worth weighing before building:
  1. exclude the byte entirely (simple, and can hide a real relay);
  2. keep the row but drop it from `totalRelayedPackets` so other percentages are right (narrower,
     but leaves a signal-less row on screen);
  3. exclude only when the byte is unambiguous — `NodeDirectorySnapshot.matchingNodeNums(byte)`
     returns exactly one node and it is ours — which is the same honesty rule the relay *naming* already
     follows, and degrades to "leave it alone" when the byte is shared.

  First step is cheap and is the owner's: look at the Relays list on the phone for a byte equal to the
  last byte of the local node number, and say whether it is actually there and how large its share is.

### Smaller items from the same six fixes, harvested before the SDD workspace was deleted

None blocking; each was seen by a reviewer and judged non-blocking at the time.

- **`SignalGaugeComplexFlashingPreview` can no longer show a flash.** It passes a static
  `lastPacketAtMillis`, and the fixed marker only fires on a *change*, so the preview is now identical
  to the non-flashing ones above it and its name misleads. A change-driven flash cannot be shown in a
  static preview at all, so the honest remedy is to delete it with a note rather than rename it.
- **No test pins the exact clock-skew boundary.** `AgeText.relative` switches from `Seconds(0)` to
  `Never` at `-MAX_CLOCK_SKEW`; an off-by-one in that comparison would not be caught.
- **A Direct packet from our own node arriving *before* the handshake still opens a Neighbours row.**
  The guard reads `localNodeNum`, which is null until `my_info`. `RadioConnectionManagerTest` pins
  `my_info` first for the managed connect path, so it should not occur — but if our own node ever
  reappears in Neighbours after a reconnect, this is the cause, not the guard.
- **A dropped `trySend` on the node-switch wipe is not self-healing.** `statisticsAddress` is assigned
  unconditionally, so if `ResetForNewNode` were ever dropped on a full channel, a re-tap would take the
  no-wipe branch and node A's statistics would be shown as B's until a manual Reset. Remote (256-slot
  channel), but unlike `reset()` a re-tap does not repair it.
- **Frames still in flight from the old link can land after the wipe** and be folded as the new node's.
  Bounded by the handoff window; usually zero at mesh traffic rates.
- **`resetStatistics` + `clearAll` clear positions and telemetry twice**, and the new "an ordinary reset
  still keeps the node database" test asserts a strict subset of a pre-existing test. Both harmless;
  the first is the price of reusing `resetStatistics` so the two reset paths cannot drift.
- **The "polite goodbye" packet is almost certainly never sent** — it is launched on a job that
  `closeTransportLocked` cancels on the next line. Pre-existing; the ACL disconnect under
  `NonCancellable` is what actually matters, but the comment claims more than happens.
- **Exit is absent from the Devices and Settings screens.** `StatsTopBar` carries it, so if the link
  drops and the app returns to the device list there is no Exit until the user reconnects.
- **`shutdown()` does not unwind the application scope or the location source** — three collectors and
  the notification updater keep running; `exitProcess` is what ends them. Acceptable only because the
  owner chose the kill.
