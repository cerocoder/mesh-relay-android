# Mesh Relay for Android — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the `mesh_stats` terminal tool to Android — show SNR and RSSI for the relay nodes forwarding Meshtastic packets toward your node, and list the remote nodes whose traffic each relay carries.

**Architecture:** A confined-state actor owns all mutable statistics on one coroutine and publishes an immutable `StatsSnapshot`; there is no redraw tick, snapshots are rebuilt from conflated dirty signals and shared `WhileSubscribed`, so nothing is built while no screen is watching. The BLE stack, transport abstraction and connection manager are ported from `mesh-test-android` unchanged. The whole `stats/` package is pure Kotlin with no `android.*` import, so every core file is unit-testable on the JVM.

**Tech Stack:** Kotlin 2.4.10, Jetpack Compose (BOM 2026.06.01), Wire protobuf (`org.meshtastic:protobufs:2.7.26`), Nordic BLE 2.11.0, coroutines 1.11.0, JUnit 4. Android only, no KMP.

**Spec:** `docs/superpowers/specs/2026-08-26-mesh-relay-android-design.md` — read it before starting any task. This plan argues from it and does not repeat its reasoning.

## Global Constraints

Every task's requirements implicitly include this section.

- AGP **9.3.1**, Gradle **9.7.1**, Kotlin **2.4.10** (carried by AGP 9 — the `org.jetbrains.kotlin.android` plugin is rejected and must not be added), JDK **21**.
- `compileSdk = 37`, `targetSdk = 36`, `minSdk = 26`. Compose BOM **2026.06.01**.
- `org.meshtastic:protobufs:2.7.26`, `com.squareup.wire:wire-runtime:6.4.5`, Nordic `ble`/`ble-ktx` **2.11.0**, `scanner` **1.6.0**, coroutines **1.11.0**.
- **No new dependencies.** Not DataStore, not `navigation-compose`, not `appcompat`, not `kotlin-reflect`. If a task appears to need one, stop and report rather than adding it.
- Package root: `com.cerocoder.meshrelay`. `applicationId` `com.cerocoder.meshrelay`. Application label `Mesh Relay`.
- **No Russian anywhere** — code, comments, commits, resources, documents. Ported files from `mesh-test-android` have their comments translated to English with the reasoning preserved, not summarised away.
- **`stats/**` must not import `android.*`**, in main or test sources. It may import `org.meshtastic.proto.*`, `okio`, and `kotlinx.coroutines`.
- **No `@Composable` may contain arithmetic or formatting.** Computation lives in a pure function with its own test; the composable only draws.
- **No user-facing literal strings in Kotlin.** Everything comes from `R.string`.
- Never call `System.currentTimeMillis()` outside `SystemTimeSource`. Time is read through the injected `TimeSource`.
- `testOptions { unitTests.isReturnDefaultValues = true }` must stay — without it every `android.util.Log` call in a JVM test throws "not mocked".
- Licence **GPL-3.0**: `mesh_stats` is GPL-3.0 and this is a derivative work.

## File Structure

```
mesh-relay-android/
├── settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml
├── LICENSE (GPL-3.0), README.md, .gitignore
├── .github/workflows/build.yml
├── docs/superpowers/{specs,plans}/, docs/third-party-assets.md
└── app/src/
    ├── main/res/values/strings.xml, values-es/strings.xml, xml/locales_config.xml
    └── main/kotlin/com/cerocoder/meshrelay/
        ├── MeshRelayApp.kt          application object, owns AppContainer
        ├── AppContainer.kt          manual DI, process-lifetime scope
        ├── MainActivity.kt          permissions, Bluetooth state, composition root
        ├── ble/**                   ported: Nordic BLE, scanning, bonding, reconnect
        ├── transport/**             ported: RadioTransport, FakeRadioTransport, factory
        ├── connection/**            ported + node-DB reload: handshake, frame channel
        ├── emulator/**              hand-written demo scenarios carrying relay traffic
        ├── service/**               ported: foreground service
        ├── settings/
        │   ├── GaugeMode.kt         SIMPLE | COMPLEX — here, not in ui/, because AppSettings carries it
        │   ├── AppSettings.kt       the persisted value object
        │   └── SettingsRepository.kt SharedPreferences, exposes StateFlow
        ├── stats/                   pure Kotlin, no android.*
        │   ├── TimeSource.kt, TimestampedFrame.kt, SortMode.kt, SignalScales.kt
        │   ├── Geo.kt, AgeBucket.kt
        │   ├── PacketClassifier.kt  one packet -> Direct | Relayed | Dropped
        │   ├── NodeDirectory.kt     mutable, engine-confined node database
        │   ├── RelayIndex.kt        which relays carry a given node's traffic
        │   ├── MeshStatsEngine.kt   the actor: owns all state, publishes snapshots
        │   └── model/               immutable value types (see spec §6, §7)
        └── ui/
            ├── theme/{Theme,Color,Type}.kt
            ├── nav/{Screen,BackStack}.kt, MeshRelayNavHost.kt, LocalizedApp.kt
            ├── common/              NodeIdText, AgeText, RelativeTimeTicker,
            │                        GaugeGeometry, SignalGauge, PositionLineText,
            │                        PositionLine, MapLinks
            ├── preview/SampleData.kt
            ├── devices/, relays/, neighbours/, detail/, settings/
```

## Execution model

Tasks are grouped into five rounds. **Within a round the tasks are independent and may be dispatched in parallel; between rounds they may not.** Each task names the model level it is sized for — Sonnet where the plan removes judgement, Opus where a plausible-looking wrong answer would survive review.

| Round | Tasks | Parallel | Gate before the next round |
| :--- | :--- | :--- | :--- |
| 0 | 1–2 | no | `gradle :app:assembleDebug` succeeds |
| 1 | 3–11 | yes, 9 | `gradle :app:testDebugUnitTest` green |
| 2 | 12–17 | yes, 6 | same |
| 3 | 18–20 | yes, 3 | same |
| 4 | 21–29 | yes, 9 | same |
| 5 | 30–32 | no | app runs on hardware |

Definition of done for every task: its tests pass, `gradle :app:compileDebugKotlin` is clean, and no global constraint is violated.

---

# Round 0 — foundation (sequential, Opus)

### Task 1: Build scaffold, manifest and CI

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `.gitignore`, `LICENSE`, `README.md`, `docs/third-party-assets.md`
- Create: `.github/workflows/build.yml`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/MainActivity.kt` (placeholder, replaced in Task 31)
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/ProtobufSmokeTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: a buildable module. Every later task adds files under `app/src/main/kotlin/com/cerocoder/meshrelay/` and `app/src/test/kotlin/com/cerocoder/meshrelay/`.

There is **no Gradle wrapper** in this project, matching `mesh-test-android`: CI pins the Gradle version through `gradle/actions/setup-gradle@v4`, and local builds use an installed `gradle`. Do not run `gradle wrapper`.

- [ ] **Step 1: Write the version catalogue**

`gradle/libs.versions.toml`:

```toml
[versions]
agp = "9.3.1"
kotlin = "2.4.10"
composeBom = "2026.06.01"
coroutines = "1.11.0"
protobufs = "2.7.26"
wire = "6.4.5"
activityCompose = "1.13.0"
nordicBle = "2.11.0"
nordicScanner = "1.6.0"

[libraries]
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
meshtastic-protobufs = { module = "org.meshtastic:protobufs", version.ref = "protobufs" }
wire-runtime = { module = "com.squareup.wire:wire-runtime", version.ref = "wire" }
nordic-ble = { module = "no.nordicsemi.android:ble", version.ref = "nordicBle" }
nordic-ble-ktx = { module = "no.nordicsemi.android:ble-ktx", version.ref = "nordicBle" }
nordic-scanner = { module = "no.nordicsemi.android.support.v18:scanner", version.ref = "nordicScanner" }
junit = { module = "junit:junit", version = "4.13.2" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

- [ ] **Step 2: Write the root build files**

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "mesh-relay-android"
include(":app")
```

`build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
```

`gradle.properties`:

```properties
android.useAndroidX=true
org.gradle.jvmargs=-Xmx2048m
```

`.gitignore`:

```gitignore
*.iml
.gradle/
/local.properties
/.idea/
.DS_Store
/build
/app/build
/captures
.externalNativeBuild
.cxx
.claude/
.superpowers/
```

- [ ] **Step 3: Write the module build file**

`app/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.cerocoder.meshrelay"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.cerocoder.meshrelay"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        resourceConfigurations += setOf("en", "es")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key. This is a personal tool for one's own node
            // and is not published; without a signature assembleRelease emits
            // app-release-unsigned.apk, which Android refuses to install, so there
            // would be no way to test the release variant on a phone at all.
            // If this ever travels beyond its owner's phone, a real key from CI
            // secrets belongs here.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Classes in transport/, emulator/ and connection/ write to android.util.Log.
    // Without this line every Log call in a JVM test fails with "not mocked".
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)

    implementation(libs.nordic.ble)
    implementation(libs.nordic.ble.ktx)
    implementation(libs.nordic.scanner)

    implementation(libs.meshtastic.protobufs)
    implementation(libs.wire.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
```

- [ ] **Step 4: Write the manifest**

`app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Android 12 and newer -->
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

    <!-- Before Android 12, scanning required a location permission -->
    <uses-permission android:name="android.permission.BLUETOOTH"
        android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN"
        android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"
        android:maxSdkVersion="30" />

    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />

    <!-- From Android 13 on, without this the foreground service notification is
         simply not shown: the service runs, but the user cannot see that the
         connection is alive and cannot return to the app from the shade. -->
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:allowBackup="true"
        android:label="@string/app_name"
        android:name=".MeshRelayApp"
        android:localeConfig="@xml/locales_config"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".service.MeshForegroundService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />
    </application>
</manifest>
```

`android:name=".MeshRelayApp"` and the service both point at classes that do not exist yet. Add temporary stubs so the module builds:

`app/src/main/kotlin/com/cerocoder/meshrelay/MeshRelayApp.kt`:

```kotlin
package com.cerocoder.meshrelay

import android.app.Application

/** Replaced in Task 31, when AppContainer exists. */
class MeshRelayApp : Application()
```

`app/src/main/kotlin/com/cerocoder/meshrelay/MainActivity.kt`:

```kotlin
package com.cerocoder.meshrelay

import android.os.Bundle
import androidx.activity.ComponentActivity

/** Replaced in Task 31. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}
```

`app/src/main/kotlin/com/cerocoder/meshrelay/service/MeshForegroundService.kt` is written in Task 11; until then, remove the `<service>` element from the manifest and add it back in that task. Note this in the task-11 checklist rather than leaving a dangling reference.

Minimal resources so the manifest resolves — `app/src/main/res/values/strings.xml`:

```xml
<resources>
    <string name="app_name">Mesh Relay</string>
</resources>
```

`app/src/main/res/xml/locales_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<locale-config xmlns:android="http://schemas.android.com/apk/res/android">
    <locale android:name="en" />
    <locale android:name="es" />
</locale-config>
```

- [ ] **Step 5: Write the failing smoke test**

`app/src/test/kotlin/com/cerocoder/meshrelay/ProtobufSmokeTest.kt`:

```kotlin
package com.cerocoder.meshrelay

import org.junit.Assert.assertEquals
import org.junit.Test
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket

/**
 * Proves the protobuf dependency resolves and that the fields this whole port
 * depends on exist and round-trip. If relay_node ever disappears from the
 * schema, this is the test that says so.
 */
class ProtobufSmokeTest {

    @Test
    fun `relay fields survive an encode and decode round trip`() {
        val original = FromRadio(
            packet = MeshPacket(
                from = 0x9e75f1a4.toInt(),
                relay_node = 0x69,
                hop_start = 3,
                hop_limit = 1,
                rx_snr = -7.5f,
                rx_rssi = -94,
            ),
        )

        val decoded = FromRadio.ADAPTER.decode(original.encode())

        assertEquals(0x69, decoded.packet?.relay_node)
        assertEquals(3, decoded.packet?.hop_start)
        assertEquals(1, decoded.packet?.hop_limit)
        assertEquals(-7.5f, decoded.packet?.rx_snr!!, 0.001f)
        assertEquals(-94, decoded.packet?.rx_rssi)
    }
}
```

- [ ] **Step 6: Run the build and the test**

Run: `gradle :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, one test passing.

If `MeshPacket(...)` rejects a named argument, the Wire-generated field name differs from the `.proto` name; read the generated class and correct the call rather than changing the assertion.

- [ ] **Step 7: Write the CI workflow**

`.github/workflows/build.yml`:

```yaml
name: build
on:
  push:
  pull_request:
  workflow_dispatch:

permissions:
  contents: write

# A new push to the same branch cancels the previous run. Without this a hung
# test holds the runner until the six-hour default timeout and the queue builds up.
concurrency:
  group: build-${{ github.ref }}
  cancel-in-progress: true

defaults:
  run:
    shell: bash

jobs:
  build:
    runs-on: ubuntu-latest
    # A healthy run finishes in about four minutes. Twenty is generous, and a hung
    # test stops costing hours of machine time.
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@v5
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '21'
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '9.7.1'

      # A debug key is generated afresh on an empty runner every run, so APK
      # signatures do not match and a build cannot be installed over the previous
      # one - only after uninstalling. Caching makes the key stable and updates
      # behave normally. The key is a debug key: not a secret, and unfit for
      # distribution.
      - uses: actions/cache@v4
        with:
          path: ~/.android/debug.keystore
          key: android-debug-keystore-v1

      - name: 1 Project configuration
        run: gradle :app:tasks 2>&1 | tee -a /tmp/gradle.log

      - name: 2 Dependency resolution
        run: gradle :app:dependencies --configuration debugCompileClasspath 2>&1 | tee -a /tmp/gradle.log

      - name: 3 Compile main
        run: gradle :app:compileDebugKotlin 2>&1 | tee -a /tmp/gradle.log

      - name: 4 Compile tests
        run: gradle :app:compileDebugUnitTestKotlin 2>&1 | tee -a /tmp/gradle.log

      - name: 5 Run tests
        run: gradle :app:testDebugUnitTest 2>&1 | tee -a /tmp/gradle.log

      - name: 6 Assemble debug APK
        run: gradle :app:assembleDebug 2>&1 | tee -a /tmp/gradle.log

      - name: 7 Assemble release APK
        run: gradle :app:assembleRelease 2>&1 | tee -a /tmp/gradle.log

      - name: Post the failure tail as a commit comment
        if: failure()
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          REPO: ${{ github.repository }}
          SHA: ${{ github.sha }}
        run: |
          TAIL=$(tail -c 50000 /tmp/gradle.log 2>/dev/null || echo "no log")
          python3 - "$TAIL" <<'PY' > payload.json
          import json, sys
          print(json.dumps({"body": "CI failed. Tail of the Gradle output:\n\n```\n" + sys.argv[1] + "\n```"}))
          PY
          curl -sS -X POST \
            -H "Authorization: Bearer $GH_TOKEN" \
            -H "Accept: application/vnd.github+json" \
            "https://api.github.com/repos/$REPO/commits/$SHA/comments" \
            -d @payload.json

      - name: Test reports (on failure)
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: test-reports
          path: app/build/reports/

      - uses: actions/upload-artifact@v4
        with:
          name: app-debug
          path: app/build/outputs/apk/debug/*.apk

      # The release variant matters for acceptance: it must contain no demo
      # devices, and a live node must still be found and connected to.
      - uses: actions/upload-artifact@v4
        with:
          name: app-release
          path: app/build/outputs/apk/release/*.apk

      # A tagged run also publishes the APKs to Releases. The run does this
      # itself rather than a person from a laptop: GITHUB_TOKEN already has write
      # access to this repository, and minting an outside token with write access
      # for a few releases a year would mean keeping a spare key around.
      # Run artifacts expire and require a GitHub login; a file in Releases has a
      # direct link and does not rot, so the phone can be updated without anyone
      # having to understand CI.
      - name: Publish APKs to Releases
        if: startsWith(github.ref, 'refs/tags/v')
        env:
          GH_TOKEN: ${{ secrets.GITHUB_TOKEN }}
          TAG: ${{ github.ref_name }}
        run: |
          # Build output names are anonymous (app-release.apk) and would be
          # indistinguishable in the release list. The version in the name fixes that.
          cp app/build/outputs/apk/release/app-release.apk "mesh-relay-$TAG-release.apk"
          cp app/build/outputs/apk/debug/app-debug.apk "mesh-relay-$TAG-debug.apk"
          gh release create "$TAG" \
            --repo "$GITHUB_REPOSITORY" \
            --title "$TAG" \
            --notes "Tagged build $TAG. release - no demo devices, for use with a real node; debug - with demo devices, for work without one." \
            "mesh-relay-$TAG-release.apk" \
            "mesh-relay-$TAG-debug.apk"
```

- [ ] **Step 8: Add the licence and the readme**

`LICENSE` — the full GNU General Public License v3.0 text, copied verbatim from `mesh_stats/LICENSE`.

`README.md` — what the app is, that it is a port of `mesh_stats`, the build command (`gradle :app:assembleDebug`), the supported connection (BLE plus a debug-only demo device), and the GPL-3.0 notice naming `mesh_stats` as the upstream work.

`docs/third-party-assets.md` — a table with columns `File | Source | Licence`, empty apart from its header. Task 22 onward append to it whenever a drawable is checked in.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "build: project scaffold, manifest and CI

Version chain copied verbatim from mesh-test-android: AGP 9.3.1 with
Gradle 9.7.1, compileSdk 37, Compose BOM 2026.06.01. The chain holds
together only as a whole - protobufs 2.7.26 itself requires compileSdk
37, which rules out AGP 8 entirely.

No Gradle wrapper: CI pins the version through setup-gradle."
```

### Task 2: Theme and shared contract types

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/theme/{Color,Type,Theme}.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/TimeSource.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/TimestampedFrame.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/SortMode.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/SignalScales.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/{LatLon,Direction,PositionSource}.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/settings/GaugeMode.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/nav/Screen.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/SignalScalesTest.kt`

**Interfaces:**
- Consumes: Task 1.
- Produces: every type below. These names are used verbatim by Tasks 3–32 and must not be renamed.

- [ ] **Step 1: Write the failing test**

`app/src/test/kotlin/com/cerocoder/meshrelay/stats/SignalScalesTest.kt`:

```kotlin
package com.cerocoder.meshrelay.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class SignalScalesTest {

    @Test
    fun `fraction maps the scale ends to zero and one`() {
        assertEquals(0f, SignalScales.fraction(-20f, SignalScales.SNR_MIN, SignalScales.SNR_MAX), 0.0001f)
        assertEquals(1f, SignalScales.fraction(15f, SignalScales.SNR_MIN, SignalScales.SNR_MAX), 0.0001f)
    }

    @Test
    fun `fraction maps the midpoint to one half`() {
        // SNR spans 35 dB from -20; the midpoint is -2.5 dB.
        assertEquals(0.5f, SignalScales.fraction(-2.5f, SignalScales.SNR_MIN, SignalScales.SNR_MAX), 0.0001f)
        // RSSI spans 100 dB from -130; the midpoint is -80 dBm.
        assertEquals(0.5f, SignalScales.fraction(-80f, SignalScales.RSSI_MIN, SignalScales.RSSI_MAX), 0.0001f)
    }

    @Test
    fun `fraction clamps values outside the scale`() {
        // A node can genuinely report SNR above +15 dB or RSSI below -130 dBm.
        // Clamping keeps the gauge inside its track instead of drawing off the end.
        assertEquals(0f, SignalScales.fraction(-999f, SignalScales.SNR_MIN, SignalScales.SNR_MAX), 0.0001f)
        assertEquals(1f, SignalScales.fraction(999f, SignalScales.SNR_MIN, SignalScales.SNR_MAX), 0.0001f)
    }

    @Test
    fun `a degenerate scale does not divide by zero`() {
        assertEquals(0f, SignalScales.fraction(5f, 3f, 3f), 0.0001f)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `gradle :app:testDebugUnitTest --tests '*SignalScalesTest*'`
Expected: FAIL — `Unresolved reference: SignalScales`.

- [ ] **Step 3: Write the contract types**

`stats/SignalScales.kt`:

```kotlin
package com.cerocoder.meshrelay.stats

/**
 * Gauge scales, single-sourced.
 *
 * Widened from the terminal tool's -20..+10 dB and -120..-60 dBm to the ranges
 * Meshtastic actually produces. The consequence is deliberate: a 100 dB RSSI
 * span compresses the -120..-60 region where nearly every real packet sits, so
 * the gauge reads less sensitively than the terminal's did. Everything reads
 * these constants, so turning the range into a setting later is a change in one
 * file.
 */
object SignalScales {
    const val SNR_MIN = -20f
    const val SNR_MAX = 15f
    const val RSSI_MIN = -130f
    const val RSSI_MAX = -30f

    /** How long the last-value marker stays lit after a packet arrives. */
    const val FLASH_MILLIS = 500L

    /** Position of [value] along the scale, clamped to 0f..1f. */
    fun fraction(value: Float, min: Float, max: Float): Float {
        val span = max - min
        if (span <= 0f) return 0f
        return ((value - min) / span).coerceIn(0f, 1f)
    }
}
```

`stats/TimeSource.kt`:

```kotlin
package com.cerocoder.meshrelay.stats

/**
 * The only place the clock is read.
 *
 * An interface rather than a direct call because a later stage replays recorded
 * packets, and every age on every screen has to be measured against the replayed
 * epoch rather than the wall clock. One stray System.currentTimeMillis() outside
 * this file makes that impossible to add without hunting it down.
 */
fun interface TimeSource {
    fun nowMillis(): Long
}

object SystemTimeSource : TimeSource {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
```

`stats/TimestampedFrame.kt`:

```kotlin
package com.cerocoder.meshrelay.stats

import org.meshtastic.proto.FromRadio

/**
 * A frame together with when it reached us.
 *
 * The engine consumes a flow of these rather than reading the connection manager
 * directly, so a file-backed source can be added later without touching the core.
 */
data class TimestampedFrame(val rxMillis: Long, val frame: FromRadio)
```

`stats/SortMode.kt`:

```kotlin
package com.cerocoder.meshrelay.stats

/** Ports SORT_MODES, mesh_stats.py:167-174. Labels live in the ui layer. */
enum class SortMode { PACKETS, PERCENT, AVG_SNR, AVG_RSSI, NAME }
```

`stats/model/LatLon.kt`:

```kotlin
package com.cerocoder.meshrelay.stats.model

data class LatLon(val lat: Double, val lon: Double)
```

`stats/model/Direction.kt`:

```kotlin
package com.cerocoder.meshrelay.stats.model

/**
 * Compass bearing from the local node, in 45-degree sectors.
 *
 * UNKNOWN also covers the case where the position obfuscation radius reaches the
 * distance: the uncertainty then exceeds the separation and any direction we
 * printed would be invented.
 */
enum class Direction { N, NE, E, SE, S, SW, W, NW, UNKNOWN }
```

`stats/model/PositionSource.kt`:

```kotlin
package com.cerocoder.meshrelay.stats.model

/** Where a position came from: the node database, or a packet heard live. */
enum class PositionSource { DB, CURRENT }
```

`settings/GaugeMode.kt`:

```kotlin
package com.cerocoder.meshrelay.settings

/**
 * Ports VIS_MODE_SIMPLE / VIS_MODE_COMPLEX, mesh_stats.py:176-182.
 *
 * Lives in settings/ rather than ui/ because AppSettings carries it, and the
 * persistence layer must not depend on the interface layer.
 */
enum class GaugeMode { SIMPLE, COMPLEX }
```

`ui/nav/Screen.kt`:

```kotlin
package com.cerocoder.meshrelay.ui.nav

enum class MainTab { RELAYS, NEIGHBOURS }

/**
 * What a detail screen is about.
 *
 * A relay is a one-byte guess that may match several nodes; a neighbour is a
 * known node. The two detail screens differ in shape because of it, and this
 * type is what carries the difference.
 */
sealed interface DetailSubject {
    data class Relay(val relayByte: Int) : DetailSubject
    data class Neighbour(val nodeNum: Int) : DetailSubject
}

sealed interface Screen {
    data object Devices : Screen
    data class Main(val tab: MainTab) : Screen
    data object Settings : Screen
    data class Detail(val subject: DetailSubject) : Screen
    data class RemoteNode(val nodeNum: Int, val viaRelayByte: Int?) : Screen
}
```

- [ ] **Step 4: Write the theme**

`ui/theme/Color.kt` — a Material 3 colour scheme. Two semantic colours beyond the scheme, used by the gauges and referenced by name in Task 16:

```kotlin
package com.cerocoder.meshrelay.ui.theme

import androidx.compose.ui.graphics.Color

val SnrTrack = Color(0xFF2E7D32)
val SnrMarker = Color(0xFF81C784)
val RssiTrack = Color(0xFF1565C0)
val RssiMarker = Color(0xFF64B5F6)
val FlashMarker = Color(0xFFFFC107)
```

`ui/theme/Type.kt` — declares `val MeshRelayTypography: Typography`, a Material 3 typography whose `bodySmall` is `FontFamily.Monospace`. Node identifiers and hexadecimal relay bytes are rendered with it so columns of them line up; `Theme.kt` below refers to it by this exact name.

`ui/theme/Theme.kt`:

```kotlin
package com.cerocoder.meshrelay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

@Composable
fun MeshRelayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(),
        typography = MeshRelayTypography,
        content = content,
    )
}
```

- [ ] **Step 5: Run the test**

Run: `gradle :app:testDebugUnitTest --tests '*SignalScalesTest*'`
Expected: PASS, four tests.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: theme and shared contract types

Every type here is named by later tasks and must not be renamed. GaugeMode
sits in settings/ rather than ui/ because AppSettings carries it and the
persistence layer must not depend on the interface layer."
```

