package com.immortal.launcher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAlbumTest {

  @Test
  fun isSupported_recognisesPublicShareLinks() {
    assertTrue(RemoteAlbum.isSupported("https://www.icloud.com/sharedalbum/#B1abcDEF"))
    assertTrue(RemoteAlbum.isSupported("https://photos.icloud.com/shared/album/037SP6pgkQQ_LNS48MIYBgiTQ"))
    assertTrue(RemoteAlbum.isSupported("https://photos.app.goo.gl/abcd1234"))
    assertTrue(RemoteAlbum.isSupported("https://photos.google.com/share/AF1Qa1b2c3d"))
    assertFalse(RemoteAlbum.isSupported("https://example.com/album/123"))
    assertFalse(RemoteAlbum.isSupported(""))
  }

  @Test
  fun providerName_distinguishesProviders() {
    assertEquals(
        "iCloud Shared Album",
        RemoteAlbum.providerName("https://www.icloud.com/sharedalbum/#B1abcDEF"))
    assertEquals(
        "iCloud Shared Album",
        RemoteAlbum.providerName("https://photos.icloud.com/shared/album/037SP6pgkQQ_LNS48MIYBgiTQ"))
    assertEquals("Google Photos", RemoteAlbum.providerName("https://photos.app.goo.gl/x"))
    assertEquals("Shared album", RemoteAlbum.providerName("https://example.com"))
  }

  @Test
  fun icloudFormats_legacyAndCloudKitAreDistinct() {
    val legacy = "https://www.icloud.com/sharedalbum/#B1abcDEF"
    val cloudKit = "https://photos.icloud.com/shared/album/037SP6pgkQQ_LNS48MIYBgiTQ"
    assertTrue(RemoteAlbum.isIcloudLegacy(legacy))
    assertFalse(RemoteAlbum.isIcloudCloudKit(legacy))
    assertTrue(RemoteAlbum.isIcloudCloudKit(cloudKit))
    assertFalse(RemoteAlbum.isIcloudLegacy(cloudKit))
  }

  @Test
  fun cloudKitToken_pullsFromPath() {
    assertEquals(
        "037SP6pgkQQ_LNS48MIYBgiTQ",
        RemoteAlbum.cloudKitToken("https://photos.icloud.com/shared/album/037SP6pgkQQ_LNS48MIYBgiTQ"))
    // Trailing query / fragment / extra path segments are stripped.
    assertEquals(
        "TOK123",
        RemoteAlbum.cloudKitToken("https://photos.icloud.com/shared/album/TOK123?l=en"))
    assertEquals(
        "TOK123",
        RemoteAlbum.cloudKitToken("https://photos.icloud.com/shared/album/TOK123#photos"))
    assertEquals(
        "TOK123",
        RemoteAlbum.cloudKitToken("https://photos.icloud.com/shared/album/TOK123/"))
    assertNull(RemoteAlbum.cloudKitToken("https://photos.icloud.com/shared/album/"))
    assertNull(RemoteAlbum.cloudKitToken("https://example.com/x"))
  }

  @Test
  fun pickBestCloudKitAsset_choosesSmallestCoveringDerivative() {
    // CPLMaster-shaped fields: each res*Res blob has a sibling res*Width.
    fun field(url: String) = JSONObject().put("value", JSONObject().put("downloadURL", url))
    fun width(w: Int) = JSONObject().put("value", w)
    val fields =
        JSONObject()
            .put("resJPEGThumbRes", field("https://cvws/thumb"))
            .put("resJPEGThumbWidth", width(360))
            .put("resJPEGMedRes", field("https://cvws/med"))
            .put("resJPEGMedWidth", width(1280))
            .put("resOriginalRes", field("https://cvws/orig"))
            .put("resOriginalWidth", width(4032))
    // 1920 long-edge → smallest derivative ≥ 1920 is the 4032 original.
    assertEquals("https://cvws/orig", RemoteAlbum.pickBestCloudKitAsset(fields, 1920, 1080))
    // 1080 long-edge → smallest ≥ 1080 is the 1280 medium.
    assertEquals("https://cvws/med", RemoteAlbum.pickBestCloudKitAsset(fields, 1080, 720))
    // Tiny screen still covered by the 360 thumb.
    assertEquals("https://cvws/thumb", RemoteAlbum.pickBestCloudKitAsset(fields, 320, 240))
  }

  @Test
  fun pickBestCloudKitAsset_fallsBackToLargestAndSkipsVideoTracks() {
    fun field(url: String) = JSONObject().put("value", JSONObject().put("downloadURL", url))
    fun width(w: Int) = JSONObject().put("value", w)
    val fields =
        JSONObject()
            .put("resJPEGThumbRes", field("https://cvws/thumb"))
            .put("resJPEGThumbWidth", width(360))
            .put("resOriginalRes", field("https://cvws/orig"))
            .put("resOriginalWidth", width(1536))
            // A video track must never be picked as a photo, even though it's largest.
            .put("resVidFullRes", field("https://cvws/video"))
            .put("resVidFullWidth", width(3840))
    // 4K target → nothing covers it among photos; fall back to the largest photo (1536).
    assertEquals("https://cvws/orig", RemoteAlbum.pickBestCloudKitAsset(fields, 3840, 2160))
  }

  @Test
  fun isCloudKitImage_keepsPhotosDropsVideos() {
    fun item(t: String) = JSONObject().put("itemType", JSONObject().put("value", t))
    assertTrue(RemoteAlbum.isCloudKitImage(item("public.jpeg")))
    assertTrue(RemoteAlbum.isCloudKitImage(item("public.heic")))
    assertFalse(RemoteAlbum.isCloudKitImage(item("com.apple.quicktime-movie")))
    assertFalse(RemoteAlbum.isCloudKitImage(item("public.mpeg-4-video")))
    // Missing itemType → keep (the derivative picker still requires a real blob).
    assertTrue(RemoteAlbum.isCloudKitImage(JSONObject()))
  }

  @Test
  fun icloudToken_pullsFromFragment() {
    assertEquals(
        "B1abcDEF",
        RemoteAlbum.icloudToken("https://www.icloud.com/sharedalbum/#B1abcDEF"))
    assertEquals(
        "B1abcDEF",
        RemoteAlbum.icloudToken("https://www.icloud.com/sharedalbum/#B1abcDEF?foo=bar"))
    assertNull(RemoteAlbum.icloudToken("https://www.icloud.com/sharedalbum/"))
  }

  @Test
  fun icloudPartition_mapsLeadingCharToPNN() {
    // Digits 0..9 → 01..10
    assertEquals("01", RemoteAlbum.icloudPartition("0xyz"))
    assertEquals("10", RemoteAlbum.icloudPartition("9xyz"))
    // 'A' onward → 11+
    assertEquals("11", RemoteAlbum.icloudPartition("Axyz"))
    assertEquals("12", RemoteAlbum.icloudPartition("Bxyz"))
  }

  @Test
  fun pickBestDerivative_prefersSmallestThatCoversScreen() {
    val derivs =
        JSONObject(
            """
            {
              "405": {"checksum": "smol"},
              "1136": {"checksum": "mid"},
              "2048": {"checksum": "big"}
            }
            """.trimIndent())
    // 1920x1080 screen, long-edge target = 1920. Smallest derivative ≥ 1920 is 2048.
    assertEquals("big", RemoteAlbum.pickBestDerivative(derivs, 1920, 1080))
    // 1080x800 screen, long-edge target = 1080. Smallest derivative ≥ 1080 is 1136.
    assertEquals("mid", RemoteAlbum.pickBestDerivative(derivs, 1080, 800))
    // 4K target → none cover; fall back to largest.
    assertEquals("big", RemoteAlbum.pickBestDerivative(derivs, 3840, 2160))
  }

  @Test
  fun isGoogleAvatarUrl_dropsOwnerAvatars() {
    // Google avatar service — owner profile pictures on share pages.
    assertTrue(
        RemoteAlbum.isGoogleAvatarUrl(
            "https://lh3.googleusercontent.com/a/ACg8ocAbCdEfGhIjK=s64-c-mo"))
    assertTrue(
        RemoteAlbum.isGoogleAvatarUrl(
            "https://lh3.googleusercontent.com/a-/AOh14GgHasItem=s40"))
    // Photo URLs — keep these.
    assertFalse(
        RemoteAlbum.isGoogleAvatarUrl(
            "https://lh3.googleusercontent.com/pw/ADCreHe-abc123=w1920-h1080-no"))
    assertFalse(
        RemoteAlbum.isGoogleAvatarUrl(
            "https://lh3.googleusercontent.com/abc123/photo=w1024-h768-no"))
  }

  @Test
  fun pickBestDerivative_skipsEntriesMissingChecksum() {
    val derivs =
        JSONObject(
            """
            {
              "405": {"checksum": "smol"},
              "1136": {},
              "2048": {"checksum": "big"}
            }
            """.trimIndent())
    assertEquals("big", RemoteAlbum.pickBestDerivative(derivs, 1920, 1080))
  }
}
