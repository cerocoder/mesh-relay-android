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
    // material3 does not transitively pull in Icons.* (verified against the
    // actual material3-android module this BOM resolves to: it vendors a
    // handful of icons under its own internal package instead of depending on
    // material-icons-core). This is a deliberate exception to "no new
    // dependencies": the artifact is entirely BOM-managed (no version pinned
    // here), and has been frozen at 1.7.8 since 2025-02-12, so it carries none
    // of the independent-drift risk that rule exists to prevent. The
    // extended icon set (material-icons-extended) is not added.
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    // androidx.lifecycle.lifecycleScope (MainActivity's Exit command) is only
    // ever reached transitively today, through activity-compose's own graph -
    // see docs/decisions.md ruling 48. Declared explicitly so a future
    // activity-compose bump that stops exporting it is a version conflict CI
    // catches, not a silent "unresolved reference" at compile time.
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.coroutines.android)

    implementation(libs.nordic.ble)
    implementation(libs.nordic.ble.ktx)
    implementation(libs.nordic.scanner)

    implementation(libs.meshtastic.protobufs)
    implementation(libs.wire.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
