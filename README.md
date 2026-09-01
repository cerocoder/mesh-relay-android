# Mesh Relay for Android

Mesh Relay shows **SNR** (signal-to-noise ratio) and received packets' **signal
levels** (`rxSnr`, `rxRssi`) for the different **relay nodes** that forward
Meshtastic packets toward your location, and lists the **remote nodes** whose
traffic each relay carries.

The app is an Android port of [`mesh_stats`](https://github.com/cerocoder/mesh_stats),
a Python curses TUI that does the same job on a laptop connected to a local
node over serial or BLE. This port targets a phone instead: it connects to a
Meshtastic node over **Bluetooth Low Energy** and re-implements every feature
of `mesh_stats`, redesigned for a touch screen rather than a terminal.

## Status

This is an early-stage, personal project under active development. It is not
yet feature-complete with `mesh_stats`.

## Build

The project has no Gradle wrapper: install `gradle` locally (CI pins the
version through `gradle/actions/setup-gradle@v4`) and build with:

```bash
gradle :app:assembleDebug
```

Without an Android SDK on the machine, that command cannot run at all and CI is
the only build. `docs/verifying.md` is how to read a CI run, fetch its APK and
check a change on the phone — none of which works the obvious way here.

## Connecting to a node

The app connects to a Meshtastic node over **Bluetooth Low Energy (BLE)**. A
**debug-only demo device** is also available, replaying a simulated stream of
packets so the app can be exercised without a real node nearby; it is not
present in release builds.

## License

This project is licensed under the **GNU General Public License v3.0** — see
the `LICENSE` file in the project root. It is a port of and derivative work
based on [`mesh_stats`](https://github.com/cerocoder/mesh_stats), also
GPL-3.0.
