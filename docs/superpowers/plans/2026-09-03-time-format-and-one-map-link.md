# A time-format option, and one map link — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Two owner requests. A **Time** setting with *12 hour* and *24 hour* variants that governs
every clock time the interface prints; and, now that **Map provider** exists, one map link on a node
panel instead of the current pair.

**Architecture:** Both are display preferences that many leaf composables need and no screen owns.
Rather than thread two parameters through fourteen call sites and every preview, both follow the
pattern this project already uses for exactly this — `LocalRelativeClock`, provided once in
`MainActivity` and read ambiently by the leaf that needs it. The formatting itself stays in the pure,
unit-tested `StatsFormat`; the composable reads the preference and passes it in.

**Tech Stack:** Unchanged. Kotlin 2.4.10, Compose BOM 2026.06.01, JUnit 4. No new dependencies.

**Spec:** none. This plan is the specification.

---

## Global Constraints

- **No new dependencies.**
- **`stats/**` must not import `android.*` or `ui/**`.**
- **No user-facing literal strings in Kotlin.** Everything from `R.string`.
- **Both locales, always**, in the same commit. `StringsParityTest` fails the build otherwise and also
  checks `%1$s`-style placeholders match.
- **No `@Composable` may contain arithmetic or formatting.** The composable reads the preference and
  hands it to `StatsFormat`; every pattern lives there, with tests.
- **No Russian anywhere.**
- Never call `System.currentTimeMillis()` outside `SystemTimeSource` (existing `@Preview` fixtures excepted).
- **No local build is possible**: no Android SDK, no Gradle wrapper, no `kotlinc`. Nothing can be
  compiled or run. CI is the gate; the phone is the acceptance.

## Decisions taken before writing this plan

1. **Exactly two variants, as asked** — `TWELVE_HOUR` and `TWENTY_FOUR_HOUR`. Not a third
   "follow the system", even though `LanguageOption` has one: the owner named two.
2. **The default is `TWENTY_FOUR_HOUR`.** This is the one judgement call here and it changes what the
   app shows on upgrade. Today the clock style is derived from the locale, so with the app in English
   it prints `1:12:34 PM`. A 24-hour clock is the better default for this tool — the mesh is in Spain
   where 24-hour is standard, and a signal log reads better without an am/pm suffix. It is one
   constant in `AppSettings` if that is wrong.
3. **Explicit patterns, not the locale's hour cycle.** The tempting implementation is a Unicode
   extension (`-u-hc-h12`/`h23`) on the locale handed to `ofLocalizedTime`. **Do not use it.** Whether
   `java.time` honours that extension depends on the CLDR provider, and this project's unit tests run
   on the JVM while the app runs on Android — so a test could pass while the device disagreed, which
   is the one failure mode this project has no way to catch. Build the time from an explicit pattern
   instead. Cost, stated: locale-specific time separators are lost. Immaterial here — the app ships
   English and Spanish, and both use `:`.
4. **Dates stay locale-derived.** 12/24 is a property of the clock, not of the date; the date half
   keeps `ofLocalizedDate(FormatStyle.SHORT)` so a Spanish reader still gets day-before-month.
5. **A `CompositionLocal` each, not two more parameters.** `NodeCard` has nine call sites and
   `RemoteNodesTab` five, most of them previews. `LocalRelativeClock` already establishes this pattern
   for a display-wide value every card needs, provided once in `MainActivity`. Both new locals get a
   sensible default, so **no preview needs touching**.
6. **The single map button reuses the settings labels.** `node_open_google_maps` and `node_open_osm`
   have exactly the same values as `map_provider_google` and `map_provider_osm` and only one caller;
   the duplicates go. The **Meshview** button is untouched — it is a different destination, not a map
   provider.

---

## Model selection

| # | Task | Implementer | Reviewer |
|---|---|---|---|
| 1 | The Time setting | Sonnet | **Opus** |
| 2 | One map link on the node panel | Sonnet | Sonnet |

Task 1 gets an Opus reviewer because it changes every clock time in the interface and its correctness
across locale × format is the kind of thing that reads fine and is wrong.

---

