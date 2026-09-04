# Opening a position in an installed map app — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make a tapped coordinate open in OsmAnd, Organic Maps or whichever map application the
owner has installed, with a labelled pin, instead of always opening a website.

**Architecture:** A pure `geoUri` beside the existing `MapLinks` builders. A new boolean setting
beside `mapProvider`. A detector that asks the package manager whether anything handles `geo:`,
used both to enable the switch and to name what it will open. One launcher that tries the `geo:`
URI and falls back to the website.

**Tech Stack:** Unchanged. Kotlin 2.4.10, Compose BOM 2026.06.01, JUnit 4.13.2. No new dependencies
— the whole feature is a URI scheme Android already routes.

**Spec:** `docs/superpowers/specs/2026-09-04-map-app-geo-uri-design.md`

## Global Constraints

- **No new dependencies.**
- **`stats/**` must not import `android.*` or `ui/**`.**
- **No user-facing literal strings in Kotlin** — every string a resource, in both `values/` and
  `values-es/`.
- **No Russian anywhere** — code, comments, commits, documents.
- Never call `System.currentTimeMillis()` outside `SystemTimeSource`.
- **No local build is possible**: no Android SDK, no Gradle wrapper, no `kotlinc`. There **is** a
  plain OpenJDK with `jshell`. CI is the gate; the phone is the acceptance.

---

## The three things that make this fail quietly

Each is a silent failure — the app keeps working and does the wrong thing.

1. **A decimal comma.** Under a Spanish locale `%f` emits `40,3057`, which is not a coordinate.
   The map opens *somewhere else* rather than failing. Everything formats under `Locale.ROOT`, and
   Task 1 pins it with a test that sets the default locale to Spain — this mesh's own locale, and
   the one that would have shipped the bug.
2. **Package visibility.** Since API 30, `resolveActivity` returns null without a `<queries>`
   declaration even when a map application is installed. Without Task 2's manifest block the switch
   would permanently and wrongly report that nothing is available. This app targets SDK 36.
3. **An uncaught `ActivityNotFoundException`.** `LocalUriHandler.openUri` on a `geo:` URI throws
   when nothing handles it, which crashes the screen rather than falling back. Task 3 catches it
   even though Task 2 detects, because an application can be uninstalled after the settings screen
   was drawn.

---

## File structure

| File | Responsibility | Task |
|---|---|---|
| `ui/common/MapLinks.kt` | `geoUri` beside the existing builders | 1 |
| `settings/AppSettings.kt`, `settings/SettingsRepository.kt` | The new flag | 2 |
| `ui/common/MapAppAvailability.kt` *(new)* | Is there a `geo:` handler, and what is it called | 2 |
| `AndroidManifest.xml` | The `<queries>` declaration | 2 |
| `ui/settings/SettingsScreen.kt` | The switch and its three-state subtitle | 2 |
| `ui/common/MapLauncher.kt` *(new)* | Try `geo:`, fall back to the website | 3 |
| `ui/common/PositionLine.kt`, `ui/graph/SignalGraphScreen.kt` | The two call sites | 3 |
| `res/values*/strings.xml` | Switch label and subtitles | 2 |
| `docs/decisions.md`, `docs/acceptance-checklist.md` | Rulings and Group M | 3 |

---

## Model selection

| # | Task | Implementer | Reviewer |
|---|---|---|---|
| 1 | `geoUri` and its tests | Haiku | Sonnet |
| 2 | The setting, the detector, the manifest, the switch | Sonnet | Sonnet |
| 3 | The launcher, both call sites, docs | Sonnet | **Opus** |

Task 3 gets an Opus reviewer because it is the one that can crash a screen: it changes what happens
when the user taps a coordinate, on a path with no test harness in this project.

---

### Task 1: `geoUri`

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/common/MapLinks.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/ui/common/MapLinksTest.kt`

**Interfaces:**
- Produces: `MapLinks.geoUri(lat: Double, lon: Double, label: String): String`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test
fun `a geo uri carries the coordinate twice and a labelled pin`() {
    // Twice on purpose: an app that ignores q= still centres on the bare geo:
    // part, and one that honours it drops a named pin.
    assertEquals(
        "geo:40.3057340,-3.7325410?q=40.3057340,-3.7325410(1ce5)",
        MapLinks.geoUri(40.305734, -3.732541, "1ce5"),
    )
}

@Test
fun `a geo uri is formatted under Locale ROOT even in Spain`() {
    // This mesh is in Spain and the app ships a Spanish locale, so this is the
    // locale that would have shipped the bug: %f emits "40,3057" under es-ES,
    // which is not a coordinate - and the map opens somewhere else rather than
    // failing, which is why only a test catches it.
    val previous = Locale.getDefault()
    try {
        Locale.setDefault(Locale.forLanguageTag("es-ES"))
        val uri = MapLinks.geoUri(40.305734, -3.732541, "1ce5")
        // Not `!uri.contains(",")` - the URI legitimately separates latitude from
        // longitude with a comma. The bug is a comma inside a *number*, so assert
        // on the shape of the numbers themselves.
        val numbers = Regex("-?\\d+\\.\\d{7}").findAll(uri).map { it.value }.toList()
        assertEquals(listOf("40.3057340", "-3.7325410", "40.3057340", "-3.7325410"), numbers)
    } finally {
        Locale.setDefault(previous)
    }
}
```

