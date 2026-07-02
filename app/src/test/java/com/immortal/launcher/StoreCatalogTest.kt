/*
 * Copyright (c) 2026 Starbright Lab.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Catalog JSON parsing + APK-URL resolution logic (no device needed). */
class StoreCatalogTest {

  private val sample =
      """
      {"categories":[
        {"name":"Media","apps":[
          {"name":"VLC","packageName":"org.videolan.vlc","source":"fdroid","fdroidId":"org.videolan.vlc","versionCode":13070106,"description":"Plays media."},
          {"name":"Immortal","packageName":"com.immortal.launcher","source":"url","apkUrl":"https://x/immortal.apk","description":"the app"}
        ]},
        {"name":"Tools","apps":[
          {"name":"Aurora","packageName":"com.aurora.store"}
        ]}
      ]}
      """.trimIndent()

  @Test
  fun parse_flattensCategoriesAndKeepsCategoryName() {
    val apps = StoreCatalog.parse(sample)
    assertEquals(3, apps.size)
    assertEquals("Media", apps[0].category)
    assertEquals("Media", apps[1].category)
    assertEquals("Tools", apps[2].category)
  }

  @Test
  fun parse_readsFieldsAndAppliesDefaults() {
    val apps = StoreCatalog.parse(sample)

    val vlc = apps.first { it.packageName == "org.videolan.vlc" }
    assertEquals("fdroid", vlc.source)
    assertEquals(13070106L, vlc.versionCode)

    // A minimal entry: source defaults to "fdroid"; optional fields are null/blank.
    val aurora = apps.first { it.packageName == "com.aurora.store" }
    assertEquals("fdroid", aurora.source)
    assertNull(aurora.apkUrl)
    assertNull(aurora.fdroidId)
    assertNull(aurora.versionCode)
    assertEquals("", aurora.description)
  }

  private val sampleV2 =
      """
      {"schemaVersion":2,"categories":[
        {"name":"Media","apps":[
          {"name":"VLC","packageName":"org.videolan.vlc","minSdk":21,
           "longDescription":"Long text.","iconUrl":"https://x/icon.png",
           "author":"VideoLAN","homepage":"https://videolan.org",
           "submittedBy":"someone","devices":["tv"],"description":"Plays media."}
        ]}
      ]}
      """.trimIndent()

  @Test
  fun parse_readsV2Fields() {
    val vlc = StoreCatalog.parse(sampleV2).single()
    assertEquals(21, vlc.minSdk)
    assertEquals("Long text.", vlc.longDescription)
    assertEquals("https://x/icon.png", vlc.iconUrl)
    assertEquals("VideoLAN", vlc.author)
    assertEquals("https://videolan.org", vlc.homepage)
    assertEquals("someone", vlc.submittedBy)
    assertEquals(listOf("tv"), vlc.devices)
  }

  @Test
  fun parse_v2FieldsDefaultSafelyOnV1Entries() {
    // A v1 catalog (no v2 fields) must keep parsing — the remote file may lag the app.
    val aurora = StoreCatalog.parse(sample).first { it.packageName == "com.aurora.store" }
    assertNull(aurora.minSdk)
    assertNull(aurora.longDescription)
    assertNull(aurora.iconUrl)
    assertNull(aurora.author)
    assertNull(aurora.homepage)
    assertNull(aurora.submittedBy)
    assertTrue(aurora.devices.isEmpty())
  }

  @Test
  fun isCompatible_comparesMinSdkAgainstDevice() {
    assertTrue(StoreCatalog.isCompatible(minSdk = null, deviceSdk = 28)) // unknown = allowed
    assertTrue(StoreCatalog.isCompatible(minSdk = 28, deviceSdk = 28)) // exact
    assertTrue(StoreCatalog.isCompatible(minSdk = 21, deviceSdk = 29)) // older app
    assertFalse(StoreCatalog.isCompatible(minSdk = 30, deviceSdk = 29)) // needs Android 11
    assertFalse(StoreCatalog.isCompatible(minSdk = 29, deviceSdk = 28)) // Gen-1 vs A10 app
  }

  @Test
  fun incompatibleLabel_mapsApiToAndroidVersion() {
    assertEquals("Needs Android 10+", StoreCatalog.incompatibleLabel(29))
    assertEquals("Needs Android 11+", StoreCatalog.incompatibleLabel(30))
    assertEquals("Needs Android API 99+", StoreCatalog.incompatibleLabel(99)) // fallback
  }

  @Test
  fun resolveApkUrl_usesDirectUrlForUrlSource() {
    val app =
        CatalogApp(
            name = "Immortal",
            packageName = "com.immortal.launcher",
            source = "url",
            fdroidId = null,
            apkUrl = "https://x/immortal.apk",
            versionCode = null,
            description = "",
            category = "Tools",
        )
    assertEquals("https://x/immortal.apk", StoreCatalog.resolveApkUrl(app))
  }