### Task 1: A Time setting that governs every clock the interface prints

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/settings/TimeFormat.kt`
- Modify: `settings/AppSettings.kt`, `settings/SettingsRepository.kt`
- Modify: `ui/common/StatsFormat.kt` (both clock formatters)
- Create: `ui/common/LocalDisplayPrefs.kt` (both CompositionLocals — Task 2 uses the other)
- Modify: `MainActivity.kt` (provide them), `ui/settings/SettingsScreen.kt` (the radio group)
- Modify: `ui/detail/NodeCard.kt:163`, `ui/graph/SignalGraphScreen.kt:540,717` (the three call sites)
- Modify: `app/src/main/res/values/strings.xml`, `values-es/strings.xml`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/ui/common/StatsFormatTest.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/settings/SettingsRepositoryTest.kt`

**Interfaces:**
- Produces: `enum class TimeFormat { TWELVE_HOUR, TWENTY_FOUR_HOUR }`;
  `AppSettings.timeFormat: TimeFormat` (default `TWENTY_FOUR_HOUR`);
  `LocalTimeFormat: ProvidableCompositionLocal<TimeFormat>`;
  `StatsFormat.nodeDatabaseLastHeard(epochSeconds, locale, timeFormat, zone)` and
  `StatsFormat.graphTimestamp(atMillis, locale, timeFormat, zone)`.

- [ ] **Step 1: Write the failing tests**

In `StatsFormatTest`:

```kotlin
@Test
fun `the time format governs the clock and leaves the date to the locale`() {
    val at = LocalDateTime.of(2026, 8, 21, 13, 1, 13)
        .atZone(ZoneId.of("Europe/Madrid")).toInstant().toEpochMilli()

    val h24 = StatsFormat.graphTimestamp(at, Locale.US, TimeFormat.TWENTY_FOUR_HOUR, ZoneId.of("Europe/Madrid"))
    val h12 = StatsFormat.graphTimestamp(at, Locale.US, TimeFormat.TWELVE_HOUR, ZoneId.of("Europe/Madrid"))

    // 24 hour: no am/pm marker anywhere, and the hour is 13.
    assertTrue(h24, h24.contains("13:01:13"))
    assertFalse(h24, h24.uppercase().contains("AM") || h24.uppercase().contains("PM"))

    // 12 hour: the hour is 1 and there is a marker.
    assertTrue(h12, h12.contains("1:01:13"))
    assertTrue(h12, h12.uppercase().contains("PM"))
}

@Test
fun `the date half still follows the locale in both formats`() {
    val at = LocalDateTime.of(2026, 8, 21, 13, 1, 13)
        .atZone(ZoneId.of("Europe/Madrid")).toInstant().toEpochMilli()
    val spain = Locale("es", "ES")
    for (format in TimeFormat.entries) {
        val us = StatsFormat.graphTimestamp(at, Locale.US, format, ZoneId.of("Europe/Madrid"))
        val es = StatsFormat.graphTimestamp(at, spain, format, ZoneId.of("Europe/Madrid"))
        // Month-first against day-first: the clock choice must not flatten the date.
        assertNotEquals("date order lost for $format", us, es)
    }
}

@Test
fun `midnight and noon are not confused in twelve hour form`() {
    // The classic pattern bug: 'HH' or 'kk' where 'hh' is meant renders midnight
    // as 00 AM or noon as 00 PM. Both must read 12.
    val zone = ZoneId.of("Europe/Madrid")
    fun at(h: Int) = LocalDateTime.of(2026, 8, 21, h, 5, 0).atZone(zone).toInstant().toEpochMilli()

    val midnight = StatsFormat.graphTimestamp(at(0), Locale.US, TimeFormat.TWELVE_HOUR, zone)
    val noon = StatsFormat.graphTimestamp(at(12), Locale.US, TimeFormat.TWELVE_HOUR, zone)
    assertTrue(midnight, midnight.contains("12:05:00") && midnight.uppercase().contains("AM"))
    assertTrue(noon, noon.contains("12:05:00") && noon.uppercase().contains("PM"))

    // And 24 hour renders them 00 and 12.
    assertTrue(StatsFormat.graphTimestamp(at(0), Locale.US, TimeFormat.TWENTY_FOUR_HOUR, zone).contains("00:05:00"))
    assertTrue(StatsFormat.graphTimestamp(at(12), Locale.US, TimeFormat.TWENTY_FOUR_HOUR, zone).contains("12:05:00"))
}

@Test
fun `the node database timestamp honours the format too`() {
    // NodeCard's "Last DB heard" is the other clock in the interface and must not
    // be left behind - the owner asked for all of them.
    val epochSeconds = LocalDateTime.of(2026, 8, 21, 13, 1, 13)
        .atZone(ZoneId.of("Europe/Madrid")).toEpochSecond().toInt()
    val h24 = StatsFormat.nodeDatabaseLastHeard(epochSeconds, Locale.US, TimeFormat.TWENTY_FOUR_HOUR, ZoneId.of("Europe/Madrid"))
    assertTrue(h24, h24.contains("13:01:13"))
    assertFalse(h24, h24.uppercase().contains("PM"))
}
```

