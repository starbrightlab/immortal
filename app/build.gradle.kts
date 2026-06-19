/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
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
    versionCode = 42
    versionName = "1.41"

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
      if (keystorePropsFile.exists()) signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Lets a debug build install alongside a provisioned release for testing.
      applicationIdSuffix = ".debug"
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

  // Unit tests (pure JVM — no device/emulator). org.json provides a real
  // implementation so JSON-parsing logic can be tested off-device (the android.jar
  // stub used in unit tests otherwise throws "not mocked").
  testImplementation(libs.junit)
  testImplementation("org.json:json:20240303")
}