  @Test
  fun latestVersionCode_usesCatalogPinForUrlSource_noNetwork() {
    // A direct-URL app declares its latest versionCode in the catalog; the bump
    // is what lights up the Update badge. No network call should be made.
    val app =
        CatalogApp(
            name = "Portal Calendar",
            packageName = "com.thefloppytaco.portalcalendar",
            source = "url",
            fdroidId = null,
            apkUrl = "https://x/portal-calendar.apk",
            versionCode = 23L,
            description = "",
            category = "Tools",
        )
    val latest =
        StoreCatalog.latestVersionCode(app) { error("must not hit the network for a declared pin") }
    assertEquals(23L, latest)
  }

  @Test
  fun latestVersionCode_urlSourceWithoutVersionCode_isUncheckable() {
    // No versionCode + no F-Droid metadata = nothing to compare against, so the
    // app is skipped (rather than spuriously offered an update).
    val app =
        CatalogApp(
            name = "Shizuku",
            packageName = "moe.shizuku.privileged.api",
            source = "url",
            fdroidId = null,
            apkUrl = "https://x/shizuku.apk",
            versionCode = null,
            description = "",
            category = "Tools",
        )
    assertNull(StoreCatalog.latestVersionCode(app) { error("must not hit the network") })
  }

  @Test
  fun latestVersionCode_urlSourceWithVersionUrl_resolvesLive() {
    // A self-published app points versionUrl at its own version.json; the store
    // reads the current versionCode live, so the catalog never needs a per-release
    // bump. The URL fetched is exactly the declared versionUrl.
    val app =
        CatalogApp(
            name = "Portal Overlays",
            packageName = "com.portal.overlays",
            source = "url",
            fdroidId = null,
            apkUrl = "https://x/releases/latest/download/PortalOverlays.apk",
            versionCode = null,
            versionUrl = "https://raw.example/version.json",
            description = "",
            category = "Portal Originals",
        )
    val latest =
        StoreCatalog.latestVersionCode(app) { url ->
          assertEquals("https://raw.example/version.json", url)
          """{"versionCode": 42, "versionName": "1.9", "apkUrl": "https://x/y.apk"}"""
        }
    assertEquals(42L, latest)
  }

  @Test
  fun latestVersionCode_versionCodePinWinsOverVersionUrl_noNetwork() {
    // An explicit pin freezes the version and must short-circuit before any fetch.
    val app =
        CatalogApp(
            name = "Portal Overlays",
            packageName = "com.portal.overlays",
            source = "url",
            fdroidId = null,
            apkUrl = "https://x/app.apk",
            versionCode = 12L,
            versionUrl = "https://raw.example/version.json",
            description = "",
            category = "Portal Originals",
        )
    val latest =
        StoreCatalog.latestVersionCode(app) { error("pin must win before any versionUrl fetch") }
    assertEquals(12L, latest)
  }

  @Test
  fun latestVersionCode_versionUrlUnreachable_isUncheckable() {
    // If the manifest can't be fetched/parsed we return null (skip) rather than
    // spuriously offering — same safe fallback as a url app with no metadata.
    val app =
        CatalogApp(
            name = "Portal Overlays",
            packageName = "com.portal.overlays",
            source = "url",
            fdroidId = null,
            apkUrl = "https://x/app.apk",
            versionCode = null,
            versionUrl = "https://raw.example/version.json",
            description = "",
            category = "Portal Originals",
        )
    assertNull(StoreCatalog.latestVersionCode(app) { throw java.io.IOException("offline") })
  }

  @Test
  fun parse_readsVersionUrl() {
    val json =
        """
        {"schemaVersion":2,"categories":[
          {"name":"Originals","apps":[
            {"name":"PO","packageName":"com.portal.overlays","source":"url",
             "apkUrl":"https://x/app.apk","versionUrl":"https://raw.example/version.json",
             "description":"HUD."}
          ]}
        ]}
        """.trimIndent()
    val po = StoreCatalog.parse(json).single()
    assertEquals("https://raw.example/version.json", po.versionUrl)
    assertNull(po.versionCode)
  }

  @Test
  fun latestVersionCode_fdroidWithoutPin_readsSuggestedVersionFromApi() {
    val app =
        CatalogApp(
            name = "SmartTube",
            packageName = "com.teamsmart.videomanager.tv",
            source = "fdroid",
            fdroidId = "com.teamsmart.videomanager.tv",
            apkUrl = null,
            versionCode = null,
            description = "",
            category = "Media",
        )
    val latest =
        StoreCatalog.latestVersionCode(app) { url ->
          assertTrue(url.endsWith("/packages/com.teamsmart.videomanager.tv"))
          """{"suggestedVersionCode": 1234, "packageName":"x"}"""
        }
    assertEquals(1234L, latest)
  }

  @Test
  fun resolveApkUrl_buildsFdroidUrlFromPinnedVersion() {
    // A pinned versionCode avoids any network call (the arm64 build of a multi-ABI app).
    val app =
        CatalogApp(
            name = "VLC",
            packageName = "org.videolan.vlc",
            source = "fdroid",
            fdroidId = "org.videolan.vlc",
            apkUrl = null,
            versionCode = 13070106L,
            description = "",
            category = "Media",
        )
    assertEquals(
        "https://f-droid.org/repo/org.videolan.vlc_13070106.apk",
        StoreCatalog.resolveApkUrl(app),
    )
  }
}
