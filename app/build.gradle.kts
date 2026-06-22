/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

// Release signing is configured only when keystore.properties exists. Every
// release MUST be signed with the same key for in-place self-update to work, so
// guard these files carefully. The canonical home is ~/.immortal-signing/ —
// OUTSIDE the repo, so no git clean/restore/checkout can ever delete it. A copy
// at the repo root (git-ignored) still works and takes precedence if present.
// storeFile is resolved relative to whichever keystore.properties was found.
val keystorePropsFile =
    rootProject.file("keystore.properties").takeIf { it.exists() }
        ?: File(System.getProperty("user.home"), ".immortal-signing/keystore.properties")
val keystoreProps =
    Properties().apply { if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use(::load) }

android {
  namespace = "com.immortal.launcher"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.immortal.launcher"
    minSdk = 24
    targetSdk = 36
    versionCode = 46
    versionName = "1.45"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    if (keystorePropsFile.exists()) {
      create("release") {
        storeFile = File(keystorePropsFile.parentFile, keystoreProps.getProperty("storeFile"))
        storePassword = keystoreProps.getProperty("storePassword")
        keyAlias = keystoreProps.getProperty("keyAlias")
        keyPassword = keystoreProps.getProperty("keyPassword")
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (keystorePropsFile.exists()) {
        signingConfig = signingConfigs.getByName("release")
      } else if (gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }) {
        // Every Immortal release MUST be signed with the same key for in-place
        // self-update to work. Without keystore.properties the release would be
        // unsigned (or debug-signed), which SILENTLY breaks self-update on devices
        // (signature mismatch). Fail loudly instead of shipping a mis-signed build.
        throw GradleException(
            "Release build requires signing but no keystore.properties was found " +
                "(looked at ${keystorePropsFile.path}). Provide it (see the signing " +
                "comment above) or build a debug variant — refusing to produce an " +
                "unsigned/mis-signed release.")
      }
    }
    debug {
      // Lets a debug build install alongside a provisioned release for testing.
      applicationIdSuffix = ".debug"
    }
    // Release-faithful iteration build. Same applicationId + same signing key + minify off
    // (inherited from release via initWith), so it provisions identically — home role, device
    // admin, and self-update signature checks all behave exactly as on a release build. The ONLY
    // difference is `debuggable = true`, which lets `adb install -r -d` freely replace or downgrade
    // it. That sidesteps the two walls a pure release build hits on a dev device: version-code
    // downgrades (a feature branch is always a lower code) and the device admin blocking uninstall.
    // Use for on-device iteration; validate the true `release` artifact before shipping.
    create("dev") {
      initWith(getByName("release"))
      isDebuggable = true
      matchingFallbacks += "release"
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures { compose = true }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  debugImplementation(libs.androidx.compose.ui.tooling)

  // SMB/CIFS client for the "network share" screensaver source (smbj — SMB 2/3, incl. 3.1.1).
  // minify is off, so no R8 keep rules are needed. slf4j-nop silences its logging facade.
  implementation("com.hierynomus:smbj:0.13.0")
  runtimeOnly("org.slf4j:slf4j-nop:1.7.36")

  // QR encoder for the "Set up from your phone" screen (scan the LAN address). Core only — we
  // render the BitMatrix to a Bitmap ourselves, no Android-specific zxing module needed.
  implementation("com.google.zxing:core:3.5.3")

  // Unit tests (pure JVM — no device/emulator). org.json provides a real
  // implementation so JSON-parsing logic can be tested off-device (the android.jar
  // stub used in unit tests otherwise throws "not mocked").
  testImplementation(libs.junit)
  testImplementation("org.json:json:20240303")
}