In `SettingsRepositoryTest`, add `assertEquals(TimeFormat.TWENTY_FOUR_HOUR, settings.timeFormat)` to
the defaults test, and a persistence test in the shape of the `usePhoneLocation` one already there —
writing the **non-default** value first, then reading it back through a fresh repository.

- [ ] **Step 2: Run them and watch them fail**

`gradle :app:testDebugUnitTest --tests '*StatsFormatTest*' --tests '*SettingsRepositoryTest*'` —
expect "unresolved reference: TimeFormat". No local SDK; observed at Step 6's CI run.

- [ ] **Step 3: The setting**

`settings/TimeFormat.kt`, modelled on `settings/GaugeMode.kt` (a small enum whose labels live in `ui`):

```kotlin
package com.cerocoder.meshrelay.settings

/**
 * Which clock the interface prints. Governs every absolute time it shows - the
 * node database's last-heard, and the Graph's two Time fields and its crosshair.
 *
 * Relative ages ("5min ago") are unaffected: they carry no clock at all.
 */
enum class TimeFormat { TWELVE_HOUR, TWENTY_FOUR_HOUR }
```

`AppSettings` gains `val timeFormat: TimeFormat = TimeFormat.TWENTY_FOUR_HOUR` with a KDoc recording
why the default is 24 (decision 2 above, in short). `SettingsRepository` gains `KEY_TIME_FORMAT`,
read through the existing `readEnum` helper and written in `persist()`'s `strings` map — follow
`gauge_mode` exactly.

- [ ] **Step 4: The formatters**

Both take a `TimeFormat` and build the clock from an explicit pattern. Add to `StatsFormat`:

```kotlin
    /**
     * The clock half of an absolute timestamp.
     *
     * An explicit pattern rather than [DateTimeFormatter.ofLocalizedTime], and
     * that is deliberate: forcing a 12- or 24-hour clock through a localized
     * formatter means a `-u-hc-` Unicode extension on the locale, and whether
     * `java.time` honours it depends on the CLDR provider. This project's unit
     * tests run on the JVM and the app runs on Android, so that route could pass
     * every test and still be wrong on the phone - the one failure this project
     * has no way to catch. A pattern behaves identically on both.
     *
     * The cost is locale-specific time separators, which this app does not need:
     * it ships English and Spanish and both use `:`.
     *
     * `hh`/`HH` rather than `kk`/`KK`: `KK` renders noon as `00 PM` and `kk`
     * renders midnight as `24`. Both are the classic form of this bug.
     */
    private fun clockPattern(timeFormat: TimeFormat): String = when (timeFormat) {
        TimeFormat.TWELVE_HOUR -> "h:mm:ss a"
        TimeFormat.TWENTY_FOUR_HOUR -> "HH:mm:ss"
    }
```

`graphTimestamp` becomes the clock pattern plus the still-localized short date:

```kotlin
    fun graphTimestamp(
        atMillis: Long,
        locale: Locale,
        timeFormat: TimeFormat,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val localDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(atMillis), zone)
        val time = DateTimeFormatter.ofPattern(clockPattern(timeFormat), locale)
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale)
        return "${time.format(localDateTime)} ${date.format(localDateTime)}"
    }
```

`nodeDatabaseLastHeard` gets the same treatment. It currently uses
`ofLocalizedDateTime(FormatStyle.MEDIUM)`, which produces date **and** time in one call — split it the
same way, so both clocks in the app are built by one rule. Keep its existing KDoc's reasoning about
`FormatStyle` where it still applies to the date, and correct what no longer does.

- [ ] **Step 5: The ambient preference, the setting screen, the call sites**

`ui/common/LocalDisplayPrefs.kt`:

```kotlin
/**
 * Display preferences that many leaf composables need and no screen owns.
 *
 * The same pattern [LocalRelativeClock] already uses, and for the same reason:
 * `NodeCard` alone has nine call sites and `RemoteNodesTab` five, most of them
 * previews, and threading a preference through all of them would put a parameter
 * on every screen between the settings and the one card that reads it.
 *
 * Both carry a default, so a preview renders without providing anything.
 */
val LocalTimeFormat = compositionLocalOf { TimeFormat.TWENTY_FOUR_HOUR }
val LocalMapProvider = compositionLocalOf { MapProvider.GOOGLE }
```