---

# Round 1 — nine tasks, dispatchable in parallel

### Task 3: Geography and age buckets *(Sonnet)*

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/Geo.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/AgeBucket.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/GeoTest.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/AgeBucketTest.kt`

**Interfaces:**
- Consumes: `LatLon`, `Direction` (Task 2).
- Produces: `Geo.haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double`, `Geo.bearingDegrees(from: LatLon, to: LatLon): Double`, `Geo.directionOf(bearingDeg: Double): Direction`, `Geo.obfuscationRadiusMeters(precisionBits: Int?): Double?`, `Geo.lastByteOfNodeNum(nodeNum: Int): Int`, `AgeBucket.of(elapsedMillis: Long): AgeBucket` with constants `M1, M5, M30, H1, H12, D1, W1, Y1, UNKNOWN`.

Ports `mesh_stats.py:184-222`, `:481-487` and the age table at `:1775-1794`.

- [ ] **Step 1: Write the failing tests**

`GeoTest.kt`:

```kotlin
package com.cerocoder.meshrelay.stats

import com.cerocoder.meshrelay.stats.model.Direction
import com.cerocoder.meshrelay.stats.model.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private val MADRID = LatLon(40.4168, -3.7038)
private val GETAFE = LatLon(40.3083, -3.7325)
private val TOLEDO = LatLon(39.8628, -4.0273)

class GeoTest {

    @Test
    fun `haversine matches known distances in the local mesh`() {
        assertEquals(12.30726, Geo.haversineKm(MADRID.lat, MADRID.lon, GETAFE.lat, GETAFE.lon), 0.001)
        assertEquals(67.46109, Geo.haversineKm(MADRID.lat, MADRID.lon, TOLEDO.lat, TOLEDO.lon), 0.001)
    }

    @Test
    fun `haversine matches one degree of longitude at the equator`() {
        assertEquals(111.19493, Geo.haversineKm(0.0, 0.0, 0.0, 1.0), 0.001)
    }

    @Test
    fun `haversine of a point with itself is zero`() {
        assertEquals(0.0, Geo.haversineKm(MADRID.lat, MADRID.lon, MADRID.lat, MADRID.lon), 1e-9)
    }

    @Test
    fun `bearing points south toward Getafe and south west toward Toledo`() {
        assertEquals(191.4047, Geo.bearingDegrees(MADRID, GETAFE), 0.001)
        assertEquals(204.1605, Geo.bearingDegrees(MADRID, TOLEDO), 0.001)
    }

    @Test
    fun `bearing is normalised into zero until three hundred and sixty`() {
        val west = Geo.bearingDegrees(MADRID, LatLon(MADRID.lat, MADRID.lon - 1.0))
        assert(west in 0.0..360.0) { "bearing must be normalised, was $west" }
        assertEquals(270.0, west, 0.5)
    }

    @Test
    fun `direction sectors are centred on their compass point`() {
        assertEquals(Direction.N, Geo.directionOf(0.0))
        assertEquals(Direction.N, Geo.directionOf(22.4))
        assertEquals(Direction.NE, Geo.directionOf(22.5))
        assertEquals(Direction.E, Geo.directionOf(67.5))
        assertEquals(Direction.S, Geo.directionOf(180.0))
        assertEquals(Direction.N, Geo.directionOf(337.5))
        assertEquals(Direction.N, Geo.directionOf(359.9))
    }

    @Test
    fun `direction accepts bearings outside one full turn`() {
        assertEquals(Direction.N, Geo.directionOf(720.0))
        assertEquals(Direction.N, Geo.directionOf(-0.1))
    }

    @Test
    fun `obfuscation radius halves with every bit`() {
        assertEquals(2918.1865234375, Geo.obfuscationRadiusMeters(13)!!, 1e-9)
        assertEquals(364.7733154296875, Geo.obfuscationRadiusMeters(16)!!, 1e-9)
    }

    @Test
    fun `obfuscation radius is unknown when precision is absent or zero`() {
        assertNull(Geo.obfuscationRadiusMeters(null))
        assertNull(Geo.obfuscationRadiusMeters(0))
        assertNull(Geo.obfuscationRadiusMeters(-1))
    }

    @Test
    fun `last byte of a node number is its low byte`() {
        assertEquals(0xa4, Geo.lastByteOfNodeNum(0x9e75f1a4.toInt()))
        assertEquals(0x01, Geo.lastByteOfNodeNum(0x00000001))
    }

    @Test
    fun `a low byte of zero reads as ff`() {
        // Not arithmetic - a firmware convention. The relay field never carries 0
        // to mean a node, so a node whose number ends in 00 identifies itself as ff.
        // This single line decides which nodes are offered as candidates for every
        // relay in the application.
        assertEquals(0xFF, Geo.lastByteOfNodeNum(0x9e75f100.toInt()))
        assertEquals(0xFF, Geo.lastByteOfNodeNum(0))
    }
}
```

`AgeBucketTest.kt`:

```kotlin
package com.cerocoder.meshrelay.stats

import org.junit.Assert.assertEquals
import org.junit.Test

private const val SECOND = 1_000L
private const val MINUTE = 60 * SECOND
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

class AgeBucketTest {

    @Test
    fun `each boundary belongs to the coarser bucket`() {
        assertEquals(AgeBucket.M1, AgeBucket.of(0))
        assertEquals(AgeBucket.M1, AgeBucket.of(MINUTE - 1))
        assertEquals(AgeBucket.M5, AgeBucket.of(MINUTE))
        assertEquals(AgeBucket.M5, AgeBucket.of(5 * MINUTE - 1))
        assertEquals(AgeBucket.M30, AgeBucket.of(5 * MINUTE))
        assertEquals(AgeBucket.M30, AgeBucket.of(30 * MINUTE - 1))
        assertEquals(AgeBucket.H1, AgeBucket.of(30 * MINUTE))
        assertEquals(AgeBucket.H1, AgeBucket.of(HOUR - 1))
        assertEquals(AgeBucket.H12, AgeBucket.of(HOUR))
        assertEquals(AgeBucket.H12, AgeBucket.of(12 * HOUR - 1))
        assertEquals(AgeBucket.D1, AgeBucket.of(12 * HOUR))
        assertEquals(AgeBucket.D1, AgeBucket.of(DAY - 1))
        assertEquals(AgeBucket.W1, AgeBucket.of(DAY))
        assertEquals(AgeBucket.W1, AgeBucket.of(7 * DAY - 1))
        assertEquals(AgeBucket.Y1, AgeBucket.of(7 * DAY))
        assertEquals(AgeBucket.Y1, AgeBucket.of(365 * DAY - 1))
        assertEquals(AgeBucket.UNKNOWN, AgeBucket.of(365 * DAY))
    }

    @Test
    fun `a negative age is unknown rather than fresh`() {
        // A node's clock can be ahead of the phone's, which makes lastHeard land in
        // the future. Reporting that as "under a minute" would present a stale
        // position as the freshest thing on screen.
        assertEquals(AgeBucket.UNKNOWN, AgeBucket.of(-1))
        assertEquals(AgeBucket.UNKNOWN, AgeBucket.of(-DAY))
    }
}
```

- [ ] **Step 2: Run them and watch them fail**

Run: `gradle :app:testDebugUnitTest --tests '*GeoTest*' --tests '*AgeBucketTest*'`
Expected: FAIL — `Unresolved reference: Geo`.

- [ ] **Step 3: Write the implementation**

`stats/Geo.kt`:

```kotlin
package com.cerocoder.meshrelay.stats

import com.cerocoder.meshrelay.stats.model.Direction
import com.cerocoder.meshrelay.stats.model.LatLon
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Ports haversine_distance, obfuscation_radius_meters, bearing_to_direction and
 *  get_last_byte_of_node_num, mesh_stats.py:184-222 and :481-487. */
object Geo {

    private const val EARTH_RADIUS_KM = 6371.0

    /**
     * Meshtastic's position obfuscation: the radius halves with every bit of
     * precision the sender chose to keep. The constant is the firmware's.
     */
    private const val OBFUSCATION_BASE_METERS = 23905784.0

    private val SECTORS = arrayOf(
        Direction.N, Direction.NE, Direction.E, Direction.SE,
        Direction.S, Direction.SW, Direction.W, Direction.NW,
    )

    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
            cos(lat1Rad) * cos(lat2Rad) * sin(dLon / 2).pow(2)
        return EARTH_RADIUS_KM * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing from [from] to [to], degrees clockwise from north. */
    fun bearingDegrees(from: LatLon, to: LatLon): Double {
        val dLon = Math.toRadians(to.lon - from.lon)
        val lat1 = Math.toRadians(from.lat)
        val lat2 = Math.toRadians(to.lat)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return normalise(Math.toDegrees(atan2(y, x)))
    }

    fun directionOf(bearingDeg: Double): Direction {
        val sector = ((normalise(bearingDeg) + 22.5) % 360.0 / 45.0).toInt()
        return SECTORS[sector]
    }

    fun obfuscationRadiusMeters(precisionBits: Int?): Double? {
        if (precisionBits == null || precisionBits <= 0) return null
        return OBFUSCATION_BASE_METERS / 2.0.pow(precisionBits)
    }

    /**
     * The relay field carries only the low byte of a node number, and never 0 -
     * the firmware substitutes ff. Reproduced exactly: this line decides which
     * database nodes are offered as candidates for every relay in the application.
     */
    fun lastByteOfNodeNum(nodeNum: Int): Int {
        val lastByte = nodeNum and 0xFF
        return if (lastByte == 0) 0xFF else lastByte
    }

    private fun normalise(degrees: Double): Double = (degrees % 360.0 + 360.0) % 360.0
}
```

`stats/AgeBucket.kt`:

```kotlin
package com.cerocoder.meshrelay.stats

/**
 * How old a piece of information is, in the grades the terminal tool shows.
 *
 * Deliberately coarse: an exact age would suggest the position is measured to the
 * second, when it was in fact learned from a broadcast at some point in the past.
 * Ports the table at mesh_stats.py:1775-1794.
 */
enum class AgeBucket {
    M1, M5, M30, H1, H12, D1, W1, Y1, UNKNOWN;

    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR

        fun of(elapsedMillis: Long): AgeBucket = when {
            // A node's clock can run ahead of the phone's, putting lastHeard in the
            // future. Calling that "fresh" would rank the stalest data first.
            elapsedMillis < 0 -> UNKNOWN
            elapsedMillis < MINUTE -> M1
            elapsedMillis < 5 * MINUTE -> M5
            elapsedMillis < 30 * MINUTE -> M30
            elapsedMillis < HOUR -> H1
            elapsedMillis < 12 * HOUR -> H12
            elapsedMillis < DAY -> D1
            elapsedMillis < 7 * DAY -> W1
            elapsedMillis < 365 * DAY -> Y1
            else -> UNKNOWN
        }
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `gradle :app:testDebugUnitTest --tests '*GeoTest*' --tests '*AgeBucketTest*'`
Expected: PASS, 13 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/stats/Geo.kt \
        app/src/main/kotlin/com/cerocoder/meshrelay/stats/AgeBucket.kt \
        app/src/test/kotlin/com/cerocoder/meshrelay/stats/GeoTest.kt \
        app/src/test/kotlin/com/cerocoder/meshrelay/stats/AgeBucketTest.kt
git commit -m "feat(stats): geography helpers and age buckets

lastByteOfNodeNum reproduces a firmware convention, not arithmetic: a low
byte of 0 reads as 0xff. It decides candidate relays everywhere, so it has
its own test."
```

### Task 4: Signal statistics *(Sonnet)*

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/SignalStats.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/SignalHistory.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/model/SignalStatsTest.kt`

**Interfaces:**
- Consumes: nothing beyond Task 1.
- Produces: `SignalStats(minVal: Float, maxVal: Float, sumVal: Double, count: Int, lastVal: Float)` with `avg: Float`, `hasData: Boolean`, `plus(value: Float): SignalStats`, `SignalStats.EMPTY`; `Sample(atMillis: Long, value: Float)`; `SignalHistory(stats: SignalStats, samples: List<Sample>)` with `plus(atMillis: Long, value: Float): SignalHistory` and `SignalHistory.MAX_SAMPLES = 500`.

Ports `SignalStats` and `SignalHistoryStat`, `mesh_stats.py:223-266`.

- [ ] **Step 1: Write the failing test**

`SignalStatsTest.kt`:

```kotlin
package com.cerocoder.meshrelay.stats.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalStatsTest {

    @Test
    fun `an empty instance reports no data`() {
        assertFalse(SignalStats.EMPTY.hasData)
        assertEquals(0, SignalStats.EMPTY.count)
        assertEquals(0f, SignalStats.EMPTY.avg, 0.0001f)
    }

    @Test
    fun `the first value becomes minimum maximum and last at once`() {
        val stats = SignalStats.EMPTY.plus(-7.5f)
        assertTrue(stats.hasData)
        assertEquals(-7.5f, stats.minVal, 0.0001f)
        assertEquals(-7.5f, stats.maxVal, 0.0001f)
        assertEquals(-7.5f, stats.lastVal, 0.0001f)
        assertEquals(-7.5f, stats.avg, 0.0001f)
    }

    @Test
    fun `minimum maximum average and last track a sequence`() {
        val stats = SignalStats.EMPTY.plus(-10f).plus(0f).plus(-5f)
        assertEquals(-10f, stats.minVal, 0.0001f)
        assertEquals(0f, stats.maxVal, 0.0001f)
        assertEquals(-5f, stats.lastVal, 0.0001f)
        assertEquals(-5f, stats.avg, 0.0001f)
        assertEquals(3, stats.count)
    }

    @Test
    fun `updating returns a new instance and leaves the old one alone`() {
        val first = SignalStats.EMPTY.plus(1f)
        val second = first.plus(9f)
        assertEquals(1, first.count)
        assertEquals(2, second.count)
    }

    @Test
    fun `the average does not drift over a long session`() {
        // The sum is a Double for exactly this reason: a Float accumulator over tens
        // of thousands of samples visibly pulls the average away from the true value.
        var stats = SignalStats.EMPTY
        repeat(100_000) { stats = stats.plus(-93.7f) }
        assertEquals(-93.7f, stats.avg, 0.0005f)
    }

    @Test
    fun `history keeps its samples in arrival order`() {
        val history = SignalHistory().plus(1_000L, -5f).plus(2_000L, -6f)
        assertEquals(listOf(Sample(1_000L, -5f), Sample(2_000L, -6f)), history.samples)
        assertEquals(2, history.stats.count)
        assertEquals(-6f, history.stats.lastVal, 0.0001f)
    }

    @Test
    fun `history drops the oldest sample past the cap but keeps the statistics`() {
        // The cap exists because a survey runs for hours: the terminal tool's list is
        // unbounded, which on a phone is a leak. Statistics must survive the eviction,
        // otherwise the minimum silently rises as old samples fall off the end.
        var history = SignalHistory()
        repeat(SignalHistory.MAX_SAMPLES + 10) { i -> history = history.plus(i.toLong(), i.toFloat()) }

        assertEquals(SignalHistory.MAX_SAMPLES, history.samples.size)
        assertEquals(10L, history.samples.first().atMillis)
        assertEquals(SignalHistory.MAX_SAMPLES + 9, history.stats.count)
        assertEquals(0f, history.stats.minVal, 0.0001f)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `gradle :app:testDebugUnitTest --tests '*SignalStatsTest*'`
Expected: FAIL — `Unresolved reference: SignalStats`.

- [ ] **Step 3: Write the implementation**

`stats/model/SignalStats.kt`:

```kotlin
package com.cerocoder.meshrelay.stats.model

/**
 * Running statistics for one signal metric. Ports mesh_stats.py:223-251.
 *
 * The sum is a Double where the original used a Python float: a survey collects
 * tens of thousands of samples, and a Float accumulator drifts far enough to be
 * visible in the average.
 */
data class SignalStats(
    val minVal: Float = Float.POSITIVE_INFINITY,
    val maxVal: Float = Float.NEGATIVE_INFINITY,
    val sumVal: Double = 0.0,
    val count: Int = 0,
    val lastVal: Float = 0f,
) {
    val avg: Float get() = if (count > 0) (sumVal / count).toFloat() else 0f

    val hasData: Boolean get() = count > 0

    fun plus(value: Float): SignalStats = SignalStats(
        minVal = minOf(minVal, value),
        maxVal = maxOf(maxVal, value),
        sumVal = sumVal + value,
        count = count + 1,
        lastVal = value,
    )

    companion object {
        val EMPTY = SignalStats()
    }
}
```

`stats/model/SignalHistory.kt`:

```kotlin
package com.cerocoder.meshrelay.stats.model

data class Sample(val atMillis: Long, val value: Float)

/**
 * Statistics plus the samples behind them. Ports SignalHistoryStat,
 * mesh_stats.py:254-266.
 *
 * The sample list is capped where the original's grows without bound. A phone
 * left collecting for an afternoon would otherwise accumulate one entry per
 * packet per metric per node. Statistics are kept whole across eviction: they are
 * folded, not recomputed from the surviving window, so the session minimum does
 * not creep upward as old samples fall off.
 */
data class SignalHistory(
    val stats: SignalStats = SignalStats.EMPTY,
    val samples: List<Sample> = emptyList(),
) {
    fun plus(atMillis: Long, value: Float): SignalHistory {
        val grown = samples + Sample(atMillis, value)
        val trimmed = if (grown.size > MAX_SAMPLES) grown.subList(grown.size - MAX_SAMPLES, grown.size) else grown
        return SignalHistory(stats.plus(value), trimmed)
    }

    companion object {
        const val MAX_SAMPLES = 500
    }
}
```

- [ ] **Step 4: Run the test**

Run: `gradle :app:testDebugUnitTest --tests '*SignalStatsTest*'`
Expected: PASS, 7 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/SignalStats.kt \
        app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/SignalHistory.kt \
        app/src/test/kotlin/com/cerocoder/meshrelay/stats/model/SignalStatsTest.kt
git commit -m "feat(stats): signal statistics and bounded history

Two deviations from the original, both deliberate: a Double accumulator so a
long session's average does not drift, and a 500-sample cap so an afternoon
of collecting does not leak."
```

### Task 5: Position reports and history *(Sonnet)*

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/PositionReport.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/PositionHistory.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/model/PositionTest.kt`

**Interfaces:**
- Consumes: nothing beyond Task 1.
- Produces: `PositionReport(atMillis: Long, latitude: Double?, longitude: Double?, altitude: Int?, precisionBits: Int?)` with `hasCoordinates`, `hasAltitude`, and `PositionReport.fromProto(position: Position, atMillis: Long): PositionReport`; `PositionHistory(nodeNum: Int, reports: List<PositionReport>)` with `last`, `best`, `plus(report: PositionReport): PositionHistory`, `PositionHistory.MAX_REPORTS = 100`.

Ports `PositionMessage` and `NodePositionHistory`, `mesh_stats.py:270-343`.

Field presence, verified against the schema: `latitude_i`, `longitude_i`, `altitude` and `altitude_hae` are declared `optional` and therefore arrive as `Int?`; `precision_bits` is not optional and arrives as `Int` defaulting to `0`, so `0` must be read as "absent".

- [ ] **Step 1: Write the failing test**

`PositionTest.kt`:

```kotlin
package com.cerocoder.meshrelay.stats.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.Position

private fun report(
    at: Long = 1_000L,
    lat: Double? = 40.4168,
    lon: Double? = -3.7038,
    alt: Int? = 667,
    bits: Int? = null,
) = PositionReport(at, lat, lon, alt, bits)

class PositionTest {

    @Test
    fun `scaled integer coordinates become degrees`() {
        val proto = Position(latitude_i = 404168000, longitude_i = -37038000, altitude = 667)
        val parsed = PositionReport.fromProto(proto, atMillis = 5_000L)

        assertEquals(40.4168, parsed.latitude!!, 1e-9)
        assertEquals(-3.7038, parsed.longitude!!, 1e-9)
        assertEquals(667, parsed.altitude)
        assertEquals(5_000L, parsed.atMillis)
    }

    @Test
    fun `height above the ellipsoid wins over height above sea level`() {
        val proto = Position(latitude_i = 1, longitude_i = 1, altitude = 600, altitude_hae = 655)
        assertEquals(655, PositionReport.fromProto(proto, 0L).altitude)
    }

