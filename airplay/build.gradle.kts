/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

// TODO(licensing): the native library links GPL-3.0 code that is NOT optional —
// cpp/third_party/UxPlay/lib/playfair/ (the FairPlay handshake) and lib/crypto.c — alongside
// LGPL-2.1+ UxPlay lib/, MIT llhttp/srp and LGPL FFmpeg. Any APK that bundles this module is
// therefore GPL-3.0 as a whole. Immortal is MIT and self-updates publicly, so DO NOT cut an
// Immortal release containing :airplay until that is resolved. Dev/debug builds only.

plugins {
    // AGP 9 bundles Kotlin; only the Compose compiler plugin is applied on top.
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.jqssun.airplay"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        // Matches Immortal, so the module drops in unchanged. The native engine is gated at
        // runtime (AirPlayEngine.isSupported), not by the manifest, so a lower minSdk costs
        // nothing: Portal gen-1 is API 28 and gen-2 is API 29. Oboe uses AAudio from API 27,
        // which is every Portal — the OpenSL ES fallback path is never taken here.
        minSdk = 24

        // Travels with the module into any host, so a minified build cannot strip the JNI bridge.
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                // Must stay c++_shared, as upstream has it: Oboe ships as a shared liboboe.so built
                // against c++_shared (see its prefab abi.json), and it exposes a C++ API, so a
                // static STL here would mean two libc++ copies exchanging C++ objects. Prefab
                // refuses to configure at all in that case ("User is using a static STL but library
                // requires a shared STL"), which is the right call. The cost is libc++_shared.so
                // (~1.3 MB) in the APK; the OpenSSL change below is where the real saving is.
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
                // Static OpenSSL: openssl-cmake already exposes this (third_party/openssl-cmake/
                // CMakeLists.txt), so it is a cache flip rather than a patch. The linker then keeps
                // only what UxPlay references and libcrypto.so leaves the APK entirely (it was
                // 5.9 MB of the debug build).
                arguments += "-DOPENSSL_USE_STATIC_LIBS=ON"

                // Every Portal is arm64 (gen-1/gen-2 Snapdragon, Portal TV Amlogic). Filtering the
                // CMake build rather than ndk.abiFilters is deliberate: it leaves a host app's own
                // ABI guard untouched — Immortal keeps its arm64-v8a + armeabi-v7a filter — while
                // still building the native library exactly once.
                abiFilters += "arm64-v8a"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildTypes {
        // Matches the app's variant of the same name so :app:assembleSanitize gets a sanitized
        // native library rather than silently falling back to the plain debug one. CMakeLists.txt
        // already defines the SANITIZE option; this is what turns it on.
        create("sanitize") {
            initWith(getByName("debug"))
            externalNativeBuild {
                cmake { arguments += "-DSANITIZE=ON" }
            }
        }
    }

    buildFeatures {
        // Oboe ships as a prefab AAR; CMakeLists resolves it with find_package(oboe REQUIRED CONFIG).
        prefab = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// Upstream keeps its UxPlay fixes as patch files applied to the submodule at configure time rather
// than as commits on a fork. Vendored verbatim, including this task — it is why UxPlay has to stay
// a real git checkout (the task runs `git checkout --` then `git apply` inside it).
tasks.register("applyUxplayPatches") {
    doLast {
        fun git(vararg args: String): String {
            val proc = ProcessBuilder("git", "-C", "$projectDir/src/main/cpp/third_party/UxPlay", *args)
                .redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            check(proc.waitFor() == 0) { "git ${args.joinToString(" ")} failed:\n$out" }
            return out
        }
        val patches = file("src/main/cpp/patches/UxPlay").listFiles { f -> f.extension == "patch" }!!.sorted()
        val touched = patches.flatMap { git("apply", "--numstat", it.path).trim().lines() }
            .map { it.substringAfterLast("\t") }.distinct()
        git("checkout", "--", *touched.toTypedArray())
        patches.forEach { git("apply", it.path) }
    }
}

tasks.configureEach {
    if (name.startsWith("configureCMake")) dependsOn("applyUxplayPatches")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.media)
    implementation(libs.kotlinx.coroutines)

    // AirPlay "video" mode (the sender hands over an HLS URL instead of mirroring), the DACP
    // player, and the video downloader are all upstream features that render through media3.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.transformer) // download/VideoDownloader remuxes the HLS stream

    // Low-latency audio output. On Portal this resolves to AAudio (API 28/29 >= 27).
    implementation(libs.oboe)
}
