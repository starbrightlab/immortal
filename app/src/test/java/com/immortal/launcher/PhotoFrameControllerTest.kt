package com.immortal.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure geometry behind the screensaver's video fill mode (no Context). */
class PhotoFrameControllerTest {

  @Test
  fun videoCoverSize_widerClipCoversByHeight() {
    // 16:9 clip on a 4:3 screen: match the screen height, overflow horizontally.
    val (w, h) = PhotoFrameController.videoCoverSize(1920, 1080, 1280, 960)!!
    assertEquals(960, h)
    assertTrue("width $w must cover 1280", w >= 1280)
    // Aspect preserved: w/h == 1920/1080 (within rounding).
    assertEquals(1707, w)
  }

  @Test
  fun videoCoverSize_tallerClipCoversByWidth() {
    // Portrait phone clip on a landscape screen: match the width, overflow vertically.
    val (w, h) = PhotoFrameController.videoCoverSize(1080, 1920, 1920, 1080)!!
    assertEquals(1920, w)
    assertTrue("height $h must cover 1080", h >= 1080)
    assertEquals(3413, h)
  }

  @Test
  fun videoCoverSize_matchingAspectIsExactlyTheScreen() {
    assertEquals(Pair(1920, 1080), PhotoFrameController.videoCoverSize(3840, 2160, 1920, 1080))
  }

  @Test
  fun videoCoverSize_unknownDimensionsFallBack() {
    // Audio-only / not-yet-reported sizes and an unlaid-out host all mean "letterbox instead".
    assertNull(PhotoFrameController.videoCoverSize(0, 0, 1920, 1080))
    assertNull(PhotoFrameController.videoCoverSize(1920, 1080, 0, 0))
    assertNull(PhotoFrameController.videoCoverSize(-1, 1080, 1920, 1080))
  }

  // --- prefetchOrder: what the cache-warming worker downloads, and in what order ---

  private val urls = listOf("p0", "v1", "p2", "v3", "v4")
  private val videos = setOf("v1", "v3", "v4")

  @Test
  fun prefetchOrder_startsAfterCurrentAndWraps() {
    // Playing index 2 ("p2"): the playlist continues v3, v4, p0, v1 — videos only.
    assertEquals(listOf("v3", "v4", "v1"), PhotoFrameController.prefetchOrder(urls, videos, 2))
  }

  @Test
  fun prefetchOrder_skipsTheCurrentlyPlayingVideo() {
    // Playing v1: it's already streaming; downloading it again would double the bandwidth.
    assertEquals(listOf("v3", "v4"), PhotoFrameController.prefetchOrder(urls, videos, 1))
  }

  @Test
  fun prefetchOrder_beforeFirstAdvanceUsesPlaylistOrder() {
    assertEquals(listOf("v1", "v3", "v4"), PhotoFrameController.prefetchOrder(urls, videos, -1))
  }

  @Test
  fun prefetchOrder_emptyInputs() {
    assertEquals(emptyList<String>(), PhotoFrameController.prefetchOrder(emptyList(), videos, 0))
    assertEquals(emptyList<String>(), PhotoFrameController.prefetchOrder(urls, emptySet(), 0))
  }
}