    @Test
    fun `sea level altitude is used when there is no ellipsoid height`() {
        val proto = Position(latitude_i = 1, longitude_i = 1, altitude = 600)
        assertEquals(600, PositionReport.fromProto(proto, 0L).altitude)
    }

    @Test
    fun `absent coordinates stay absent rather than becoming zero`() {
        // latitude_i is an optional field: null means the sender withheld its
        // position. Reading that as 0.0 would place the node in the Gulf of Guinea
        // and give it a plausible-looking distance.
        val parsed = PositionReport.fromProto(Position(), 0L)
        assertNull(parsed.latitude)
        assertNull(parsed.longitude)
        assertNull(parsed.altitude)
        assertFalse(parsed.hasCoordinates)
        assertFalse(parsed.hasAltitude)
    }

    @Test
    fun `zero precision bits means absent, not full precision`() {
        // precision_bits is not an optional field, so 0 is what an unset value looks
        // like. Treating it as a real precision would make the obfuscation radius
        // enormous and hide every direction behind UNKNOWN.
        assertNull(PositionReport.fromProto(Position(latitude_i = 1, longitude_i = 1), 0L).precisionBits)
        assertEquals(13, PositionReport.fromProto(Position(latitude_i = 1, longitude_i = 1, precision_bits = 13), 0L).precisionBits)
    }

    @Test
    fun `last is the newest report`() {
        val history = PositionHistory(nodeNum = 1)
            .plus(report(at = 1_000L))
            .plus(report(at = 2_000L))
        assertEquals(2_000L, history.last!!.atMillis)
    }

    @Test
    fun `best prefers the newest report carrying both coordinates and altitude`() {
        val history = PositionHistory(nodeNum = 1)
            .plus(report(at = 1_000L, alt = 600))
            .plus(report(at = 2_000L, alt = null))
        assertEquals(1_000L, history.best!!.atMillis)
    }

    @Test
    fun `best is the newest report when it is already complete`() {
        val history = PositionHistory(nodeNum = 1)
            .plus(report(at = 1_000L, alt = 600))
            .plus(report(at = 2_000L, alt = 700))
        assertEquals(2_000L, history.best!!.atMillis)
    }

    @Test
    fun `best is absent when no report has both coordinates and altitude`() {
        // A quirk of the original, reproduced on purpose: a report with coordinates
        // but no altitude does not count, and the caller falls back to the node
        // database instead. Callers rely on this to label the position source, so
        // "fixing" it here would make CUR and DB disagree with the terminal tool.
        val history = PositionHistory(nodeNum = 1)
            .plus(report(at = 1_000L, alt = null))
            .plus(report(at = 2_000L, alt = null))
        assertNull(history.best)
    }

