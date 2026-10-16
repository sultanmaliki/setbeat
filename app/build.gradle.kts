import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Private release signing key: keystore.properties is gitignored and lives only on the maintainer's
// machine. Without it (a fresh clone) packaging a release APK/bundle FAILS, so a build can never
// quietly ship signed with the well-known debug key. Pass -PallowDebugSignedRelease to opt in
// deliberately (local experiments only; such an APK cannot update, or be updated by, a real release).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val hasReleaseKey = keystoreProps.containsKey("storeFile")
val allowDebugSignedRelease = providers.gradleProperty("allowDebugSignedRelease").isPresent
tasks.matching { it.name.matches(Regex("(package|bundle)\\w*Release")) }.configureEach {
    doFirst {
        if (!hasReleaseKey && !allowDebugSignedRelease) {
            throw GradleException(
                "keystore.properties not found: refusing to produce a release signed with the debug key. " +
                    "Add the private key config (see CONTRIBUTING.md), or pass -PallowDebugSignedRelease " +
                    "for a throwaway local build."
            )
        }
    }
}

android {
    namespace = "dev.fitnesstimer"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.fitnesstimer"
        // Spike minSdk: 29 is the floor for BlendMode.Difference (see DECISIONS.md).
        // Not yet the final v1 minSdk decision.
        minSdk = 29
        targetSdk = 37
        versionCode = 12
        versionName = "0.3.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    // Editions of the same app (same package id):
    //  - full:       companion mode. Declares a NotificationListenerService (needed to read what other
    //                apps are playing) and Record audio (the beat wave). Google Play Protect BLOCKS
    //                sideloaded installs of apps declaring notification-listener in some markets, so
    //                this edition is for Google Play or `adb install`.
    //  - standalone: no notification listener and no audio capture, so it installs anywhere.
    //                Local media, playlists and the timer only.
    //  - vibes:      companion mode's real track info, but the wave is a procedural animation
    //                instead of real audio analysis, so it always moves (published edition,
    //                DECISIONS.md). No Record audio permission. Full now finds the playing track's
    //                own audio session when the system mix is silent, so Vibes is for people who
    //                want guaranteed motion rather than a wave that follows the real audio.
    flavorDimensions += "edition"
    productFlavors {
        create("full") { dimension = "edition" }
        create("standalone") {
            dimension = "edition"
            versionNameSuffix = "-standalone"
        }
        create("vibes") {
            dimension = "edition"
            versionNameSuffix = "-vibes"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the private release key when keystore.properties exists (never committed).
            // The release variant is NOT debuggable, which switches off the debug-only test hooks.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // AGP otherwise embeds a Google-signed blob listing the dependencies in every APK/bundle. It is
    // opaque to anyone verifying a build; the dependency list is public in gradle/libs.versions.toml
    // anyway.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui.compose)
    implementation(libs.media3.common)
    implementation(libs.media3.session)
    implementation(libs.androidx.palette.ktx)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    // android.jar's org.json is a stub in JVM unit tests; use the real one.
    testImplementation("org.json:json:20240303")
}