```kotlin
@Test
fun `seven decimal places, the protobufs own resolution`() {
    // 1e-7 degrees is exactly Position.latitude_i's scale, so the format neither
    // invents precision the mesh never carried nor discards any it did.
    assertEquals("geo:0.0000001,0.0000000?q=0.0000001,0.0000000(x)", MapLinks.geoUri(1e-7, 0.0, "x"))
}

@Test
fun `a label is encoded, because parentheses are structural here`() {
    val uri = MapLinks.geoUri(40.0, -3.0, "a(b)c")
    assertTrue(uri.endsWith("(a%28b%29c)"))
}
```

**Do not use `Uri.encode`.** It is `android.net.Uri`, which returns -1 or throws under a plain JVM
unit test, so the whole function would become untestable here — and this file's value is that it is
pure. `java.net.URLEncoder` is also wrong: it encodes a space as `+`, which is form encoding, not
URI encoding.

Hand-roll the escape instead. Only three characters need it, and `%` must be first or it would
double-escape the others:

```kotlin
/**
 * The three characters that are structural inside `q=...(label)`. Hand-rolled
 * rather than `Uri.encode`, which is `android.net.Uri` and unavailable to a JVM
 * unit test, and rather than `URLEncoder`, which encodes a space as `+` - form
 * encoding, not URI encoding. `%` is replaced first, or it would escape the
 * escapes that follow it.
 */
private fun encodeLabel(label: String): String = label
    .replace("%", "%25")
    .replace("(", "%28")
    .replace(")", "%29")
```

- [ ] **Step 2: Run them and watch them fail** — "unresolved reference: geoUri".

- [ ] **Step 3: Implement**

```kotlin
    private const val GEO_TEMPLATE = "geo:%.7f,%.7f?q=%.7f,%.7f(%s)"

    /**
     * A coordinate for whatever map application the device has, with a named pin.
     *
     * The coordinate appears twice deliberately: an application that ignores `q=`
     * still centres on the bare `geo:` part, and one that honours it drops a pin
     * labelled with the node's name rather than an anonymous dot.
     *
     * `%.7f` is the protobuf's own resolution - `Position.latitude_i` is degrees
     * scaled by 1e7 - so this neither invents precision the mesh never carried nor
     * discards any it did. [Locale.ROOT] for the reason recorded on every other
     * builder in this file: a Spanish locale emits `40,3057`, which is not a
     * coordinate, and the map opens somewhere else rather than failing.
     */
    fun geoUri(lat: Double, lon: Double, label: String): String =
        String.format(Locale.ROOT, GEO_TEMPLATE, lat, lon, lat, lon, encodeLabel(label))
```

- [ ] **Step 4: Run the tests, green**
- [ ] **Step 5: Commit** — `feat(ui): build a geo: URI with a labelled pin`

---

### Task 2: The setting, the detector and the switch

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/settings/AppSettings.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/settings/SettingsRepository.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/common/MapAppAvailability.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-es/strings.xml`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/settings/SettingsRepositoryTest.kt`

**Interfaces:**
- Produces: `AppSettings.preferInstalledMapApp: Boolean`,
  `SettingsRepository.setPreferInstalledMapApp(value: Boolean)`,
  `MapAppAvailability.of(packageManager: PackageManager): MapAppState`
  where `MapAppState` is `None` / `One(name: String)` / `Several`.

- [ ] **Step 1: The setting**

`AppSettings` gains `val preferInstalledMapApp: Boolean = false`. Default **off**: the current
behaviour is what an existing installation already has, and a setting that changes where taps go
should be chosen rather than inherited.

`SettingsRepository` persists it beside `KEY_MAP_PROVIDER`, reading through whatever defaulting
helper the boolean settings already use. Add a test that a stored value round-trips and an absent
one reads as false — follow the shape the existing settings tests use.

- [ ] **Step 2: The manifest**

```xml
    <queries>
        <intent>
            <action android:name="android.intent.action.VIEW" />
            <data android:scheme="geo" />
        </intent>
    </queries>
```

A sibling of `<application>`, not inside it. Without this, `resolveActivity` returns null on API 30
and above even when a map application is installed, so the switch would permanently claim there is
nothing to switch to. Put that sentence in the manifest as a comment: it is the sort of block that
looks removable.

- [ ] **Step 3: The detector**

```kotlin
sealed interface MapAppState {
    data object None : MapAppState
    data class One(val name: String) : MapAppState
    data object Several : MapAppState
}
```

Resolve `Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0"))`. Query all handlers; with none return
`None`; with exactly one, or when a user default is set, return `One` carrying its loaded label;
with several and no default return `Several`.

