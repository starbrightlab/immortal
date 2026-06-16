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
    assertEquals("Google Photos", RemoteAlbum.providerName("https://photos.app.goo.gl/x"))
    assertEquals("Shared album", RemoteAlbum.providerName("https://example.com"))
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