`MainActivity` provides both from the collected settings, wrapping the same content
`ProvideRelativeClock` already wraps.

`SettingsScreen` gains a **Time** radio group in the shape of the Gauge mode and Map provider groups,
with a `timeFormatLabelRes` helper beside the existing ones. Put it after Map provider.

The three call sites read `LocalTimeFormat.current` and pass it in: `NodeCard.kt:163`,
`SignalGraphScreen.kt:540` and `:717`.

- [ ] **Step 6: Strings, both locales**

```xml
<!-- values -->
<string name="settings_time_format">Time</string>
<string name="time_format_12">12 hour format</string>
<string name="time_format_24">24 hour format</string>
<!-- values-es -->
<string name="settings_time_format">Hora</string>
<string name="time_format_12">Formato de 12 horas</string>
<string name="time_format_24">Formato de 24 horas</string>
```

- [ ] **Step 7: Run the tests, green**
- [ ] **Step 8: Commit** — `feat(settings): a 12 or 24 hour clock, everywhere the app prints one`

---

### Task 2: One map link on the node panel

`PositionLine` offers **Google Maps** and **OpenStreetMap** side by side. Now that **Map provider**
exists, it should offer the chosen one.

**Files:**
- Modify: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/common/PositionLine.kt`
- Modify: `app/src/main/res/values/strings.xml`, `values-es/strings.xml`

**Interfaces:**
- Consumes: `LocalMapProvider` (Task 1 creates it), `MapLinks.forProvider` (already exists).

- [ ] **Step 1: One button, from the ambient preference**

In `PositionLine`, replace the two `TextButton`s (`:113-118`) with one:

```kotlin
                if (lat != null && lon != null) {
                    // One link, not two: the Map provider setting decides which.
                    // MapLinks.forProvider formats under Locale.ROOT - a Spanish
                    // decimal comma would break the query string, which is why
                    // coordinates never go through a display formatter.
                    val mapProvider = LocalMapProvider.current
                    TextButton(onClick = { uriHandler.openUri(MapLinks.forProvider(mapProvider, lat, lon)) }) {
                        Text(stringResource(mapProviderLabelRes(mapProvider)))
                    }
                }
```

reading the label through the same `mapProviderLabelRes` mapping `SettingsScreen` already has — lift
it to a shared place rather than duplicating the `when`, so the button and the setting cannot come to
disagree about what a provider is called.

**The Meshview button is untouched.** It is a different destination — the community's node database —
not a map provider, and it keeps its own condition.

- [ ] **Step 2: Delete the two duplicate strings**

`node_open_google_maps` and `node_open_osm` have exactly the same values as `map_provider_google` and
`map_provider_osm`, and after Step 1 they have no caller. Remove them from **both** locale files.
Confirm with `grep -rn 'node_open_google_maps\|node_open_osm' app/src` that nothing is left.

- [ ] **Step 3: Update `PositionLine`'s KDoc.** It describes offering the map links as a pair,
      and the F-3 note about `FlowRow` mentions "three links at once" — with one map link plus
      Meshview it is now two. Correct what is no longer true; keep the `FlowRow` and its reasoning,
      which still holds for two buttons in Spanish at a large font scale.

- [ ] **Step 4: Green CI run**
- [ ] **Step 5: Commit** — `feat(ui): one map link on a node panel, per the Map provider setting`

---

## Acceptance, on the phone

Add to `docs/acceptance-checklist.md` as **Group J**, in the format its neighbours use:

1. Settings shows **Time** with **12 hour format** and **24 hour format**; the default is 24 hour.
2. Switching it changes **every** absolute time: the Graph's two `Time` fields, the Graph crosshair,
   and a node card's **Last DB heard**. Relative ages (`5min ago`) are unchanged — they carry no clock.
3. In 12 hour form, a time around midnight reads `12:0x:xx AM` and around noon `12:0x:xx PM` — not
   `00`. This is the pattern bug the unit tests pin; confirm it on screen once.
4. In Spanish, both forms still print the date day-before-month (`21/8/26`), and the labels fit.
5. A node panel shows **one** map link, named for the chosen provider, and it opens that provider.
   Switching the Map provider setting changes the link. The **Meshview** button is still there.
