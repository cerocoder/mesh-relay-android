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
        versionCode = 2
        versionName = "0.2.0"
        resourceConfigurations += setOf("en", "es")
    }

    // The release key comes from the environment (CI secrets), never from the repo.
    // Without RELEASE_KEYSTORE_PATH - a local build, or a run without access to
    // secrets such as a pull request from a fork - the release variant falls back
    // to the debug key below, so it can still be installed for testing.
    val releaseKeystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // A debug-signed release APK must never be published: the key that signs
            // the first published APK has to sign every later one. The tagged CI run
            // refuses to build without the real key (see build.yml).
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
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
