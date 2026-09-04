# Design: opening a node's position in an installed map app

**Date:** 2026-09-04
**Status:** approved, ready for the implementation plan
**Source:** the owner's question of 2026-09-04 and the brainstorming session of the same day
**Builds on:** `docs/superpowers/specs/2026-08-26-mesh-relay-android-design.md`

---

## 1. Goal

Tapping a coordinate opens a **website**. `MapLinks` emits
`https://www.openstreetmap.org/?mlat=…` or `https://maps.google.com/?q=…`, and
`LocalUriHandler.openUri` hands it to a browser.

The owner has OsmAnd-class map applications installed, which hold offline maps of this mesh's
area. This makes a coordinate open there instead, with a labelled pin, and falls back to the
website when no such application exists.

No new dependency, which matters: this application is otherwise F-Droid-clean and carries an
explicit no-new-dependencies rule.

## 2. Decisions

| Decision | Choice | Reason |
| :--- | :--- | :--- |
| Where the choice lives | **A separate switch, "Prefer an installed map app"** | The owner's ruling, taken against a third value on `MapProvider`. The two are independent axes: `MapProvider` answers *which website*, the switch answers *try a local application first*. Keeping them apart makes the fallback explicit — prefer the application, and when none exists use the website already chosen |
| When none is installed | **Detect it; disable the switch and say so** | The owner's ruling. A switch that is on and silently does nothing is indistinguishable from a defect |
| The URI | `geo:` with the coordinate twice, see §4 | An application ignoring `q=` still centres correctly; one honouring it drops a labelled pin |
| The pin's label | **The node's short name** | On a tool where several nodes are opened in a row, a pin reading `1ce5` is worth one parameter |
| Failure at the call site | **Catch and fall back, even though §5 detects** | An application can be uninstalled after the settings screen was drawn |

## 3. The setting

`AppSettings` gains `preferInstalledMapApp: Boolean = false`, persisted beside `mapProvider`
through the same mechanism. `MapProvider` stays a two-value enum and keeps its meaning.

Default **off**: the current behaviour is what an existing installation already has, and a setting
that changes where taps go should be chosen, not inherited.

## 4. The URI

```kotlin
fun geoUri(lat: Double, lon: Double, label: String): String
// geo:<lat>,<lon>?q=<lat>,<lon>(<label>)
```

Formatted `%.7f` under **`Locale.ROOT`**, both non-negotiable and for different reasons:

- `1e-7` degrees is exactly the protobuf's own coordinate scale (`Position.latitude_i`), so the
  format neither invents precision the mesh never carried nor discards any it did.
- Under a Spanish locale `%f` emits `40,3057`. That is not a coordinate, and it fails silently —
  the map opens somewhere else rather than not at all. `MapLinks` already applies `Locale.ROOT`
  for this reason and the rule carries over unchanged.

The label goes through `Uri.encode`. A short name is four characters of user-controlled text and
`(`, `)` are structural in this syntax.

## 5. Detecting a handler

```xml
<queries>
    <intent>
        <action android:name="android.intent.action.VIEW" />
        <data android:scheme="geo" />
    </intent>
</queries>
```

**Required.** Since API 30 `resolveActivity` returns null without it even when a map application
is installed, so the switch would permanently and wrongly claim there is nothing to switch to.
This application targets SDK 36.

The subtitle under the switch has three states, because Android can genuinely be undecided:

| State | Subtitle | Switch |
| :--- | :--- | :--- |
| no handler | *No map app installed* | disabled |
| exactly one, or a user default is set | *Coordinates open in <name>* | enabled |
| several, no default | *You'll be asked which app to use* | enabled |

The third state is not a nicety. With two map applications and no default, `resolveActivity`
returns the system resolver rather than an application, and naming one would be a guess.

## 6. Where it fires

Three call sites, all through one helper: the position line on the node panel, the same line on
the neighbour list, and the Graph crosshair's globe. The helper tries the `geo:` URI, catches, and
falls back to `MapLinks.forProvider(...)`.

The Meshview link is untouched. It is a website about a node, not a coordinate.

## 7. Testing

`geoUri` is pure and takes unit tests for: the format, including the coordinate appearing twice;
`Locale.ROOT` under a locale that would otherwise emit a decimal comma — the test sets the default
locale to Spain, which is this mesh's own locale and the one that would have shipped the bug;
`%.7f` at the protobuf's resolution; and label encoding for a name containing a parenthesis.

Detection, launching and the fallback are Android-only with no harness in this project, and go to
the acceptance checklist as **Group M**.

## 8. Out of scope

- Targeting a specific application by package (`osmand.geo:`, `om://`). More precise, more
  brittle, and it needs a `<queries>` entry per package.
- Passing anything but a point — no routes, no multiple pins, no track of a node's movement.
- Changing what `MapProvider` means, or its default.