`Several` is not a nicety. With two map applications and no default, `resolveActivity` returns the
system **resolver activity**, not an application, and naming that would be a guess presented as a
fact.

- [ ] **Step 4: The switch**

In `SettingsScreen`, beneath the Map provider group, using the same labelled-switch row the screen
already has (`SettingsScreen.kt:384-390`) rather than a second shape. It needs a subtitle, which
that row does not currently carry — extend it, or add a sibling that does, and use the same one for
both so the screen keeps one row idiom.

| `MapAppState` | Subtitle | Switch |
|---|---|---|
| `None` | `settings_map_app_none` | disabled |
| `One` | `settings_map_app_one` with the name | enabled |
| `Several` | `settings_map_app_several` | enabled |

Read the state with `LocalContext.current.packageManager` inside a `remember` — it changes only
when applications are installed, which does not happen while this screen is open.

- [ ] **Step 5: Strings, both locales**

`settings_prefer_installed_map_app`, `settings_map_app_none`, `settings_map_app_one`,
`settings_map_app_several`. Read the neighbouring Spanish strings for register and match them.

- [ ] **Step 6: Run the tests, green**
- [ ] **Step 7: Commit** — `feat(settings): offer an installed map app when one exists`

---

### Task 3: The launcher, the call sites and the record

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/common/MapLauncher.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/common/PositionLine.kt`
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/graph/SignalGraphScreen.kt`
- Modify: `docs/decisions.md`, `docs/acceptance-checklist.md`

**Interfaces:**
- Consumes: `MapLinks.geoUri`, `AppSettings.preferInstalledMapApp`, `MapLinks.forProvider`.

- [ ] **Step 1: The launcher**

```kotlin
/**
 * Opens a coordinate where the owner asked for it.
 *
 * Tries the `geo:` URI when the setting is on, and falls back to the website
 * otherwise **or on failure**. The catch is not redundant with the settings
 * screen's detection: an application can be uninstalled between that screen being
 * drawn and this line running, and an uncaught ActivityNotFoundException does not
 * degrade - it takes the screen down.
 */
fun openPosition(
    uriHandler: UriHandler,
    preferInstalledApp: Boolean,
    provider: MapProvider,
    lat: Double,
    lon: Double,
    label: String,
) {
    if (preferInstalledApp) {
        try {
            uriHandler.openUri(MapLinks.geoUri(lat, lon, label))
            return
        } catch (noHandler: Exception) {
            // Deliberately Exception: Compose's AndroidUriHandler wraps
            // ActivityNotFoundException in an IllegalStateException, and which one
            // surfaces has changed between Compose versions. Falling through to a
            // website that always resolves is strictly better than either.
        }
    }
    uriHandler.openUri(MapLinks.forProvider(provider, lat, lon))
}
```

- [ ] **Step 2: Both call sites**

`PositionLine.kt:128` and `SignalGraphScreen.kt:577`. Two places in the source, three on screen —
`PositionLine` serves both the node panel and the neighbour list.

Each needs the label. `PositionLine` has `nodeNum` and can resolve a short name; the Graph's globe
knows its subject. Where no name is known, pass the formatted node id — a pin labelled `!a4f0c1e5`
is still better than an unlabelled one.

Both need `preferInstalledMapApp`. It reaches them the same way `mapProvider` already does; follow
that wiring rather than adding a second route.

- [ ] **Step 3: Record the rulings**

Append to `docs/decisions.md`, reading the end of the file for the next number, in that file's
voice. Three:

1. A separate switch rather than a third `MapProvider` value — the owner's ruling. The two are
   independent axes: which website, versus try a local application first.
2. Detect and disable rather than try-and-hope — the owner's ruling. A switch that is on and
   silently does nothing is indistinguishable from a defect. Cost if wrong: a `<queries>` block and
   a package-manager read on a settings screen.
3. The catch stays despite the detection, because an application can be uninstalled after that
   screen was drawn. Cost if wrong: nothing; the fallback is a website that always resolves.

- [ ] **Step 4: Group M in the acceptance checklist**

Same format as its neighbours, blank result lines, and update the "Total items run" denominator by
counting `^### [A-Z][0-9]+\.` headings yourself.

1. With no map application installed, the switch is disabled and says so.
2. With one installed, the switch names it, and tapping a coordinate opens it at the node's
   position with a **named pin**, not an anonymous marker.
3. With two installed and no default, the subtitle says you will be asked, and tapping shows the
   chooser.
4. With the switch **off**, tapping still opens the website chosen under Map provider.
5. Turn the switch on, then uninstall the map application without reopening Settings, and tap a
   coordinate: the website opens, and the app does not crash. This is the case the catch exists
   for.
6. Check a node at a negative longitude — the whole of this mesh — arrives at the right place. A
   decimal-comma bug would put it in the wrong hemisphere rather than nowhere.

- [ ] **Step 5: Commit, push, read CI**

---

## Acceptance, on the phone

Group M above. Item 5 is the one no test in this project can reach, and item 6 is the one that
catches the failure that looks like success.