    @Test
    fun `history is bounded and keeps the newest reports`() {
        var history = PositionHistory(nodeNum = 1)
        repeat(PositionHistory.MAX_REPORTS + 5) { i -> history = history.plus(report(at = i.toLong())) }
        assertEquals(PositionHistory.MAX_REPORTS, history.reports.size)
        assertEquals(5L, history.reports.first().atMillis)
        assertTrue(history.last!!.atMillis == (PositionHistory.MAX_REPORTS + 4).toLong())
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `gradle :app:testDebugUnitTest --tests '*PositionTest*'`
Expected: FAIL — `Unresolved reference: PositionReport`.

- [ ] **Step 3: Write the implementation**

`stats/model/PositionReport.kt`:

```kotlin
package com.cerocoder.meshrelay.stats.model

import org.meshtastic.proto.Position

/**
 * One position as it was heard. Ports PositionMessage, mesh_stats.py:270-307.
 */
data class PositionReport(
    val atMillis: Long,
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Int?,
    val precisionBits: Int?,
) {
    val hasCoordinates: Boolean get() = latitude != null && longitude != null
    val hasAltitude: Boolean get() = altitude != null

    companion object {
        /** Coordinates travel as integers scaled by ten million. */
        private const val COORD_SCALE = 1e-7

        fun fromProto(position: Position, atMillis: Long): PositionReport = PositionReport(
            atMillis = atMillis,
            // Multiplied in Double: at this scale a Float loses roughly ten metres.
            latitude = position.latitude_i?.let { it * COORD_SCALE },
            longitude = position.longitude_i?.let { it * COORD_SCALE },
            // altitude_hae is height above the WGS84 ellipsoid and is preferred when
            // present; altitude is the older mean-sea-level field.
            altitude = position.altitude_hae ?: position.altitude,
            // precision_bits is not an optional field, so 0 is what "unset" looks
            // like on the wire and must not be read as a real precision.
            precisionBits = position.precision_bits.takeIf { it != 0 },
        )
    }
}
```

`stats/model/PositionHistory.kt`:

```kotlin
package com.cerocoder.meshrelay.stats.model

/**
 * Positions heard from one node. Ports NodePositionHistory, mesh_stats.py:310-343.
 */
data class PositionHistory(
    val nodeNum: Int,
    val reports: List<PositionReport> = emptyList(),
) {
    val last: PositionReport? get() = reports.lastOrNull()

    /**
     * The newest report carrying both coordinates and altitude.
     *
     * Null when no report has both - even when reports with coordinates alone
     * exist. That is a quirk of the original and is reproduced deliberately:
     * callers fall back to the node database in that case, and the position source
     * they then label (DB rather than CUR) is what the terminal tool shows.
     */
    val best: PositionReport?
        get() = reports.lastOrNull { it.hasCoordinates && it.hasAltitude }

    fun plus(report: PositionReport): PositionHistory {
        val grown = reports + report
        val trimmed = if (grown.size > MAX_REPORTS) grown.subList(grown.size - MAX_REPORTS, grown.size) else grown
        return copy(reports = trimmed)
    }

    companion object {
        const val MAX_REPORTS = 100
    }
}
```

- [ ] **Step 4: Run the test**

Run: `gradle :app:testDebugUnitTest --tests '*PositionTest*'`
Expected: PASS, 10 tests.

If a named argument such as `latitude_i` is rejected, read the Wire-generated `Position` class and use the generated name; do not change the assertion.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/Position*.kt \
        app/src/test/kotlin/com/cerocoder/meshrelay/stats/model/PositionTest.kt
git commit -m "feat(stats): position reports and bounded history

Reproduces the original's 'best position' quirk on purpose - a report with
coordinates but no altitude does not count, and the caller falls back to the
node database. Callers label the position source from that fallback."
```

### Task 6: Settings repository *(Sonnet)*

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/settings/{LanguageOption,AppSettings,SettingsRepository}.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/NodeId.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/settings/SettingsRepositoryTest.kt`

**Interfaces:**
- Consumes: `GaugeMode` (Task 2), `SortMode` (Task 2).
- Produces: `LanguageOption { SYSTEM, EN, ES }`; `AppSettings(language, gaugeMode, defaultSortMode, meshviewUrl, keepScreenOn, backgroundCollection)`; `SettingsRepository(store: SettingsStore, ioScope: CoroutineScope)` with `settings: StateFlow<AppSettings>`, `skippedRelayNodes: StateFlow<Set<Int>>`, `update(transform: (AppSettings) -> AppSettings)`, `addSkippedRelayNode(nodeNum: Int)`, `removeSkippedRelayNode(nodeNum: Int)`, `clearSkippedForRelay(relayByte: Int)`; `interface SettingsStore` with `AndroidSettingsStore(context: Context)`; and, in `app/src/main/kotlin/com/cerocoder/meshrelay/stats/NodeId.kt`, `object NodeId { fun parse(value: String): Int?; fun format(nodeNum: Int): String }`.

`NodeId` lives in `stats/` rather than `settings/` because both the settings layer and the interface layer need it, and `ui/` must not depend on `settings/` for a formatter.

`SharedPreferences` sits behind `SettingsStore` so the repository is testable on the JVM without Robolectric. The interface is three methods; the Android implementation is a thin wrapper.

- [ ] **Step 1: Write the failing test**

`SettingsRepositoryTest.kt`:

```kotlin
package com.cerocoder.meshrelay.settings

import com.cerocoder.meshrelay.stats.SortMode
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeStore(
    private val strings: MutableMap<String, String> = mutableMapOf(),
    private val bools: MutableMap<String, Boolean> = mutableMapOf(),
    private val sets: MutableMap<String, Set<String>> = mutableMapOf(),
) : SettingsStore {
    var writes = 0
        private set

    override fun getString(key: String, default: String) = strings[key] ?: default
    override fun getBoolean(key: String, default: Boolean) = bools[key] ?: default
    override fun getStringSet(key: String, default: Set<String>) = sets[key] ?: default

    override fun put(strings: Map<String, String>, bools: Map<String, Boolean>, sets: Map<String, Set<String>>) {
        writes++
        this.strings.putAll(strings)
        this.bools.putAll(bools)
        this.sets.putAll(sets)
    }
}

class SettingsRepositoryTest {

    private fun repo(store: SettingsStore, scope: TestScope) = SettingsRepository(store, scope)

    @Test
    fun `defaults match the terminal tool and the local mesh`() = runTest(StandardTestDispatcher()) {
        val settings = repo(FakeStore(), this).settings.value
        assertEquals(LanguageOption.SYSTEM, settings.language)
        assertEquals(GaugeMode.SIMPLE, settings.gaugeMode)
        assertEquals(SortMode.PACKETS, settings.defaultSortMode)
        assertEquals("https://meshview.meshtastic.es", settings.meshviewUrl)
        assertEquals(false, settings.keepScreenOn)
        assertEquals(true, settings.backgroundCollection)
    }

    @Test
    fun `an update is visible immediately, before it reaches storage`() = runTest(StandardTestDispatcher()) {
        // The flow must not wait on disk: a settings toggle that lags behind the tap
        // by a write reads as a broken control.
        val store = FakeStore()
        val subject = repo(store, this)
        subject.update { it.copy(gaugeMode = GaugeMode.COMPLEX) }
        assertEquals(GaugeMode.COMPLEX, subject.settings.value.gaugeMode)
        advanceUntilIdle()
        assertEquals(1, store.writes)
    }

    @Test
    fun `skipped nodes round trip through storage`() = runTest(StandardTestDispatcher()) {
        val store = FakeStore()
        val first = repo(store, this)
        first.addSkippedRelayNode(0x9e75f1a4.toInt())
        advanceUntilIdle()

        val reopened = repo(store, this)
        assertTrue(reopened.skippedRelayNodes.value.contains(0x9e75f1a4.toInt()))
    }

    @Test
    fun `skipped nodes are stored in the notation the terminal tool accepts`() = runTest(StandardTestDispatcher()) {
        // --skip-relay takes !xxxxxxxx, and a value should be movable between the
        // two tools by hand.
        val store = FakeStore()
        repo(store, this).addSkippedRelayNode(0x9e75f1a4.toInt())
        advanceUntilIdle()
        assertEquals(setOf("!9e75f1a4"), store.getStringSet("skipped_relay_nodes", emptySet()))
    }

    @Test
    fun `clearing by relay byte removes only nodes sharing that low byte`() = runTest(StandardTestDispatcher()) {
        val subject = repo(FakeStore(), this)
        subject.addSkippedRelayNode(0x9e75f1a4.toInt())
        subject.addSkippedRelayNode(0x11223344)
        subject.clearSkippedForRelay(0xa4)
        assertEquals(setOf(0x11223344), subject.skippedRelayNodes.value)
    }

    @Test
    fun `node identifiers parse with and without the leading mark`() {
        assertEquals(0x9e75f1a4.toInt(), NodeId.parse("!9e75f1a4"))
        assertEquals(0x9e75f1a4.toInt(), NodeId.parse("9e75f1a4"))
        assertEquals(0x9e75f1a4.toInt(), NodeId.parse("  !9E75F1A4 "))
        assertEquals("!9e75f1a4", NodeId.format(0x9e75f1a4.toInt()))
    }

    @Test
    fun `an unparseable identifier is rejected rather than guessed`() {
        // Stored values can come from a hand-edited preference file.
        assertNull(NodeId.parse("not a node"))
        assertNull(NodeId.parse(""))
        assertNull(NodeId.parse("!"))
        assertNull(NodeId.parse("9e75f1a4ff"))
    }

    @Test
    fun `a corrupt stored entry is dropped without losing the rest`() {
        val store = FakeStore(sets = mutableMapOf("skipped_relay_nodes" to setOf("!9e75f1a4", "rubbish")))
        val scope = TestScope(StandardTestDispatcher())
        assertEquals(setOf(0x9e75f1a4.toInt()), SettingsRepository(store, scope).skippedRelayNodes.value)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `gradle :app:testDebugUnitTest --tests '*SettingsRepositoryTest*'`
Expected: FAIL — `Unresolved reference: SettingsStore`.

- [ ] **Step 3: Write the implementation**

`settings/LanguageOption.kt`:

```kotlin
package com.cerocoder.meshrelay.settings

enum class LanguageOption { SYSTEM, EN, ES }
```

`settings/AppSettings.kt`:

```kotlin
package com.cerocoder.meshrelay.settings

import com.cerocoder.meshrelay.stats.SortMode

data class AppSettings(
    val language: LanguageOption = LanguageOption.SYSTEM,
    val gaugeMode: GaugeMode = GaugeMode.SIMPLE,
    val defaultSortMode: SortMode = SortMode.PACKETS,
    /** The Spanish community instance, used throughout this mesh. */
    val meshviewUrl: String = "https://meshview.meshtastic.es",
    val keepScreenOn: Boolean = false,
    val backgroundCollection: Boolean = true,
)
```

`settings/SettingsRepository.kt` — write `SettingsStore`, `AndroidSettingsStore`, the node-id helpers, and the repository. Key points the tests pin down:

- The repository reads the store **once** in its constructor into `MutableStateFlow`s. Nothing on a screen ever touches disk.
- `update` mutates the flow synchronously and launches the write on `ioScope`.
- Skipped nodes are persisted as a `Set<String>` of `!xxxxxxxx` under the key `skipped_relay_nodes`; unparseable entries are dropped on read rather than throwing.
- `NodeId.parse` accepts an optional `!`, is case-insensitive, trims whitespace, requires exactly eight hexadecimal digits, and returns `null` otherwise. Parse through `Long` and narrow with `toInt()`: `"9e75f1a4"` overflows a signed `Int` and `Integer.parseInt` would throw.
- `NodeId.format` produces `"!%08x"` in lower case.
- `AndroidSettingsStore(context)` wraps `context.getSharedPreferences("mesh_relay", Context.MODE_PRIVATE)`; `put` applies all three maps in one `edit { }`.
- Preference keys: `language`, `gauge_mode`, `default_sort_mode`, `meshview_url`, `keep_screen_on`, `background_collection`, `skipped_relay_nodes`. Enums are stored by `name` and read with a `runCatching { enumValueOf(...) }.getOrDefault(...)` so a renamed constant degrades to the default instead of crashing at launch.

- [ ] **Step 4: Run the test**

Run: `gradle :app:testDebugUnitTest --tests '*SettingsRepositoryTest*'`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/settings/ \
        app/src/test/kotlin/com/cerocoder/meshrelay/settings/
git commit -m "feat(settings): preferences and the relay skip-list

SharedPreferences behind a SettingsStore interface so the repository is
testable on the JVM. Skipped nodes are stored as !xxxxxxxx, the same notation
--skip-relay takes, so a value can be moved between the two tools by hand."
```

### Task 7: String resources, English and Spanish *(Sonnet)*

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values-es/strings.xml`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/StringsParityTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: **the string contract for Tasks 22–31.** No screen may add a key; a screen needing one that is missing is a defect in this task, to be fixed here.

- [ ] **Step 1: Write the failing parity test**

`StringsParityTest.kt`:

```kotlin
package com.cerocoder.meshrelay

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A missing Spanish key does not fail the build - Android silently falls back to
 * English, and the gap is only found by someone using the app in Spanish. This
 * test is the only thing that notices.
 */
class StringsParityTest {

    private fun keys(path: String, tag: String): Set<String> {
        val file = File(path)
        assertTrue("missing resource file: $path", file.exists())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName(tag)
        return (0 until nodes.length)
            .map { nodes.item(it).attributes.getNamedItem("name").nodeValue }
            .toSet()
    }

    private val english = "src/main/res/values/strings.xml"
    private val spanish = "src/main/res/values-es/strings.xml"

    @Test
    fun `both locales define the same strings`() {
        assertEquals(emptySet<String>(), keys(english, "string") - keys(spanish, "string"))
        assertEquals(emptySet<String>(), keys(spanish, "string") - keys(english, "string"))
    }

    @Test
    fun `both locales define the same plurals`() {
        assertEquals(emptySet<String>(), keys(english, "plurals") - keys(spanish, "plurals"))
        assertEquals(emptySet<String>(), keys(spanish, "plurals") - keys(english, "plurals"))
    }

    @Test
    fun `format placeholders match between locales`() {
        // A translated string that drops a %1$s crashes at format time, in Spanish
        // only, on the one screen nobody tested in Spanish.
        val placeholder = Regex("""%\d+\$[sd]""")
        fun values(path: String): Map<String, String> {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
            val nodes = doc.getElementsByTagName("string")
            return (0 until nodes.length).associate {
                nodes.item(it).attributes.getNamedItem("name").nodeValue to nodes.item(it).textContent
            }
        }
        val en = values(english)
        val es = values(spanish)
        for ((key, text) in en) {
            assertEquals(
                "placeholder mismatch in $key",
                placeholder.findAll(text).map { it.value }.toSet(),
                placeholder.findAll(es.getValue(key)).map { it.value }.toSet(),
            )
        }
    }
}
```

The test reads files relative to the module directory, which is Gradle's working directory for `:app` unit tests.

- [ ] **Step 2: Run it and watch it fail**

Run: `gradle :app:testDebugUnitTest --tests '*StringsParityTest*'`
Expected: FAIL — `missing resource file: src/main/res/values-es/strings.xml`.

- [ ] **Step 3: Write the English strings**

`app/src/main/res/values/strings.xml` — the complete set. Group with comments in this order and use exactly these names:

| Group | Keys |
| :--- | :--- |
| App | `app_name` |
| Common | `common_unknown`, `common_not_available`, `common_none`, `common_never`, `common_yes`, `common_no`, `common_cancel`, `common_ok`, `common_back`, `common_close`, `common_clear`, `common_loading` |
| Formats | `format_distance_km`, `format_distance_km_uncertain`, `format_altitude_m`, `format_snr_db`, `format_rssi_dbm`, `format_percent`, `format_coordinates`, `format_ago_seconds`, `format_ago_minutes`, `format_ago_hours`, `format_source`, `format_source_aged`, `format_db_header`, `format_packets_per_hour`, `format_uptime` |
| Ages | `age_1m`, `age_5m`, `age_30m`, `age_1h`, `age_12h`, `age_1d`, `age_1w`, `age_1y`, `age_unknown` |
| Directions | `direction_n`, `direction_ne`, `direction_e`, `direction_se`, `direction_s`, `direction_sw`, `direction_w`, `direction_nw`, `direction_unknown` |
| Sources | `source_db`, `source_current` |
| Sort | `sort_label`, `sort_packets`, `sort_percent`, `sort_avg_snr`, `sort_avg_rssi`, `sort_name` |
| Gauge | `gauge_mode`, `gauge_simple`, `gauge_complex`, `gauge_snr`, `gauge_rssi` |
| Navigation | `nav_relays`, `nav_neighbours` |
| Actions | `action_pause`, `action_resume`, `action_reset`, `action_reset_confirm_title`, `action_reset_confirm_body`, `action_reload_db`, `action_sort`, `action_gauge_mode`, `action_settings`, `action_back`, `action_disconnect`, `action_skip_node`, `action_skip_confirm_title`, `action_skip_confirm_body`, `action_clear_skipped`, `action_clear_skipped_confirm_title`, `action_clear_skipped_confirm_body` |
| Devices | `devices_title`, `devices_scanning`, `devices_empty_title`, `devices_empty_body`, `devices_demo_section`, `devices_found_section`, `devices_bonded`, `devices_permissions_title`, `devices_permissions_body`, `devices_permissions_grant`, `devices_adapter_off_title`, `devices_adapter_off_body`, `devices_unsupported_title`, `devices_unsupported_body`, `devices_state_connecting`, `devices_state_connected`, `devices_state_disconnected` |
| Relays | `relays_title`, `relays_empty_title`, `relays_empty_body`, `relays_status_total`, `relays_status_relayed`, `relays_status_paused`, `relays_local_node`, `relays_local_node_unknown` |
| Neighbours | `neighbours_title`, `neighbours_empty_title`, `neighbours_empty_body`, `neighbours_status_direct` |
| Detail | `detail_relay_title`, `detail_neighbour_title`, `detail_tab_matching_nodes`, `detail_tab_node`, `detail_tab_remote_nodes`, `detail_total_relayed`, `detail_total_direct`, `detail_packets_per_hour`, `detail_skipped_nodes`, `detail_signal_snr`, `detail_signal_rssi`, `detail_stat_min`, `detail_stat_avg`, `detail_stat_max`, `detail_stat_last`, `detail_stat_count`, `detail_no_signal_data` |
| Node card | `node_long_name`, `node_short_name`, `node_role`, `node_hardware`, `node_position`, `node_last_snr_db`, `node_last_heard_db`, `node_firmware`, `node_uptime`, `node_restarts`, `node_telemetry`, `node_public_key_present`, `node_hops_away`, `node_no_matching_title`, `node_no_matching_body`, `node_open_google_maps`, `node_open_osm`, `node_open_meshview` |
| Remote nodes | `remote_title`, `remote_column_node`, `remote_column_packets`, `remote_column_hops`, `remote_column_left`, `remote_direction_hint`, `remote_empty`, `remote_via_relays`, `remote_via_relay_current` |
| Settings | `settings_title`, `settings_language`, `settings_language_system`, `settings_language_english`, `settings_language_spanish`, `settings_gauge_mode`, `settings_default_sort`, `settings_meshview_url`, `settings_meshview_url_hint`, `settings_keep_screen_on`, `settings_background_collection`, `settings_background_collection_summary`, `settings_skipped_nodes`, `settings_skipped_nodes_empty`, `settings_clear_all_skipped`, `settings_about`, `settings_version`, `settings_licence`, `settings_upstream` |
| Service | `service_channel_name`, `service_notification_title`, `service_notification_text`, `service_notification_counters` |
| Plurals | `plural_matching_nodes`, `plural_known_nodes`, `plural_packets`, `plural_skipped_nodes` |

Format strings, exactly:

```xml
<string name="format_distance_km">%1$s km</string>
<string name="format_distance_km_uncertain">%1$s±%2$s km</string>
<string name="format_altitude_m">%1$d m</string>
<string name="format_snr_db">%1$s dB</string>
<string name="format_rssi_dbm">%1$s dBm</string>
<string name="format_percent">%1$s%%</string>
<string name="format_coordinates">%1$s, %2$s</string>
<string name="format_ago_seconds">%1$ds ago</string>
<string name="format_ago_minutes">%1$dm %2$02ds</string>
<string name="format_ago_hours">%1$dh %2$02dm</string>
<string name="service_notification_counters">%1$d packets · %2$d relayed</string>
<string name="format_source">Src: %1$s</string>
<string name="format_source_aged">Src: %1$s:%2$s</string>
<string name="format_db_header">DB(%1$d) · %2$s</string>
<string name="format_packets_per_hour">%1$s pkt/h</string>
<string name="format_uptime">%1$dd %2$dh %3$dm</string>
<string name="detail_relay_title">Relay %1$s</string>
<string name="detail_neighbour_title">Neighbour %1$s</string>
```

Copy that carries meaning rather than a label — write these as sentences, not as headings:

- `relays_empty_body`: *"No relayed packets yet. Traffic arriving directly, without being forwarded, is on the Neighbours tab."*
- `neighbours_empty_body`: *"No directly received packets yet. Nodes whose traffic reaches you through a relay are on the Relays tab."*
- `node_no_matching_body`: *"No node in the database ends with this byte. The relay may not have broadcast its node info yet."*
- `remote_direction_hint`: *"Many relays hear from one direction only, so the spread of directions here shows where this relay listens."*
- `action_skip_confirm_body`: *"Stop treating %1$s as this relay. It stays in the node database and can be restored from Settings."*
- `action_reset_confirm_body`: *"Clear all collected relay and neighbour statistics. The node database and the skip-list are kept."*
- `settings_background_collection_summary`: *"Keep collecting while the app is in the background. Without it, Android suspends the app and the survey stops."*
- `settings_upstream`: *"A port of mesh_stats, GPL-3.0."*

- [ ] **Step 4: Write the Spanish strings**

`app/src/main/res/values-es/strings.xml` — every key from step 3, translated. `app_name` stays `Mesh Relay`.

Glossary, to be used consistently — these are the terms a general translation gets wrong:

| English | Spanish | Note |
| :--- | :--- | :--- |
| relay (noun) | **repetidor** | The term the Spanish Meshtastic community uses; *relé* means an electrical relay |
| neighbour | **vecino** | |
| node | **nodo** | |
| hop | **salto** | |
| hops left | **saltos restantes** | |
| packet | **paquete** | |
| skip / skipped | **descartar / descartados** | *omitir* reads as "overlook by accident" |
| node database | **base de nodos** | |
| signal level | **nivel de señal** | |
| range / distance | **distancia** | |
| altitude | **altitud** | |
| bearing / direction | **dirección** | |
| gauge | **indicador** | |
| pause / resume | **pausar / reanudar** | |
| reset | **reiniciar** | |
| reload | **recargar** | |
| bonded (Bluetooth) | **vinculado** | Android's own Spanish wording |
| scanning | **buscando** | |
| uptime | **tiempo activo** | |
| restarts | **reinicios** | |
| firmware | **firmware** | Not translated |
| foreground service | **servicio en primer plano** | |

Compass points are translated: `N`, `NE`, `E`, `SE`, `S`, `SO`, `O`, `NO` — note that west is **O** (*oeste*), not W, and south-west is **SO**. Units are not: `dB`, `dBm`, `km`, `m`, `pkt/h`.

Spanish needs both `one` and `other` in every `<plurals>`.

- [ ] **Step 5: Run the test**

Run: `gradle :app:testDebugUnitTest --tests '*StringsParityTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/ app/src/test/kotlin/com/cerocoder/meshrelay/StringsParityTest.kt
git commit -m "i18n: English and Spanish strings, with a parity test

A missing Spanish key does not fail the build - Android falls back to English
silently. The parity test is the only thing that notices, and it also catches
a translation that drops a format placeholder."
```

### Task 8: Port the BLE stack *(Sonnet)*

**Files:**
- Create, by copying from `mesh-test-android/app/src/main/kotlin/com/cerocoder/meshtest/ble/` into `app/src/main/kotlin/com/cerocoder/meshrelay/ble/`: `BleRadioTransport.kt`, `BleScanner.kt`, `BluetoothAvailability.kt`, `ReconnectPolicy.kt`, `nordic/{NordicMeshGattClient,MeshBleManager,NordicBleSession,BleFailureText,BleBonding,BleScannerImpl}.kt`, `protocol/{BleFailure,MeshGattClient,MeshRadioProfile,BleSession}.kt`
- Create, by copying tests: `app/src/test/kotlin/com/cerocoder/meshrelay/ble/{ReconnectPolicyTest,BleRadioTransportTest}.kt`, `ble/protocol/{FakeBleSession,FakeMeshGattClient,MeshRadioProfileTest}.kt`

**Interfaces:**
- Consumes: Task 1. Depends on `transport/` types from Task 9 — the two tasks compile together at the end of the round, which is why they are in the same round rather than sequenced.
- Produces: `BleRadioTransport`, `interface BleScanner { fun scan(): Flow<DeviceListEntry.Ble> }`, `BluetoothAvailability(context)` with `check(): BleReadiness` and `requiredPermissions: Array<String>`, `enum BleReadiness { READY, PERMISSIONS_MISSING, ADAPTER_OFF, UNSUPPORTED }`, `openNordicSession(context, address)`.

- [ ] **Step 1: Copy the files verbatim**

Copy each file listed above, changing only the package declaration and imports from `com.cerocoder.meshtest` to `com.cerocoder.meshrelay`. **Change nothing else on this step** — not a name, not a default, not a timeout.

- [ ] **Step 2: Translate every comment to English**

Translate the Russian comments in place. The rule: **preserve the reasoning, do not summarise it away.** These comments record findings that cost runs on real hardware, and a shortened version loses the finding. In particular, keep in full:

- Why a Nordic `CancellationException` must be distinguished from a real coroutine cancellation by checking `currentCoroutineContext().isActive`, and that the library manufactures one from `FailCallback.REASON_CANCELLED`.
- Why characteristic reads and writes are not cancellable, and why a library-level timeout is attached even though it does not make them cancellable.
- Why `notifications` is a `by lazy` property and not a getter — `setNotificationCallback` replaces the single listener rather than adding one, and the evicted collector goes silent for ever with no error and no completion.
- Why bonding must complete strictly before connecting, why the bond state is polled rather than taken from the broadcast, and why the bonding timeout is two minutes — it measures a person reading a code off the node's screen and typing it in.
- Why `autoConnect` is chosen from the state *before* bonding.
- Why scanning must stop while a GATT connection is active, and the five-scans-per-thirty-seconds quota.
- Why `getName()` and `getBondState()` are dangerous on the system callback thread without `BLUETOOTH_CONNECT`, and that the advertised name is used instead.

- [ ] **Step 3: Translate the user-facing failure text**

`BleFailureText.kt` produces text shown on screen. Every message it returns moves into `strings.xml` (Task 7) and the function returns a resource identifier or a key, not a literal. Keep its rule intact: **a message names the cause and the next step, never the raw exception and never a numeric status.** The two examples the skeleton records are worth preserving as test cases — a raw "status -5" tells a person nothing, and "could not start bonding" when Bluetooth is simply switched off sends them to inspect the node.

If a message has no key in Task 7's list, that is a gap in Task 7: add the key there and reference it here.

- [ ] **Step 4: Run the ported tests**

Run: `gradle :app:testDebugUnitTest --tests '*ble*'`
Expected: PASS, all carried-over tests.

- [ ] **Step 5: Verify no Russian survived**

Run: `grep -rP '[\x{0400}-\x{04FF}]' app/src/main/kotlin/com/cerocoder/meshrelay/ble app/src/test/kotlin/com/cerocoder/meshrelay/ble`
Expected: no output.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/ble app/src/test/kotlin/com/cerocoder/meshrelay/ble
git commit -m "feat(ble): port the Nordic BLE stack from mesh-test-android

Copied unchanged apart from the package and the comment language. The
comments record findings that cost runs on real hardware - manufactured
cancellations, uncancellable reads, the single-listener notification getter -
and are translated in full rather than summarised."
```

### Task 9: Port the transport layer *(Sonnet)*

**Files:**
- Create, by copying from `mesh-test-android/.../transport/` into `app/src/main/kotlin/com/cerocoder/meshrelay/transport/`: `RadioTransport.kt`, `RadioTransportFactoryImpl.kt`, `FakeRadioTransport.kt`, `DeviceListEntry.kt`, `MeshProtocol.kt`
- Create, by copying tests: `app/src/test/kotlin/com/cerocoder/meshrelay/transport/{MeshProtocolTest,RadioTransportFactoryImplTest,FakeRadioTransportTest}.kt`
- Modify: `MeshProtocol.kt` — add one constant

**Interfaces:**
- Consumes: Task 1; `BleRadioTransport` and `openNordicSession` from Task 8.
- Produces: `interface RadioTransport { fun start(); fun send(bytes: ByteArray); suspend fun close() }`; `interface RadioTransportCallback { fun onConnect(); fun onDisconnect(isPermanent: Boolean, reason: String?); fun onDataReceived(bytes: ByteArray) }`; `interface RadioTransportFactory { fun create(address: String, callback: RadioTransportCallback): RadioTransport }`; `RadioTransportFactoryImpl(scope, isDebugBuild, context)`; `FakeRadioTransport(scenario, callback, parentScope)`; `sealed class DeviceListEntry { Demo(scenarioId, name); Ble(name, mac, bonded, rssi) }`; `object MeshProtocol` with `CONFIG_NONCE = 69420`, `NODE_INFO_NONCE = 69421`, **`NODE_INFO_RELOAD_NONCE = 69422`**, `MAX_FRAME_BYTES = 512`, `DEMO_PREFIX = "m:"`, `BLE_PREFIX = "x"`, `scenarioIdOrNull`, `bleMacOrNull`.

Do **not** port `frame/**`. That package belongs to the frame inspector and has no consumer here.

- [ ] **Step 1: Copy the files, changing only package and imports**

- [ ] **Step 2: Translate the comments to English, preserving the reasoning**

Keep in full: why demo devices are rejected outside a debug build even when the address arrives from saved state, and why the address prefix is the single branch point between transports.

- [ ] **Step 3: Add the reload nonce**

In `MeshProtocol`:

```kotlin
/**
 * Nonce for a node-database reload requested mid-session (the terminal tool's
 * [D] key).
 *
 * Deliberately distinct from NODE_INFO_NONCE. Reusing the handshake nonce would
 * drive the connection manager back through its "handshake finished" branch,
 * which republishes Connected and restarts the heartbeat - and the heartbeat
 * restart resets the silence detector, so a genuinely dying link would get a free
 * extension every time the user pressed reload.
 */
const val NODE_INFO_RELOAD_NONCE = 69422
```

- [ ] **Step 4: Run the ported tests**

Run: `gradle :app:testDebugUnitTest --tests '*transport*'`
Expected: PASS.

Add one test for the new constant in `MeshProtocolTest`:

```kotlin
@Test
fun `the reload nonce differs from both handshake nonces`() {
    // If it ever collides, a reload silently reruns handshake completion and hands
    // a dying link a free extension of the silence timeout.
    assertNotEquals(MeshProtocol.CONFIG_NONCE, MeshProtocol.NODE_INFO_RELOAD_NONCE)
    assertNotEquals(MeshProtocol.NODE_INFO_NONCE, MeshProtocol.NODE_INFO_RELOAD_NONCE)
}
```

- [ ] **Step 5: Verify no Russian survived, then commit**

```bash
grep -rP '[\x{0400}-\x{04FF}]' app/src/main/kotlin/com/cerocoder/meshrelay/transport && echo FAIL || echo clean
git add app/src/main/kotlin/com/cerocoder/meshrelay/transport app/src/test/kotlin/com/cerocoder/meshrelay/transport
git commit -m "feat(transport): port the transport abstraction

Adds NODE_INFO_RELOAD_NONCE, distinct from the handshake nonces so a
mid-session database reload does not rerun handshake completion."
```

### Task 10: Port the connection manager and add node-database reload *(Opus)*

**Files:**
- Create, by copying from `mesh-test-android/.../connection/`: `app/src/main/kotlin/com/cerocoder/meshrelay/connection/{ConnectionState,RadioConnectionManager}.kt`
- Create, by copying: `app/src/test/kotlin/com/cerocoder/meshrelay/connection/RadioConnectionManagerTest.kt`
- Modify both: replace the frame log with a timestamped frame flow, and add the reload

**Interfaces:**
- Consumes: Tasks 1, 8, 9; `TimestampedFrame`, `TimeSource` (Task 2).
- Produces: `RadioConnectionManager(factory, scope, time: TimeSource, handshakeTimeout, heartbeatInterval, silenceTimeout, recoveryDelay)` with `connectionState: StateFlow<ConnectionState>`, `frames: Flow<TimestampedFrame>`, `nodeDbReloading: StateFlow<Boolean>`, `droppedFrames: Int`, `fun connect(address: String)`, `suspend fun disconnect()`, `fun reloadNodeDatabase()`; `sealed interface ConnectionState { Disconnected(reason, retrying); Connecting; Connected }`.

Three changes to the copied class. Everything else — the handshake watchdog, the heartbeat with its monotonic nonce, the silence detector, the bounded self-recovery, the transport mutex and the volatile-timer reasoning — is copied unchanged, including its comments, translated.

- [ ] **Step 1: Copy the two files and translate the comments**

Preserve in full the reasoning for: why the packet queue is a `Channel` with strict FIFO rather than a `SharedFlow`; why the newest frame is dropped on overflow rather than the oldest; why `watchdog`, `keepAlive`, `recovery` and `recoveryAttempts` are `@Volatile`; why a permanent disconnect captures the doomed transport before taking the lock; why `catch (e: Exception)` rather than `IOException` around the frame decode; why the heartbeat nonce must increase; why closing the transport and publishing `Disconnected` happen under one lock; and why `byUser` resets the recovery budget but a recovery attempt does not.

- [ ] **Step 2: Replace the frame log with a timestamped frame flow**

Delete `packetLog`, `_packetLog`, `frameSeq` and the `FrameRecord` import — the frame inspector is not part of this app.

Change the channel's element type:

```kotlin
private val _frames = Channel<TimestampedFrame>(capacity = PACKET_QUEUE_CAPACITY)
val frames: Flow<TimestampedFrame> = _frames.receiveAsFlow()
```

In `onDataReceived`, wrap the decoded frame with the reception time taken from the injected `TimeSource`, not from `System.currentTimeMillis()`:

```kotlin
if (_frames.trySend(TimestampedFrame(time.nowMillis(), frame)).isFailure) {
    droppedFrameCount.incrementAndGet()
}
```

The existing `now: () -> Long` constructor parameter is replaced by `time: TimeSource`, and every internal use of `now()` becomes `time.nowMillis()`. The reason it was injected in the first place holds unchanged: the silence detector runs on `runTest`'s virtual clock, and without injection it could not tell real silence from a test that finished instantly.

- [ ] **Step 3: Write the failing tests for reload**

Append to `RadioConnectionManagerTest.kt`:

```kotlin
@Test
fun `reload asks the node for its database again`() = runTest {
    val transport = RecordingTransport()
    val subject = manager(factoryReturning(transport), backgroundScope)
    subject.connect("m:demo")
    advanceUntilIdle()
    completeHandshake(subject)
    transport.sent.clear()

    subject.reloadNodeDatabase()
    advanceUntilIdle()

    val request = ToRadio.ADAPTER.decode(transport.sent.single())
    assertEquals(MeshProtocol.NODE_INFO_RELOAD_NONCE, request.want_config_id)
    assertTrue(subject.nodeDbReloading.value)
}

@Test
fun `reload finishes when the node acknowledges it`() = runTest {
    val transport = RecordingTransport()
    val subject = manager(factoryReturning(transport), backgroundScope)
    subject.connect("m:demo")
    advanceUntilIdle()
    completeHandshake(subject)
    subject.reloadNodeDatabase()
    advanceUntilIdle()

    subject.onDataReceived(FromRadio(config_complete_id = MeshProtocol.NODE_INFO_RELOAD_NONCE).encode())
    advanceUntilIdle()

    assertFalse(subject.nodeDbReloading.value)
}

@Test
fun `reload leaves the connection state and the heartbeat alone`() = runTest {
    // The whole reason for a separate nonce. Rerunning handshake completion would
    // republish Connected and restart the heartbeat, and a heartbeat restart resets
    // the silence detector - handing a dying link a free extension on every reload.
    val transport = RecordingTransport()
    val subject = manager(factoryReturning(transport), backgroundScope)
    subject.connect("m:demo")
    advanceUntilIdle()
    completeHandshake(subject)
    subject.reloadNodeDatabase()
    subject.onDataReceived(FromRadio(config_complete_id = MeshProtocol.NODE_INFO_RELOAD_NONCE).encode())
    advanceUntilIdle()

    assertEquals(ConnectionState.Connected, subject.connectionState.value)
    // The silence detector still measures from the last frame, not from the reload.
    advanceTimeBy(SILENCE_TIMEOUT.inWholeMilliseconds + 1_000)
    assertTrue(subject.connectionState.value is ConnectionState.Disconnected)
}

@Test
fun `a reload that is never answered stops claiming to be in progress`() = runTest {
    // Without a timeout the spinner spins for ever on a node that ignores the
    // request, and the user cannot tell a slow reload from a broken one.
    val subject = manager(factoryReturning(RecordingTransport()), backgroundScope)
    subject.connect("m:demo")
    advanceUntilIdle()
    completeHandshake(subject)
    subject.reloadNodeDatabase()
    advanceUntilIdle()
    assertTrue(subject.nodeDbReloading.value)

    advanceTimeBy(RELOAD_TIMEOUT.inWholeMilliseconds + 1_000)
    assertFalse(subject.nodeDbReloading.value)
}

@Test
fun `reload is refused when there is no connection`() = runTest {
    val subject = manager(factoryReturning(RecordingTransport()), backgroundScope)
    subject.reloadNodeDatabase()
    advanceUntilIdle()
    assertFalse(subject.nodeDbReloading.value)
}
```

`completeHandshake` is a helper feeding `FromRadio(config_complete_id = CONFIG_NONCE)` then `FromRadio(config_complete_id = NODE_INFO_NONCE)` through `onDataReceived`. `RecordingTransport` is a `RadioTransport` collecting `send` payloads into a list. Both may already exist in the copied test file; reuse them if so.

These tests run on `backgroundScope` because the heartbeat loop is endless: `advanceUntilIdle()` over an endless `while (isActive) { delay(...) }` never returns, and the run hangs rather than failing.

- [ ] **Step 4: Run them and watch them fail**

Run: `gradle :app:testDebugUnitTest --tests '*RadioConnectionManagerTest*'`
Expected: FAIL — `Unresolved reference: reloadNodeDatabase`.

- [ ] **Step 5: Implement the reload**

```kotlin
private val _nodeDbReloading = MutableStateFlow(false)
val nodeDbReloading: StateFlow<Boolean> = _nodeDbReloading.asStateFlow()

@Volatile
private var reloadWatchdog: Job? = null

/**
 * Ask the node to send its database again. Ports the terminal tool's [D] key,
 * mesh_stats.py:582-609.
 *
 * The database is otherwise kept fresh by node_info frames arriving over the air,
 * so this exists for the case the terminal tool has: the node has learned about
 * nodes we have not heard from directly, and we want them now rather than
 * whenever they next transmit.
 *
 * Statistics are untouched, exactly as in the original.
 */
fun reloadNodeDatabase() {
    if (_connectionState.value != ConnectionState.Connected) {
        Log.d(TAG, "reload ignored: not connected")
        return
    }
    _nodeDbReloading.value = true
    reloadWatchdog?.cancel()
    reloadWatchdog = scope.launch {
        delay(RELOAD_TIMEOUT)
        // A node that never answers must not leave the interface claiming a reload
        // is still running: an endless spinner is indistinguishable from a hang.
        Log.w(TAG, "node did not acknowledge the database reload within $RELOAD_TIMEOUT")
        _nodeDbReloading.value = false
    }
    sendToRadio(ToRadio(want_config_id = MeshProtocol.NODE_INFO_RELOAD_NONCE))
}
```

In `onDataReceived`, add a third branch to the existing `when (frame.config_complete_id)` — placed with the other two, and touching neither the connection state nor the heartbeat:

```kotlin
MeshProtocol.NODE_INFO_RELOAD_NONCE -> {
    reloadWatchdog?.cancel()
    _nodeDbReloading.value = false
    Log.i(TAG, "node database reload finished")
}
```

Cancel `reloadWatchdog` alongside the other timers in `connect`, `disconnect` and the permanent branch of `onDisconnect`, and set `_nodeDbReloading.value = false` there — a reload in flight when the link drops is over, whatever the node was going to say. Add `private val RELOAD_TIMEOUT = 30.seconds` to the companion.

- [ ] **Step 6: Run the tests**

Run: `gradle :app:testDebugUnitTest --tests '*RadioConnectionManagerTest*'`
Expected: PASS, the carried-over tests plus five.

- [ ] **Step 7: Verify no Russian survived, then commit**

```bash
grep -rP '[\x{0400}-\x{04FF}]' app/src/main/kotlin/com/cerocoder/meshrelay/connection && echo FAIL || echo clean
git add app/src/main/kotlin/com/cerocoder/meshrelay/connection app/src/test/kotlin/com/cerocoder/meshrelay/connection
git commit -m "feat(connection): port the connection manager, add node-DB reload

The frame log is replaced by a Flow<TimestampedFrame>: the engine consumes a
source-agnostic stream so a file-backed source can be added later without
touching the core.

Reload uses its own nonce so it does not rerun handshake completion, which
would restart the heartbeat and reset the silence detector."
```

### Task 11: Port the foreground service *(Sonnet)*

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/service/MeshForegroundService.kt`
- Modify: `app/src/main/AndroidManifest.xml` — restore the `<service>` element removed in Task 1

**Interfaces:**
- Consumes: Task 1, string keys `service_channel_name`, `service_notification_title`, `service_notification_text` (Task 7).
- Produces: `MeshForegroundService` with `companion object { fun start(context: Context); fun stop(context: Context); fun updateText(context: Context, text: String) }`.

- [ ] **Step 1: Copy the file, change the package, translate the comments**

Preserve in full: why `START_NOT_STICKY` rather than `START_STICKY`, and why the notification carries a `PendingIntent` back into the activity.

- [ ] **Step 2: Replace every literal with a string resource**

`"Mesh Test"` and `"Соединение с нодой активно"` become `getString(R.string.service_notification_title)` and `getString(R.string.service_notification_text)`; the channel name becomes `getString(R.string.service_channel_name)`.

- [ ] **Step 3: Add an updatable notification body**

The notification shows live counters, refreshed at a slow cadence by Task 31. Add:

```kotlin
/**
 * Replace the notification body without restarting the service.
 *
 * Counters only, and no faster than every thirty seconds: this is the one thing
 * that still runs with the screen off, and the whole point of building no
 * snapshots in the background is lost if the notification asks for one.
 */
fun updateText(context: Context, text: String) {
    context.startService(
        Intent(context, MeshForegroundService::class.java).putExtra(EXTRA_TEXT, text),
    )
}
```

`onStartCommand` reads `EXTRA_TEXT` and falls back to the default string when it is absent, then rebuilds the notification and calls `startForeground` again with the same id. Calling `startForeground` repeatedly with one id updates the existing notification rather than creating another.

- [ ] **Step 4: Restore the manifest entry**

```xml
<service
    android:name=".service.MeshForegroundService"
    android:exported="false"
    android:foregroundServiceType="connectedDevice" />
```

- [ ] **Step 5: Build and commit**

Run: `gradle :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/service app/src/main/AndroidManifest.xml
git commit -m "feat(service): port the foreground service

Notification text is updatable so the background can show counters without
anyone building a snapshot for it."
```

**Round 1 gate:** `gradle :app:testDebugUnitTest` green and `gradle :app:assembleDebug` succeeds before Round 2 is dispatched.

---

# Round 2 — six tasks, dispatchable in parallel

### Task 12: Relay, neighbour and telemetry aggregates *(Sonnet)*

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/{RemoteNodeStats,RelayStats,NeighbourStats,TelemetryRecord,Counters}.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/model/AggregatesTest.kt`

**Interfaces:**
- Consumes: `SignalStats`, `SignalHistory` (Task 4).
- Produces: exactly the signatures in spec §6.3, plus `Counters(totalPackets, totalRelayedPackets, totalDirectPackets, relayCount)`.

Ports `mesh_stats.py:346-479`.

- [ ] **Step 1: Write the failing test**

`AggregatesTest.kt`:

```kotlin
package com.cerocoder.meshrelay.stats.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AggregatesTest {

    @Test
    fun `hop averages follow the packets that carried hop information`() {
        val stats = RemoteNodeStats()
            .plus(hopStart = 3, hopLimit = 1, hopsKnown = true)
            .plus(hopStart = 5, hopLimit = 2, hopsKnown = true)
        assertEquals(2, stats.packetCount)
        assertEquals(2.5f, stats.avgHopsMade!!, 0.0001f)   // (2 + 3) / 2
        assertEquals(1.5f, stats.avgHopsLeft!!, 0.0001f)   // (1 + 2) / 2
    }

    @Test
    fun `a packet without hop information still counts but does not skew the averages`() {
        // hop_start is not an optional field, so 0 is what an unset value looks like.
        // Folding 0 into the average would pull every relay's hop count toward zero
        // and make distant nodes look adjacent.
        val stats = RemoteNodeStats()
            .plus(hopStart = 3, hopLimit = 1, hopsKnown = true)
            .plus(hopStart = 0, hopLimit = 0, hopsKnown = false)
        assertEquals(2, stats.packetCount)
        assertEquals(2f, stats.avgHopsMade!!, 0.0001f)
        assertEquals(1f, stats.avgHopsLeft!!, 0.0001f)
    }

    @Test
    fun `hop averages are absent when nothing carried hop information`() {
        val stats = RemoteNodeStats().plus(0, 0, hopsKnown = false)
        assertNull(stats.avgHopsMade)
        assertNull(stats.avgHopsLeft)
    }

    @Test
    fun `the hexadecimal identifier is always two lower case digits`() {
        assertEquals("0x69", RelayStats(relayByte = 0x69).hexId)
        assertEquals("0x0a", RelayStats(relayByte = 0x0a).hexId)
        assertEquals("0xff", RelayStats(relayByte = 0xff).hexId)
    }

    @Test
    fun `known nodes counts the distinct senders behind a relay`() {
        val relay = RelayStats(
            relayByte = 0x69,
            fromNodeStats = mapOf(1 to RemoteNodeStats(), 2 to RemoteNodeStats()),
        )
        assertEquals(2, relay.knownNodesCount)
    }

    @Test
    fun `the packet rate is measured over the observed window`() {
        val relay = RelayStats(
            relayByte = 0x69,
            packetCount = 60,
            firstPacketAtMillis = 1_000_000L,
            lastPacketAtMillis = 1_000_000L + 30 * 60_000L,
        )
        assertEquals(120f, relay.packetsPerHour, 0.01f)   // 60 packets in half an hour
    }

    @Test
    fun `the packet rate is zero before there is a window to measure`() {
        // One packet gives no duration, and dividing by it would report an infinite
        // rate for every relay in its first second.
        assertEquals(0f, RelayStats(relayByte = 1, packetCount = 1).packetsPerHour, 0.0001f)
        assertEquals(0f, RelayStats(relayByte = 1, packetCount = 0).packetsPerHour, 0.0001f)
        assertEquals(
            0f,
            RelayStats(relayByte = 1, packetCount = 9, firstPacketAtMillis = 5L, lastPacketAtMillis = 5L).packetsPerHour,
            0.0001f,
        )
    }

    @Test
    fun `an uptime that falls back counts as a restart`() {
        // The only way to notice a node rebooting: the counter starts again from zero.
        var record = TelemetryRecord()
        record = record.withUptime(3_600)
        record = record.withUptime(7_200)
        assertEquals(0, record.observedRestartCount)
        record = record.withUptime(30)
        assertEquals(1, record.observedRestartCount)
        assertEquals(30, record.lastUptimeSeconds)
    }
}
```

- [ ] **Step 2: Run it, watch it fail, then implement**

Write the five files to the signatures in spec §6.3. Details the tests pin down:

- `RelayStats.hexId` is `"0x%02x".format(relayByte)` — lower case, always two digits.
- `packetsPerHour` returns `0f` when `packetCount < 2` or the window is not positive; otherwise `packetCount / durationSeconds * 3600`.
- `RemoteNodeStats.plus` always increments `packetCount`, and folds hops only when `hopsKnown`.
- `TelemetryRecord` gains two helpers so the engine does not reach into its fields: `withUptime(seconds: Int): TelemetryRecord`, incrementing `observedRestartCount` when the new value is lower than the stored one, and `withMetric(key: String, atMillis: Long, value: Float): TelemetryRecord`.
- `Counters` is a plain value class with default zeros and a `companion object { val EMPTY = Counters() }`.

- [ ] **Step 3: Run the test, then commit**

Run: `gradle :app:testDebugUnitTest --tests '*AggregatesTest*'` — Expected: PASS, 8 tests.

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/ app/src/test/kotlin/com/cerocoder/meshrelay/stats/model/AggregatesTest.kt
git commit -m "feat(stats): relay, neighbour and telemetry aggregates"
```

### Task 13: Packet classifier *(Opus)*

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/PacketClassifier.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/PacketClassifierTest.kt`

**Interfaces:**
- Consumes: `Geo.lastByteOfNodeNum` (Task 3).
- Produces: `data class Signal(val snr: Float, val rssi: Float)`; `sealed interface Ingest { Relayed(relayByte, fromNode, hopStart, hopLimit, signal); Direct(fromNode, signal); Dropped }`; `object PacketClassifier { fun classify(packet: MeshPacket, skippedRelayNodes: Set<Int>): Ingest; fun signalOf(packet: MeshPacket): Signal? }`.

Ports the decision in `StatsCollector.on_receive`, `mesh_stats.py:1035-1073`. **Read spec §6.3 before writing a line of this** — the presence rule is the part of the port most likely to be silently undone.

- [ ] **Step 1: Write the failing test**

`PacketClassifierTest.kt`:

```kotlin
package com.cerocoder.meshrelay.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.meshtastic.proto.MeshPacket

private const val SENDER = 0x9e75f1a4.toInt()      // low byte a4
private const val OTHER = 0x11223344                // low byte 44

private fun packet(
    from: Int = SENDER,
    relay: Int = 0x69,
    hopStart: Int = 3,
    hopLimit: Int = 1,
    snr: Float = -7.5f,
    rssi: Int = -94,
) = MeshPacket(
    from = from, relay_node = relay, hop_start = hopStart,
    hop_limit = hopLimit, rx_snr = snr, rx_rssi = rssi,
)

class PacketClassifierTest {

    @Test
    fun `an ordinary forwarded packet is relayed`() {
        val result = PacketClassifier.classify(packet(), emptySet())
        assertEquals(Ingest.Relayed(0x69, SENDER, 3, 1, Signal(-7.5f, -94f)), result)
    }

    @Test
    fun `a packet with no relay byte came to us directly`() {
        // relay_node is not an optional field, so 0 means the sender never set it.
        assertEquals(Ingest.Direct(SENDER, Signal(-7.5f, -94f)), PacketClassifier.classify(packet(relay = 0), emptySet()))
    }

    @Test
    fun `a sender relaying its own packet with no hops made is direct`() {
        // A node writes its own low byte into the relay field on first transmission.
        // Counted as a relay it would appear as a relay for its own traffic only,
        // which is exactly what the Neighbours view exists to separate out.
        val result = PacketClassifier.classify(packet(relay = 0xa4, hopStart = 3, hopLimit = 3), emptySet())
        assertEquals(Ingest.Direct(SENDER, Signal(-7.5f, -94f)), result)
    }

    @Test
    fun `the same low byte with hops made is a genuine relay`() {
        val result = PacketClassifier.classify(packet(relay = 0xa4, hopStart = 3, hopLimit = 2), emptySet())
        assertEquals(Ingest.Relayed(0xa4, SENDER, 3, 2, Signal(-7.5f, -94f)), result)
    }

    @Test
    fun `a sender whose low byte is zero identifies itself as ff`() {
        val zeroTailed = 0x9e75f100.toInt()
        val result = PacketClassifier.classify(
            packet(from = zeroTailed, relay = 0xFF, hopStart = 2, hopLimit = 2), emptySet(),
        )
        assertEquals(Ingest.Direct(zeroTailed, Signal(-7.5f, -94f)), result)
    }

    @Test
    fun `a skipped sender heard first hand is dropped`() {
        // One hop made means we are the first receiver, so this is the skipped node's
        // own transmission and must not be credited to any relay.
        assertEquals(Ingest.Dropped, PacketClassifier.classify(packet(hopStart = 3, hopLimit = 2), setOf(SENDER)))
    }

    @Test
    fun `a skipped sender heard through someone else still counts`() {
        // Two hops made: whatever this node is, something forwarded the packet, and
        // that something is a relay worth measuring.
        val result = PacketClassifier.classify(packet(hopStart = 3, hopLimit = 1), setOf(SENDER))
        assertEquals(Ingest.Relayed(0x69, SENDER, 3, 1, Signal(-7.5f, -94f)), result)
    }

    @Test
    fun `a skipped sender with no hop information is dropped`() {
        assertEquals(Ingest.Dropped, PacketClassifier.classify(packet(relay = 0x69, hopStart = 0, hopLimit = 0), setOf(SENDER)))
    }

    @Test
    fun `skipping one node does not affect another`() {
        val result = PacketClassifier.classify(packet(hopStart = 3, hopLimit = 2), setOf(OTHER))
        assertEquals(Ingest.Relayed(0x69, SENDER, 3, 2, Signal(-7.5f, -94f)), result)
    }

    @Test
    fun `zero received signal strength means the packet carried no signal information`() {
        // THE deviation from the original, and the one most likely to be quietly
        // undone. The Python tool reads packets as dicts with protobuf defaults
        // omitted, so it can tell an absent rx_snr from a real 0.0 dB. Wire cannot:
        // both arrive as 0. RSSI is the witness for the pair because 0 dBm is not
        // physically observable, whereas exactly 0.0 dB SNR is ordinary.
        //
        // Accept rx_rssi == 0 as a real sample and every relay collects a stream of
        // phantom 0/0 readings that drag its averages toward zero.
        assertNull(PacketClassifier.signalOf(packet(snr = 0f, rssi = 0)))
        assertNull(PacketClassifier.signalOf(packet(snr = -7.5f, rssi = 0)))
    }

    @Test
    fun `a real zero decibel signal to noise ratio is kept`() {
        assertEquals(Signal(0f, -94f), PacketClassifier.signalOf(packet(snr = 0f, rssi = -94)))
    }

    @Test
    fun `a packet without signal information is still classified and counted`() {
        val result = PacketClassifier.classify(packet(rssi = 0, snr = 0f), emptySet())
        assertEquals(Ingest.Relayed(0x69, SENDER, 3, 1, null), result)
    }

    @Test
    fun `an encrypted packet is classified like any other`() {
        // The point of the whole tool: relay topology is readable without reading
        // the traffic. An encrypted packet carries relay_node, hops and signal just
        // the same, and dropping it would hide most of the mesh.
        val encrypted = packet().copy(decoded = null, encrypted = okio.ByteString.of(1, 2, 3))
        assertEquals(Ingest.Relayed(0x69, SENDER, 3, 1, Signal(-7.5f, -94f)), PacketClassifier.classify(encrypted, emptySet()))
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

Run: `gradle :app:testDebugUnitTest --tests '*PacketClassifierTest*'`
Expected: FAIL — `Unresolved reference: PacketClassifier`.

- [ ] **Step 3: Write the implementation**

```kotlin
package com.cerocoder.meshrelay.stats

import org.meshtastic.proto.MeshPacket

/** Signal strength of one received packet. */
data class Signal(val snr: Float, val rssi: Float)

/** What a packet turned out to be. */
sealed interface Ingest {
    data class Relayed(
        val relayByte: Int,
        val fromNode: Int,
        val hopStart: Int,
        val hopLimit: Int,
        val signal: Signal?,
    ) : Ingest

    data class Direct(val fromNode: Int, val signal: Signal?) : Ingest

    data object Dropped : Ingest
}

/**
 * Decides what one packet says about the mesh. Ports mesh_stats.py:1035-1073.
 *
 * Pure and total: no clock, no state, no logging. Everything the engine does with
 * a packet follows from the answer here, which is why this file is tested to the
 * edge of its decision table.
 */
object PacketClassifier {

    fun classify(packet: MeshPacket, skippedRelayNodes: Set<Int>): Ingest {
        val from = packet.from
        val relayByte = packet.relay_node
        val hopStart = packet.hop_start
        val hopLimit = packet.hop_limit
        val hopsMade = hopStart - hopLimit
        val signal = signalOf(packet)

        // No relay byte, or a byte that is just the sender announcing itself on a
        // packet that has not been forwarded yet: we heard this node ourselves.
        if (relayByte == 0 || (hopsMade == 0 && Geo.lastByteOfNodeNum(from) == relayByte)) {
            return Ingest.Direct(from, signal)
        }

        if (from in skippedRelayNodes) {
            // Exactly one hop made means we were the first receiver, so this is the
            // skipped node's own transmission. More than one, and something did
            // forward it, and that something is a relay worth measuring.
            // No hop information at all: keep the original's behaviour and drop.
            val heardFirstHand = hopStart == 0 || hopsMade == 1
            if (heardFirstHand) return Ingest.Dropped
        }

        return Ingest.Relayed(relayByte, from, hopStart, hopLimit, signal)
    }

    /**
     * Signal strength, or null when the packet carried none.
     *
     * rx_snr and rx_rssi are not optional fields, so an unset value and a real 0
     * are the same bits. RSSI decides for both: 0 dBm is not physically
     * observable, while exactly 0.0 dB SNR is ordinary. Read spec section 6.3
     * before changing this - accepting rx_rssi == 0 gives every relay a stream of
     * phantom 0/0 samples that drag its averages toward zero.
     */
    fun signalOf(packet: MeshPacket): Signal? {
        if (packet.rx_rssi == 0) return null
        return Signal(packet.rx_snr, packet.rx_rssi.toFloat())
    }
}
```

- [ ] **Step 4: Run the test, then commit**

Run: `gradle :app:testDebugUnitTest --tests '*PacketClassifierTest*'` — Expected: PASS, 13 tests.

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/stats/PacketClassifier.kt \
        app/src/test/kotlin/com/cerocoder/meshrelay/stats/PacketClassifierTest.kt
git commit -m "feat(stats): packet classifier

Pure and total, and tested to the edge of its decision table. Carries the one
deliberate deviation from the original: rx_rssi != 0 witnesses that a packet
brought signal information at all, because Wire cannot distinguish an absent
proto3 scalar from its default and the Python dict layer can."
```

### Task 14: Node record and location info *(Sonnet)*

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/{NodeRecord,LocationInfo}.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/model/NodeRecordTest.kt`

**Interfaces:**
- Consumes: `PositionReport` (Task 5), `Direction`, `PositionSource` (Task 2).
- Produces: the two types from spec §7, with `NodeRecord.fromProto(info: NodeInfo): NodeRecord`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.cerocoder.meshrelay.stats.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.HardwareModel
import org.meshtastic.proto.Config
import org.meshtastic.proto.NodeInfo
import org.meshtastic.proto.Position
import org.meshtastic.proto.User

class NodeRecordTest {

    @Test
    fun `identity fields come across from the protobuf`() {
        val info = NodeInfo(
            num = 0x9e75f1a4.toInt(),
            user = User(long_name = "PQPL1", short_name = "1ce5", hw_model = HardwareModel.T_ECHO),
            snr = 6.25f,
            last_heard = 1_700_000_000,
            hops_away = 2,
        )
        val record = NodeRecord.fromProto(info)

        assertEquals(0x9e75f1a4.toInt(), record.num)
        assertEquals("PQPL1", record.longName)
        assertEquals("1ce5", record.shortName)
        assertEquals("T_ECHO", record.hwModel)
        assertEquals(6.25f, record.dbSnr!!, 0.0001f)
        assertEquals(1_700_000_000, record.lastHeardEpochSeconds)
        assertEquals(2, record.hopsAway)
    }

    @Test
    fun `an absent role reads as CLIENT`() {
        // The protocol default. The detail screen uses the role to judge how likely
        // a candidate is to be relaying at all, so a blank there would remove the
        // one hint that distinguishes a router from a silent client.
        assertEquals("CLIENT", NodeRecord.fromProto(NodeInfo(num = 1, user = User())).role)
    }

    @Test
    fun `a declared role is carried through`() {
        val info = NodeInfo(num = 1, user = User(role = Config.DeviceConfig.Role.ROUTER))
        assertEquals("ROUTER", NodeRecord.fromProto(info).role)
    }

    @Test
    fun `a node with no user record still produces a record`() {
        // Nodes appear in the database from routing alone, before ever broadcasting
        // their node info. Dropping them would hide candidate relays.
        val record = NodeRecord.fromProto(NodeInfo(num = 42))
        assertEquals(42, record.num)
        assertNull(record.longName)
        assertNull(record.shortName)
        assertFalse(record.hasPublicKey)
    }

    @Test
    fun `zero signal to noise and zero last heard read as absent`() {
        // Neither field is optional, so 0 is what unset looks like. Shown literally,
        // a node nobody has heard from would claim to have been heard in 1970.
        val record = NodeRecord.fromProto(NodeInfo(num = 1, snr = 0f, last_heard = 0))
        assertNull(record.dbSnr)
        assertNull(record.lastHeardEpochSeconds)
    }

    @Test
    fun `a database position becomes a position report with no precision`() {
        // The database does not carry precision_bits, so the obfuscation radius is
        // unknown for a database position and the direction must not be suppressed
        // on account of it.
        val info = NodeInfo(num = 1, position = Position(latitude_i = 404168000, longitude_i = -37038000, altitude = 667))
        val position = NodeRecord.fromProto(info).dbPosition!!
        assertEquals(40.4168, position.latitude!!, 1e-9)
        assertEquals(667, position.altitude)
        assertNull(position.precisionBits)
    }

    @Test
    fun `a public key is reported as present without being exposed`() {
        val info = NodeInfo(num = 1, user = User(public_key = okio.ByteString.of(1, 2, 3)))
        assertTrue(NodeRecord.fromProto(info).hasPublicKey)
    }
}
```

- [ ] **Step 2: Run it, watch it fail, then implement**

`LocationInfo` is the value type from spec §7 with an `EMPTY` companion whose `direction` is `Direction.UNKNOWN` and every other field `null`.

`NodeRecord.fromProto` maps as the tests require. Two details:

- `dbPosition` is built through `PositionReport.fromProto(position, atMillis = lastHeard * 1000L)`, then copied with `precisionBits = null` — the database genuinely does not carry it, and leaving a stale value there would suppress directions that should be shown.
- Enum fields are stored as their `name`, so the interface shows the schema's own vocabulary and needs no translation table.

- [ ] **Step 3: Run the test, then commit**

Run: `gradle :app:testDebugUnitTest --tests '*NodeRecordTest*'` — Expected: PASS, 7 tests.

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/NodeRecord.kt \
        app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/LocationInfo.kt \
        app/src/test/kotlin/com/cerocoder/meshrelay/stats/model/NodeRecordTest.kt
git commit -m "feat(stats): node record and location info"
```

### Task 15: Node identifiers, relative ages and the clock ticker *(Sonnet)*

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/AgeText.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/common/{NodeIdText,AgeLabel,RelativeTimeTicker}.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/AgeFormatTest.kt`

**Interfaces:**
- Consumes: `NodeId.format` (Task 6), `AgeBucket` (Task 3), `TimeSource` (Task 2).
- Produces: `sealed interface RelativeAge { Seconds(seconds); Minutes(minutes, seconds); Hours(hours, minutes); Never }`; `object AgeText { fun relative(elapsedMillis: Long): RelativeAge }`; `@Composable fun NodeIdText(nodeNum: Int, modifier)`; `@Composable fun AgeLabel(atMillis: Long, modifier)`; `val LocalRelativeClock: ProvidableCompositionLocal<Long>`; `val LocalAppResumed: ProvidableCompositionLocal<Boolean>`; `@Composable fun ProvideRelativeClock(time: TimeSource, content: @Composable () -> Unit)`.

**Deviation from spec §5.2, recorded here deliberately:** the ticker runs at a flat 1 Hz while the app is resumed, and does not step to 30 s once every visible age is over a minute. The ticker has no way to learn what is on screen without threading view state into it, and with the display on the screen dominates power anyway. What the spec actually asked for — *no work while backgrounded* — is kept in full, and is the part that matters.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.cerocoder.meshrelay.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class AgeFormatTest {

    @Test
    fun `under a minute counts in whole seconds`() {
        assertEquals(RelativeAge.Seconds(0), AgeText.relative(0))
        assertEquals(RelativeAge.Seconds(2), AgeText.relative(2_400))
        assertEquals(RelativeAge.Seconds(59), AgeText.relative(59_999))
    }

    @Test
    fun `under an hour counts in minutes and seconds`() {
        assertEquals(RelativeAge.Minutes(1, 0), AgeText.relative(60_000))
        assertEquals(RelativeAge.Minutes(1, 12), AgeText.relative(72_000))
        assertEquals(RelativeAge.Minutes(59, 59), AgeText.relative(3_599_999))
    }

    @Test
    fun `an hour and beyond counts in hours and minutes`() {
        assertEquals(RelativeAge.Hours(1, 0), AgeText.relative(3_600_000))
        assertEquals(RelativeAge.Hours(2, 5), AgeText.relative(2 * 3_600_000L + 5 * 60_000L))
    }

    @Test
    fun `an age that has not happened is never, not zero seconds`() {
        // A relay with no packet yet has lastPacketAtMillis == 0. Rendered through
        // the seconds branch it would read "0s ago" - the freshest thing on screen,
        // for the one relay that has never been heard from.
        assertEquals(RelativeAge.Never, AgeText.relative(Long.MIN_VALUE))
        assertEquals(RelativeAge.Never, AgeText.relative(-1))
    }
}
```

- [ ] **Step 2: Run it, watch it fail, then implement**

`stats/AgeText.kt` — pure, no Android. Boundaries exactly as the test states; a negative elapsed time is `Never`. Ports the `since_str` branches at `mesh_stats.py:1637-1648`.

`ui/common/NodeIdText.kt` — a `Text` showing `NodeId.format(nodeNum)` in `MaterialTheme.typography.bodySmall` (monospace, from Task 2), so columns of identifiers align.

`ui/common/AgeLabel.kt` — reads `LocalRelativeClock`, computes `AgeText.relative(clock - atMillis)`, and renders it through `format_ago_seconds`, `format_ago_minutes`, `format_ago_hours` or `common_never`. **The composable does no arithmetic beyond the subtraction and no formatting of its own** — every branch is a string resource.

`ui/common/RelativeTimeTicker.kt`:

```kotlin
/** Milliseconds, refreshed about once a second while the app is on screen. */
val LocalRelativeClock = compositionLocalOf { 0L }

/** Whether the activity is between onResume and onPause. Provided by MainActivity. */
val LocalAppResumed = compositionLocalOf { true }

/**
 * The only periodic work in the application.
 *
 * Statistics are pushed, not polled - a snapshot is rebuilt when a packet arrives,
 * never on a timer. Relative ages are the one thing that changes with nothing
 * happening, so they get a ticker, and it stops the moment the app leaves the
 * screen. Keyed on LocalAppResumed rather than on the composition, because the
 * composition survives being backgrounded and a LaunchedEffect inside it would
 * keep ticking behind a dark screen.
 */
@Composable
fun ProvideRelativeClock(time: TimeSource, content: @Composable () -> Unit) {
    val resumed = LocalAppResumed.current
    var now by remember { mutableLongStateOf(time.nowMillis()) }
    LaunchedEffect(resumed) {
        while (resumed) {
            now = time.nowMillis()
            delay(1_000)
        }
    }
    CompositionLocalProvider(LocalRelativeClock provides now) { content() }
}
```

- [ ] **Step 3: Run the test, then commit**

Run: `gradle :app:testDebugUnitTest --tests '*AgeFormatTest*'` — Expected: PASS, 4 tests.

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/stats/AgeText.kt \
        app/src/main/kotlin/com/cerocoder/meshrelay/ui/common/ \
        app/src/test/kotlin/com/cerocoder/meshrelay/stats/AgeFormatTest.kt
git commit -m "feat(ui): node identifiers, relative ages and the clock ticker

The ticker is the only periodic work in the app and stops when the app leaves
the screen. Statistics are pushed, never polled."
```

### Task 16: Signal gauge *(Sonnet)*

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/common/GaugeGeometry.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/common/SignalGauge.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/ui/common/GaugeGeometryTest.kt`

**Interfaces:**
- Consumes: `SignalStats` (Task 4), `SignalScales` (Task 2), `GaugeMode` (Task 2), theme colours (Task 2).
- Produces: `data class GaugeMarks(val fillStart: Float, val fillEnd: Float, val avg: Float?, val last: Float?)`; `object GaugeGeometry { fun marks(stats: SignalStats, scaleMin: Float, scaleMax: Float, mode: GaugeMode): GaugeMarks }`; `@Composable fun SignalGauge(stats: SignalStats, scaleMin: Float, scaleMax: Float, mode: GaugeMode, lastPacketAtMillis: Long, trackColor: Color, markerColor: Color, modifier: Modifier)`.

Ports `render_bar_simple` and `render_bar_complex`, `mesh_stats.py:1167-1263`. All positions are fractions of the track in `0f..1f`; the composable multiplies by its measured width and never computes anything else.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.cerocoder.meshrelay.ui.common

import com.cerocoder.meshrelay.settings.GaugeMode
import com.cerocoder.meshrelay.stats.SignalScales
import com.cerocoder.meshrelay.stats.model.SignalStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

private const val MIN = SignalScales.SNR_MIN   // -20
private const val MAX = SignalScales.SNR_MAX   // +15

private fun stats(vararg values: Float) = values.fold(SignalStats.EMPTY) { acc, v -> acc.plus(v) }

class GaugeGeometryTest {

    @Test
    fun `an empty gauge draws nothing at all`() {
        val marks = GaugeGeometry.marks(SignalStats.EMPTY, MIN, MAX, GaugeMode.COMPLEX)
        assertEquals(0f, marks.fillStart, 0.0001f)
        assertEquals(0f, marks.fillEnd, 0.0001f)
        assertNull(marks.avg)
        assertNull(marks.last)
    }

    @Test
    fun `simple mode fills from the scale floor to the latest value`() {
        val marks = GaugeGeometry.marks(stats(-20f, -2.5f), MIN, MAX, GaugeMode.SIMPLE)
        assertEquals(0f, marks.fillStart, 0.0001f)
        assertEquals(0.5f, marks.fillEnd, 0.0001f)
        assertNull(marks.avg)
        assertNull(marks.last)
    }

    @Test
    fun `complex mode fills the observed span and marks the average and the latest`() {
        // Values -20, 15, -2.5: span is the whole track, average is -2.5 (the middle),
        // latest is -2.5 as well.
        val marks = GaugeGeometry.marks(stats(-20f, 15f, -2.5f), MIN, MAX, GaugeMode.COMPLEX)
        assertEquals(0f, marks.fillStart, 0.0001f)
        assertEquals(1f, marks.fillEnd, 0.0001f)
        assertEquals(0.5f, marks.avg!!, 0.0001f)
        assertEquals(0.5f, marks.last!!, 0.0001f)
    }

    @Test
    fun `both marks are returned even when they coincide`() {
        // The original gives the latest-value marker priority when it lands on the
        // average. Geometry does not resolve that - it returns both, and the
        // composable draws the average first so the latest covers it.
        val marks = GaugeGeometry.marks(stats(-5f), MIN, MAX, GaugeMode.COMPLEX)
        assertNotNull(marks.avg)
        assertNotNull(marks.last)
        assertEquals(marks.avg!!, marks.last!!, 0.0001f)
    }

    @Test
    fun `a single sample gives a span of zero width rather than an inverted one`() {
        val marks = GaugeGeometry.marks(stats(-5f), MIN, MAX, GaugeMode.COMPLEX)
        assertEquals(marks.fillStart, marks.fillEnd, 0.0001f)
    }

    @Test
    fun `values beyond the scale are clamped into the track`() {
        // A node can report SNR above the scale maximum. Without clamping the fill
        // would be drawn past the end of its own track.
        val marks = GaugeGeometry.marks(stats(-200f, 200f), MIN, MAX, GaugeMode.COMPLEX)
        assertEquals(0f, marks.fillStart, 0.0001f)
        assertEquals(1f, marks.fillEnd, 0.0001f)
    }
}
```

- [ ] **Step 2: Run it, watch it fail, then implement the geometry**

```kotlin
object GaugeGeometry {
    fun marks(stats: SignalStats, scaleMin: Float, scaleMax: Float, mode: GaugeMode): GaugeMarks {
        if (!stats.hasData) return GaugeMarks(0f, 0f, null, null)
        fun at(value: Float) = SignalScales.fraction(value, scaleMin, scaleMax)
        return when (mode) {
            GaugeMode.SIMPLE -> GaugeMarks(0f, at(stats.lastVal), null, null)
            GaugeMode.COMPLEX -> GaugeMarks(at(stats.minVal), at(stats.maxVal), at(stats.avg), at(stats.lastVal))
        }
    }
}
```

- [ ] **Step 3: Write the composable**

`SignalGauge` is a `Canvas` of fixed height inside a `Row` with the numeric value beside it. Drawing order, and nothing else:

1. The empty track, at low alpha, full width.
2. The fill, from `fillStart * width` to `fillEnd * width`, in `trackColor`.
3. `avg`, if present: a thin vertical rule in `markerColor`.
4. `last`, if present: a thicker vertical rule, drawn **after** the average so it covers it when they coincide — which is the original's priority rule, expressed as draw order rather than as a branch.

The last-value marker flashes:

```kotlin
val flashing by produceState(false, lastPacketAtMillis) {
    // A packet has just landed. Light the marker, then put it out - no polling,
    // no timer that outlives the row.
    if (lastPacketAtMillis == 0L) return@produceState
    value = true
    delay(SignalScales.FLASH_MILLIS)
    value = false
}
val markColor = if (flashing) FlashMarker else markerColor
```

Add `@Preview` composables for: no data, simple mode, complex mode, and complex mode mid-flash.

- [ ] **Step 4: Run the test, then commit**

Run: `gradle :app:testDebugUnitTest --tests '*GaugeGeometryTest*'` — Expected: PASS, 6 tests.

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/ui/common/Gauge*.kt \
        app/src/main/kotlin/com/cerocoder/meshrelay/ui/common/SignalGauge.kt \
        app/src/test/kotlin/com/cerocoder/meshrelay/ui/common/GaugeGeometryTest.kt
git commit -m "feat(ui): signal gauge with both terminal modes

Geometry is a pure function so the arithmetic has a test; the composable only
draws. The original's 'latest wins over average' rule becomes draw order
rather than a branch."
```

### Task 17: Demo scenarios carrying relay traffic *(Sonnet)*

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/emulator/{MeshScenario,Scenarios}.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/emulator/ScenariosTest.kt`

**Interfaces:**
- Consumes: `FakeRadioTransport` (Task 9).
- Produces: `MeshScenario` with `id`, `displayName`, `nodes: List<NodeInfo>`, `configStageFrames(nonce: Int)`, `nodeStageFrames(nonce: Int)`, and **new**: `trafficFrames(): Sequence<FromRadio>` plus `trafficIntervalMillis: Long`; `object Scenarios { val all: List<MeshScenario>; fun byId(id: String): MeshScenario? }`.

Copy `MeshScenario` and `Scenarios` from `mesh-test-android`, translate the comments, then extend. The skeleton's scenarios stop after the handshake; this app has nothing to show until traffic arrives, so the scenario must keep producing packets.

`FakeRadioTransport` needs a loop emitting `trafficFrames()` every `trafficIntervalMillis` after the second handshake stage completes, cycling when the sequence is exhausted.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.cerocoder.meshrelay.emulator

import com.cerocoder.meshrelay.stats.Geo
import com.cerocoder.meshrelay.stats.Ingest
import com.cerocoder.meshrelay.stats.PacketClassifier
import org.junit.Assert.assertTrue
import org.junit.Test

class ScenariosTest {

    private val scenario = Scenarios.all.first()
    private val traffic = scenario.trafficFrames().take(400).mapNotNull { it.packet }.toList()

    @Test
    fun `there is enough traffic to fill a screen`() {
        assertTrue("traffic must not run out immediately", traffic.size >= 100)
    }

    @Test
    fun `traffic exercises every branch of the classifier`() {
        val kinds = traffic.map { PacketClassifier.classify(it, emptySet()) }
        assertTrue("no relayed packets", kinds.any { it is Ingest.Relayed })
        assertTrue("no direct packets", kinds.any { it is Ingest.Direct })
    }

    @Test
    fun `several distinct relays appear`() {
        val relays = traffic.mapNotNull { (PacketClassifier.classify(it, emptySet()) as? Ingest.Relayed)?.relayByte }
        assertTrue("need at least three relays, had ${relays.distinct().size}", relays.distinct().size >= 3)
    }

    @Test
    fun `at least one relay byte matches more than one node in the database`() {
        // The whole reason the relay list shows a match count: one byte is not an
        // identity. A demo where every byte resolves uniquely would let a screen
        // that ignores ambiguity look correct.
        val byLowByte = scenario.nodes.groupBy { Geo.lastByteOfNodeNum(it.num) }
        assertTrue("no ambiguous relay byte in the scenario", byLowByte.any { it.value.size > 1 })
    }

    @Test
    fun `signal strengths span enough of the scale to show a gauge working`() {
        val snr = traffic.filter { it.rx_rssi != 0 }.map { it.rx_snr }
        assertTrue("SNR spread too narrow", snr.max() - snr.min() >= 10f)
    }

    @Test
    fun `encrypted packets are present`() {
        // Most real mesh traffic cannot be decoded by the phone, and a demo made
        // only of decoded packets would hide any screen that mishandles them.
        assertTrue(traffic.any { it.encrypted != null })
    }

    @Test
    fun `position and telemetry packets are present`() {
        val ports = traffic.mapNotNull { it.decoded?.portnum?.name }.toSet()
        assertTrue("no POSITION_APP", "POSITION_APP" in ports)
        assertTrue("no TELEMETRY_APP", "TELEMETRY_APP" in ports)
    }

    @Test
    fun `every scenario identifier is unique`() {
        assertTrue(Scenarios.all.map { it.id }.distinct().size == Scenarios.all.size)
    }
}
```

- [ ] **Step 2: Run it, watch it fail, then build the scenario**

Build one scenario, `zona-centro`, modelled on the mesh this tool was written for:

- **Twelve nodes**, with names, roles and positions in the Madrid–Toledo corridor. Give two of them node numbers sharing a low byte, so one relay byte is genuinely ambiguous. Give one a number ending in `00`, so `lastByteOfNodeNum`'s `0xff` substitution is exercised end to end. Roles: two `ROUTER`, one `CLIENT_MUTE`, the rest `CLIENT`.
- **Four relay bytes** in the traffic, with distinctly different signal profiles: one strong and steady, one weak and variable, one intermittent with long gaps, one that appears only later in the sequence.
- **Traffic**: a repeating sequence of at least 120 packets mixing `TEXT_MESSAGE_APP`, `POSITION_APP`, `TELEMETRY_APP` (device metrics including a falling `uptime_seconds` at one point, so restart detection has something to detect) and `NODEINFO_APP`, plus encrypted packets with no `decoded` payload. Hop counts vary between 1 and 3.
- `trafficIntervalMillis = 700` — fast enough to watch the list build, slow enough to see the flash.

- [ ] **Step 3: Extend `FakeRadioTransport`**

After the node stage completes, start a coroutine in the transport's scope emitting one frame per `trafficIntervalMillis`, looping the sequence. It must stop on `close()` like every other part of the transport.

- [ ] **Step 4: Run the test, then commit**

Run: `gradle :app:testDebugUnitTest --tests '*ScenariosTest*'` — Expected: PASS, 8 tests.

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/emulator app/src/test/kotlin/com/cerocoder/meshrelay/emulator
git commit -m "feat(emulator): demo scenario with relay traffic

Modelled on the Madrid-Toledo mesh, including an ambiguous relay byte and a
node number ending in 00, so the screens are developed against the two cases
that make relay identification a guess rather than a lookup."
```

**Round 2 gate:** `gradle :app:testDebugUnitTest` green before Round 3.

---

# Round 3 — three tasks, dispatchable in parallel

### Task 18: Node directory, snapshots and the relay index *(Opus)*

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/NodeDirectory.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/RelayIndex.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/NodeDirectorySnapshot.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/model/StatsSnapshot.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/NodeDirectoryTest.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/RelayIndexTest.kt`

**Interfaces:**
- Consumes: `Geo` (Task 3), `PositionReport`/`PositionHistory` (Task 5), aggregates (Task 12), `NodeRecord`/`LocationInfo` (Task 14), `TimeSource`, `SortMode` (Task 2).
- Produces: the signatures in spec §7 and §6.4.

Ports `StatsCollector`'s database half: `mesh_stats.py:704-762`, `:793-919`, `:921-999`.

`NodeDirectory` is mutable and lives inside the engine's single coroutine, so it uses plain `HashMap`s and no synchronisation at all. `snapshot()` is the only thing that crosses a thread boundary, and it copies.

**Do not port `_local_stats_last`** (`mesh_stats.py:527`, `:999`). It is written and never read — dead code in the original. Noted here so a reviewer does not read its absence as an omission.

- [ ] **Step 1: Write the failing tests**

`NodeDirectoryTest.kt` — the cases that matter, each with the reason it exists:

```kotlin
@Test fun `a live position beats the database position`()
// Ports the CUR-over-DB precedence at mesh_stats.py:862-880. The database entry
// can be days old; a position packet heard a minute ago is the better answer, and
// the source label is what tells the user which they are looking at.

@Test fun `the database position is used when nothing has been heard live`()

@Test fun `a live position with no altitude falls back to the database`()
// Consequence of PositionHistory.best returning null unless a report has both
// coordinates and altitude. Fixing the quirk here would make the source label
// disagree with the terminal tool.

@Test fun `distance and direction are computed from the local position`()
// Madrid -> Getafe: 12.307 km, bearing 191.4, direction S.

@Test fun `direction is unknown when the obfuscation radius reaches the distance`()
// A node 300 m away that published its position to 13 bits of precision has an
// uncertainty of 2.9 km. Any direction printed would be invented.

@Test fun `distance is absent when the local position is unknown`()

@Test fun `matching nodes are those whose low byte equals the relay byte`()

@Test fun `a skipped node is not offered as a candidate`()

@Test fun `a node whose number ends in zero matches relay byte ff`()

@Test fun `the relay name is shown only when exactly one node matches`()
// Ports get_node_name, mesh_stats.py:734-750. With two candidates there is no
// name to show, and showing either would present a guess as a fact.

@Test fun `a node info frame and a later node info packet merge rather than replace`()
// A NODEINFO_APP packet carries a User and nothing else. Replacing the record
// would erase the position learned from the handshake.

@Test fun `an uptime that falls back is counted as a restart`()

@Test fun `device, environment and power metrics are all recorded under their protobuf names`()
// Keys are shown verbatim, so they must be the schema's own: battery_level,
// voltage, channel_utilization, air_util_tx, temperature, current, ch1_voltage ...

@Test fun `clearing runtime data keeps the node database and the skip list`()
// Ports reset, mesh_stats.py:1100-1113. Reset is for starting a fresh measurement,
// not for forgetting who is out there.

@Test fun `a snapshot does not change when the directory afterwards does`()
// The snapshot crosses to the UI thread. If it shared the directory's maps, the
// engine would be mutating live data underneath a running composition.
```

`RelayIndexTest.kt`:

```kotlin
@Test fun `relays carrying a node are returned most packets first`()
@Test fun `a node carried by no relay yields an empty list`()
@Test fun `a node carried by several relays yields all of them`()
// The reason this exists at all: the README calls knowing it essential, and the
// terminal tool can only ever show one relay at a time.
```

Write these out in full with bodies before implementing.

- [ ] **Step 2: Run them and watch them fail**

Run: `gradle :app:testDebugUnitTest --tests '*NodeDirectoryTest*' --tests '*RelayIndexTest*'`

- [ ] **Step 3: Implement `NodeDirectory`**

Behaviour, precisely:

- `applyNodeInfo(info)` maps through `NodeRecord.fromProto` and **merges** onto any existing record rather than replacing it: a field the new message leaves absent keeps its previous value.
- `applyUser(nodeNum, user)` updates identity fields only, creating a bare record if the node is unknown. Nodes appear in traffic before they appear in the database.
- `applyPosition(nodeNum, position)` appends to that node's `PositionHistory`, timestamped from the injected `TimeSource`.
- `applyTelemetry(nodeNum, telemetry, atMillis)` folds `DeviceMetrics` (`battery_level`, `voltage`, `channel_utilization`, `air_util_tx`, and `uptime_seconds` through `withUptime`), `EnvironmentMetrics` (`temperature`, `voltage`, `current`) and `PowerMetrics` (`ch1_voltage`…`ch8_voltage`, `ch1_current`…`ch8_current`). Keys are the protobuf names verbatim.
- `clearRuntimeData()` clears positions and telemetry; nodes, `localNodeNum` and `loadedAtMillis` survive.
- `snapshot(skipped)` copies all three maps into the immutable snapshot.

- [ ] **Step 4: Implement `NodeDirectorySnapshot`**

`locationInfo(nodeNum, from)` in exactly this order, porting `mesh_stats.py:830-919`:

1. `positions[nodeNum]?.best` → `source = CURRENT`, `atMillis` from the report, `precisionBits` from the report.
2. Otherwise `nodes[nodeNum]?.dbPosition` → `source = DB`, `atMillis` from it, `precisionBits = null`.
3. Altitude is filled from whichever was used, even when coordinates are absent.
4. No coordinates → return with `distanceKm = null` and `direction = UNKNOWN`.
5. Coordinates and a local position → `distanceKm` by haversine; `obfuscationRadiusMeters` from the precision; then **`direction = UNKNOWN` when the radius is greater than or equal to the distance in metres**, otherwise from the bearing.

`matchingNodeNums(relayByte)` filters on `Geo.lastByteOfNodeNum(num) == relayByte` and excludes the skip-list; results are sorted ascending so the numbered `[1]`, `[2]` labels on the detail screen are stable between recompositions. `uniqueRelayName` returns the short name only when exactly one node matches, `""` otherwise. `localPosition()` resolves through `locationInfo(localNodeNum, null)`.

- [ ] **Step 5: Implement `RelayIndex` and `StatsSnapshot`**

`RelayIndex.relaysCarrying(nodeNum, relays)` filters `relays` to those whose `fromNodeStats` contains the node, sorted by that node's packet count through the relay, descending.

`StatsSnapshot` is the value type from spec §6.4 with an `EMPTY` companion — empty lists, `Counters.EMPTY`, `paused = false`, `SortMode.PACKETS`, an empty directory snapshot.

- [ ] **Step 6: Run the tests, then commit**

Run: `gradle :app:testDebugUnitTest --tests '*NodeDirectoryTest*' --tests '*RelayIndexTest*'`
Expected: PASS.

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/stats/ app/src/test/kotlin/com/cerocoder/meshrelay/stats/
git commit -m "feat(stats): node directory, snapshots and the relay index

Mutable directory confined to the engine's coroutine; the snapshot is the only
thing that crosses a thread, and it copies. RelayIndex adds what the terminal
tool cannot show: every relay carrying one node's traffic, in one place."
```

### Task 19: Position line and map links *(Sonnet)*

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/common/{PositionLineText,PositionLine,MapLinks}.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/ui/common/PositionLineTextTest.kt`

**Interfaces:**
- Consumes: `LocationInfo` (Task 14), `AgeBucket` (Task 3), string keys (Task 7).
- Produces: `data class PositionParts(val coordinates: String?, val distance: String?, val altitude: String?, val source: String?)`; `object PositionLineText { fun parts(info: LocationInfo, nowMillis: Long, res: PositionStrings): PositionParts }` where `PositionStrings` is a small value object holding the already-resolved format strings and direction labels; `@Composable fun PositionLine(info: LocationInfo, nodeNum: Int, meshviewUrl: String?, modifier)`; `object MapLinks { fun googleMaps(lat: Double, lon: Double): String; fun openStreetMap(lat: Double, lon: Double): String; fun meshview(baseUrl: String, nodeNum: Int): String }`.

Ports `render_position_oneline`, `mesh_stats.py:1747-1800`, and the map URLs at `:1868-1872`.

Passing resolved strings in through `PositionStrings` is what keeps the formatter pure and testable while still letting it produce localised text — the composable resolves the resources and hands them over.

- [ ] **Step 1: Write the failing test**

Cases, each with its reason:

```kotlin
@Test fun `coordinates are shown to six decimal places`()
@Test fun `distance carries the direction when one is known`()          // "12.3 km/S"
@Test fun `distance carries the uncertainty when the position is obfuscated`()
// "2.1±2.9 km" - and the uncertainty is only shown from 0.1 km up, because below
// that it is narrower than the distance is precise.
@Test fun `no direction is appended when the direction is unknown`()
@Test fun `altitude is omitted rather than shown as zero when absent`()
@Test fun `the source is shown with its age bucket`()                   // "Src: DB:5m"
@Test fun `the source is shown without an age when no timestamp is known`()
@Test fun `an empty location produces no parts at all`()
// A node that never published a position must render as nothing, not as a line of
// dashes - the relay list has one of these for most relays.
```

Map links:

```kotlin
@Test fun `map links use the coordinate order each site expects`() {
    assertEquals("https://maps.google.com/?q=40.4168,-3.7038", MapLinks.googleMaps(40.4168, -3.7038))
    assertEquals(
        "https://www.openstreetmap.org/?mlat=40.4168&mlon=-3.7038&zoom=15",
        MapLinks.openStreetMap(40.4168, -3.7038),
    )
    assertEquals("https://meshview.meshtastic.es/node/42", MapLinks.meshview("https://meshview.meshtastic.es/", 42))
}
```

The Meshview case pins down that a trailing slash on the configured base URL is trimmed — the setting is typed by hand, and a double slash gives a 404 that looks like a broken link rather than a typo.

Coordinates and distances are formatted through `java.util.Locale.ROOT` inside `MapLinks` — a URL must never carry a Spanish decimal comma — while `PositionLineText` formats for display using the active locale, which is why the two do not share a formatter.

- [ ] **Step 2: Run it, watch it fail, implement, run again, commit**

`PositionLine` renders the parts as flowing text with the map links as small `TextButton`s, opening through an `Intent(Intent.ACTION_VIEW, url.toUri())`. It shows the Meshview button only when a base URL is configured.

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/ui/common/ app/src/test/kotlin/com/cerocoder/meshrelay/ui/common/PositionLineTextTest.kt
git commit -m "feat(ui): position line and map links

URLs are formatted with Locale.ROOT and display text with the active locale:
a Spanish decimal comma in a coordinate produces a link to nowhere."
```

### Task 20: Preview sample data *(Sonnet)*

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/preview/SampleData.kt`

**Interfaces:**
- Consumes: every model type from Tasks 12, 14, 18.
- Produces: `object SampleData` with `val snapshot: StatsSnapshot`, `val emptySnapshot: StatsSnapshot`, `val pausedSnapshot: StatsSnapshot`, `fun relay(byte: Int): RelayStats`, `val directory: NodeDirectorySnapshot`.

Every screen in Round 4 previews from this object, and nine agents build those screens without seeing each other's work — so the data has to cover the awkward cases, not the tidy ones. It must contain:

- a relay with exactly one matching node, so the name and distance appear;
- a relay with three matching nodes, so the name is absent and the count reads `[3]`;
- a relay with no matching node at all, so the "may not have broadcast its node info" path is visible;
- a relay with one skipped candidate, so the skipped list on the detail screen is non-empty;
- a relay heard once, so `packetsPerHour` is `0` and the gauge has a single-point span;
- a relay never heard, so the age reads `Never` rather than `0s ago`;
- a node with a position, one with a position but no altitude, and one with none;
- a node with an obfuscated position whose radius exceeds its distance, so `Direction.UNKNOWN` is on screen;
- a node with telemetry including a restart count, and one with none;
- a very long node name, so truncation is visible in the layout rather than discovered on a phone.

No test file: this is fixture data, exercised by the previews and by Round 4's screens.

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/ui/preview/SampleData.kt
git commit -m "feat(ui): preview fixtures covering the awkward cases

Nine screens are built in parallel against this object, so it carries the
ambiguous relay byte, the missing position and the node never heard from -
the cases a tidy fixture would hide until the phone."
```

**Round 3 gate:** `gradle :app:testDebugUnitTest` green before Round 4.

---

# Round 4 — nine tasks, dispatchable in parallel

### Task 21: The statistics engine *(Opus)*

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngine.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngineTest.kt`

**Interfaces:**
- Consumes: everything in `stats/`.
- Produces: the surface in spec §8.1.

Ports the state half of `StatsCollector`: `mesh_stats.py:1001-1155`. **Read spec §5.1 and §5.2 first.** This is the one file whose defects a single-threaded test suite cannot see, which is why its shape — not just its behaviour — is prescribed.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.cerocoder.meshrelay.stats

import app.cash.turbine.Turbine // NOT AVAILABLE - collect into a list instead
```

Do **not** add a test library. Collect into a list on `backgroundScope`:

```kotlin
package com.cerocoder.meshrelay.stats

import com.cerocoder.meshrelay.stats.model.StatsSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.meshtastic.proto.FromRadio
import org.meshtastic.proto.MeshPacket

private const val SENDER = 0x9e75f1a4.toInt()

private fun relayed(relay: Int = 0x69, from: Int = SENDER, snr: Float = -7.5f, rssi: Int = -94) =
    TimestampedFrame(
        rxMillis = 1_000L,
        frame = FromRadio(
            packet = MeshPacket(
                from = from, relay_node = relay, hop_start = 3, hop_limit = 1,
                rx_snr = snr, rx_rssi = rssi,
            ),
        ),
    )

private fun direct(from: Int = SENDER) = TimestampedFrame(
    rxMillis = 1_000L,
    frame = FromRadio(packet = MeshPacket(from = from, relay_node = 0, rx_snr = -3f, rx_rssi = -80)),
)

class MeshStatsEngineTest {

    private fun engine(scope: kotlinx.coroutines.CoroutineScope, skipped: MutableStateFlow<Set<Int>> = MutableStateFlow(emptySet())) =
        MeshStatsEngine(scope, skipped, SortMode.PACKETS) { 1_000L }

    @Test
    fun `a relayed packet builds a relay entry`() = runTest(StandardTestDispatcher()) {
        val subject = engine(backgroundScope)
        val seen = mutableListOf<StatsSnapshot>()
        backgroundScope.launch { subject.snapshot.collect { seen += it } }
        advanceUntilIdle()

        subject.attach(flowOf(relayed()))
        advanceUntilIdle()

        val relay = seen.last().relays.single()
        assertEquals(0x69, relay.relayByte)
        assertEquals(1, relay.packetCount)
        assertEquals(-7.5f, relay.snr.lastVal, 0.0001f)
        assertEquals(1, seen.last().counters.totalRelayedPackets)
    }

    @Test
    fun `a direct packet builds a neighbour entry and no relay`() = runTest(StandardTestDispatcher()) {
        val subject = engine(backgroundScope)
        val seen = mutableListOf<StatsSnapshot>()
        backgroundScope.launch { subject.snapshot.collect { seen += it } }
        advanceUntilIdle()

        subject.attach(flowOf(direct()))
        advanceUntilIdle()

        assertTrue(seen.last().relays.isEmpty())
        assertEquals(SENDER, seen.last().neighbours.single().nodeNum)
        assertEquals(1, seen.last().counters.totalDirectPackets)
    }

    @Test
    fun `a burst of frames costs one snapshot, not one per frame`() = runTest(StandardTestDispatcher()) {
        // The whole reason the loop drains before building. At mesh traffic rates a
        // snapshot per packet would rebuild and recompose the list dozens of times a
        // second for no visible difference.
        val subject = engine(backgroundScope)
        val seen = mutableListOf<StatsSnapshot>()
        backgroundScope.launch { subject.snapshot.collect { seen += it } }
        advanceUntilIdle()
        val before = seen.size

        subject.attach(flowOf(*Array(30) { relayed() }))
        advanceUntilIdle()

        assertEquals("expected one snapshot for the burst", 1, seen.size - before)
        assertEquals(30, seen.last().relays.single().packetCount)
    }

    @Test
    fun `no snapshot is built while nothing is subscribed`() = runTest(StandardTestDispatcher()) {
        // With the screen off and the foreground service running, this is the whole
        // battery argument. Ingestion must continue; snapshot building must not.
        val subject = engine(backgroundScope)
        subject.attach(flowOf(relayed(), relayed(), relayed()))
        advanceUntilIdle()

        assertSame(StatsSnapshot.EMPTY, subject.snapshot.value)
        assertEquals(3, subject.counters.value.totalRelayedPackets)
    }

    @Test
    fun `subscribing after the fact gets the current state, not an empty one`() = runTest(StandardTestDispatcher()) {
        val subject = engine(backgroundScope)
        subject.attach(flowOf(relayed()))
        advanceUntilIdle()

        val seen = mutableListOf<StatsSnapshot>()
        backgroundScope.launch { subject.snapshot.collect { seen += it } }
        advanceUntilIdle()

        assertEquals(1, seen.last().relays.single().packetCount)
    }

    @Test
    fun `a paused engine drops packets whole`() = runTest(StandardTestDispatcher()) {
        // Ports mesh_stats.py:1012-1014: paused means the packet never happened, not
        // that it happened and was not displayed. Even the total is untouched.
        val subject = engine(backgroundScope)
        backgroundScope.launch { subject.snapshot.collect {} }
        advanceUntilIdle()
        subject.setPaused(true)
        advanceUntilIdle()

        subject.attach(flowOf(relayed()))
        advanceUntilIdle()

        assertEquals(Counters.EMPTY, subject.counters.value)
        assertTrue(subject.snapshot.value.relays.isEmpty())
        assertTrue(subject.snapshot.value.paused)
    }

    @Test
    fun `reset clears statistics but keeps the node database and the skip list`() = runTest(StandardTestDispatcher()) {
        val skipped = MutableStateFlow(setOf(0x11223344))
        val subject = engine(backgroundScope, skipped)
        backgroundScope.launch { subject.snapshot.collect {} }
        subject.attach(flowOf(nodeInfoFrame(SENDER, "PQPL1"), relayed()))
        advanceUntilIdle()

        subject.reset()
        advanceUntilIdle()

        val after = subject.snapshot.value
        assertTrue(after.relays.isEmpty())
        assertEquals(Counters.EMPTY, after.counters)
        assertEquals(1, after.directory.count)                       // the node survives
        assertEquals(setOf(0x11223344), after.skippedRelayNodes)     // so does the skip list
    }

    @Test
    fun `changing the sort mode reorders without losing anything`() = runTest(StandardTestDispatcher()) {
        val subject = engine(backgroundScope)
        backgroundScope.launch { subject.snapshot.collect {} }
        subject.attach(flowOf(relayed(relay = 0x69, snr = -15f), relayed(relay = 0x69, snr = -15f), relayed(relay = 0xa4, snr = 5f)))
        advanceUntilIdle()

        subject.setSortMode(SortMode.PACKETS)
        advanceUntilIdle()
        assertEquals(listOf(0x69, 0xa4), subject.snapshot.value.relays.map { it.relayByte })

        subject.setSortMode(SortMode.AVG_SNR)
        advanceUntilIdle()
        assertEquals(listOf(0xa4, 0x69), subject.snapshot.value.relays.map { it.relayByte })
    }

    @Test
    fun `a relay with no signal samples sorts last rather than first`() = runTest(StandardTestDispatcher()) {
        // A relay whose packets all arrived with rx_rssi == 0 has no average. Treated
        // as 0.0 dB it would outrank every real measurement on the screen.
        val subject = engine(backgroundScope)
        backgroundScope.launch { subject.snapshot.collect {} }
        subject.attach(flowOf(relayed(relay = 0x69, snr = -15f), relayed(relay = 0xa4, rssi = 0, snr = 0f)))
        advanceUntilIdle()
        subject.setSortMode(SortMode.AVG_SNR)
        advanceUntilIdle()

        assertEquals(listOf(0x69, 0xa4), subject.snapshot.value.relays.map { it.relayByte })
    }

    @Test
    fun `skipping a node changes which relays it is a candidate for`() = runTest(StandardTestDispatcher()) {
        val skipped = MutableStateFlow(emptySet<Int>())
        val subject = engine(backgroundScope, skipped)
        backgroundScope.launch { subject.snapshot.collect {} }
        subject.attach(flowOf(nodeInfoFrame(SENDER, "PQPL1"), relayed(relay = 0xa4)))
        advanceUntilIdle()
        assertEquals("1ce5", subject.snapshot.value.directory.uniqueRelayName(0xa4))

        skipped.value = setOf(SENDER)
        advanceUntilIdle()

        assertEquals("", subject.snapshot.value.directory.uniqueRelayName(0xa4))
    }

    @Test
    fun `an undecodable payload does not stop ingestion`() = runTest(StandardTestDispatcher()) {
        // One malformed position packet must not end the only channel data arrives on.
        val subject = engine(backgroundScope)
        backgroundScope.launch { subject.snapshot.collect {} }
        subject.attach(flowOf(brokenPositionFrame(), relayed()))
        advanceUntilIdle()

        assertEquals(1, subject.snapshot.value.relays.single().packetCount)
    }
}
```

Write `nodeInfoFrame(num, longName)` and `brokenPositionFrame()` as private helpers in the test file — the first a `FromRadio(node_info = NodeInfo(num = …, user = User(long_name = …, short_name = "1ce5")))`, the second a `POSITION_APP` packet whose payload is bytes that do not decode.

- [ ] **Step 2: Run them and watch them fail**

Run: `gradle :app:testDebugUnitTest --tests '*MeshStatsEngineTest*'`
Expected: FAIL — `Unresolved reference: MeshStatsEngine`.

- [ ] **Step 3: Write the engine**

```kotlin
package com.cerocoder.meshrelay.stats

/**
 * Owns every mutable statistic in the application.
 *
 * One coroutine, one owner. Commands - frames and user actions alike - arrive
 * through a channel and are applied in order on that coroutine, so there is no
 * lock here, no @Volatile and no Atomic. That is deliberate rather than
 * economical: the whole test suite is single-threaded while this runs on
 * Dispatchers.Default, so a concurrency defect would be invisible to every test
 * that could be written. Confinement removes the class of defect instead of
 * testing for it.
 *
 * There is no tick. A snapshot is built when state changes and someone is
 * watching, never on a timer.
 */
class MeshStatsEngine(
    private val scope: CoroutineScope,
    skippedRelayNodes: StateFlow<Set<Int>>,
    initialSortMode: SortMode,
    private val time: TimeSource = SystemTimeSource,
) {

    private sealed interface Command {
        data class Frame(val frame: TimestampedFrame) : Command
        data class SetPaused(val paused: Boolean) : Command
        data class SetSort(val mode: SortMode) : Command
        data class SetSkipped(val skipped: Set<Int>) : Command
        data object Reset : Command
        data object Refresh : Command
    }

    private val commands = Channel<Command>(capacity = COMMAND_CAPACITY)

    // Everything below is touched only from the consumer coroutine started in init.
    private val relays = HashMap<Int, RelayStats>()
    private val neighbours = HashMap<Int, NeighbourStats>()
    private val directory = NodeDirectory(time)
    private var counterState = Counters.EMPTY
    private var paused = false
    private var sortMode = initialSortMode
    private var skipped: Set<Int> = emptySet()
    private var lastPacketAtMillis: Long? = null
    private var lastRelayedPacketAtMillis: Long? = null

    private val _snapshot = MutableStateFlow(StatsSnapshot.EMPTY)
    val snapshot: StateFlow<StatsSnapshot> = _snapshot.asStateFlow()

    /** Four integers for the foreground notification. Cheap enough to publish always. */
    private val _counters = MutableStateFlow(Counters.EMPTY)
    val counters: StateFlow<Counters> = _counters.asStateFlow()

    init {
        scope.launch {
            // The skip-list belongs to SettingsRepository, which touches storage.
            // It reaches the engine as a plain flow and becomes a command like any
            // other, so it lands on the owning coroutine rather than racing it.
            launch { skippedRelayNodes.collect { commands.send(Command.SetSkipped(it)) } }

            // A screen that has just opened must not see EMPTY while the engine
            // already holds an afternoon of statistics.
            launch {
                _snapshot.subscriptionCount
                    .map { it > 0 }
                    .distinctUntilChanged()
                    .collect { watched -> if (watched) commands.send(Command.Refresh) }
            }

            for (command in commands) {
                apply(command)
                // Drain whatever is already queued before building. A burst of thirty
                // packets must cost one snapshot, not thirty - at mesh traffic rates
                // the difference is dozens of rebuilds a second for no visible gain.
                while (true) {
                    apply(commands.tryReceive().getOrNull() ?: break)
                }
                _counters.value = counterState
                // Nothing subscribed means nothing to build. With the screen off and
                // the service running, this is what keeps the app cheap: ingestion
                // continues, snapshot building stops entirely.
                if (_snapshot.subscriptionCount.value > 0) {
                    _snapshot.value = buildSnapshot()
                }
            }
        }
    }

    fun attach(frames: Flow<TimestampedFrame>): Job =
        scope.launch { frames.collect { commands.send(Command.Frame(it)) } }

    fun setPaused(paused: Boolean) { commands.trySend(Command.SetPaused(paused)) }
    fun setSortMode(mode: SortMode) { commands.trySend(Command.SetSort(mode)) }
    fun reset() { commands.trySend(Command.Reset) }

    private companion object {
        const val COMMAND_CAPACITY = 256
    }
}
```

The remaining private methods, described precisely:

**`apply(command)`** dispatches. `SetSkipped` stores the set and refreshes every relay's `nodeName` through `directory.snapshot(skipped).uniqueRelayName(byte)`, because a skip changes which candidates a relay has and therefore whether it has a unique name at all.

**`handleFrame(tf)`**, in this order:

1. `frame.my_info` → `directory.setLocalNodeNum(it.my_node_num)`.
2. `frame.node_info` → `directory.applyNodeInfo(it)`.
3. `frame.config_complete_id != null` → `directory.markLoaded(tf.rxMillis)`.
4. `val packet = frame.packet ?: return`.
5. **`if (paused) return`** — here, after the handshake frames and before anything else. Pausing means the packet never happened; the node database is not statistics and keeps updating.
6. `counterState = counterState.copy(totalPackets = counterState.totalPackets + 1)`; `lastPacketAtMillis = tf.rxMillis`.
7. Decode the payload by portnum — `POSITION_APP` → `Position` → `directory.applyPosition`; `NODEINFO_APP` → `User` → `directory.applyUser`; `TELEMETRY_APP` → `Telemetry` → `directory.applyTelemetry`. Each inside `runCatching`: a malformed payload must not end the only channel data arrives on. Catch `Exception`, not `IOException` — Wire's generated constructors validate `oneof` occupancy with `require()`, and a frame with two variants filled throws `IllegalArgumentException` straight past a narrower catch.
8. `PacketClassifier.classify(packet, skipped)`, then fold the result.

**Folding** `Ingest.Relayed`: bump `totalRelayedPackets`, set `lastRelayedPacketAtMillis`, create the `RelayStats` if new with its name from `uniqueRelayName`, add the signal when present, fold the sender into `fromNodeStats` with `hopsKnown = hopStart != 0`, and set `firstPacketAtMillis` on the first packet only. `Ingest.Direct`: bump `totalDirectPackets` and update the `NeighbourStats` history. `Ingest.Dropped`: nothing beyond the total already counted.

**`reset()`** clears `relays`, `neighbours`, `counterState`, both timestamps and calls `directory.clearRuntimeData()`. It does not touch the node list or the skip-list.

**`buildSnapshot()`** assembles `StatsSnapshot` with the relays and neighbours already sorted. Sorting rules, porting `get_sorted_nodes` and `get_sorted_neighbours` (`mesh_stats.py:1120-1140`, `:770-791`):

| Mode | Order |
| :--- | :--- |
| `PACKETS` | packet count, descending |
| `PERCENT` | share of the relevant total, descending |
| `AVG_SNR` | average SNR descending; **a relay with no samples sorts last**, via `Float.NEGATIVE_INFINITY`, not 0 |
| `AVG_RSSI` | as above for RSSI |
| `NAME` | node name ascending, falling back to `hexId` when there is no unique name |

- [ ] **Step 4: Run the tests**

Run: `gradle :app:testDebugUnitTest --tests '*MeshStatsEngineTest*'`
Expected: PASS, 11 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngine.kt \
        app/src/test/kotlin/com/cerocoder/meshrelay/stats/MeshStatsEngineTest.kt
git commit -m "feat(stats): the statistics engine

One coroutine owns every mutable statistic. No lock, no @Volatile, no Atomic -
the suite is single-threaded and production is not, so confinement removes the
defect class rather than testing for it.

No tick: a snapshot is built when state changes and someone is watching. With
nothing subscribed none is built at all, which is what makes collecting with
the screen off cheap."
```

## Screens — Tasks 22 to 29

**Rules that apply to all eight**, because eight agents build them without seeing each other's work:

- **Stateless.** Every screen is a `@Composable` taking data and lambdas. None reads `AppContainer`, holds a ViewModel, launches a coroutine, or navigates. Navigation is a lambda the caller supplies.
- **No literals, no arithmetic.** Text comes from `R.string` (Task 7); anything computed comes from `GaugeGeometry`, `PositionLineText`, `AgeText` or the model.
- **Previews from `SampleData` only** (Task 20). Every screen gets at least: a populated preview, an empty preview, and a dark-theme preview.
- **Verification** is `gradle :app:compileDebugKotlin` plus rendering every preview. The real verification is Task 32 on hardware; a screen is not "done" in any stronger sense until then.
- Missing string key → add it to `values/strings.xml` **and** `values-es/strings.xml`, keeping `StringsParityTest` green.
- Missing icon → check in an Apache-2.0 Material Symbols vector as `res/drawable/ic_<meaning>.xml` and append a row to `docs/third-party-assets.md`. Do not add `material-icons-extended`; if a task believes it needs to, stop and report.

### Task 22: Relay list screen *(Sonnet)*

**Files:** create `ui/relays/RelayListScreen.kt`, `ui/relays/RelayCard.kt`, `ui/relays/StatusStrip.kt`, `ui/relays/SortModeLabels.kt`

**Produces:**

```kotlin
@Composable
fun RelayListScreen(
    snapshot: StatsSnapshot,
    connection: ConnectionState,
    gaugeMode: GaugeMode,
    meshviewUrl: String?,
    nodeDbReloading: Boolean,
    onOpenRelay: (relayByte: Int) -> Unit,
    onSetSortMode: (SortMode) -> Unit,
    onSetGaugeMode: (GaugeMode) -> Unit,
    onTogglePause: () -> Unit,
    onReset: () -> Unit,
    onReloadNodeDb: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
)
```

`RelayCard(relay: RelayStats, matchCount: Int, uniqueName: String, location: LocationInfo?, totalRelayed: Int, gaugeMode: GaugeMode, onClick: () -> Unit)` is public so Task 23 can mirror its shape without copying it.
`SortModeLabels.labelOf(mode: SortMode): Int` returns the string resource for a mode; Task 28 uses it too.

Ports the main view, `mesh_stats.py:1348-1522` and `:1661-1745`.

Layout:
- `TopAppBar` — `relays_title`, then actions: sort (menu of the five modes), gauge mode (toggle), pause/resume, reset (confirmation dialog), reload node DB (a spinner in place of the icon while `nodeDbReloading`), settings.
- `StatusStrip` — `format_db_header` with the node count and load time, `relays_status_total`, `relays_status_relayed`, the current sort, and a `PAUSED` badge from `relays_status_paused`.
- Local node line — short name and a `PositionLine`, or `relays_local_node_unknown`.
- `LazyColumn` of `RelayCard`, keyed on `relayByte` so a re-sort animates rather than rebuilding.
- Empty state — `relays_empty_title` and `relays_empty_body`, the latter pointing at the Neighbours tab.

`RelayCard`, three lines:
1. `hexId` in monospace, the match count in brackets, the unique name when there is one, and an `AgeLabel` for the last packet on the right.
2. Two `SignalGauge`s, SNR then RSSI, each with its min/avg/max triple and its latest value.
3. Distance, direction and altitude **only when exactly one node matches** — with several candidates there is no single position to speak of; then packet count, percentage of relayed, and the known-node count through `plural_known_nodes`.

Show `[n]` and never a name when `matchCount != 1`: the relay byte is one byte, and a name shown next to an ambiguous byte presents a guess as a fact.

### Task 23: Neighbour list screen *(Sonnet)*

**Files:** create `ui/neighbours/NeighbourListScreen.kt`, `ui/neighbours/NeighbourCard.kt`

**Produces:**

```kotlin
@Composable
fun NeighbourListScreen(
    snapshot: StatsSnapshot,
    gaugeMode: GaugeMode,
    meshviewUrl: String?,
    onOpenNeighbour: (nodeNum: Int) -> Unit,
    onSetSortMode: (SortMode) -> Unit,
    onSetGaugeMode: (GaugeMode) -> Unit,
    onTogglePause: () -> Unit,
    onReset: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Ports the `[N]` view, `mesh_stats.py:1524-1659`. Same card geometry as Task 22 — reuse `SignalGauge`, `PositionLine` and `AgeLabel`, not the card itself, because the columns differ:

1. `NodeIdText` and the short name; `AgeLabel` on the right.
2. Both gauges with their triples.
3. Distance and altitude, direct packet count, and its percentage of `totalDirectPackets`.

There is no reload or node-DB action here: the neighbour list is built from live traffic. Empty state `neighbours_empty_body` points at the Relays tab.

### Task 24: Detail screen shell *(Sonnet)*

**Files:** create `ui/detail/DetailScreen.kt`, `ui/detail/DetailSummary.kt`, `ui/detail/SignalBlock.kt`

**Produces:**

```kotlin
@Composable
fun DetailScreen(
    subject: DetailSubject,
    snapshot: StatsSnapshot,
    gaugeMode: GaugeMode,
    meshviewUrl: String?,
    onBack: () -> Unit,
    onOpenRemoteNode: (nodeNum: Int) -> Unit,
    onSkipNode: (nodeNum: Int) -> Unit,
    onClearSkipped: () -> Unit,
    modifier: Modifier = Modifier,
)
```

This is where the port stops being literal. One shell, two subjects (spec §10.4):

| | `DetailSubject.Relay` | `DetailSubject.Neighbour` |
| :--- | :--- | :--- |
| Title | `detail_relay_title` with `hexId`, plus the name when unique | `detail_neighbour_title` with the node id and short name |
| Summary | `detail_total_relayed`, `detail_packets_per_hour`, `detail_skipped_nodes` when any | `detail_total_direct`, `detail_packets_per_hour`, `node_hops_away` |
| Signal block | min / avg / max / last / count for both metrics, with both gauges | identical |
| Tab 1 | `detail_tab_matching_nodes` | `detail_tab_node` |
| Tab 2 | `detail_tab_remote_nodes` | `detail_tab_remote_nodes`, **present only when this node's low byte also appears as a relay byte** |

A neighbour's identity is known, so its first tab is one card with no index number and no skip action. Offering "skip this node as a candidate for itself" would be nonsense.

`SignalBlock(snr: SignalStats, rssi: SignalStats, gaugeMode: GaugeMode)` is public — Task 25 does not need it, but it keeps the numeric block in one place. Shows `detail_no_signal_data` when neither metric has samples.

### Task 25: Matching nodes tab *(Sonnet)*

**Files:** create `ui/detail/MatchingNodesTab.kt`, `ui/detail/NodeCard.kt`

**Produces:**

```kotlin
@Composable
fun MatchingNodesTab(
    relayByte: Int,
    snapshot: StatsSnapshot,
    meshviewUrl: String?,
    onSkipNode: (nodeNum: Int) -> Unit,
    onClearSkipped: () -> Unit,
    modifier: Modifier = Modifier,
)

@Composable
fun NodeCard(
    index: Int?,                 // null on the neighbour tab, where there is nothing to number
    record: NodeRecord,
    location: LocationInfo,
    telemetry: TelemetryRecord?,
    meshviewUrl: String?,
    onSkip: (() -> Unit)?,       // null where skipping makes no sense
    modifier: Modifier = Modifier,
)
```

Ports `build_detail_lines`, `mesh_stats.py:1838-1907`. Task 24 uses `NodeCard` for its neighbour tab too, which is why it takes a nullable index and a nullable skip action.

`NodeCard` shows, in order: `[n]` and `NodeIdText`; `node_long_name`; `node_short_name`; `node_role`; `node_hardware`; a `PositionLine`; Google Maps, OpenStreetMap and Meshview buttons when there are coordinates; `node_last_snr_db`; `node_last_heard_db`; `node_firmware` when present; `node_uptime` with `node_restarts`; every telemetry metric under its protobuf key with its latest value; `node_public_key_present` when a key is known.

Two things not to invent:

- **Firmware.** `NodeInfo` has no `firmware_version` field. The original reads one and can never find it for a remote node. Keep the row, expect it empty except for the local node, and do not substitute anything.
- **Role.** An absent role reads `CLIENT` — already handled in `NodeRecord`, so do not add a second default here.

Skipping is confirmed with an `AlertDialog` using `action_skip_confirm_title` and `action_skip_confirm_body`; clearing likewise. The empty case shows `node_no_matching_title` and `node_no_matching_body`.

### Task 26: Remote nodes tab *(Sonnet)*

**Files:** create `ui/detail/RemoteNodesTab.kt`

**Produces:**

```kotlin
@Composable
fun RemoteNodesTab(
    relay: RelayStats,
    snapshot: StatsSnapshot,
    onOpenRemoteNode: (nodeNum: Int) -> Unit,
    modifier: Modifier = Modifier,
)
```

Ports the "Known Remote Nodes" table, `mesh_stats.py:1908-1943`. Rows sorted by packet count descending, each showing: `NodeIdText`, short name, packets, `avgHopsMade`, `avgHopsLeft`, and a `PositionLine`. Hop averages absent read as `common_not_available`, never as `0.0`.

Above the list, `remote_direction_hint` as an explanatory line. It is the reason the tab exists: many relays hear from one direction only, and the spread of directions in this table is what shows where a relay listens. Empty state: `remote_empty`.

### Task 27: Remote node details screen *(Sonnet)*

**Files:** create `ui/detail/RemoteNodeScreen.kt`

**Produces:**

```kotlin
@Composable
fun RemoteNodeScreen(
    nodeNum: Int,
    viaRelayByte: Int?,
    snapshot: StatsSnapshot,
    meshviewUrl: String?,
    onBack: () -> Unit,
    onOpenRelay: (relayByte: Int) -> Unit,
    modifier: Modifier = Modifier,
)
```

Everything known about one node: a `NodeCard` with no index and no skip action, then **the relays carrying its traffic**, from `RelayIndex.relaysCarrying(nodeNum, snapshot.relays)` — each row showing the relay's `hexId`, its unique name when it has one, and how many of this node's packets it carried. The relay named by `viaRelayByte` is marked with `remote_via_relay_current`. Rows are tappable and call `onOpenRelay`.

This section has no counterpart in the terminal tool, which can only show one relay at a time. It is the direct answer to whether a node is reachable through more than one path.

### Task 28: Settings screen *(Sonnet)*

**Files:** create `ui/settings/SettingsScreen.kt`

**Produces:**

```kotlin
@Composable
fun SettingsScreen(
    settings: AppSettings,
    skippedRelayNodes: Set<Int>,
    appVersion: String,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onRemoveSkipped: (nodeNum: Int) -> Unit,
    onClearAllSkipped: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Sections: language (System / English / Español); gauge mode; default sort, labelled through `SortModeLabels`; Meshview base URL as a text field with `settings_meshview_url_hint`, empty meaning no Meshview links anywhere; keep screen on; background collection with `settings_background_collection_summary`; the skipped-node list with a remove button per entry, `settings_skipped_nodes_empty` when there are none, and clear-all; and About with the version, `settings_licence` and `settings_upstream`.

Node identifiers in the skipped list render through `NodeIdText`, so a value can be read off the screen and typed into `--skip-relay` on the terminal tool unchanged.

### Task 29: Device list screen *(Sonnet)*

**Files:** create `ui/devices/DeviceListScreen.kt`

**Produces:**

```kotlin
@Composable
fun DeviceListScreen(
    devices: List<DeviceListEntry>,
    state: ConnectionState,
    readiness: BleReadiness,
    onSelect: (DeviceListEntry) -> Unit,
    onDisconnect: () -> Unit,
    onRequestPermissions: () -> Unit,
    modifier: Modifier = Modifier,
)
```

Restyle `mesh-test-android`'s screen of the same name. **Its logic is sound and is kept** — the four readiness states, demo devices only in debug, disconnect while connected. Two changes: every literal becomes a string resource, and the appearance becomes the app's rather than a test harness's.

Each of the four `BleReadiness` states gets a title and a body saying what to do next, not what went wrong: `devices_permissions_*`, `devices_adapter_off_*`, `devices_unsupported_*`. This carries over the rule `devel-notes.md §7` records — a message that names where a failure surfaced instead of what to do sends the reader to inspect the node when all they had to do was switch Bluetooth on.

**Round 4 gate:** `gradle :app:testDebugUnitTest` green, `gradle :app:assembleDebug` succeeds, every preview renders.

---

# Round 5 — integration (sequential, Opus)

### Task 30: Navigation

**Files:**
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/nav/BackStack.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/MeshRelayNavHost.kt`
- Test: `app/src/test/kotlin/com/cerocoder/meshrelay/ui/nav/BackStackTest.kt`

**Interfaces:**
- Consumes: `Screen`, `MainTab`, `DetailSubject` (Task 2); every screen from Tasks 22–29.
- Produces: `class BackStack(initial: Screen)` with `val current: Screen`, `val canGoBack: Boolean`, `fun push(screen: Screen)`, `fun pop(): Boolean`, `fun selectTab(tab: MainTab)`, and `screenListSaver` / `backStackSaver` for `rememberSaveable`; `@Composable fun MeshRelayNavHost(container: AppContainer)`.

No navigation library: six destinations in a strict stack (Global Constraints).

- [ ] **Step 1: Write the failing test**

```kotlin
@Test fun `push and pop walk the stack`()
@Test fun `pop at the root does nothing and reports it`()
// The Activity's BackHandler must be able to tell "I handled it" from "let the
// system close the app", or back at the root traps the user in the app.

@Test fun `selecting a tab replaces the root instead of pushing`()
// Otherwise a minute of tapping between Relays and Neighbours builds a stack of
// forty entries and back takes forty presses to leave.

@Test fun `selecting a tab from a detail screen returns to the list`()
@Test fun `a stack survives being saved and restored`()
// Rotation. The saver round-trips every Screen variant, including the two that
// carry arguments.
```

Write `BackStack` as a Compose-state-backed list (`mutableStateListOf`), and `backStackSaver` as a `listSaver` encoding each screen to a small list of primitives. Test the saver by round-tripping every variant: `Devices`, `Main(RELAYS)`, `Main(NEIGHBOURS)`, `Settings`, `Detail(Relay(0x69))`, `Detail(Neighbour(0x9e75f1a4))`, `RemoteNode(42, 0x69)`, `RemoteNode(42, null)`.

- [ ] **Step 2: Run it, watch it fail, implement, run again**

- [ ] **Step 3: Write `MeshRelayNavHost`**

Holds the `BackStack` in `rememberSaveable`, installs `BackHandler(enabled = backStack.canGoBack) { backStack.pop() }`, collects `snapshot`, `connectionState`, `settings`, `skippedRelayNodes` and `nodeDbReloading` from the container, and dispatches on `backStack.current`. `Screen.Main` renders a `Scaffold` with a `NavigationBar` of two items — `nav_relays`, `nav_neighbours` — and the matching list screen in its body. Every other screen is full-bleed.

Route the callbacks: `onOpenRelay` pushes `Detail(Relay(byte))`, `onOpenNeighbour` pushes `Detail(Neighbour(num))`, `onOpenRemoteNode` pushes `RemoteNode(num, viaRelayByte)`, `onOpenSettings` pushes `Settings`, and `onBack` pops.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/cerocoder/meshrelay/ui/nav app/src/main/kotlin/com/cerocoder/meshrelay/ui/MeshRelayNavHost.kt app/src/test/kotlin/com/cerocoder/meshrelay/ui/nav
git commit -m "feat(ui): navigation stack and host

Hand-rolled rather than navigation-compose: six destinations in a strict stack,
and the version chain is fragile enough without another dependency in it. Tab
selection replaces the root so switching tabs does not build a back stack."
```

### Task 31: Wiring

**Files:**
- Rewrite: `app/src/main/kotlin/com/cerocoder/meshrelay/{MeshRelayApp,AppContainer,MainActivity}.kt`
- Create: `app/src/main/kotlin/com/cerocoder/meshrelay/ui/LocalizedApp.kt`

**Interfaces:**
- Consumes: everything.
- Produces: a running application.

- [ ] **Step 1: Write `AppContainer`**

Owns the process-lifetime scope and wires the parts together:

```kotlin
class AppContainer(private val context: Context, isDebugBuild: Boolean) {

    private val errors = CoroutineExceptionHandler { _, e ->
        Log.e("AppContainer", "unhandled exception in the application scope", e)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errors)

    val availability = BluetoothAvailability(context)
    val scanner: BleScanner = BleScannerImpl()
    val settings = SettingsRepository(AndroidSettingsStore(context), scope)

    private val factory: RadioTransportFactory = RadioTransportFactoryImpl(scope, isDebugBuild, context)
    val connectionManager = RadioConnectionManager(factory, scope, SystemTimeSource)

    val engine = MeshStatsEngine(
        scope = scope,
        skippedRelayNodes = settings.skippedRelayNodes,
        initialSortMode = settings.settings.value.defaultSortMode,
    )

    val devices: List<DeviceListEntry> =
        if (isDebugBuild) Scenarios.all.map { DeviceListEntry.Demo(it.id, it.displayName) } else emptyList()

    init {
        // The engine consumes a source-agnostic frame stream, so this is the only
        // line that knows the frames come from a radio at all. A file-backed source
        // is added here later and nowhere else.
        engine.attach(connectionManager.frames)

        // The notification carries counters, not a snapshot: it is the one thing
        // that still updates with the screen off, and building a snapshot for it
        // would give back exactly the saving that subscription-scoped sharing buys.
        scope.launch {
            while (isActive) {
                delay(NOTIFICATION_INTERVAL)
                val counters = engine.counters.value
                if (connectionManager.connectionState.value == ConnectionState.Connected) {
                    val text = context.getString(
                        R.string.service_notification_counters,
                        counters.totalPackets,
                        counters.totalRelayedPackets,
                    )
                    MeshForegroundService.updateText(context, text)
                }
            }
        }
    }

    fun skipRelayNode(nodeNum: Int) = settings.addSkippedRelayNode(nodeNum)
    fun clearSkippedForRelay(relayByte: Int) = settings.clearSkippedForRelay(relayByte)

    private companion object { val NOTIFICATION_INTERVAL = 30.seconds }
}
```

- [ ] **Step 2: Write `LocalizedApp`**

The language override, without `appcompat` (Global Constraints):

```kotlin
/**
 * Applies the language chosen in Settings.
 *
 * A locale-overridden Context provided as LocalContext, rather than
 * AppCompatDelegate.setApplicationLocales, which would mean adding appcompat to a
 * dependency chain that already holds together only as a whole. Every
 * stringResource call resolves against whatever LocalContext offers, so this is
 * enough. SYSTEM passes the context through untouched.
 */
@Composable
fun LocalizedApp(language: LanguageOption, content: @Composable () -> Unit) {
    val base = LocalContext.current
    val localized = remember(language, base) {
        when (language) {
            LanguageOption.SYSTEM -> base
            LanguageOption.EN -> base.withLocale(Locale.ENGLISH)
            LanguageOption.ES -> base.withLocale(Locale("es"))
        }
    }
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides localized.resources.configuration,
    ) { content() }
}
```

`withLocale` builds a `Configuration` copy with `setLocale` and returns `createConfigurationContext(it)`. Both `LocalContext` and `LocalConfiguration` must be provided: `stringResource` reads the context, while some Compose internals read the configuration, and providing one without the other gives a screen with two languages on it.

**Verify before relying on it:** `LocalConfiguration` has been deprecated in recent Compose releases in favour of `LocalResources`/`LocalWindowInfo`. Check what BOM 2026.06.01 actually exposes and use the one that is current; if the replacement cannot be provided, provide `LocalContext` alone and confirm by switching to Español on every screen that nothing falls back to English. Do not add a dependency to resolve this.

- [ ] **Step 3: Rewrite `MainActivity`**

Carry over from `mesh-test-android`, translating the comments and keeping every reason intact:

- `readinessState` lives on the activity and is re-read in `onResume`, because permissions are granted in system settings and the adapter is switched on from the shade — both outside the app. Without it one refusal locks the user on the explanatory text until the process restarts.
- A `BroadcastReceiver` on `ACTION_STATE_CHANGED`, because the shade does not stop the activity and `onResume` never fires for it.
- Scanning is keyed on the user's **intent** to be connected, not on the connection state: the state cycles on every reconnection attempt, and restarting the scan each time would hit Android's five-scans-per-thirty-seconds quota.
- The foreground service is started from the **user's tap**, not from a state change: `startForegroundService` from the background throws on Android 12+, and the reconnect loop passes through `Connecting` at any time, including while the app is minimised.
- The service is stopped only when `Disconnected.retrying` is false. While the loop is still trying, the process must stay protected — the pauses between attempts are exactly when the system would suspend it.

New: publish `LocalAppResumed` from `onResume`/`onPause`, and wrap the content as

```kotlin
LocalizedApp(settings.language) {
    MeshRelayTheme {
        CompositionLocalProvider(LocalAppResumed provides resumed) {
            ProvideRelativeClock(SystemTimeSource) {
                MeshRelayNavHost(container)
            }
        }
    }
}
```

Apply `keepScreenOn` by toggling `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON` from the setting.

- [ ] **Step 4: Build, install and check by hand**

Run: `gradle :app:assembleDebug` then install.

Walk through: pick the demo device; the relay list fills; open a relay; both tabs; open a remote node; back out; the Neighbours tab; a neighbour detail; Settings; switch to Español and confirm every screen is translated; switch back.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: wire the application together

AppContainer is the only place that knows frames come from a radio - the engine
consumes a source-agnostic stream, so a file source plugs in here and nowhere
else.

Language is applied through a locale-overridden Context rather than appcompat,
keeping the dependency chain closed."
```

### Task 32: Acceptance on hardware

**Files:**
- Create: `docs/acceptance-checklist.md`

No amount of green CI substitutes for this. On the previous project six defects passed three reviews and a fully green build, and were found only by someone holding the node — two of them regressions introduced while fixing the others (`devel-notes.md §6`).

- [ ] **Step 1: Write the checklist**

`docs/acceptance-checklist.md`, with a line to tick and a place to record what happened, for each of:

1. Fresh install on a phone with no permissions granted: the permission flow completes and the device list appears.
2. Bluetooth switched off from the shade: the screen explains it and offers the next step, and recovers when it is switched back on without restarting the app.
3. Pairing the T114 from cold: the code from the node's screen is accepted, and the two-minute bonding timeout is not hit while typing it.
4. Pairing the T-Echo from cold.
5. Handshake completes and the node database count in the status strip matches the node's own.
6. Relay list populates from live traffic; relay bytes and match counts look right against `meshtastic --info` on the same node.
7. A relay with several matching nodes shows `[n]` and no name.
8. Signal gauges move; the flash fires on arrival; both modes look right.
9. Detail screen: matching nodes carry position, role and hardware; map and Meshview links open.
10. Skip a candidate, confirm the relay's name and match count change, then clear it.
11. Remote nodes tab lists senders with hop counts; open a remote node; the relays carrying it are listed.
12. Neighbours tab shows directly-heard nodes and nothing that is relayed.
13. Pause stops the counters entirely; resume continues from where it stopped.
14. Reset clears statistics while the node database count and the skip-list survive.
15. Reload node database: the spinner appears and clears, the count updates, statistics are untouched.
16. Lock the screen for thirty minutes: the notification stays, counters keep rising, and the relay list is up to date on return.
17. Walk out of range until the link drops, then back: the reconnect loop recovers without a tap, and the service survives the gaps between attempts.
18. Kill Bluetooth mid-session: the message names the cause and the next step, not a status code.
19. Rotate the phone on every screen: nothing is lost, including an open detail screen and the list scroll position.
20. Switch to Español: every screen, dialog, notification and empty state is translated, and no format placeholder is missing.
21. Release build: no demo devices are offered, and a live node still connects.
22. Battery over a two-hour session with the screen mostly off, compared against the phone's own reporting.

- [ ] **Step 2: Run it, on both devices**

Record what happened next to each item. **Do not tick an item because it was mentioned in conversation** — only a run or a log counts (`devel-notes.md §8`).

- [ ] **Step 3: Fix what it finds, then re-run the items the fixes touched**

Fixes made under the pressure of a live test are more dangerous than the original code — two of the six defects last time were introduced this way. Every fix gets its own commit and its own re-run.

- [ ] **Step 4: Commit and tag**

```bash
git add docs/acceptance-checklist.md
git commit -m "docs: hardware acceptance checklist, completed

Green CI does not substitute for this: on the previous project six defects
passed three reviews and a clean build and were found only on hardware."
git tag v0.1.0
```

---

**Round 5 gate:** `gradle :app:testDebugUnitTest` green, both APK variants build, and every item in `docs/acceptance-checklist.md` is recorded as run.

## What this plan does not build

From spec §16, repeated so "port all features" is not read mid-stream as licence to add them: USB-serial and TCP transports; packet recording, replay and speed control (the seams exist, the features do not); node-database persistence; statistics persistence; anything that writes to the mesh beyond the handshake and heartbeat; in-app maps; `cluster_analysis.py`.
