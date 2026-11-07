# Screenshots

All images show app version **0.6.1**, taken with `adb screencap` from a debug build on a
1080×2340 phone (dark theme, English interface), **in demo mode**: the built-in scenario that
models a small regional mesh, with made-up nodes and traffic. The nodes are placed in real towns,
so town names and their coordinates are visible and intentionally left as they are. No real node
or person appears, with one exception: `05-my-node.png` was taken with a real node connected, and the
node's ID, long and short names, coordinates and altitude are blurred.

The demo replays packets quickly (one every 0.7 s), so packet rates such as `1800 pkt/h` are
much higher than on a real mesh. Do not take them as typical.

Each file is cropped to remove the Android status bar and navigation bar, and is palette-reduced
to keep the repository small.

| File | Screen and state |
| :--- | :--- |
| `01-devices-demo.png` | Devices screen, disconnected, demo list. The first scenario's name is blurred. |
| `02-relays-complex.png` | Relays, Complex gauges, three relays, one ambiguous (`0x2a[2]`). |
| `02-relays-simple.png` | Relays, Simple gauges. |
| `02-relays-sort-menu.png` | The sort list. |
| `02-relays-more-menu.png` | The ⋮ menu on Relays. |
| `02-reset-confirm.png` | Reset confirmation. |
| `02-relays-paused.png` | Relays while paused. |
| `03-relay-detail.png` | Relay detail, Matching nodes tab. |
| `03-detail-menu.png` | The ⋮ menu on a detail screen. |
| `03-relay-node-card.png` | A candidate's node card, scrolled down. |
| `03-skip-confirm.png` | Skip confirmation. |
| `03-relay-remote-nodes.png` | Relay detail, Remote nodes tab. |
| `03-remote-node.png` | A remote node's page. |
| `04-neighbours.png` | Neighbours tab, Simple gauges. |
| `04-neighbour-detail.png` | A neighbour's detail screen. |
| `05-my-node.png` | My node with a real node connected. ID, long name, short name, coordinates and altitude are blurred. |
| `05-my-node-empty.png` | My node before the node has announced itself (the demo never does). |
| `05-my-node-menu.png` | The My node menu with Save nodes and Load nodes. |
| `06-graph.png` | Graph of a relay. |
| `06-graph-crosshair.png` | Crosshair on a row. |
| `06-graph-bookmark.png` | Frozen graph, crosshair on a bookmarked row. |
| `06-bookmark-note.png` | The bookmark note dialog. |
| `06-graph-marks.png` | The Marks sheet. |
| `06-graph-compare-list.png` | The Compare with list. |
| `06-graph-two-clusters.png` | A real relay graph with two groups of dots, taken with an **earlier version** of the app (red line only). The candidate's name is blurred; the status and navigation bars are cropped. |
| `08-settings-top.png` | Settings, first half. The name of the installed map app is blurred. |
| `08-settings-bottom.png` | Settings, second half. The Meshview URL and the skipped-node entry are blurred. |

## Not shown (and why)

- Permission, Bluetooth-off and pairing screens: they need system state this setup does not change.
- The Graph's candidate line and verdict: the demo's candidates are never heard directly, so
  no line can be drawn.
- The map globe and distance/direction: the demo's local node has no position.
- The Save As window of Export data and the file pickers of Save/Load nodes: these are Android
  system windows.

## How to reproduce

Install the debug build, open it, pick the scenario that streams traffic, wait about a minute, and
capture. Each capture is cropped to remove the top 110 px (status bar) and everything below y=2205 (navigation
bar), then any blurred areas listed in the table above are covered with a blurred, darkened box.
